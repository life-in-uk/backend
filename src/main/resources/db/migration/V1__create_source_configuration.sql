CREATE TABLE source (
    id UUID PRIMARY KEY,
    source_key VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL CHECK (btrim(display_name) <> ''),
    scope VARCHAR(40) NOT NULL CHECK (scope = 'UK_BANK_HOLIDAYS'),
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    CHECK (source_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE source_endpoint (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES source(id),
    endpoint_key VARCHAR(100) NOT NULL UNIQUE CHECK (endpoint_key = 'gov-uk-bank-holidays-json'),
    url VARCHAR(300) NOT NULL CHECK (url = 'https://www.gov.uk/bank-holidays.json'),
    qualification_status VARCHAR(20) NOT NULL CHECK (qualification_status IN ('PENDING', 'QUALIFIED')),
    qualification_record TEXT,
    attribution_reference VARCHAR(300) NOT NULL CHECK (btrim(attribution_reference) <> ''),
    use_retention_policy TEXT NOT NULL CHECK (btrim(use_retention_policy) <> ''),
    enabled BOOLEAN NOT NULL,
    calendar_scope VARCHAR(20) NOT NULL CHECK (calendar_scope = 'CURRENT_YEAR'),
    poll_interval_seconds INTEGER NOT NULL CHECK (poll_interval_seconds = 86400),
    next_poll_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    CHECK (qualification_status <> 'QUALIFIED'
        OR (qualification_record IS NOT NULL AND btrim(qualification_record) <> ''))
);
