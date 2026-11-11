-- Controlled expiry of raw Travel evidence (TfL Underground, National Highways road closures).
--
-- Numbering: V11, directly after main's V10. The unmerged Guide publication migrations were
-- renumbered to V12-V14 so the two changes are independent. (The local development database
-- recorded the old V11; see docs/travel-evidence-retention.md for its separate remediation.)
--
-- This migration deletes nothing. It adds:
--   * travel_evidence_expiry_candidates: read-only view of what the policy would expire now;
--   * expire_travel_evidence(batch_size, dry_run): the only supported deletion path;
--   * protect_ingestion_run / protect_evidence_artifact: unchanged rules, except that a DELETE is
--     allowed only inside an expiry transaction, only by a role holding EXECUTE on
--     expire_travel_evidence (the schema owner, or a role explicitly granted it; PUBLIC is revoked),
--     AND only for a row the policy makes eligible. The policy is re-checked per row inside the
--     triggers, so no caller (including one setting the transaction flag directly or holding table
--     DELETE) can delete retained, Bank Holidays or currently displayed evidence.
--
-- Policy (owner decision 2026-11-11):
--   * TfL Underground SUCCESS runs + artifacts: 72 hours, measured from EvidenceArtifact observation
--     time as in the recorded TfL qualification (the run must also have completed 72 hours ago);
--   * National Highways SUCCESS runs + artifacts: 7 days after completion (PROPOSED: pending explicit
--     policy and licensing approval before any production use);
--   * FAILED runs (no artifacts): 30 days after completion;
--   * STARTED runs left by an interrupted process: 30 days after start;
--   * only the two Travel endpoints; GOV.UK Bank Holidays evidence is never eligible (its API
--     serves the artifact itself);
--   * never the run or artifact referenced by the current Underground or Roads snapshot.
-- Guide tables are unrelated (no reference to ingestion evidence) and are untouched.

CREATE INDEX ingestion_run_endpoint_started_idx ON ingestion_run (source_endpoint_id, started_at);

