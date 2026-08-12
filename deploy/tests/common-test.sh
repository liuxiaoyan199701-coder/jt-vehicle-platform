#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
source "${TEST_DIR}/../lib/common.sh"

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

environment_file="${temporary_directory}/service.env"
printf "FIRST='one'\n" >"$environment_file"
[[ "$(read_env_value "$environment_file" FIRST)" == one ]]

ensure_env_value "$environment_file" SECOND 'two'
ensure_env_value "$environment_file" SECOND 'must-not-replace'
[[ "$(read_env_value "$environment_file" SECOND)" == two ]]
[[ "$(grep -c '^SECOND=' "$environment_file")" -eq 1 ]]

artifact="${temporary_directory}/artifact"
printf 'verified-content' >"$artifact"
digest="$(sha256sum "$artifact" | awk '{print $1}')"
verify_sha256 "$artifact" "$digest"

RELEASE_ID='release-2026.08.11'
validate_release_id "$RELEASE_ID"
PUBLIC_HOST='console.example.invalid'
validate_public_host "$PUBLIC_HOST"

printf 'common deployment helper tests passed\n'

