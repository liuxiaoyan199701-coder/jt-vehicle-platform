#!/usr/bin/env bash
set -euo pipefail

DEPLOY_LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd -- "${DEPLOY_LIB_DIR}/.." && pwd)"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '==> %s\n' "$*"
}

require_root() {
  [[ "$(id -u)" -eq 0 ]] || die "this command must run as root"
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || die "required command not found: ${command_name}"
}

load_deploy_env() {
  local env_file="${DEPLOY_ENV_FILE:-/etc/jt-deploy/deploy.env}"
  [[ -f "$env_file" ]] || die "deployment environment file not found: ${env_file}"
  [[ -r "$env_file" ]] || die "deployment environment file is not readable: ${env_file}"
  set -a
  # The deployment file is root-controlled input and intentionally uses shell
  # environment syntax so paths containing spaces remain representable.
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
  DEPLOY_ENV_FILE="$env_file"
  export DEPLOY_ENV_FILE
}

require_var() {
  local variable_name="$1"
  [[ -n "${!variable_name:-}" ]] || die "required deployment value is empty: ${variable_name}"
}

require_absolute_path() {
  local variable_name="$1"
  local value="${!variable_name:-}"
  [[ "$value" == /* ]] || die "${variable_name} must be an absolute path"
  [[ "$value" != "/" ]] || die "${variable_name} must not be the filesystem root"
}

validate_public_host() {
  local host="$1"
  [[ "$host" =~ ^[A-Za-z0-9.-]+$ ]] || die "PUBLIC_HOST must be a DNS name or IPv4 address"
  [[ "$host" != .* && "$host" != *. ]] || die "PUBLIC_HOST has an invalid leading or trailing dot"
}

validate_release_id() {
  local release_id="$1"
  [[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] \
    || die "RELEASE_ID must be 1-128 safe filename characters"
}

validate_sha256() {
  local variable_name="$1"
  local value="${!variable_name:-}"
  [[ "$value" =~ ^[A-Fa-f0-9]{64}$ ]] || die "${variable_name} must be a 64-character SHA-256 digest"
}

verify_sha256() {
  local file="$1"
  local expected="${2,,}"
  local actual
  actual="$(sha256sum -- "$file" | awk '{print tolower($1)}')"
  [[ "$actual" == "$expected" ]] || die "SHA-256 mismatch for ${file}"
}

read_env_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 1
  awk -v key="$key" '
    index($0, key "=") == 1 {
      value = substr($0, length(key) + 2)
      if (value ~ /^\047.*\047$/ || value ~ /^".*"$/) {
        value = substr(value, 2, length(value) - 2)
      }
      print value
      found = 1
      exit
    }
    END { if (!found) exit 1 }
  ' "$file"
}

ensure_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"
  if read_env_value "$file" "$key" >/dev/null 2>&1; then
    return 0
  fi
  [[ "$value" != *$'\n'* && "$value" != *"'"* ]] || die "unsafe value for ${key}"
  printf "%s='%s'\n" "$key" "$value" >>"$file"
}

assert_service_env_permissions() {
  local file="$1"
  local expected_group="$2"
  local owner group mode
  owner="$(stat -c '%U' "$file")"
  group="$(stat -c '%G' "$file")"
  mode="$(stat -c '%a' "$file")"
  [[ "$owner" == root ]] || die "${file} must be owned by root"
  [[ "$group" == "$expected_group" ]] || die "${file} must use group ${expected_group}"
  [[ "$mode" == 640 || "$mode" == 600 ]] || die "${file} permissions must be 0640 or stricter"
}

wait_for_http_content() {
  local url="$1"
  local expected="$2"
  local timeout_seconds="${3:-${HEALTH_TIMEOUT_SECONDS:-60}}"
  local retry_seconds="${4:-${HEALTH_RETRY_SECONDS:-1}}"
  local deadline=$((SECONDS + timeout_seconds))
  local response
  local compact_response

  while ((SECONDS < deadline)); do
    if response="$(curl --fail --silent --show-error --max-time 5 "$url" 2>/dev/null)" \
      && compact_response="$(printf '%s' "$response" | tr -d '[:space:]')"; then
      if [[ "$compact_response" == *"$expected"* ]]; then
        return 0
      fi
    fi
    sleep "$retry_seconds"
  done
  die "health check timed out for ${url}"
}

atomic_symlink() {
  local target="$1"
  local link="$2"
  local temporary="${link}.new.$$"
  ln -s -- "$target" "$temporary"
  mv -Tf -- "$temporary" "$link"
}
