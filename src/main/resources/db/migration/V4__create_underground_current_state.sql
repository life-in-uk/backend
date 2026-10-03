-- One coherent latest-known Underground snapshot. No fake state is seeded.
-- Scalar provenance intentionally has NO FK to raw evidence: retention lifecycles differ.
-- Seconds/nanos preserve Instant exactly, including sub-microsecond observations.
CREATE TABLE underground_current_snapshot (
    singleton SMALLINT PRIMARY KEY CHECK (singleton = 1),
    evidence_artifact_id UUID NOT NULL,
    observed_epoch_second BIGINT NOT NULL,
    observed_nano INTEGER NOT NULL CHECK (observed_nano BETWEEN 0 AND 999999999)
);

CREATE TABLE underground_current_line (
    line_id TEXT PRIMARY KEY CHECK (btrim(line_id) <> ''),
    snapshot_id SMALLINT NOT NULL DEFAULT 1 REFERENCES underground_current_snapshot(singleton),
    line_name TEXT NOT NULL CHECK (btrim(line_name) <> ''),
    line_order INTEGER NOT NULL UNIQUE CHECK (line_order >= 0)
);

CREATE TABLE underground_current_status (
    line_id TEXT NOT NULL REFERENCES underground_current_line(line_id) ON DELETE CASCADE,
    status_order INTEGER NOT NULL CHECK (status_order >= 0),
    severity INTEGER NOT NULL,
    description TEXT NOT NULL CHECK (btrim(description) <> ''),
    reason TEXT,
    PRIMARY KEY (line_id, status_order)
);
