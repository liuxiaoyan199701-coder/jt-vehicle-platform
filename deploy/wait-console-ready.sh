#!/bin/sh
set -eu

health_url="${JT_CONSOLE_HEALTH_URL:-http://127.0.0.1:8300/actuator/health}"
timeout_seconds="${HEALTH_TIMEOUT_SECONDS:-60}"
retry_seconds="${HEALTH_RETRY_SECONDS:-1}"
case "$timeout_seconds:$retry_seconds" in
  *[!0-9:]* | :* | *:) printf 'invalid health retry configuration\n' >&2; exit 1 ;;
esac

deadline=$(( $(date +%s) + timeout_seconds ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if response="$(curl --fail --silent --show-error --max-time 5 "$health_url" 2>/dev/null)"; then
    compact_response="$(printf '%s' "$response" | tr -d '[:space:]')"
    case "$compact_response" in
      *'"status":"UP"'*) exit 0 ;;
    esac
  fi
  sleep "$retry_seconds"
done
printf 'jt-console did not become ready before the bounded timeout\n' >&2
exit 1

