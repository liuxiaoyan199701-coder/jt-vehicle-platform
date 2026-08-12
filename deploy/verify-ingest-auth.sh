#!/usr/bin/env bash
# 实测投递链路：网关与 jt-console 的 ingest key 必须一致，否则所有协议消息被 401 丢弃。
# 只输出结果，不打印密钥本身。
set -uo pipefail

INGEST_URL="http://127.0.0.1:8300/ingest/jt-events"
KEY_FILE=/etc/jt-console/ingest.key
PLATFORM_ENV=/etc/jt-platform/jt-platform.env

console_key="$(tr -d '\r\n' <"$KEY_FILE")"
platform_key="$(sed -n "s/^JT_PLATFORM_INGEST_KEY='\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$PLATFORM_ENV" | tr -d '\r\n')"

echo "===> 1. 网关与后端的投递密钥是否一致"
if [[ -n "$console_key" && "$console_key" == "$platform_key" ]]; then
  echo "    一致（长度 ${#console_key}）"
else
  echo "    不一致 —— 投递会全部失败"
  exit 1
fi

envelope() {
  cat <<EOF
{"eventId":"deploy-verify-$1","deviceId":"deploy-verify","messageId":2,
 "receivedAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","type":"heartbeat","payload":{}}
EOF
}

echo
echo "===> 2. 不带密钥投递（期望 401）"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$INGEST_URL" \
  -H 'Content-Type: application/json' -d "$(envelope nokey)")
echo "    HTTP $code"
[[ "$code" == 401 ]] || { echo "    未按预期拒绝，投递接口缺少认证保护"; exit 1; }

echo
echo "===> 3. 带错误密钥投递（期望 401）"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$INGEST_URL" \
  -H 'Content-Type: application/json' -H 'X-JT-Ingest-Key: wrong-key-for-verification' \
  -d "$(envelope badkey)")
echo "    HTTP $code"
[[ "$code" == 401 ]] || { echo "    错误密钥未被拒绝"; exit 1; }

echo
echo "===> 4. 带正确密钥投递（期望 204）"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$INGEST_URL" \
  -H 'Content-Type: application/json' -H "X-JT-Ingest-Key: ${console_key}" \
  -d "$(envelope ok-$$)")
echo "    HTTP $code"
[[ "$code" == 204 ]] || { echo "    正确密钥被拒，网关投递将无法送达"; exit 1; }

echo
echo "投递链路认证正常：网关持有的密钥可以送达，未授权请求被拒绝。"
