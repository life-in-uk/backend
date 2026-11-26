-- BTC/USD and ETH/USD through the same collector, batches and tables as the four stock indices
-- (docs/market-indices.md, "Crypto"). One batch may now carry all six instruments.
--   reference_24h      the USD price exactly 24 hours before observed_at, crypto only, and only when the collector
--                      verified a continuous window; NULL means "no 24-hour change", never "unchanged".
--   reference_24h_at   observed_at - 24 hours (stored, and checked, so a different window can never be stored).
-- Crypto rows never carry previous_close; index rows never carry a 24-hour reference. Existing rows are unchanged.
-- Additive for the previous image: it reads only its own four symbols and ignores the new columns.

ALTER TABLE market_index_ingest_batch DROP CONSTRAINT market_index_ingest_batch_observation_count_check;
ALTER TABLE market_index_ingest_batch ADD CONSTRAINT market_index_ingest_batch_observation_count_check
    CHECK (observation_count BETWEEN 1 AND 6);

ALTER TABLE market_index_observation DROP CONSTRAINT market_index_observation_symbol_check;
ALTER TABLE market_index_observation ADD CONSTRAINT market_index_observation_symbol_check
    CHECK (symbol IN ('UKX', 'SPX', 'IXIC', 'DJI', 'BTCUSD', 'ETHUSD'));

ALTER TABLE market_index_observation
    ADD COLUMN reference_24h    numeric(12,4),
    ADD COLUMN reference_24h_at timestamptz;

ALTER TABLE market_index_observation ADD CONSTRAINT market_index_observation_reference_24h CHECK (
    (reference_24h IS NULL) = (reference_24h_at IS NULL)
    AND (reference_24h IS NULL OR (reference_24h > 0 AND reference_24h_at = observed_at - interval '24 hours'))
    AND (symbol IN ('BTCUSD', 'ETHUSD') OR reference_24h IS NULL)
    AND (symbol NOT IN ('BTCUSD', 'ETHUSD') OR previous_close IS NULL)
);

-- Per-pair crypto collection health, reported in the collector heartbeat's optional "instruments" list and kept
-- separately from the source's (stock-index) heartbeat row, whose meaning is unchanged. One row per source and pair,
-- replaced with the source's newest applied heartbeat; a heartbeat without the list (crypto off) removes the rows.
-- A new table only: the previous image never reads it.
CREATE TABLE market_index_instrument_health (
    source                         text NOT NULL REFERENCES market_index_collector_heartbeat (source) ON DELETE CASCADE,
    symbol                         text NOT NULL CHECK (symbol IN ('BTCUSD', 'ETHUSD')),
    status                         text NOT NULL CHECK (status IN ('OK', 'FAILED')),
    failure_code                   text CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    consecutive_failures           integer NOT NULL CHECK (consecutive_failures BETWEEN 0 AND 1000000),
    last_attempt_at                timestamptz NOT NULL,
    last_successful_collection_at  timestamptz,
    sent_at                        timestamptz NOT NULL,
    PRIMARY KEY (source, symbol),
    CHECK ((status = 'OK') = (consecutive_failures = 0) AND (status = 'OK') = (failure_code IS NULL)),
    CHECK (status = 'FAILED' OR (last_successful_collection_at IS NOT NULL AND last_successful_collection_at >= last_attempt_at)),
    CHECK (status = 'OK' OR last_successful_collection_at IS NULL OR last_successful_collection_at < last_attempt_at)
);

-- Upstream corrections (docs/market-indices.md, "Corrections"). Until V18 a second, different value for an existing
-- (symbol, source, observed_at) rejected the whole batch (409 OBSERVATION_CONFLICT), so one revised close discarded
-- every other instrument in that batch and every later batch that repeated it. From V18 the value collected latest
-- wins: the stored row is updated in place only when the correcting batch's (collected_at, batch_id) is greater than
-- that of the batch that last set it, so an older or replayed batch can never overwrite a newer correction. The value
-- it replaces is kept here, insert-only. Rows are removed with their observation (retention). Batch ids are kept as
-- plain values, not foreign keys, so batch retention is unchanged. A new table only: the previous image never reads it
-- (and, after a rollback, answers 409 for a differing value again, as before).
CREATE TABLE market_index_observation_revision (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    observation_id              bigint NOT NULL REFERENCES market_index_observation (id) ON DELETE CASCADE,
    symbol                      text NOT NULL,
    source                      text NOT NULL,
    observed_at                 timestamptz NOT NULL,
    previous_observed_at_source text NOT NULL CHECK (previous_observed_at_source IN ('EXCHANGE', 'PROVIDER', 'COLLECTOR')),
    previous_value              numeric(12,4) NOT NULL CHECK (previous_value > 0),
    previous_previous_close     numeric(12,4),
    previous_reference_24h      numeric(12,4),
    previous_reference_24h_at   timestamptz,
    previous_batch_id           uuid NOT NULL,
    previous_collected_at       timestamptz NOT NULL,
    previous_received_at        timestamptz NOT NULL,
    batch_id                    uuid NOT NULL,
    collected_at                timestamptz NOT NULL,
    revised_at                  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (collected_at > previous_collected_at OR (collected_at = previous_collected_at AND batch_id > previous_batch_id))
);

CREATE INDEX market_index_observation_revision_observation ON market_index_observation_revision (observation_id);
