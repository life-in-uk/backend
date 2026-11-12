# Local real-AI Guide pipeline

The tested mock workflow remains `workflows/life-in-uk-five-locale-mock-guide.json`. The real-model extension is `workflows/life-in-uk-five-locale-ai-draft.json`; it is inactive, manually triggered, and stops at the existing owner approval gate. Both use the existing Spring Boot editorial API and database model.

The real workflow calls the local adapter at `127.0.0.1:8092`. The adapter then calls only the three provider URLs configured in its protected local environment file. It has no database access, filesystem access to Guides, shell execution endpoint, publication route, or source-fetch route. Source material must be supplied to the workflow as verified text and provenance; it is not discovered automatically in this phase.

## Provider contracts and configuration

The adapter supports:

- Sol 6.1 for a cited policy-analysis brief and the Chinese source draft using an OpenAI-compatible Chat Completions API.
- Terra 6.1 for the four locale translations using an OpenAI-compatible Chat Completions API.
- Claude for independent factual and translation review using an Anthropic-compatible Messages API.

These are protocol requirements, not assumptions that the products expose those APIs. The owner must confirm provider endpoint, authentication, model identifier, usage fields, and API version. If a provider does not implement the stated protocol, add and test its protocol adapter before configuring or enabling it.

Copy `ai-provider.env.example` to `life-in-uk-frontend/.local/ai-provider.env` and set mode `0600`. That exact path is ignored by Git. Do not copy real keys into this repository or into workflow JSON. Keep `AI_ADAPTER_ENABLED=false` until all provider checks and owner actions below are complete. The file intentionally contains blank provider URL, key, and model fields. Model identifiers are configurable through `SOL_MODEL_ID`, `TERRA_MODEL_ID`, and `CLAUDE_MODEL_ID`; do not infer them from product names.

`AI_ADAPTER_TOKEN` is a separate local bearer secret between n8n and the adapter. It is not a provider key. Provider keys are read only by the adapter. The adapter binds to `127.0.0.1`, requires HTTPS provider URLs, rejects redirects, applies fixed-source and request-size checks, rate-limits calls, and writes usage-only records to `AI_ADAPTER_METRICS_FILE` with restrictive permissions. `AI_ADAPTER_IDEMPOTENCY_DIR` stores mode-0600 claim markers keyed by a one-way hash; the key value and content are not stored. Keep this directory persistent and protected alongside adapter backups; deleting claims would allow old requests to be replayed. A repeated or concurrent generation key, or repeated review of the same locale hash, is rejected before another provider request. Usage records identify `SUCCEEDED`, `OUTPUT_INVALID`, or `PROVIDER_ERROR` attempts and include token usage when returned, latency, and hashes. They do not contain prompts, source text, Guide bodies, or API keys. Cost remains null unless all applicable input/output/cache rates are configured.

The workflow template has an empty `sourceMaterials` array by design. Before a manual run, the editor must provide a unique idempotency key, verified policy/source versions, a valid slug, a verified topic candidate, and one or more allowlisted source items with `key`, `organisation`, `title`, absolute HTTPS `url`, `accessedAt`, and source text in `content`. `AI_SOURCE_ALLOWLIST` must name the verified official source hosts. Do not put unverified or speculative material in this input.

## Local adapter start

After provider access has been confirmed, load the protected env file and start the adapter from the backend checkout:

```bash
set -a
. /home/yejun_guan/projects/life-in-uk-frontend/.local/ai-provider.env
set +a
node ops/n8n/ai-provider-adapter.mjs
```

This process does not start automatically. With `AI_ADAPTER_ENABLED=false`, `/healthz` works but generation/review routes return HTTP 503. The adapter refuses live startup if required provider IDs, API URLs, keys, API version, source allowlist, adapter token, or absolute metrics path are missing. Never change `AI_ADAPTER_BIND` from `127.0.0.1` for this local POC.

## n8n credentials and workflow

Import `workflows/life-in-uk-five-locale-ai-draft.json` only after provider access is confirmed. Set up these n8n Header Auth credentials locally; credential values belong in n8n's encrypted credential store and must not be added to exported JSON:

1. `Life UK Local AI Adapter`: `Authorization: Bearer <AI_ADAPTER_TOKEN>`.
2. `Life UK Editorial Draft API`: the existing draft-ingestion token.
3. `Life UK Editorial Claude Review API`: the separate review token that Spring Boot maps to `claude-independent`.

Do not add the human approval token to n8n or any model credential. Do not use the mock-review token for Claude. The workflow deliberately has `active: false`; it calls draft and review APIs only and contains no approval or publication request. Its paid-provider nodes have automatic retries disabled. The owner must keep it inactive until confirming the requirements below.

The adapter makes a separate Sol policy-analysis call before the Chinese draft; each finding/date/question must cite supplied source keys. It returns exact Guide JSON after schema, source provenance, source-excerpt, evidence-reference, and translation-link checks. Before review, it recomputes Spring's `guide-content-v1` canonical SHA-256 and rejects a mismatch. A shared digest fixture is checked by both Node and Java tests. Spring Boot remains authoritative for Guide validation and canonical content hashes. Claude receives the persisted Guide `contentHash`; its returned review is stored using the same hash. The workflow's final node verifies all five locale hashes/review hashes and reports usage for two Sol calls, four Terra calls, and five Claude reviews, then stops for explicit human approval.

If a provider request times out or returns invalid output, its durable claim remains consumed. Do not retry or replay the same key. Check the adapter usage log, provider usage/billing console, and Spring batch state first. Only after confirming that no completed batch needs recovery should an operator create a new editorial idempotency key and manually retry. A timeout may still have been billed by the provider; the guard prevents silent automatic duplicate calls but cannot resolve provider-side timeout ambiguity.

The existing native n8n environment disables saving successful, failed, and manual execution data and prunes retained history (`EXECUTIONS_DATA_SAVE_ON_SUCCESS=none`, `EXECUTIONS_DATA_SAVE_ON_ERROR=none`, `EXECUTIONS_DATA_SAVE_MANUAL_EXECUTIONS=false`). Provider keys remain in the adapter environment and are never sent to n8n. n8n stores only its separate encrypted adapter and Spring editorial credentials. Keep these execution settings in place for the controlled test.

## Owner actions before activation

1. Confirm that separate API credentials and billing are available for Sol 6.1, Terra 6.1, and Claude; a ChatGPT subscription alone is not an API credential.
2. Obtain and verify each provider's API protocol, HTTPS endpoint, exact model identifier, authentication format, usage schema, and—where applicable—required API-version header.
3. Populate the protected local env file and adapter bearer token. Set cost rates only from the verified provider price schedule.
4. Confirm the official source host allowlist and the source-material provenance/content supplied to the workflow.
5. Run unit and integration tests against provider-compatible test endpoints or local stubs. A model endpoint must be reviewed before real credentials are used.
6. Import the inactive workflow, bind the three local credentials, run one manually, inspect the persisted five-locale batch and hash-specific reviews, and verify the final `WAITING_FOR_EXPLICIT_OWNER_APPROVAL` output.
7. Keep publication manual. Any later workflow activation is a separate owner decision; no automated approval, publication, expiry deletion, or V10 body redaction is included here.

## Regression checks

```bash
node --test ops/n8n/tests/*.test.mjs
node --check ops/n8n/ai-provider-adapter.mjs
```

The existing mock adapter and mock workflow remain available and do not invoke real providers.
