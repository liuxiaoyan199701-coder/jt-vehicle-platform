#!/usr/bin/env bash
set -uo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# 该脚本既在源目录（同级有 lib/）运行，也会被 03-system-setup.sh 安装到
# /usr/local/sbin 供运维随时复检。安装后同级没有 lib/，因此回退到 03 一并
# 安装的共享副本，两种位置都能直接执行。
if [[ -r "${SCRIPT_DIR}/lib/common.sh" ]]; then
  # shellcheck source=lib/common.sh
  source "${SCRIPT_DIR}/lib/common.sh"
elif [[ -r /usr/local/lib/jt-deploy/common.sh ]]; then
  # shellcheck source=lib/common.sh
  source /usr/local/lib/jt-deploy/common.sh
else
  printf 'shared deployment library not found\n' >&2
  exit 1
fi

require_root
load_deploy_env
for command_name in curl jq openssl nginx ss stat systemctl getent runuser mktemp; do
  require_command "$command_name"
done
require_var PUBLIC_HOST
validate_public_host "$PUBLIC_HOST"

failures=0
pass() {
  printf 'PASS: %s\n' "$1"
}
fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}
check() {
  local label="$1"
  shift
  if "$@"; then
    pass "$label"
  else
    fail "$label"
  fi
}

file_has_identity() {
  local file="$1"
  local owner="$2"
  local group="$3"
  local mode="$4"
  [[ -f "$file" ]] \
    && [[ "$(stat -c '%U' "$file")" == "$owner" ]] \
    && [[ "$(stat -c '%G' "$file")" == "$group" ]] \
    && [[ "$(stat -c '%a' "$file")" == "$mode" ]]
}

directory_has_identity() {
  local directory="$1"
  local owner="$2"
  local group="$3"
  local mode="$4"
  [[ -d "$directory" ]] \
    && [[ "$(stat -c '%U' "$directory")" == "$owner" ]] \
    && [[ "$(stat -c '%G' "$directory")" == "$group" ]] \
    && [[ "$(stat -c '%a' "$directory")" == "$mode" ]]
}

service_account_is_restricted() {
  local account="$1"
  local entry shell
  entry="$(getent passwd "$account")" || return 1
  shell="${entry##*:}"
  [[ "$shell" == /usr/sbin/nologin || "$shell" == /sbin/nologin || "$shell" == /bin/false ]]
}

unit_property_equals() {
  local unit="$1"
  local property="$2"
  local expected="$3"
  [[ "$(systemctl show "$unit" --property "$property" --value)" == "$expected" ]]
}

unit_property_contains() {
  local unit="$1"
  local property="$2"
  local expected="$3"
  [[ "$(systemctl show "$unit" --property "$property" --value)" == *"$expected"* ]]
}

http_contains() {
  local url="$1"
  local expected="$2"
  local response compact
  response="$(curl --fail --silent --show-error --max-time 8 "$url" 2>/dev/null)" || return 1
  compact="$(printf '%s' "$response" | tr -d '[:space:]')"
  [[ "$compact" == *"$expected"* ]]
}

tcp_listens() {
  local port="$1"
  ss -H -ltn | awk -v suffix=":${port}" '$4 ~ suffix "$" { found = 1 } END { exit !found }'
}

udp_listens() {
  local port="$1"
  ss -H -lun | awk -v suffix=":${port}" '$4 ~ suffix "$" { found = 1 } END { exit !found }'
}

tcp_is_loopback_only() {
  local port="$1"
  local endpoints endpoint found=false
  endpoints="$(ss -H -ltn | awk -v suffix=":${port}" '$4 ~ suffix "$" { print $4 }')"
  [[ -n "$endpoints" ]] || return 1
  while IFS= read -r endpoint; do
    found=true
    case "$endpoint" in
      127.* | '[::1]:'* | ::1:*) ;;
      *) return 1 ;;
    esac
  done <<<"$endpoints"
  [[ "$found" == true ]]
}

firewall_is_restricted() {
  if [[ "${FIREWALL_MODE:-}" == external ]]; then
    [[ "${EXTERNAL_FIREWALL_CONFIRMED:-false}" == true ]]
    return
  fi
  [[ "${FIREWALL_MODE:-}" == ufw ]] || return 1
  command -v ufw >/dev/null 2>&1 || return 1
  local status
  status="$(ufw status verbose)" || return 1
  [[ "$status" == *"Status: active"* ]] || return 1
  [[ "$status" == *"Default: deny (incoming)"* ]] || return 1
  if printf '%s\n' "$status" \
    | grep -E '(^|[[:space:]])(7810|7815|8100|8109|8300)(/tcp)?[[:space:]]+ALLOW|7811:7815/tcp[[:space:]]+ALLOW' \
      >/dev/null; then
    return 1
  fi
}

