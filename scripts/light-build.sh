#!/usr/bin/env bash
# Build the Reader the way Light's release builder does (light-sdk/builder: Dockerfile +
# bin/build-apk.sh), minus the container:
#   1. a clean copy of the SDK's committed files, like the image's baked SDK source;
#   2. warm the Gradle cache by building the SDK's own template tool, like the image build;
#   3. Light's extractor copies the allowlisted tool/ files from a clean clone of our committed HEAD;
#   4. an unsigned, minified release build, --offline, so a dependency the template didn't pull in
#      fails here exactly as it would on Light's servers.
#
#   scripts/light-build.sh [SDK_DIR]     SDK_DIR defaults to the pinned light-sdk submodule
#
# Prints "light-build: OK sdk=<ref> files=<n>" on success. Needs git, python3, JDK 17, ANDROID_HOME.
set -euo pipefail

repo=$(git rev-parse --show-toplevel)
sdk=$(cd "${1:-$repo/light-sdk}" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gradle=(./gradlew --no-daemon --no-build-cache --console=plain -q)

# 1. Clean SDK: committed files only (no local edits, no stale build/ or .gradle/ directories).
mkdir -p "$work/ws"
git -C "$sdk" archive --format=tar HEAD | tar -x -C "$work/ws"
if [ -n "${ANDROID_HOME:-}" ]; then echo "sdk.dir=$ANDROID_HOME" > "$work/ws/local.properties"; fi
ref=$(git -C "$sdk" describe --tags --always --dirty 2>/dev/null || echo unknown)

# 2. Warm the dependency cache with the SDK's template tool (network allowed only here).
(cd "$work/ws" && "${gradle[@]}" :tool:assembleRelease -DlightSdk.unsigned=true)
rm -rf "$work/ws/tool/build" "$work/ws/build"

# 3. Our committed files only: untracked local files must not make the build pass.
git clone --quiet --no-hardlinks "$repo" "$work/dev"
git -C "$work/dev" checkout --quiet "$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$work/out"
if ! (cd "$sdk/builder" && python3 -m lightbuilder prepare --dev-repo "$work/dev" \
      --workspace-tool "$work/ws/tool" --tool-path tool --output-dir "$work/out"); then
  cat "$work/out/error.json" 2>/dev/null >&2 || true
  echo "light-build: FAIL extraction policy" >&2
  exit 1
fi
files=$(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))['files']))" "$work/out/extraction.json")

# 4. Offline, unsigned, minified release, with Light's reproducibility timestamp.
SOURCE_DATE_EPOCH=$(git -C "$work/dev" log -1 --format=%ct) \
  bash -c 'cd "$1" && shift && "$@"' _ "$work/ws" "${gradle[@]}" --offline :tool:assembleRelease -DlightSdk.unsigned=true
apk="$work/ws/tool/build/outputs/apk/release/tool-release-unsigned.apk"
[ -f "$apk" ] || { echo "light-build: FAIL no unsigned APK" >&2; exit 1; }

echo "light-build: OK sdk=$ref files=$files"
