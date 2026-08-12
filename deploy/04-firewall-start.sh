#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
load_deploy_env
FIREWALL_MODE="${FIREWALL_MODE:-}"

if [[ "$FIREWALL_MODE" == external ]]; then
  [[ "${EXTERNAL_FIREWALL_CONFIRMED:-false}" == true ]] \
    || die "external firewall mode requires EXTERNAL_FIREWALL_CONFIRMED=true"
  info "external firewall protection was explicitly confirmed; no host rules were changed"
  exit 0
fi

[[ "$FIREWALL_MODE" == ufw ]] || die "FIREWALL_MODE must be ufw or external"
require_command ufw
SSH_PORT="${SSH_PORT:-22}"
[[ "$SSH_PORT" =~ ^[0-9]+$ && "$SSH_PORT" -ge 1 && "$SSH_PORT" -le 65535 ]] \
  || die "SSH_PORT must be a valid TCP port"

info "applying the minimum host firewall allow list"
ufw default deny incoming
ufw default allow outgoing
ufw allow "${SSH_PORT}/tcp" comment 'restricted SSH administration'
ufw allow 80/tcp comment 'HTTP redirect and certificate validation'
ufw allow 443/tcp comment 'HTTPS and browser WSS'
ufw allow 7100/tcp comment 'JT/T 808 TCP'
ufw allow 7101/udp comment 'JT/T 808 UDP'

# Remove rules created by the former prototype before adding the reduced media
# range. Absence is expected on a clean host.
ufw --force delete allow 7811:7815/tcp >/dev/null 2>&1 || true
for private_port in 7810 7815 8100 8109 8300; do
  ufw --force delete allow "${private_port}/tcp" >/dev/null 2>&1 || true
done
ufw allow 7811:7814/tcp comment 'JT/T 1078 device ingress'
ufw --force enable
ufw reload

ufw_status="$(ufw status verbose)"
[[ "$ufw_status" == *"Status: active"* ]] || die "ufw did not become active"
for required_rule in "${SSH_PORT}/tcp" 80/tcp 443/tcp 7100/tcp 7101/udp 7811:7814/tcp; do
  [[ "$ufw_status" == *"$required_rule"* ]] || die "ufw rule missing after reload: ${required_rule}"
done
info "host firewall is active with the minimum public port set"