TLS_MODE="${TLS_MODE:-}"
tls_curl_arguments=(--silent --show-error --max-time 10 \
  --resolve "${PUBLIC_HOST}:443:127.0.0.1")
if [[ "$TLS_MODE" == production ]]; then
  require_var TLS_CA_FILE
  tls_curl_arguments+=(--cacert "$TLS_CA_FILE")
elif [[ "$TLS_MODE" == development ]]; then
  tls_curl_arguments+=(--insecure)
  printf 'NOTICE: development TLS is not a production deployment baseline\n'
else
  die "TLS_MODE must be production or development"
fi

public_status() {
  local path="$1"
  local expected_status="$2"
  local actual_status
  actual_status="$(curl "${tls_curl_arguments[@]}" --output /dev/null --write-out '%{http_code}' \
    "https://${PUBLIC_HOST}${path}" 2>/dev/null)" || return 1
  [[ "$actual_status" == "$expected_status" ]]
}

http_redirects_to_https() {
  local path="$1"
  local expected_location="https://${PUBLIC_HOST}${path}"
  local result status location
  result="$(curl --silent --show-error --max-time 10 \
    --resolve "${PUBLIC_HOST}:80:127.0.0.1" \
    --output /dev/null --write-out '%{http_code}\n%{redirect_url}' \
    "http://${PUBLIC_HOST}${path}" 2>/dev/null)" || return 1
  status="${result%%$'\n'*}"
  location="${result#*$'\n'}"
  [[ "$status" == 301 && "$location" == "$expected_location" ]]
}

verify_authenticated_console() {
  local verify_environment="${VERIFY_ENV_FILE:-/etc/jt-console/verify.env}"
  [[ -r "$verify_environment" ]] || return 1
  set -a
  # shellcheck disable=SC1090
  source "$verify_environment"
  set +a
  [[ -n "${JT_CONSOLE_ADMIN_PASSWORD_FILE:-}" && -r "$JT_CONSOLE_ADMIN_PASSWORD_FILE" ]] || return 1
  local administrator_password login_body login_response api_headers response_file
  local login_status user_status logout_status access_token
  administrator_password="$(tr -d '\r\n' <"$JT_CONSOLE_ADMIN_PASSWORD_FILE")"
  [[ -n "$administrator_password" ]] || return 1
  login_body="${temporary_directory}/login.json"
  login_response="${temporary_directory}/login-response.json"
  api_headers="${temporary_directory}/api.headers"
  response_file="${temporary_directory}/api-response.json"
  jq -n \
    --arg userName "${JT_CONSOLE_ADMIN_USERNAME:-admin}" \
    --arg password "$administrator_password" \
    '{userName:$userName,password:$password}' >"$login_body"
  unset administrator_password

  login_status="$(curl "${tls_curl_arguments[@]}" \
    --silent --show-error --max-time 10 \
    --output "$login_response" --write-out '%{http_code}' \
    --header 'Content-Type: application/json' \
    --data-binary "@${login_body}" \
    "https://${PUBLIC_HOST}/api/auth/login" 2>/dev/null)" || return 1
  [[ "$login_status" == 200 ]] || return 1
  access_token="$(jq -er '.data.token | select(type == "string" and length > 0)' "$login_response")" \
    || return 1
  printf 'Authorization: Bearer %s\n' "$access_token" >"$api_headers"

  user_status="$(curl "${tls_curl_arguments[@]}" \
    --silent --show-error --max-time 10 \
    --output "$response_file" --write-out '%{http_code}' \
    --header "@${api_headers}" \
    "https://${PUBLIC_HOST}/api/auth/getUserInfo" 2>/dev/null)" || return 1
  [[ "$user_status" == 200 ]] || return 1
  jq -e '.code == "0000"' "$response_file" >/dev/null || return 1

  logout_status="$(curl "${tls_curl_arguments[@]}" \
    --silent --show-error --max-time 10 \
    --output "$response_file" --write-out '%{http_code}' \
    --request POST --header "@${api_headers}" \
    "https://${PUBLIC_HOST}/api/auth/logout" 2>/dev/null)" || return 1
  unset access_token
  [[ "$logout_status" == 200 ]]
}

temporary_directory="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_directory"
}
trap cleanup EXIT HUP INT TERM

