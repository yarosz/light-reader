#!/usr/bin/env bash
# Build the Reader the way Light's release builder does (light-sdk/builder/bin/build-apk.sh), minus
# the container: Light's extractor copies the allowlisted tool/ files from a clean clone of the
# committed HEAD into a copy of an SDK checkout, then Gradle builds an unsigned, minified release.
#
#   scripts/light-build.sh [SDK_DIR]     SDK_DIR defaults to the pinned light-sdk submodule
#
# Prints "light-build: OK sdk=<ref> files=<n>" on success. Needs python3, JDK 17, ANDROID_HOME.
set -euo pipefail

repo=$(git rev-parse --show-toplevel)
sdk=$(cd "${1:-$repo/light-sdk}" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Committed files only: untracked local files must not make the build pass.
git clone --quiet --no-hardlinks "$repo" "$work/dev"
git -C "$work/dev" checkout --quiet "$(git -C "$repo" rev-parse HEAD)"

mkdir -p "$work/ws"
cp -a "$sdk/." "$work/ws/"
rm -rf "$work/ws/tool/build" "$work/ws/build" "$work/ws/.git"
if [ -n "${ANDROID_HOME:-}" ]; then echo "sdk.dir=$ANDROID_HOME" > "$work/ws/local.properties"; fi

mkdir -p "$work/out"
if ! (cd "$sdk/builder" && python3 -m lightbuilder prepare --dev-repo "$work/dev" \
      --workspace-tool "$work/ws/tool" --tool-path tool --output-dir "$work/out"); then
  cat "$work/out/error.json" 2>/dev/null >&2 || true
  echo "light-build: FAIL extraction policy" >&2
  exit 1
fi
files=$(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))['files']))" "$work/out/extraction.json")

(cd "$work/ws" && ./gradlew :tool:assembleRelease --no-daemon --no-build-cache --console=plain \
   -DlightSdk.unsigned=true -q)
apk="$work/ws/tool/build/outputs/apk/release/tool-release-unsigned.apk"
[ -f "$apk" ] || { echo "light-build: FAIL no unsigned APK" >&2; exit 1; }

ref=$(git -C "$sdk" describe --tags --always 2>/dev/null || echo unknown)
echo "light-build: OK sdk=$ref files=$files"
