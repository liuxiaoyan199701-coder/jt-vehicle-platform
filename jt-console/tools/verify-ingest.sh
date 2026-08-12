#!/usr/bin/env bash
# 安全投递端到端验收：认证、投递密钥、幂等和精确设备键。
set -euo pipefail
umask 077

BASE="${1:-${JT_CONSOLE_BASE_URL:-http://127.0.0.1:8300}}"
ENV_FILE="${JT_CONSOLE_VERIFY_ENV_FILE:-}"

if [[ -n "$ENV_FILE" ]]; then
  [[ -r "$ENV_FILE" ]] || { echo "验收环境文件不可读" >&2; exit 2; }
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

for command in curl jq mktemp; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "缺少必需命令: $command" >&2
    exit 2
  }
done

ADMIN_USERNAME="${JT_CONSOLE_ADMIN_USERNAME:-admin}"
if [[ -n "${JT_CONSOLE_ADMIN_PASSWORD_FILE:-}" ]]; then
  [[ -r "$JT_CONSOLE_ADMIN_PASSWORD_FILE" ]] || {
    echo "管理员密码文件不可读" >&2
    exit 2
  }
  IFS= read -r ADMIN_PASSWORD < "$JT_CONSOLE_ADMIN_PASSWORD_FILE"
else
  ADMIN_PASSWORD="${JT_CONSOLE_ADMIN_PASSWORD:-}"
fi

if [[ -n "${JT_CONSOLE_INGEST_KEY_FILE:-}" ]]; then
  [[ -r "$JT_CONSOLE_INGEST_KEY_FILE" ]] || {
    echo "投递密钥文件不可读" >&2
    exit 2
  }
  IFS= read -r INGEST_KEY < "$JT_CONSOLE_INGEST_KEY_FILE"
else
  INGEST_KEY="${JT_CONSOLE_INGEST_KEY:-}"
fi

[[ -n "$ADMIN_PASSWORD" ]] || {
  echo "必须通过 JT_CONSOLE_ADMIN_PASSWORD(_FILE) 提供验收密码" >&2
  exit 2
}
[[ -n "$INGEST_KEY" ]] || {
  echo "必须通过 JT_CONSOLE_INGEST_KEY(_FILE) 提供投递密钥" >&2
  exit 2
}

