#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
load_deploy_env
require_var PUBLIC_HOST
require_var MEDIA_REACHABLE_ADDRESS
require_absolute_path JDK_HOME
validate_public_host "$PUBLIC_HOST"
validate_public_host "$MEDIA_REACHABLE_ADDRESS"

DEVICE_AUTH_MODE="${DEVICE_AUTH_MODE:-local-list}"
DEVICE_LOCAL_LIST="${DEVICE_LOCAL_LIST:-}"
STREAM_AUTH_MODE="${STREAM_AUTH_MODE:-disabled}"
MEDIA_MAX_STREAMS="${MEDIA_MAX_STREAMS:-20}"
MEDIA_MAX_OUTBOUND_BPS="${MEDIA_MAX_OUTBOUND_BPS:-50000000}"
[[ "$DEVICE_AUTH_MODE" =~ ^(allow-all|local-list|remote-api)$ ]] || die "invalid DEVICE_AUTH_MODE"
[[ "$STREAM_AUTH_MODE" =~ ^(disabled|jwt)$ ]] || die "invalid STREAM_AUTH_MODE"
[[ "$DEVICE_LOCAL_LIST" =~ ^[0-9A-Za-z,._-]*$ ]] || die "DEVICE_LOCAL_LIST contains unsupported characters"
[[ "$MEDIA_MAX_STREAMS" =~ ^[1-9][0-9]*$ ]] || die "MEDIA_MAX_STREAMS must be positive"
[[ "$MEDIA_MAX_OUTBOUND_BPS" =~ ^[1-9][0-9]*$ ]] || die "MEDIA_MAX_OUTBOUND_BPS must be positive"

console_runtime="/etc/jt-console/runtime.env"
platform_runtime="/etc/jt-platform/runtime.env"
console_temporary="${console_runtime}.new.$$"
platform_temporary="${platform_runtime}.new.$$"
umask 0027

cat >"$console_temporary" <<EOF
JDK_HOME='${JDK_HOME}'
JT_PUBLIC_HOST='${PUBLIC_HOST}'
JT_CONSOLE_DB='/var/lib/jt-console/data/jt-console.db'
JT_GATEWAY_BASE_URL='http://127.0.0.1:8100'
JT_CONSOLE_ALLOWED_ORIGINS='https://${PUBLIC_HOST}'
EOF

cat >"$platform_temporary" <<EOF
JDK_HOME='${JDK_HOME}'
JT_MEDIA_REACHABLE_ADDRESS='${MEDIA_REACHABLE_ADDRESS}'
JT_DEVICE_AUTH_MODE='${DEVICE_AUTH_MODE}'
JT_DEVICE_LOCAL_LIST='${DEVICE_LOCAL_LIST}'
JT_STREAM_AUTH_MODE='${STREAM_AUTH_MODE}'
JT_MEDIA_MAX_STREAMS='${MEDIA_MAX_STREAMS}'
JT_MEDIA_MAX_OUTBOUND_BPS='${MEDIA_MAX_OUTBOUND_BPS}'
# 多媒体文件（拍照、苏标附件）的浏览器访问前缀，nginx 会把 /files/multimedia/ 反代到网关
JT_MULTIMEDIA_ACCESS_BASE_URL='https://${PUBLIC_HOST}'
EOF

chown root:jt-console "$console_temporary"
chown root:jt-platform "$platform_temporary"
chmod 0640 "$console_temporary" "$platform_temporary"
mv -f -- "$console_temporary" "$console_runtime"
mv -f -- "$platform_temporary" "$platform_runtime"
info "parameterized runtime environment files are installed"
