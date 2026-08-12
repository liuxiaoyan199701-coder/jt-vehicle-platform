#!/usr/bin/env bash
# 设置控制台管理员密码。
# 用法: NEW_ADMIN_PASSWORD='...' bash set-admin-password.sh
#
# 密码只以 BCrypt 哈希形式进入服务环境文件；明文单独写入 root-only 的
# admin-initial-password，因为 07-verify-deployment.sh 依赖它做登录复检，
# 两者不同步会导致部署验证失败。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
require_command htpasswd

CONSOLE_ENV_FILE="${CONSOLE_ENV_FILE:-/etc/jt-console/jt-console.env}"
ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:-/etc/jt-console/admin-initial-password}"
BCRYPT_COST="${BCRYPT_COST:-12}"

[[ -f "$CONSOLE_ENV_FILE" ]] || die "console environment file not found: ${CONSOLE_ENV_FILE}"
: "${NEW_ADMIN_PASSWORD:?NEW_ADMIN_PASSWORD is required}"
[[ -n "$NEW_ADMIN_PASSWORD" ]] || die "NEW_ADMIN_PASSWORD must not be empty"

admin_username="$(read_env_value "$CONSOLE_ENV_FILE" JT_CONSOLE_ADMIN_USERNAME || true)"
admin_username="${admin_username:-admin}"

admin_hash="$(printf '%s\n' "$NEW_ADMIN_PASSWORD" \
  | htpasswd -inBC "$BCRYPT_COST" "$admin_username" \
  | awk -F: 'NR == 1 { print $2 }')"
[[ "$admin_hash" =~ ^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]] \
  || die "generated hash is not a supported BCrypt value"

# ensure_env_value 的语义是「不存在才追加」，无法覆盖已有口令哈希，
# 因此这里原子替换整行。BCrypt 字符集不含单引号，可安全用单引号包裹。
env_temporary="${CONSOLE_ENV_FILE}.new.$$"
umask 0027
grep -v '^JT_CONSOLE_ADMIN_PASSWORD_HASH=' "$CONSOLE_ENV_FILE" >"$env_temporary" || true
printf "JT_CONSOLE_ADMIN_PASSWORD_HASH='%s'\n" "$admin_hash" >>"$env_temporary"
chown root:jt-console "$env_temporary"
chmod 0640 "$env_temporary"
mv -f -- "$env_temporary" "$CONSOLE_ENV_FILE"

password_temporary="${ADMIN_PASSWORD_FILE}.new.$$"
umask 0077
printf '%s\n' "$NEW_ADMIN_PASSWORD" >"$password_temporary"
chown root:root "$password_temporary"
chmod 0600 "$password_temporary"
mv -f -- "$password_temporary" "$ADMIN_PASSWORD_FILE"

assert_service_env_permissions "$CONSOLE_ENV_FILE" jt-console
info "administrator password hash updated for ${admin_username}; restart jt-console to apply"
