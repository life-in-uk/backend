-- Generic, optional request provenance for an EvidenceArtifact, recorded beside (never inside) the
-- provider-exact payload. Needed when one run captures several responses from parameterised or
-- paginated requests. Existing artifacts keep all four values NULL; V2's append-only trigger still
-- forbids any later UPDATE/DELETE, so provenance is as immutable as the payload.
ALTER TABLE evidence_artifact
    ADD COLUMN request_query VARCHAR(2000),
    ADD COLUMN page_number INTEGER,
    ADD COLUMN http_status INTEGER,
    ADD COLUMN requested_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE evidence_artifact ADD CONSTRAINT evidence_artifact_request_provenance CHECK (
    (request_query IS NULL AND page_number IS NULL AND http_status IS NULL AND requested_at IS NULL)
    OR (request_query IS NOT NULL AND btrim(request_query) <> ''
        -- Credentials are sent only as headers; a query that could carry one is never evidence.
        AND request_query !~* '(subscription-key|ocp-apim|api[-_]?key)'
        -- Explicit NOT NULLs: a CHECK treats NULL comparisons as passing.
        AND page_number IS NOT NULL AND page_number >= 1
        AND http_status IS NOT NULL AND http_status BETWEEN 100 AND 599
        AND requested_at IS NOT NULL AND requested_at <= observed_at)
);

-- One captured response per page position within a run.
CREATE UNIQUE INDEX evidence_artifact_run_page_idx ON evidence_artifact(ingestion_run_id, page_number)
    WHERE page_number IS NOT NULL;
