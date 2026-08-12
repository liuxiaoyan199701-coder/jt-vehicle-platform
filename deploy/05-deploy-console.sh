#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
load_deploy_env
for command_name in sha256sum tar systemctl curl; do
  require_command "$command_name"
done
require_absolute_path JDK_HOME
require_var RELEASE_ID
validate_release_id "$RELEASE_ID"
for variable_name in JT_PLATFORM_JAR JT_CONSOLE_JAR JT_CONSOLE_UI_ARCHIVE; do
  require_var "$variable_name"
  require_absolute_path "$variable_name"
  [[ -f "${!variable_name}" ]] || die "release artifact not found: ${!variable_name}"
done
for digest_name in JT_PLATFORM_JAR_SHA256 JT_CONSOLE_JAR_SHA256 JT_CONSOLE_UI_SHA256; do
  validate_sha256 "$digest_name"
done
[[ -x "${JDK_HOME}/bin/jar" ]] || die "configured JDK does not provide bin/jar"

# Every artifact is verified before a release directory or current link changes.
verify_sha256 "$JT_PLATFORM_JAR" "$JT_PLATFORM_JAR_SHA256"
verify_sha256 "$JT_CONSOLE_JAR" "$JT_CONSOLE_JAR_SHA256"
verify_sha256 "$JT_CONSOLE_UI_ARCHIVE" "$JT_CONSOLE_UI_SHA256"
"${JDK_HOME}/bin/jar" --list --file "$JT_PLATFORM_JAR" >/dev/null
"${JDK_HOME}/bin/jar" --list --file "$JT_CONSOLE_JAR" >/dev/null

while IFS= read -r archive_entry; do
  case "$archive_entry" in
    /* | ../* | */../* | */..)
      die "frontend archive contains an unsafe path"
      ;;
  esac
done < <(tar -tzf "$JT_CONSOLE_UI_ARCHIVE")

platform_release="/opt/jt-platform/releases/${RELEASE_ID}"
console_release="/opt/jt-console/releases/${RELEASE_ID}"
ui_release="/var/www/jt-console/releases/${RELEASE_ID}"
for release_path in "$platform_release" "$console_release" "$ui_release"; do
  [[ ! -e "$release_path" ]] || die "release path already exists: ${release_path}"
done

platform_stage="$(mktemp -d /opt/jt-platform/releases/.stage.XXXXXX)"
console_stage="$(mktemp -d /opt/jt-console/releases/.stage.XXXXXX)"
ui_stage="$(mktemp -d /var/www/jt-console/releases/.stage.XXXXXX)"
old_platform=""
old_console=""
old_ui=""
links_changed=false

restore_link() {
  local link="$1"
  local old_target="$2"
  if [[ -n "$old_target" ]]; then
    atomic_symlink "$old_target" "$link"
  else
    rm -f -- "$link"
  fi
}

rollback_release() {
  info "release failed; restoring previous current links"
  systemctl stop jt-platform >/dev/null 2>&1 || true
  restore_link /opt/jt-platform/current "$old_platform"
  restore_link /opt/jt-console/current "$old_console"
  restore_link /var/www/jt-console/current "$old_ui"
  if [[ -n "$old_console" ]]; then
    systemctl restart jt-console >/dev/null 2>&1 || true
    /usr/local/libexec/jt-wait-console-ready >/dev/null 2>&1 || true
  else
    systemctl stop jt-console >/dev/null 2>&1 || true
  fi
  if [[ -n "$old_platform" && -n "$old_console" ]]; then
    systemctl restart jt-platform >/dev/null 2>&1 || true
  fi
}

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$status" -ne 0 && "$links_changed" == true ]]; then
    rollback_release
  fi
  [[ -z "${platform_stage:-}" ]] || rm -rf -- "$platform_stage"
  [[ -z "${console_stage:-}" ]] || rm -rf -- "$console_stage"
  [[ -z "${ui_stage:-}" ]] || rm -rf -- "$ui_stage"
  exit "$status"
}
trap cleanup EXIT

install -o root -g jt-platform -m 0640 "$JT_PLATFORM_JAR" "${platform_stage}/app.jar"
printf '%s  app.jar\n' "${JT_PLATFORM_JAR_SHA256,,}" >"${platform_stage}/SHA256SUMS"
chown root:jt-platform "$platform_stage"
chmod 0750 "$platform_stage"
chown root:jt-platform "${platform_stage}/SHA256SUMS"
chmod 0640 "${platform_stage}/SHA256SUMS"

install -o root -g jt-console -m 0640 "$JT_CONSOLE_JAR" "${console_stage}/app.jar"
printf '%s  app.jar\n' "${JT_CONSOLE_JAR_SHA256,,}" >"${console_stage}/SHA256SUMS"
chown root:jt-console "$console_stage"
chmod 0750 "$console_stage"
chown root:jt-console "${console_stage}/SHA256SUMS"
chmod 0640 "${console_stage}/SHA256SUMS"

tar --extract --gzip --file "$JT_CONSOLE_UI_ARCHIVE" --directory "$ui_stage" \
  --no-same-owner --no-same-permissions
if [[ -f "${ui_stage}/index.html" ]]; then
  ui_source="$ui_stage"
elif [[ -f "${ui_stage}/dist/index.html" ]]; then
  ui_source="${ui_stage}/dist"
else
  die "verified frontend archive does not contain index.html at its root or dist root"
fi
printf '%s  source-archive.tar.gz\n' "${JT_CONSOLE_UI_SHA256,,}" >"${ui_source}/SHA256SUMS"
chown -R root:root "$ui_source"
find "$ui_source" -type d -exec chmod 0755 {} +
find "$ui_source" -type f -exec chmod 0644 {} +

mv -- "$platform_stage" "$platform_release"
platform_stage=""
mv -- "$console_stage" "$console_release"
console_stage=""
mv -- "$ui_source" "$ui_release"
if [[ "$ui_source" == "$ui_stage" ]]; then
  ui_stage=""
fi

old_platform="$(readlink /opt/jt-platform/current 2>/dev/null || true)"
old_console="$(readlink /opt/jt-console/current 2>/dev/null || true)"
old_ui="$(readlink /var/www/jt-console/current 2>/dev/null || true)"
links_changed=true

# Stop the event producer before replacing the transactional receiver.
systemctl stop jt-platform >/dev/null 2>&1 || true
if [[ -n "$old_console" ]]; then
  atomic_symlink "$old_console" /opt/jt-console/previous
fi
atomic_symlink "$console_release" /opt/jt-console/current
systemctl enable jt-console >/dev/null
systemctl restart jt-console
wait_for_http_content http://127.0.0.1:8300/actuator/health '"status":"UP"'

if [[ -n "$old_platform" ]]; then
  atomic_symlink "$old_platform" /opt/jt-platform/previous
fi
atomic_symlink "$platform_release" /opt/jt-platform/current
systemctl enable jt-platform >/dev/null
systemctl restart jt-platform
wait_for_http_content http://127.0.0.1:8109/actuator/health '"status":"UP"'
wait_for_http_content http://127.0.0.1:7810/health '"status":"UP"'

if [[ -n "$old_ui" ]]; then
  atomic_symlink "$old_ui" /var/www/jt-console/previous
fi
atomic_symlink "$ui_release" /var/www/jt-console/current
links_changed=false
info "release ${RELEASE_ID} is active; previous links were retained when available"