WORK_DIR="$(mktemp -d)"
cleanup() {
  unset ADMIN_PASSWORD INGEST_KEY ACCESS_TOKEN
  rm -f -- "$WORK_DIR"/*
  rmdir -- "$WORK_DIR"
}
trap cleanup EXIT HUP INT TERM

LOGIN_BODY="$WORK_DIR/login.json"
LOGIN_RESPONSE="$WORK_DIR/login-response.json"
API_HEADERS="$WORK_DIR/api.headers"
INGEST_HEADERS="$WORK_DIR/ingest.headers"
INVALID_INGEST_HEADERS="$WORK_DIR/invalid-ingest.headers"
RESPONSE="$WORK_DIR/response.json"

request() {
  local method="$1"
  local url="$2"
  local output="$3"
  local headers="${4:-}"
  local body="${5:-}"
  local args=(
    --silent --show-error
    --output "$output"
    --write-out '%{http_code}'
    --max-time 10
    --request "$method"
  )
  [[ -z "$headers" ]] || args+=(--header "@$headers")
  if [[ -n "$body" ]]; then
    args+=(--header 'Content-Type: application/json' --data-binary "@$body")
  fi
  curl "${args[@]}" "$url"
}

expect_status() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    local message
    message="$(jq -r '.msg // .message // "无安全错误摘要"' "$RESPONSE" 2>/dev/null || true)"
    echo "失败: $label，期望 HTTP $expected，实际 HTTP $actual（$message）" >&2
    exit 1
  fi
  echo "通过: $label"
}

expect_business_success() {
  local file="$1"
  local label="$2"
  jq -e '.code == "0000"' "$file" >/dev/null || {
    local message
    message="$(jq -r '.msg // "业务响应失败"' "$file" 2>/dev/null || true)"
    echo "失败: $label（$message）" >&2
    exit 1
  }
}

make_vehicle() {
  local output="$1"
  local device_id="$2"
  local plate_no="$3"
  jq -n \
    --arg deviceId "$device_id" \
    --arg plateNo "$plate_no" \
    '{deviceId:$deviceId,plateNo:$plateNo,plateColor:"蓝色",brand:"验收车辆",channelCount:1}' \
    > "$output"
}

make_location() {
  local output="$1"
  local event_id="$2"
  local device_id="$3"
  local device_time="$4"
  local latitude="$5"
  local longitude="$6"
  jq -n \
    --arg eventId "$event_id" \
    --arg deviceId "$device_id" \
    --arg deviceTime "$device_time" \
    --arg receivedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson latitude "$latitude" \
    --argjson longitude "$longitude" \
    '{
      eventId:$eventId,
      deviceId:$deviceId,
      messageId:512,
      serialNo:7,
      protocolVersion:"JT/T 808-2019",
      receivedAt:$receivedAt,
      instanceId:"verify-ingest",
      type:"location",
      payload:{
        latitude:$latitude,
        longitude:$longitude,
        speedKph:45.5,
        direction:90,
        altitude:50,
        deviceTime:$deviceTime,
        attributes:{"0x1":12345},
        alarmFlags:{overspeed:false},
        statusFlags:{accOn:true,positioned:true}
      }
    }' > "$output"
}

echo "开始安全投递验收: $BASE"

CODE="$(request GET "$BASE/api/monitor/live" "$RESPONSE")"
expect_status 401 "$CODE" "未登录车辆位置 API 被拒绝"

jq -n --arg userName "$ADMIN_USERNAME" --arg password "$ADMIN_PASSWORD" \
  '{userName:$userName,password:$password}' > "$LOGIN_BODY"
CODE="$(request POST "$BASE/api/auth/login" "$LOGIN_RESPONSE" "" "$LOGIN_BODY")"
if [[ "$CODE" != "200" ]]; then
  echo "失败: 管理员登录，HTTP $CODE" >&2
  exit 1
fi
expect_business_success "$LOGIN_RESPONSE" "管理员登录"
ACCESS_TOKEN="$(jq -er '.data.token | select(type == "string" and length > 0)' "$LOGIN_RESPONSE")"

printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN" > "$API_HEADERS"
printf 'X-JT-Ingest-Key: %s\n' "$INGEST_KEY" > "$INGEST_HEADERS"
printf 'X-JT-Ingest-Key: invalid-key-for-verification\n' > "$INVALID_INGEST_HEADERS"

SUFFIX_RAW="$(date -u +%H%M%S)$$"
SUFFIX="${SUFFIX_RAW: -8}"
DEVICE_ZERO="00${SUFFIX}"
DEVICE_PLAIN="$SUFFIX"
PLATE_ZERO="VERIFY-Z-$SUFFIX"
PLATE_PLAIN="VERIFY-P-$SUFFIX"
EVENT_PREFIX="verify-$(date -u +%Y%m%dT%H%M%S)-$$"
TODAY="$(date -u +%Y-%m-%d)"
DEVICE_TIME_ZERO="${TODAY}T12:00:00"
DEVICE_TIME_PLAIN="${TODAY}T12:00:30"

VEHICLE_ZERO="$WORK_DIR/vehicle-zero.json"
VEHICLE_PLAIN="$WORK_DIR/vehicle-plain.json"
LOCATION_ZERO="$WORK_DIR/location-zero.json"
LOCATION_PLAIN="$WORK_DIR/location-plain.json"
make_vehicle "$VEHICLE_ZERO" "$DEVICE_ZERO" "$PLATE_ZERO"
make_vehicle "$VEHICLE_PLAIN" "$DEVICE_PLAIN" "$PLATE_PLAIN"
make_location "$LOCATION_ZERO" "${EVENT_PREFIX}-zero" "$DEVICE_ZERO" \
  "$DEVICE_TIME_ZERO" 39.908722 116.397496
make_location "$LOCATION_PLAIN" "${EVENT_PREFIX}-plain" "$DEVICE_PLAIN" \
  "$DEVICE_TIME_PLAIN" 31.230416 121.473701

CODE="$(request POST "$BASE/api/vehicles" "$RESPONSE" "$API_HEADERS" "$VEHICLE_ZERO")"
expect_status 200 "$CODE" "创建前导零设备档案"
expect_business_success "$RESPONSE" "创建前导零设备档案"
CODE="$(request POST "$BASE/api/vehicles" "$RESPONSE" "$API_HEADERS" "$VEHICLE_PLAIN")"
expect_status 200 "$CODE" "创建普通设备档案"
expect_business_success "$RESPONSE" "创建普通设备档案"

CODE="$(request POST "$BASE/ingest/jt-events" "$RESPONSE" "$INVALID_INGEST_HEADERS" "$LOCATION_ZERO")"
expect_status 401 "$CODE" "错误投递密钥被拒绝"

CODE="$(request POST "$BASE/ingest/jt-events" "$RESPONSE" "$INGEST_HEADERS" "$LOCATION_ZERO")"
expect_status 204 "$CODE" "同一 eventId 在错误密钥后仍可首次提交"
CODE="$(request POST "$BASE/ingest/jt-events" "$RESPONSE" "$INGEST_HEADERS" "$LOCATION_ZERO")"
expect_status 204 "$CODE" "重复 eventId 返回幂等成功"
CODE="$(request POST "$BASE/ingest/jt-events" "$RESPONSE" "$INGEST_HEADERS" "$LOCATION_PLAIN")"
expect_status 204 "$CODE" "不同精确设备键独立提交"

CODE="$(request GET "$BASE/api/monitor/live" "$RESPONSE" "$API_HEADERS")"
expect_status 200 "$CODE" "鉴权实时状态查询"
expect_business_success "$RESPONSE" "鉴权实时状态查询"
jq -e \
  --arg zero "$DEVICE_ZERO" --arg plain "$DEVICE_PLAIN" \
  --arg zeroPlate "$PLATE_ZERO" --arg plainPlate "$PLATE_PLAIN" \
  'any(.data[]; .deviceId == $zero and .plateNo == $zeroPlate)
   and any(.data[]; .deviceId == $plain and .plateNo == $plainPlate)' \
  "$RESPONSE" >/dev/null || {
    echo "失败: 前导零设备与普通设备的状态/档案关联发生串线" >&2
    exit 1
  }
echo "通过: 精确设备键状态关联"

START="${TODAY}T00:00:00"
END="${TODAY}T23:59:59"
CODE="$(request GET "$BASE/api/tracks?deviceId=$DEVICE_ZERO&start=$START&end=$END" "$RESPONSE" "$API_HEADERS")"
expect_status 200 "$CODE" "前导零设备轨迹查询"
jq -e '.code == "0000" and .data.count == 1' "$RESPONSE" >/dev/null || {
  echo "失败: 前导零设备轨迹应恰好包含一个幂等点" >&2
  exit 1
}
CODE="$(request GET "$BASE/api/tracks?deviceId=$DEVICE_PLAIN&start=$START&end=$END" "$RESPONSE" "$API_HEADERS")"
expect_status 200 "$CODE" "普通设备轨迹查询"
jq -e '.code == "0000" and .data.count == 1' "$RESPONSE" >/dev/null || {
  echo "失败: 普通设备轨迹应恰好包含一个独立点" >&2
  exit 1
}
echo "通过: 幂等与精确设备键轨迹隔离"

CODE="$(request POST "$BASE/api/auth/logout" "$RESPONSE" "$API_HEADERS")"
expect_status 200 "$CODE" "注销并撤销当前会话"

echo "安全投递验收全部通过"
