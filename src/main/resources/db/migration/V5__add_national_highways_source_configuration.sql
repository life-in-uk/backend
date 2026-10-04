-- Admit the National Highways Road and Lane Closures v2 identity alongside the two existing
-- controlled providers. Constraints only: no rows are seeded and no credential column exists.
-- V3's key/URL, scope and observed-ownership triggers continue to apply unchanged.
ALTER TABLE source DROP CONSTRAINT source_scope_check;
ALTER TABLE source ADD CONSTRAINT source_scope_check CHECK (
    (scope = 'UK_BANK_HOLIDAYS' AND source_key NOT IN ('transport-for-london', 'national-highways'))
    OR (scope = 'TFL_UNDERGROUND_STATUS' AND source_key = 'transport-for-london')
    OR (scope = 'NATIONAL_HIGHWAYS_ROAD_CLOSURES' AND source_key = 'national-highways')
);

ALTER TABLE source_endpoint DROP CONSTRAINT source_endpoint_canonical_configuration;
ALTER TABLE source_endpoint ADD CONSTRAINT source_endpoint_canonical_configuration CHECK (
    (endpoint_key = 'gov-uk-bank-holidays-json' AND url = 'https://www.gov.uk/bank-holidays.json'
        AND calendar_scope IS NOT NULL AND calendar_scope = 'CURRENT_YEAR'
        AND poll_interval_seconds IS NOT NULL AND poll_interval_seconds = 86400
        AND next_poll_at IS NOT NULL)
    OR
    (endpoint_key = 'tfl-underground-status' AND url = 'https://api.tfl.gov.uk/Line/Mode/tube/Status'
        AND calendar_scope IS NULL AND poll_interval_seconds IS NULL AND next_poll_at IS NULL)
    OR
    -- One endpoint for planned and unplanned closures: query modes are not separate identities.
    (endpoint_key = 'national-highways-road-closures'
        AND url = 'https://api.data.nationalhighways.co.uk/roads/v2.0/closures'
        AND calendar_scope IS NULL AND poll_interval_seconds IS NULL AND next_poll_at IS NULL)
);
