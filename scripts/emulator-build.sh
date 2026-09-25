#!/usr/bin/env bash
# Run a command with tool/lighttool.toml pointing at the LightOS *emulator* app instead of LightOS, then
# restore the committed file whatever happens. The committed value must stay "com.lightos": Light builds
# releases from it, and an APK bound to the emulator's package cannot talk to LightOS on a phone.
#
#   scripts/emulator-build.sh ./gradlew :tool:assembleDebug
#
# Wrap assemble tasks only: installing and launching need no swap.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
toml=tool/lighttool.toml
backup=$(mktemp)
cp "$toml" "$backup"
trap 'cp "$backup" "$toml"; rm -f "$backup"' EXIT
sed 's/^serverPackage = .*/serverPackage = "com.thelightphone.sdk.emulator"/' "$backup" > "$toml"
grep -qx 'serverPackage = "com.thelightphone.sdk.emulator"' "$toml" \
  || { echo "emulator-build: tool/lighttool.toml needs the exact line serverPackage = \"com.lightos\"" >&2; exit 1; }
"$@"
