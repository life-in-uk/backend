#!/usr/bin/env bash
# Builds the production image on the workstation (deploy/RUNBOOK.md "Backend release").
#
#   deploy/scripts/build-image.sh
#
# Requires a clean tracked tree, runs `mvn -B verify` (all tests against a
# temporary PostgreSQL), builds lifeinuk-backend:<12-char commit> and saves it
# as a gzipped archive for transfer to the server (no registry is used).
# Set MVN to the Maven binary if it is not on PATH.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }

[ -z "$(git status --porcelain --untracked-files=no)" ] || { log "commit or stash tracked changes first"; exit 1; }
commit=$(git rev-parse HEAD)
tag="lifeinuk-backend:${commit:0:12}"
mvn=${MVN:-$(command -v mvn || ls "$HOME"/.m2/wrapper/dists/apache-maven-*/*/apache-maven-*/bin/mvn 2>/dev/null | tail -1)}
[ -x "$mvn" ] || { log "Maven not found; set MVN"; exit 1; }

log "mvn verify at ${commit:0:12}"
"$mvn" -B -q verify
docker build --pull=false --build-arg "REVISION=$commit" -t "$tag" .
out=${OUT_DIR:-$HOME/.local/state/life-in-uk/release-images}
mkdir -p "$out"
archive="$out/${tag/:/-}.tar.gz"
docker save "$tag" | gzip -1 > "$archive"
(cd "$out" && sha256sum "$(basename "$archive")" > "$(basename "$archive").sha256")
log "built $tag → $archive ($(du -h "$archive" | cut -f1))"
printf '%s\n' "$tag"
