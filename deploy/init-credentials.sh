#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
load_deploy_env
require_command openssl
require_command htpasswd
require_var PUBLIC_HOST
validate_public_host "$PUBLIC_HOST"

CONSOLE_ADMIN_USERNAME="${CONSOLE_ADMIN_USERNAME:-admin}"
BCRYPT_COST="${BCRYPT_COST:-12}"
[[ "$BCRYPT_COST" =~ ^(1[0-7]|[4-9])$ ]] || die "BCRYPT_COST must be between 4 and 17"

CONSOLE_ENV_FILE="${CONSOLE_ENV_FILE:-/etc/jt-console/jt-console.env}"
PLATFORM_ENV_FILE="${PLATFORM_ENV_FILE:-/etc/jt-platform/jt-platform.env}"
ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:-/etc/jt-console/admin-initial-password}"
INGEST_KEY_FILE="${INGEST_KEY_FILE:-/etc/jt-console/ingest.key}"
VERIFY_ENV_FILE="${VERIFY_ENV_FILE:-/etc/jt-console/verify.env}"

install -d -o root -g jt-console -m 0750 "$(dirname -- "$CONSOLE_ENV_FILE")"
install -d -o root -g jt-platform -m 0750 "$(dirname -- "$PLATFORM_ENV_FILE")"
[[ -e "$CONSOLE_ENV_FILE" ]] || install -o root -g jt-console -m 0640 /dev/null "$CONSOLE_ENV_FILE"
[[ -e "$PLATFORM_ENV_FILE" ]] || install -o root -g jt-platform -m 0640 /dev/null "$PLATFORM_ENV_FILE"
chown root:jt-console "$CONSOLE_ENV_FILE"
chown root:jt-platform "$PLATFORM_ENV_FILE"
chmod 0640 "$CONSOLE_ENV_FILE" "$PLATFORM_ENV_FILE"

existing_admin_username="$(read_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_ADMIN_USERNAME || true)"
if [[ -n "$existing_admin_username" ]]; then
  CONSOLE_ADMIN_USERNAME="$existing_admin_username"
fi
[[ "$CONSOLE_ADMIN_USERNAME" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "invalid CONSOLE_ADMIN_USERNAME"
existing_deployment_mode="$(read_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_DEPLOYMENT_MODE || true)"
[[ -z "$existing_deployment_mode" || "$existing_deployment_mode" == true ]] \
  || die "existing console environment must enable deployment mode"

console_key="$(read_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_INGEST_KEY || true)"
platform_key="$(read_env_value "$PLATFORM_ENV_FILE" JT_PLATFORM_INGEST_KEY || true)"
if [[ -n "$console_key" && -n "$platform_key" && "$console_key" != "$platform_key" ]]; then
  die "existing console and platform ingest keys differ; explicit rotation is required"
fi
ingest_key="${console_key:-$platform_key}"
if [[ -z "$ingest_key" ]]; then
  ingest_key="$(openssl rand -hex 32)"
fi
[[ "$ingest_key" =~ ^[A-Za-z0-9_+/=-]{43,}$ ]] || die "existing ingest key does not meet the minimum format"

admin_hash="$(read_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_ADMIN_PASSWORD_HASH || true)"
if [[ -z "$admin_hash" ]]; then
  if [[ -r "$ADMIN_PASSWORD_FILE" ]]; then
    admin_password="$(tr -d '\r\n' <"$ADMIN_PASSWORD_FILE")"
    [[ -n "$admin_password" ]] || die "existing initial administrator password file is empty"
  else
    admin_password="$(openssl rand -base64 36 | tr -d '\r\n')"
    password_temporary="${ADMIN_PASSWORD_FILE}.new.$$"
    umask 0077
    printf '%s\n' "$admin_password" >"$password_temporary"
    chown root:root "$password_temporary"
    chmod 0600 "$password_temporary"
    mv -f -- "$password_temporary" "$ADMIN_PASSWORD_FILE"
  fi
  admin_hash="$(printf '%s\n' "$admin_password" \
    | htpasswd -inBC "$BCRYPT_COST" "$CONSOLE_ADMIN_USERNAME" \
    | awk -F: 'NR == 1 { print $2 }')"
  unset admin_password
fi
[[ "$admin_hash" =~ ^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]] || die "administrator hash is not a supported BCrypt value"

ensure_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_DEPLOYMENT_MODE true
ensure_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_ADMIN_USERNAME "$CONSOLE_ADMIN_USERNAME"
ensure_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_ADMIN_PASSWORD_HASH "$admin_hash"
ensure_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_INGEST_KEY "$ingest_key"
ensure_env_value "$PLATFORM_ENV_FILE" JT_PLATFORM_INGEST_KEY "$ingest_key"

ingest_temporary="${INGEST_KEY_FILE}.new.$$"
umask 0077
printf '%s\n' "$ingest_key" >"$ingest_temporary"
chown root:root "$ingest_temporary"
chmod 0600 "$ingest_temporary"
mv -f -- "$ingest_temporary" "$INGEST_KEY_FILE"

verify_temporary="${VERIFY_ENV_FILE}.new.$$"
cat >"$verify_temporary" <<EOF
JT_CONSOLE_ADMIN_USERNAME='${CONSOLE_ADMIN_USERNAME}'
JT_CONSOLE_ADMIN_PASSWORD_FILE='${ADMIN_PASSWORD_FILE}'
JT_CONSOLE_INGEST_KEY_FILE='${INGEST_KEY_FILE}'
EOF
chown root:root "$verify_temporary"
chmod 0600 "$verify_temporary"
mv -f -- "$verify_temporary" "$VERIFY_ENV_FILE"

assert_service_env_permissions "$CONSOLE_ENV_FILE" jt-console
assert_service_env_permissions "$PLATFORM_ENV_FILE" jt-platform
info "credentials are initialized; secret values were not printed"
if [[ -f "$ADMIN_PASSWORD_FILE" ]]; then
  info "the one-time administrator password is available only to root at ${ADMIN_PASSWORD_FILE}"
fi
info "verification tools can use JT_CONSOLE_VERIFY_ENV_FILE=${VERIFY_ENV_FILE}"