check 'jt-platform account is non-login' service_account_is_restricted jt-platform
check 'jt-console account is non-login' service_account_is_restricted jt-console
check 'jt-platform configuration directory is root-owned 0750' \
  directory_has_identity /etc/jt-platform root jt-platform 750
check 'jt-console configuration directory is root-owned 0750' \
  directory_has_identity /etc/jt-console root jt-console 750
check 'jt-platform credential environment is root-owned 0640' \
  file_has_identity /etc/jt-platform/jt-platform.env root jt-platform 640
check 'jt-console credential environment is root-owned 0640' \
  file_has_identity /etc/jt-console/jt-console.env root jt-console 640
check 'jt-platform runtime environment is root-owned 0640' \
  file_has_identity /etc/jt-platform/runtime.env root jt-platform 640
check 'jt-console runtime environment is root-owned 0640' \
  file_has_identity /etc/jt-console/runtime.env root jt-console 640
check 'administrator plaintext file is root-only 0600' \
  file_has_identity /etc/jt-console/admin-initial-password root root 600
check 'operator ingest key file is root-only 0600' \
  file_has_identity /etc/jt-console/ingest.key root root 600
check 'verification environment is root-only 0600' \
  file_has_identity /etc/jt-console/verify.env root root 600
check 'jt-platform artifact is not writable by its service account' \
  runuser -u jt-platform -- test ! -w /opt/jt-platform/current/app.jar
check 'jt-console artifact is not writable by its service account' \
  runuser -u jt-console -- test ! -w /opt/jt-console/current/app.jar

check 'jt-console systemd user is restricted' unit_property_equals jt-console.service User jt-console
check 'jt-platform systemd user is restricted' unit_property_equals jt-platform.service User jt-platform
check 'jt-console systemd prevents privilege escalation' unit_property_equals jt-console.service NoNewPrivileges yes
check 'jt-platform systemd prevents privilege escalation' unit_property_equals jt-platform.service NoNewPrivileges yes
check 'jt-console systemd protects the filesystem' unit_property_equals jt-console.service ProtectSystem strict
check 'jt-platform systemd protects the filesystem' unit_property_equals jt-platform.service ProtectSystem strict
check 'jt-platform requires jt-console' unit_property_contains jt-platform.service Requires jt-console.service
check 'jt-platform starts after jt-console' unit_property_contains jt-platform.service After jt-console.service
check 'jt-console service is active' systemctl is-active --quiet jt-console
check 'jt-platform service is active' systemctl is-active --quiet jt-platform
check 'nginx service is active' systemctl is-active --quiet nginx

check 'jt-console database health is UP' http_contains \
  http://127.0.0.1:8300/actuator/health '"status":"UP"'
check 'jt-platform health is UP' http_contains \
  http://127.0.0.1:8109/actuator/health '"status":"UP"'
check 'media management health is UP' http_contains \
  http://127.0.0.1:7810/health '"status":"UP"'
check 'Nginx configuration syntax is valid' nginx -t
check 'HTTP redirects to same-host HTTPS with the original request URI' \
  http_redirects_to_https '/healthz?deployment-verification=1'
check 'public TLS health is UP' public_status /healthz 200
check 'unauthenticated browser API is rejected' public_status /api/monitor/live 401
for private_path in /internal/health /device/routes /actuator/health /ingest/jt-events; do
  check "Nginx rejects ${private_path}" public_status "$private_path" 404
done
check 'root-only credentials can authenticate without being printed' verify_authenticated_console

check 'console port 8300 listens only on loopback' tcp_is_loopback_only 8300
check 'gateway API port 8100 listens only on loopback' tcp_is_loopback_only 8100
check 'gateway management port 8109 listens only on loopback' tcp_is_loopback_only 8109
check 'Nginx HTTP port 80 is listening' tcp_listens 80
check 'Nginx HTTPS port 443 is listening' tcp_listens 443
check 'JT/T 808 TCP port 7100 is listening' tcp_listens 7100
check 'JT/T 808 UDP port 7101 is listening' udp_listens 7101
for media_port in 7811 7812 7813 7814; do
  check "JT/T 1078 ingress port ${media_port} is listening" tcp_listens "$media_port"
done
check 'media management port 7810 is available only behind the firewall' tcp_listens 7810
check 'media browser port 7815 is available to local Nginx' tcp_listens 7815
check 'firewall mode is active or explicitly external, with private ports excluded' firewall_is_restricted

if [[ "$failures" -ne 0 ]]; then
  printf 'Deployment verification failed: %d check(s) failed.\n' "$failures" >&2
  exit 1
fi
printf 'Deployment verification passed without printing credential values.\n'
