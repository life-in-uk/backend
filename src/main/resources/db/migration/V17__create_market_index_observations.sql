-- Collector-pushed stock index observations (docs/market-indices.md). Insert-only history:
--   observed_at          upstream observation time, never replaced by any other time;
--   observed_at_source   where observed_at came from (EXCHANGE, PROVIDER or COLLECTOR);
--   collected_at         the collector's local collection time (batch);
--   received_at          server receipt time, for audit and liveness only.
-- "Latest" is the greatest observed_at per symbol and source, so a late or replayed (older) observation is kept as
-- history and can never replace a newer one. No row is updated after insert.

CREATE TABLE market_index_ingest_batch (
    batch_id          uuid PRIMARY KEY,
    source            text NOT NULL CHECK (source ~ '^[a-z0-9][a-z0-9._-]{0,39}$'),
    collected_at      timestamptz NOT NULL,
    request_sha256    char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    observation_count smallint NOT NULL CHECK (observation_count BETWEEN 1 AND 4),
    credential_id     text NOT NULL CHECK (credential_id ~ '^[a-z0-9][a-z0-9-]{0,31}$'),
    received_at       timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE market_index_observation (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol             text NOT NULL CHECK (symbol IN ('UKX', 'SPX', 'IXIC', 'DJI')),
    source             text NOT NULL CHECK (source ~ '^[a-z0-9][a-z0-9._-]{0,39}$'),
    observed_at        timestamptz NOT NULL,
    observed_at_source text NOT NULL CHECK (observed_at_source IN ('EXCHANGE', 'PROVIDER', 'COLLECTOR')),
    value              numeric(12,4) NOT NULL CHECK (value > 0),
    previous_close     numeric(12,4) CHECK (previous_close > 0),
    batch_id           uuid NOT NULL REFERENCES market_index_ingest_batch (batch_id),
    received_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- Idempotency and the latest-value lookup (symbol, source, observed_at DESC) share this index.
    CONSTRAINT market_index_observation_natural UNIQUE (symbol, source, observed_at),
    -- Mock data has no market time: it can only carry a collector-derived timestamp.
    CONSTRAINT market_index_observation_mock_time CHECK (source <> 'mock' OR observed_at_source = 'COLLECTOR')
);

CREATE INDEX market_index_observation_batch ON market_index_observation (batch_id);

-- Collector liveness, one row per source: the newest report by the collector's own sent_at wins. A heartbeat is not
-- an observation: it never creates, changes or dates an index value.
CREATE TABLE market_index_collector_heartbeat (
    source                         text PRIMARY KEY CHECK (source ~ '^[a-z0-9][a-z0-9._-]{0,39}$'),
    credential_id                  text NOT NULL,
    collector_status               text NOT NULL CHECK (collector_status IN ('OK', 'PROVIDER_FAILED')),
    provider_failure_code          text CHECK (provider_failure_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    sent_at                        timestamptz NOT NULL,
    last_attempt_at                timestamptz NOT NULL,
    last_successful_collection_at  timestamptz,
    pending_batches                integer NOT NULL CHECK (pending_batches BETWEEN 0 AND 1000000),
    oldest_pending_collected_at    timestamptz,
    received_at                    timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((collector_status = 'PROVIDER_FAILED') = (provider_failure_code IS NOT NULL))
);
