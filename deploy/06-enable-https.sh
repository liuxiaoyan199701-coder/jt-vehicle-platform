#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
load_deploy_env
for command_name in openssl nginx systemctl curl sha256sum; do
  require_command "$command_name"
done
require_var PUBLIC_HOST
validate_public_host "$PUBLIC_HOST"
TLS_MODE="${TLS_MODE:-}"
[[ "$TLS_MODE" == production || "$TLS_MODE" == development ]] \
  || die "TLS_MODE must be production or development"

validate_tls_path() {
  local variable_name="$1"
  require_absolute_path "$variable_name"
  local value="${!variable_name}"
  [[ "$value" =~ ^/[A-Za-z0-9._/-]+$ ]] || die "${variable_name} contains unsupported path characters"
}

verify_certificate_pair() {
  local certificate="$1"
  local private_key="$2"
  openssl x509 -in "$certificate" -noout >/dev/null 2>&1 || die "TLS certificate is invalid"
  openssl pkey -in "$private_key" -check -noout >/dev/null 2>&1 || die "TLS private key is invalid"
  openssl x509 -in "$certificate" -checkend 86400 -noout >/dev/null \
    || die "TLS certificate expires within 24 hours"

  local certificate_key_digest private_key_digest
  certificate_key_digest="$(openssl x509 -in "$certificate" -pubkey -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | sha256sum | awk '{print $1}')"
  private_key_digest="$(openssl pkey -in "$private_key" -pubout 2>/dev/null \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | sha256sum | awk '{print $1}')"
  [[ "$certificate_key_digest" == "$private_key_digest" ]] \
    || die "TLS certificate and private key do not match"

  if [[ "$PUBLIC_HOST" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    openssl x509 -in "$certificate" -checkip "$PUBLIC_HOST" -noout >/dev/null \
      || die "TLS certificate does not match PUBLIC_HOST"
  else
    openssl x509 -in "$certificate" -checkhost "$PUBLIC_HOST" -noout >/dev/null \
      || die "TLS certificate does not match PUBLIC_HOST"
  fi
}

if [[ "$TLS_MODE" == production ]]; then
  require_var TLS_CERT_FILE
  require_var TLS_KEY_FILE
  require_var TLS_CA_FILE
  validate_tls_path TLS_CERT_FILE
  validate_tls_path TLS_KEY_FILE
  validate_tls_path TLS_CA_FILE
  [[ -r "$TLS_CERT_FILE" && -f "$TLS_CERT_FILE" ]] || die "production TLS certificate is not readable"
  [[ -r "$TLS_KEY_FILE" && -f "$TLS_KEY_FILE" ]] || die "production TLS private key is not readable"
  [[ -r "$TLS_CA_FILE" && -f "$TLS_CA_FILE" ]] || die "production TLS CA bundle is not readable"
  verify_certificate_pair "$TLS_CERT_FILE" "$TLS_KEY_FILE"
  openssl verify -CAfile "$TLS_CA_FILE" -untrusted "$TLS_CERT_FILE" "$TLS_CERT_FILE" >/dev/null \
    || die "production TLS certificate does not build a trusted chain"
else
  TLS_DEVELOPMENT_CERT_DIR="${TLS_DEVELOPMENT_CERT_DIR:-/etc/nginx/jt-console-development-tls}"
  validate_tls_path TLS_DEVELOPMENT_CERT_DIR
  install -d -o root -g root -m 0700 "$TLS_DEVELOPMENT_CERT_DIR"
  safe_host="${PUBLIC_HOST//./_}"
  TLS_CERT_FILE="${TLS_DEVELOPMENT_CERT_DIR}/${safe_host}.crt"
  TLS_KEY_FILE="${TLS_DEVELOPMENT_CERT_DIR}/${safe_host}.key"
  if [[ ! -f "$TLS_CERT_FILE" || ! -f "$TLS_KEY_FILE" ]]; then
    certificate_temporary="${TLS_CERT_FILE}.new.$$"
    key_temporary="${TLS_KEY_FILE}.new.$$"
    if [[ "$PUBLIC_HOST" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
      subject_alt_name="IP:${PUBLIC_HOST}"
    else
      subject_alt_name="DNS:${PUBLIC_HOST}"
    fi
    openssl req -x509 -nodes -days 30 -newkey rsa:3072 \
      -keyout "$key_temporary" \
      -out "$certificate_temporary" \
      -subj "/O=JT Platform Development/CN=${PUBLIC_HOST}" \
      -addext "subjectAltName=${subject_alt_name}" \
      -addext "basicConstraints=CA:FALSE" \
      -addext "keyUsage=digitalSignature,keyEncipherment" \
      -addext "extendedKeyUsage=serverAuth" >/dev/null 2>&1
    chown root:root "$certificate_temporary" "$key_temporary"
    chmod 0644 "$certificate_temporary"
    chmod 0600 "$key_temporary"
    mv -f -- "$certificate_temporary" "$TLS_CERT_FILE"
    mv -f -- "$key_temporary" "$TLS_KEY_FILE"
  fi
  verify_certificate_pair "$TLS_CERT_FILE" "$TLS_KEY_FILE"
  info "development TLS uses a self-signed certificate and does not meet the production baseline"
fi

template="${SCRIPT_DIR}/nginx-jt-console.conf"
[[ -f "$template" ]] || die "Nginx template is missing"
sites_available=/etc/nginx/sites-available
sites_enabled=/etc/nginx/sites-enabled
install -d -o root -g root -m 0755 "$sites_available" "$sites_enabled"
candidate="${sites_available}/.jt-console.candidate.$$"
test_configuration="/etc/nginx/.jt-console-test.$$"
current_configuration="${sites_available}/jt-console"
enabled_configuration="${sites_enabled}/jt-console"
backup_configuration="${sites_available}/.jt-console.backup.$$"
old_enabled_target="$(readlink "$enabled_configuration" 2>/dev/null || true)"
had_current=false
rollback_required=false

cleanup() {
  local status=$?
  trap - EXIT
  rm -f -- "$candidate" "$test_configuration"
  if [[ "$status" -ne 0 && "$rollback_required" == true ]]; then
    if [[ "$had_current" == true ]]; then
      mv -f -- "$backup_configuration" "$current_configuration"
    else
      rm -f -- "$current_configuration"
    fi
    if [[ -n "$old_enabled_target" ]]; then
      atomic_symlink "$old_enabled_target" "$enabled_configuration"
    else
      rm -f -- "$enabled_configuration"
    fi
    nginx -t >/dev/null 2>&1 && systemctl reload nginx >/dev/null 2>&1 || true
  else
    rm -f -- "$backup_configuration"
  fi
  exit "$status"
}
trap cleanup EXIT

rendered="$(<"$template")"
rendered="${rendered//__PUBLIC_HOST__/$PUBLIC_HOST}"
rendered="${rendered//__TLS_CERT_FILE__/$TLS_CERT_FILE}"
rendered="${rendered//__TLS_KEY_FILE__/$TLS_KEY_FILE}"
[[ "$rendered" != *'__PUBLIC_HOST__'* && "$rendered" != *'__TLS_CERT_FILE__'* \
  && "$rendered" != *'__TLS_KEY_FILE__'* ]] || die "Nginx template rendering left unresolved placeholders"
printf '%s\n' "$rendered" >"$candidate"
chown root:root "$candidate"
chmod 0644 "$candidate"

cat >"$test_configuration" <<EOF
events {}
http {
    include /etc/nginx/mime.types;
    include ${candidate};
}
EOF
nginx -t -c "$test_configuration"

if [[ -e "$current_configuration" ]]; then
  cp -a -- "$current_configuration" "$backup_configuration"
  had_current=true
fi
mv -f -- "$candidate" "$current_configuration"
atomic_symlink "$current_configuration" "$enabled_configuration"
rollback_required=true
nginx -t
systemctl enable nginx >/dev/null
if systemctl is-active --quiet nginx; then
  systemctl reload nginx
else
  systemctl start nginx
fi

curl_arguments=(--fail --silent --show-error --max-time 10 \
  --resolve "${PUBLIC_HOST}:443:127.0.0.1")
if [[ "$TLS_MODE" == production ]]; then
  curl_arguments+=(--cacert "$TLS_CA_FILE")
else
  curl_arguments+=(--insecure)
fi
health_response="$(curl "${curl_arguments[@]}" "https://${PUBLIC_HOST}/healthz")"
compact_health="$(printf '%s' "$health_response" | tr -d '[:space:]')"
[[ "$compact_health" == *'"status":"UP"'* ]] || die "Nginx TLS health response is not ready"

rollback_required=false
info "Nginx TLS configuration is active in ${TLS_MODE} mode"

