#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
load_deploy_env

if [[ "${INSTALL_SYSTEM_PACKAGES:-true}" == true ]]; then
  require_command apt-get
  export DEBIAN_FRONTEND=noninteractive
  packages=(ca-certificates curl openssl apache2-utils nginx jq)
  if [[ "${FIREWALL_MODE:-}" == ufw ]]; then
    packages+=(ufw)
  fi
  apt-get update -qq
  apt-get install -y -qq "${packages[@]}"
fi

for command_name in curl openssl htpasswd nginx; do
  require_command "$command_name"
done

if ! id jt-platform >/dev/null 2>&1; then
  useradd --system --shell /usr/sbin/nologin --home-dir /var/lib/jt-platform jt-platform
fi
if ! id jt-console >/dev/null 2>&1; then
  useradd --system --shell /usr/sbin/nologin --home-dir /var/lib/jt-console jt-console
fi

install -d -o root -g jt-platform -m 0750 /etc/jt-platform
install -d -o root -g jt-console -m 0750 /etc/jt-console
install -d -o root -g root -m 0750 /etc/jt-deploy

install -d -o root -g jt-platform -m 0750 /opt/jt-platform /opt/jt-platform/releases
install -d -o root -g jt-console -m 0750 /opt/jt-console /opt/jt-console/releases
install -d -o root -g root -m 0755 /var/www/jt-console /var/www/jt-console/releases
install -d -o root -g root -m 0755 /usr/local/libexec
# 校验脚本被安装到 /usr/local/sbin 后，同级不再有 lib/，需要一份共享副本
install -d -o root -g root -m 0755 /usr/local/lib/jt-deploy
install -o root -g root -m 0644 "${SCRIPT_DIR}/lib/common.sh" /usr/local/lib/jt-deploy/common.sh
install -o root -g root -m 0755 "${SCRIPT_DIR}/jt-java" /usr/local/libexec/jt-java
install -o root -g root -m 0755 "${SCRIPT_DIR}/wait-console-ready.sh" \
  /usr/local/libexec/jt-wait-console-ready
install -o root -g root -m 0750 "${SCRIPT_DIR}/07-verify-deployment.sh" \
  /usr/local/sbin/jt-verify-deployment

install -d -o jt-platform -g jt-platform -m 0750 \
  /var/lib/jt-platform \
  /var/lib/jt-platform/logs \
  /var/lib/jt-platform/recordings \
  /var/lib/jt-platform/recording-exports \
  /var/lib/jt-platform/data/signal/multimedia \
  /var/lib/jt-platform/data/signal/alarm-attachments \
  /var/lib/jt-platform/data/delivery-overflow/api
install -d -o jt-console -g jt-console -m 0750 \
  /var/lib/jt-console \
  /var/lib/jt-console/data \
  /var/lib/jt-console/logs

"${SCRIPT_DIR}/configure-runtime.sh"
"${SCRIPT_DIR}/init-credentials.sh"
install -o root -g jt-platform -m 0640 "${SCRIPT_DIR}/application.yml" \
  /etc/jt-platform/application.yml
install -o root -g jt-console -m 0640 "${SCRIPT_DIR}/jt-console-application.yml" \
  /etc/jt-console/jt-console-application.yml
install -o root -g root -m 0644 "${SCRIPT_DIR}/jt-platform.service" \
  /etc/systemd/system/jt-platform.service
install -o root -g root -m 0644 "${SCRIPT_DIR}/jt-console.service" \
  /etc/systemd/system/jt-console.service
systemctl daemon-reload
info "service accounts, restricted directories and credentials are ready"