CREATE FUNCTION travel_evidence_run_expired(run_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
    SELECT EXISTS (
        SELECT 1 FROM ingestion_run r JOIN source_endpoint e ON e.id = r.source_endpoint_id
        WHERE r.id = run_id
          AND e.endpoint_key IN ('tfl-underground-status', 'national-highways-road-closures')
          AND CASE
                WHEN r.status = 'SUCCESS' AND e.endpoint_key = 'tfl-underground-status' THEN
                    r.completed_at < now() - interval '72 hours'
                    AND NOT EXISTS (SELECT 1 FROM evidence_artifact a WHERE a.ingestion_run_id = r.id
                                    AND a.observed_at >= now() - interval '72 hours')
                WHEN r.status = 'SUCCESS' AND e.endpoint_key = 'national-highways-road-closures' THEN
                    r.completed_at < now() - interval '7 days'
                WHEN r.status = 'FAILED'  THEN r.completed_at < now() - interval '30 days'
                WHEN r.status = 'STARTED' THEN r.started_at   < now() - interval '30 days'
                ELSE false END
          AND NOT EXISTS (SELECT 1 FROM roads_current_snapshot s WHERE s.ingestion_run_id = r.id)
          AND NOT EXISTS (SELECT 1 FROM underground_current_snapshot s
                          JOIN evidence_artifact a ON a.id = s.evidence_artifact_id
                          WHERE a.ingestion_run_id = r.id))
$$;

-- Deletion needs an explicit database privilege: EXECUTE on expire_travel_evidence, held by the
-- schema owner or granted deliberately by an administrator. Table DELETE alone is not enough.
-- (Looked up by name at call time; false if the function is absent.)
CREATE FUNCTION travel_evidence_expiry_authorised() RETURNS boolean
    LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
    SELECT coalesce((SELECT has_function_privilege(current_user, p.oid, 'EXECUTE')
                     FROM pg_proc p
                     WHERE p.proname = 'expire_travel_evidence'
                       AND p.pronamespace = current_schema()::regnamespace
                       AND p.proargtypes = '23 16'::oidvector), false)
$$;

CREATE VIEW travel_evidence_expiry_candidates AS
    SELECT e.endpoint_key, r.status, count(DISTINCT r.id) AS runs, count(a.id) AS artifacts,
           coalesce(sum(octet_length(a.payload)), 0) AS payload_bytes,
           min(r.started_at) AS oldest_started_at, max(r.started_at) AS newest_started_at
    FROM ingestion_run r
    JOIN source_endpoint e ON e.id = r.source_endpoint_id
    LEFT JOIN evidence_artifact a ON a.ingestion_run_id = r.id
    WHERE travel_evidence_run_expired(r.id)
    GROUP BY e.endpoint_key, r.status;

CREATE OR REPLACE FUNCTION protect_ingestion_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF current_setting('life_in_uk.travel_evidence_expiry', true) = 'on'
           AND travel_evidence_expiry_authorised()
           AND travel_evidence_run_expired(OLD.id)
           AND NOT EXISTS (SELECT 1 FROM evidence_artifact WHERE ingestion_run_id = OLD.id) THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'Historical runs cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- Locks serialize qualification/ownership changes against creation of a run.
        PERFORM 1 FROM source_endpoint e JOIN source s ON s.id = e.source_id
        WHERE e.id = NEW.source_endpoint_id AND e.enabled AND s.enabled
            AND e.qualification_status = 'QUALIFIED'
            AND e.qualification_record IS NOT NULL AND btrim(e.qualification_record) <> ''
        FOR SHARE OF e, s;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Run requires an existing qualified and enabled endpoint' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF OLD.status <> 'STARTED' OR NEW.id <> OLD.id
            OR NEW.source_endpoint_id <> OLD.source_endpoint_id OR NEW.started_at <> OLD.started_at THEN
            RAISE EXCEPTION 'Historical run identity and terminal outcome cannot change' USING ERRCODE = '23514';
        END IF;
        IF EXISTS (SELECT 1 FROM evidence_artifact WHERE ingestion_run_id = OLD.id
                   AND observed_at > NEW.completed_at) THEN
            RAISE EXCEPTION 'Completion cannot precede observed evidence' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION protect_evidence_artifact() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run_start TIMESTAMP WITH TIME ZONE; run_end TIMESTAMP WITH TIME ZONE;
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('life_in_uk.travel_evidence_expiry', true) = 'on'
       AND travel_evidence_expiry_authorised()
       AND travel_evidence_run_expired(OLD.ingestion_run_id) THEN
        RETURN OLD;
    END IF;
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Evidence is append-only' USING ERRCODE = '23514';
    END IF;
    SELECT started_at, completed_at INTO run_start, run_end FROM ingestion_run
        WHERE id = NEW.ingestion_run_id FOR UPDATE;
    IF NEW.observed_at < run_start OR NEW.observed_at > run_end THEN
        RAISE EXCEPTION 'Observation must be within the run interval' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Deletes at most batch_size eligible runs (oldest first) with their artifacts, in the caller's
-- transaction. Rows locked by a concurrent writer are skipped and picked up next time.
-- dry_run (default) only counts. Returns the runs, artifacts and payload bytes affected.
CREATE FUNCTION expire_travel_evidence(batch_size integer DEFAULT 500, dry_run boolean DEFAULT true)
    RETURNS TABLE (runs integer, artifacts integer, payload_bytes bigint)
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE batch uuid[];
BEGIN
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 5000 THEN
        RAISE EXCEPTION 'batch_size must be between 1 and 5000';
    END IF;
    SELECT coalesce(array_agg(id), '{}') INTO batch FROM (
        SELECT r.id FROM ingestion_run r
        JOIN source_endpoint e ON e.id = r.source_endpoint_id
        WHERE e.endpoint_key IN ('tfl-underground-status', 'national-highways-road-closures')
          AND r.started_at < now() - interval '72 hours'
          AND travel_evidence_run_expired(r.id)
        ORDER BY r.started_at
        LIMIT batch_size
        FOR UPDATE OF r SKIP LOCKED) candidates;
    SELECT count(*)::integer, coalesce(sum(octet_length(a.payload)), 0)
        INTO artifacts, payload_bytes FROM evidence_artifact a WHERE a.ingestion_run_id = ANY (batch);
    runs := cardinality(batch);
    IF NOT dry_run AND runs > 0 THEN
        PERFORM set_config('life_in_uk.travel_evidence_expiry', 'on', true);
        DELETE FROM evidence_artifact WHERE ingestion_run_id = ANY (batch);
        DELETE FROM ingestion_run WHERE id = ANY (batch);
        PERFORM set_config('life_in_uk.travel_evidence_expiry', 'off', true);
    END IF;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION expire_travel_evidence(integer, boolean) FROM PUBLIC;
