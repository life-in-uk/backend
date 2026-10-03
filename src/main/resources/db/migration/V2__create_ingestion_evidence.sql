CREATE TABLE ingestion_run (
    id UUID PRIMARY KEY,
    source_endpoint_id UUID NOT NULL REFERENCES source_endpoint(id),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('STARTED', 'SUCCESS', 'FAILED')),
    failure_code VARCHAR(100),
    failure_message VARCHAR(1000),
    version BIGINT NOT NULL CHECK (version >= 0),
    CHECK (completed_at IS NULL OR completed_at >= started_at),
    CHECK (
        (status = 'STARTED' AND completed_at IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'SUCCESS' AND completed_at IS NOT NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL
            AND failure_code IS NOT NULL AND btrim(failure_code) <> ''
            AND failure_message IS NOT NULL AND btrim(failure_message) <> '')
    )
);
CREATE INDEX ingestion_run_endpoint_idx ON ingestion_run(source_endpoint_id);

CREATE TABLE evidence_artifact (
    id UUID PRIMARY KEY,
    ingestion_run_id UUID NOT NULL REFERENCES ingestion_run(id),
    payload BYTEA NOT NULL CHECK (octet_length(payload) > 0),
    media_type VARCHAR(200) NOT NULL CHECK (btrim(media_type) <> ''),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (sha256 = encode(sha256(payload), 'hex'))
);
CREATE INDEX evidence_artifact_run_idx ON evidence_artifact(ingestion_run_id);

CREATE FUNCTION protect_ingestion_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
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
CREATE TRIGGER ingestion_run_history BEFORE INSERT OR UPDATE OR DELETE ON ingestion_run
    FOR EACH ROW EXECUTE FUNCTION protect_ingestion_run();

CREATE FUNCTION protect_evidence_artifact() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE run_start TIMESTAMP WITH TIME ZONE; run_end TIMESTAMP WITH TIME ZONE;
BEGIN
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
CREATE TRIGGER evidence_artifact_history BEFORE INSERT OR UPDATE OR DELETE ON evidence_artifact
    FOR EACH ROW EXECUTE FUNCTION protect_evidence_artifact();

-- URL/key are already fixed by V1; freeze ownership/identity once referenced by history.
CREATE FUNCTION protect_observed_endpoint_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id <> OLD.id OR NEW.source_id <> OLD.source_id)
        AND EXISTS (SELECT 1 FROM ingestion_run WHERE source_endpoint_id = OLD.id) THEN
        RAISE EXCEPTION 'Observed endpoint ownership cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER observed_endpoint_identity BEFORE UPDATE ON source_endpoint
    FOR EACH ROW EXECUTE FUNCTION protect_observed_endpoint_identity();
