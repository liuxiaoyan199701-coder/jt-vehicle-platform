#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

load_deploy_env
require_command curl
require_var JDK_ARCHIVE_URL
validate_sha256 JDK_ARCHIVE_SHA256

info "checking the configured JDK release endpoint"
curl --fail --silent --show-error --location \
  --range 0-0 \
  --connect-timeout 10 \
  --max-time 20 \
  --output /dev/null \
  "$JDK_ARCHIVE_URL"
info "the configured JDK endpoint is reachable; the archive digest will be verified during installation"

