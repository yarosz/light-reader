#!/usr/bin/env bash
# Local CI for checks that only run on this machine, posted as GitHub commit statuses via gh-signoff.
# The ONLY supported way to post signoff/* statuses: never run `gh signoff` by hand.
#
#   scripts/ci.sh              run everything that applies, post evidence + statuses
#   scripts/ci.sh --dry-run    run the checks, print the evidence, post nothing
#
# Contexts:
#   signoff/emulator  unit tests + Light-builder simulation + emulator font round trip
#                     (docs-only diffs: posted green as "not applicable: docs-only")
#   signoff/lp3       the same round trip on an attached Light Phone III (posted only when one is attached)
#
# Evidence is public (a PR comment). It carries generic facts only: commit, test counts, SDK ref,
# round-trip lines, device model and LightOS version. Never serials, hostnames, or local paths.
set -uo pipefail

post=1; [ "${1:-}" = "--dry-run" ] && post=0
repo=$(git rev-parse --show-toplevel); cd "$repo"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
pkg=com.yarosz.reader
started=$(date +%s)
evidence=()
note() { evidence+=("$1"); echo "ci: $1"; }
die() { echo "ci: FAIL $1" >&2; exit 1; }

# --- Preflight: the statuses attest to one exact, pushed commit.
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "tracked files are modified; commit first"
head=$(git rev-parse HEAD)
git fetch --quiet origin
if [ "$post" = 1 ] && [ -z "$(git branch -r --contains "$head")" ]; then die "HEAD is not pushed"; fi
base=$(git merge-base origin/main HEAD)
changed=$(git diff --name-only "$base" HEAD)
docs_only=1
while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in *.md|docs/*|.github/PULL_REQUEST_TEMPLATE*|.github/ISSUE_TEMPLATE/*) ;; *) docs_only=0 ;; esac
done <<<"$changed"
note "commit \`${head:0:12}\`; files changed vs main: $(grep -c . <<<"$changed")"

fail_ctx() {  # context, step description
  echo "ci: FAIL [$1] $2" >&2
  [ "$post" = 1 ] && gh signoff fail "$1" --description "local ci: $2" >/dev/null
  exit 1
}

# Font round trip: page forward, cycle A+ A+ A- A-, require the identical Page and first words.
state() { ANDROID_SERIAL="$1" mise run ui 2>/dev/null \
  | awk '/^   ~/{top=substr($0,5,40)} /p [0-9]+\//{gsub(/^ +| +\(.*$/,""); foot=$0} END{print foot " | " top}'; }
roundtrip() {  # serial -> prints "before => after" line, returns 1 if not identical
  local s=$1 before after trail=""
  ANDROID_SERIAL=$s mise run ui wait "p 1/" >/dev/null 2>&1 || return 1
  for _ in 1 2 3 4; do "$adb" -s "$s" shell input keyevent KEYCODE_VOLUME_DOWN; done; sleep 1
  before=$(state "$s")
  for key in "A+" "A+" "A−" "A−"; do
    ANDROID_SERIAL=$s mise run ui tap "$key" >/dev/null 2>&1
    trail="$trail → $(state "$s" | cut -d'|' -f1 | tr -d ' "')"
  done
  after=$(state "$s")
  echo "$(cut -d'|' -f1 <<<"$before" | tr -d ' "')$trail"
  [ "$before" = "$after" ]
}
install_and_launch() {  # serial apk
  "$adb" -s "$1" install -r "$2" >/dev/null && "$adb" -s "$1" shell am force-stop $pkg \
    && "$adb" -s "$1" shell monkey -p $pkg 1 >/dev/null 2>&1
}

# --- signoff/emulator
if [ "$docs_only" = 1 ]; then
  note "emulator: not applicable (docs-only change)"
else
  ./gradlew -q --console=plain :tool:testDebugUnitTest || fail_ctx emulator "unit tests"
  tests=$(cat tool/build/test-results/testDebugUnitTest/*.xml | grep -oE '<testsuite [^>]*tests="[0-9]+"' | grep -oE 'tests="[0-9]+"' | grep -oE '[0-9]+' | paste -sd+ - | bc)
  note "unit + property tests: $tests passed"

  lb=$(scripts/light-build.sh 2>&1 | grep '^light-build:') || fail_ctx emulator "Light-builder simulation"
  note "Light-builder simulation (lightbuilder prepare + unsigned minified release): ${lb#light-build: }"

  emu=$("$adb" devices | awk '/^emulator-[0-9]+\tdevice/{print $1; exit}')
  [ -n "$emu" ] || fail_ctx emulator "no emulator running (mise run emu)"
  ./gradlew -q --console=plain :tool:assembleDebug || fail_ctx emulator "assembleDebug"
  install_and_launch "$emu" tool/build/outputs/apk/debug/tool-debug.apk || fail_ctx emulator "install"
  line=$(roundtrip "$emu") || fail_ctx emulator "font round trip: $line"
  note "emulator font round trip (identical Page): $line"
fi

# --- signoff/lp3 (only when a Light Phone III is attached)
lp3=$("$adb" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/{print $1}' | while read -r s; do
  [ "$("$adb" -s "$s" shell getprop ro.product.model | tr -d '\r')" = TLP301 ] && echo "$s"; done | head -1)
lp3_ran=0
if [ -n "$lp3" ] && [ "$docs_only" = 0 ]; then
  lightos=$("$adb" -s "$lp3" shell dumpsys package com.lightos | grep -m1 -oE 'versionName=[^ ]+' | cut -d= -f2)
  android=$("$adb" -s "$lp3" shell getprop ro.build.version.release | tr -d '\r')
  # A device build talks to LightOS itself; restore the emulator setting whatever happens.
  trap 'git checkout --quiet -- tool/lighttool.toml' EXIT
  sed -i.bak 's/^serverPackage = .*/serverPackage = "com.lightos"/' tool/lighttool.toml && rm -f tool/lighttool.toml.bak
  ./gradlew -q --console=plain :tool:assembleDebug || fail_ctx lp3 "assembleDebug (device)"
  git checkout --quiet -- tool/lighttool.toml
  "$adb" -s "$lp3" shell input keyevent KEYCODE_WAKEUP
  install_and_launch "$lp3" tool/build/outputs/apk/debug/tool-debug.apk || fail_ctx lp3 "install"
  line=$(roundtrip "$lp3") || fail_ctx lp3 "font round trip: $line"
  note "LP3 (TLP301, Android $android, LightOS $lightos) font round trip (identical Page): $line"
  lp3_ran=1
fi

note "run: $(( $(date +%s) - started ))s by \`scripts/ci.sh\` (local CI, agent session)"

body=$(printf '**Local CI** for `%s`\n\n' "${head:0:12}"; printf -- '- %s\n' "${evidence[@]}")
if [ "$post" = 0 ]; then printf '\n%s\n' "$body"; exit 0; fi

url=""
if pr=$(gh pr view --json number -q .number 2>/dev/null); then
  url=$(gh pr comment "$pr" --body "$body" 2>/dev/null | grep -oE 'https://github.com/[^ ]+' | tail -1)
fi
gh signoff emulator ${url:+--url "$url"} >/dev/null || die "posting signoff/emulator"
[ "$lp3_ran" = 1 ] && { gh signoff lp3 ${url:+--url "$url"} >/dev/null || die "posting signoff/lp3"; }
echo "ci: posted signoff/emulator$([ "$lp3_ran" = 1 ] && echo ' + signoff/lp3') on ${head:0:12}${url:+ ($url)}"
