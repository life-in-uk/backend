# Life in UK backend

## Source foundation (Issue #2)

Flyway creates `source` and `source_endpoint`; Hibernate uses `ddl-auto: validate`.
Startup inserts the GOV.UK Bank Holidays configuration if absent. It performs no
HTTP requests and runs no polling timer. Existing records, IDs, enablement,
qualification, versions and polling eligibility are never overwritten by bootstrap.

The only supported endpoint is `https://www.gov.uk/bank-holidays.json`. Its URL is
fixed in the model and constrained in PostgreSQL. The calendar scope is rolling
`CURRENT_YEAR` in Europe/London, with daily polling intent and a persisted
`next_poll_at`. These are policy data, not a scheduler or observation history.

Configuration is **not permission**. New configuration defaults to `PENDING`.
To initialize a **new** qualified endpoint, explicitly supply all three properties:

- `life-in-uk.source.bank-holidays.qualified=true`
- `life-in-uk.source.bank-holidays.qualification-record=<owner qualification decision>`
- `life-in-uk.source.bank-holidays.use-retention-policy=<approved use, attribution and retention policy>`

Use standard Spring configuration/environment binding. The qualification input is
an owner decision, not a legal approval inferred by the application. Setting these
properties on a later restart does not upgrade an existing pending endpoint.
Existing policy changes require a deliberate source-owned operation; there is no
management API in this issue. Both source and endpoint must be enabled, and the
endpoint explicitly qualified, before `isQualifiedAndEnabled()` returns true.

## Tests

Run `mvn test` and `mvn verify` as a non-root user with Java 21 and PostgreSQL 16
server binaries installed. On Ubuntu the default binary path is
`/usr/lib/postgresql/16/bin`; elsewhere set `TEST_POSTGRES_BIN` to their directory.

Tests automatically initialize an empty temporary PostgreSQL cluster, bind it to
loopback on a temporary port, apply Flyway, validate with Hibernate, and stop/remove
the cluster when the test JVM exits. No manually prepared database is needed.
Tests never read `DB_URL`, `DB_USERNAME` or `DB_PASSWORD`; there is no development
database fallback. A test without the initializer fails against an intentionally
unusable test URL. Missing binaries fail the suite rather than skipping it.

No GOV.UK, Ollama, Docker or additional runtime/testing service is required.
After Maven dependencies have been cached, the tests can run offline.
