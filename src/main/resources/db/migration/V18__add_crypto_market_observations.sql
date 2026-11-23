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
