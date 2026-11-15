# Local Guide editorial credentials

Editorial credentials belong in an owner-controlled file outside this repository, for example:

`/home/yejun_guan/.config/life-in-uk/editorial-local.yml`

Create the directory and file with owner-only permissions (`umask 077`; directory mode `700`, file mode `600`). Do not add this file to Git or put its values in shell history, workflow exports, model prompts, logs, or screenshots. The backend can load it with:

```sh
SPRING_CONFIG_ADDITIONAL_LOCATION=file:/home/yejun_guan/.config/life-in-uk/editorial-local.yml
```

The file uses Spring property names and owner-generated values:

```yaml
life-in-uk:
  editorial:
    draft-token: "<unique local draft-ingestion credential>"
    review-token: "<different local review credential>"
    approval-token: "<different owner-only approval credential>"
    approval-actor: "<owner identity recorded in the publication audit>"
```

All three credentials must be different. The API fails closed with HTTP 503 when a required credential is unset. Draft credentials can create/read batches and submit locale content. The review credential can submit hash-bound review results. Only the owner should hold the approval credential; do not give it to n8n, Sol, or Claude. The approval actor is server configuration, not a caller-controlled request header. This local configuration is not enabled or populated by this change.

Use separate values for every environment. Rotate credentials through the local secret/configuration process; never create real credentials in a committed example.
