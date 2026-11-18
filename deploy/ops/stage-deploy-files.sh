#!/usr/bin/env bash
# Workstation: packs the deployment files of a commit for update-deploy-files.sh
# (docs/deploy-files-update.md). Reads only git; uploads nothing.
#
#   deploy/ops/stage-deploy-files.sh <commit> <out dir>
#
# Writes <out dir>/deploy-files.tar.gz (compose.yml nginx db scripts systemd env
# certbot from deploy/ at <commit>) and prints its sha256 and the commit's
# deploy-files digest (what CI and release.sh compare).
set -euo pipefail
repo=$(git -C "$(dirname "$(readlink -f "$0")")" rev-parse --show-toplevel)
commit=$(git -C "$repo" rev-parse --verify "${1:?usage: stage-deploy-files.sh <commit> <out dir>}^{commit}")
out=${2:?usage: stage-deploy-files.sh <commit> <out dir>}
mkdir -p "$out"
tree=$(mktemp -d); trap 'rm -rf "$tree"' EXIT
git -C "$repo" archive --format=tar "$commit" deploy | tar -xf - -C "$tree"
source "$repo/deploy/scripts/common.sh"
parts=()
for p in compose.yml nginx db scripts systemd env certbot; do [ ! -e "$tree/deploy/$p" ] || parts+=("$p"); done
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 -czf "$out/deploy-files.tar.gz" -C "$tree/deploy" "${parts[@]}"
echo "commit=$commit"
echo "archive=$out/deploy-files.tar.gz"
echo "archive_sha256=$(sha256sum "$out/deploy-files.tar.gz" | cut -c1-64)"
echo "deploy_files_digest=$(deploy_files_digest "$tree/deploy")"
