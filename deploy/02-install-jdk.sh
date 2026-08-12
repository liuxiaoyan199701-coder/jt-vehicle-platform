#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "${SCRIPT_DIR}/lib/common.sh"

require_root
load_deploy_env
for command_name in curl sha256sum tar; do
  require_command "$command_name"
done
require_var JDK_ARCHIVE_URL
require_absolute_path JDK_HOME
validate_sha256 JDK_ARCHIVE_SHA256

if [[ -x "${JDK_HOME}/bin/java" ]]; then
  info "JDK is already installed at the configured path"
  "${JDK_HOME}/bin/java" -version 2>&1
  exit 0
fi
[[ ! -e "$JDK_HOME" ]] || die "JDK_HOME exists but does not contain an executable bin/java"

jdk_parent="$(dirname -- "$JDK_HOME")"
install -d -o root -g root -m 0755 "$jdk_parent"
download_directory="$(mktemp -d)"
stage_directory="$(mktemp -d "${jdk_parent}/.jdk-stage.XXXXXX")"
cleanup() {
  rm -rf -- "$download_directory" "$stage_directory"
}
trap cleanup EXIT

archive="${download_directory}/jdk.tar.gz"
info "downloading the configured JDK archive"
curl --fail --silent --show-error --location \
  --retry 3 \
  --retry-all-errors \
  --connect-timeout 15 \
  --max-time 900 \
  --output "$archive" \
  "$JDK_ARCHIVE_URL"
verify_sha256 "$archive" "$JDK_ARCHIVE_SHA256"

while IFS= read -r archive_entry; do
  case "$archive_entry" in
    /* | ../* | */../* | */..)
      die "JDK archive contains an unsafe path"
      ;;
  esac
done < <(tar -tzf "$archive")

extract_directory="${stage_directory}/extract"
install -d -o root -g root -m 0755 "$extract_directory"
tar --extract --gzip --file "$archive" --directory "$extract_directory" \
  --no-same-owner --no-same-permissions

mapfile -t top_level < <(find "$extract_directory" -mindepth 1 -maxdepth 1 -print)
if [[ -x "${extract_directory}/bin/java" ]]; then
  jdk_source="$extract_directory"
elif [[ "${#top_level[@]}" -eq 1 && -d "${top_level[0]}" && -x "${top_level[0]}/bin/java" ]]; then
  jdk_source="${top_level[0]}"
else
  die "verified JDK archive does not contain exactly one usable JDK"
fi

resolved_java="$(readlink -f -- "${jdk_source}/bin/java")"
resolved_source="$(readlink -f -- "$jdk_source")"
[[ "$resolved_java" == "${resolved_source}/"* ]] || die "JDK bin/java resolves outside the extracted archive"

mv -- "$jdk_source" "$JDK_HOME"
chown -R root:root "$JDK_HOME"
"${JDK_HOME}/bin/java" -version 2>&1
info "verified JDK installed at the configured JDK_HOME"

