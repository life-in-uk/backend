-- Generalise only V1's single-provider constraints. V1/V2 remain accepted history.
ALTER TABLE source DROP CONSTRAINT source_scope_check;
ALTER TABLE source ADD CONSTRAINT source_scope_check CHECK (
    (scope = 'UK_BANK_HOLIDAYS' AND source_key <> 'transport-for-london')
    OR (scope = 'TFL_UNDERGROUND_STATUS' AND source_key = 'transport-for-london')
);

ALTER TABLE source_endpoint DROP CONSTRAINT source_endpoint_endpoint_key_check;
ALTER TABLE source_endpoint DROP CONSTRAINT source_endpoint_url_check;
ALTER TABLE source_endpoint DROP CONSTRAINT source_endpoint_calendar_scope_check;
ALTER TABLE source_endpoint DROP CONSTRAINT source_endpoint_poll_interval_seconds_check;
ALTER TABLE source_endpoint ALTER COLUMN calendar_scope DROP NOT NULL;
ALTER TABLE source_endpoint ALTER COLUMN poll_interval_seconds DROP NOT NULL;
ALTER TABLE source_endpoint ALTER COLUMN next_poll_at DROP NOT NULL;
ALTER TABLE source_endpoint ADD CONSTRAINT source_endpoint_canonical_configuration CHECK (
    (endpoint_key = 'gov-uk-bank-holidays-json' AND url = 'https://www.gov.uk/bank-holidays.json'
        AND calendar_scope IS NOT NULL AND calendar_scope = 'CURRENT_YEAR'
        AND poll_interval_seconds IS NOT NULL AND poll_interval_seconds = 86400
        AND next_poll_at IS NOT NULL)
    OR
    (endpoint_key = 'tfl-underground-status' AND url = 'https://api.tfl.gov.uk/Line/Mode/tube/Status'
        AND calendar_scope IS NULL AND poll_interval_seconds IS NULL AND next_poll_at IS NULL)
);
-- No history, acquisition, interpreted-data or retention machinery is added.

-- V1 had only one possible key/URL and scope; admitting a second pair must not
-- permit rewriting historical provenance by switching between the valid pairs.
CREATE FUNCTION protect_endpoint_key_url() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.endpoint_key <> OLD.endpoint_key OR NEW.url <> OLD.url THEN
        RAISE EXCEPTION 'Endpoint key and URL cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_endpoint_identity BEFORE UPDATE ON source_endpoint
    FOR EACH ROW EXECUTE FUNCTION protect_endpoint_key_url();

CREATE FUNCTION protect_source_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.scope <> OLD.scope THEN
        RAISE EXCEPTION 'Source scope cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_scope_identity BEFORE UPDATE ON source
    FOR EACH ROW EXECUTE FUNCTION protect_source_scope();
