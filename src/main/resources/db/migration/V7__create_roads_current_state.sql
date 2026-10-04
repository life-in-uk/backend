-- One coherent normalized logical snapshot, independent of raw evidence retention.
-- Scalar provenance deliberately has no foreign key to acquisition/evidence tables.
CREATE TABLE roads_current_snapshot (
    singleton smallint PRIMARY KEY CHECK (singleton = 1),
    ingestion_run_id uuid NOT NULL,
    source_endpoint_id uuid NOT NULL,
    snapshot_epoch_second bigint NOT NULL,
    snapshot_nano integer NOT NULL CHECK (snapshot_nano BETWEEN 0 AND 999999999),
    projected_at timestamptz NOT NULL,
    page_count integer NOT NULL CHECK (page_count BETWEEN 1 AND 8),
    closures jsonb NOT NULL CHECK (jsonb_typeof(closures) = 'array')
);
