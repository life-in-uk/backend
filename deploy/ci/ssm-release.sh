#!/usr/bin/env bash
# Runs the SSM document LifeInUK-BackendRelease on the instance and waits for it
# (GitHub deploy workflow; docs/ci-cd.md). Prints the script's output; exits 0
# only if the command succeeded. Standard output is also written to $SSM_OUT.
#
#   INSTANCE_ID=i-… SSM_DOCUMENT=LifeInUK-BackendRelease deploy/ci/ssm-release.sh <action> [name=value …]
set -euo pipefail
: "${INSTANCE_ID:?}" "${SSM_DOCUMENT:?}"
out=${SSM_OUT:-/dev/null}
params=$(python3 -c 'import json,sys; p={"action":[sys.argv[1]]}; p.update({k:[v] for k,v in (a.split("=",1) for a in sys.argv[2:])}); print(json.dumps(p))' "$@")
id=$(aws ssm send-command --instance-ids "$INSTANCE_ID" --document-name "$SSM_DOCUMENT" --parameters "$params" \
  --comment "GitHub ${GITHUB_REPOSITORY:-local} run ${GITHUB_RUN_ID:-0}: $1" --timeout-seconds 600 \
  --query Command.CommandId --output text)
echo "SSM command $id ($1)"
state=Pending
for _ in $(seq 1 120); do
  sleep "${SSM_POLL_SECONDS:-10}"
  state=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE_ID" --query Status --output text 2>/dev/null || echo Pending)
  case "$state" in Pending|InProgress|Delayed) continue ;; *) break ;; esac
done
aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE_ID" --query StandardOutputContent --output text | tee "$out"
echo "--- server log ---"
aws ssm get-command-invocation --command-id "$id" --instance-id "$INSTANCE_ID" --query StandardErrorContent --output text
echo "SSM command $id: $state"
[ "$state" = Success ]
