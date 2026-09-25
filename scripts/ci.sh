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

case "${1:-}" in
  "") post=1 ;;
  --dry-run) post=0 ;;
  *) echo "usage: scripts/ci.sh [--dry-run]" >&2; exit 2 ;;
esac
repo=$(git rev-parse --show-toplevel) && cd "$repo" || exit 1
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
pkg=com.yarosz.reader
started=$(date +%s)
evidence=()
note() { evidence+=("$1"); echo "ci: $1"; }
die() { echo "ci: FAIL $1" >&2; exit 1; }

# --- Preflight: the statuses attest to one exact, pushed commit.
# Untracked files count: the tests and APKs build from the live tree, so it must equal the commit.
[ -z "$(git status --porcelain)" ] || die "working tree differs from HEAD (modified or untracked files); commit or remove them"
head=$(git rev-parse HEAD)
git fetch --quiet origin
if [ "$post" = 1 ] && [ -z "$(git branch -r --contains "$head")" ]; then die "HEAD is not pushed"; fi
base=$(git merge-base origin/main HEAD)
changed=$(git diff --name-only "$base" HEAD)
# Docs-only = a non-empty diff touching nothing that ships or builds. Anything under tool/ ships
# (Light's extractor takes .md assets too), and an empty diff (e.g. main itself) is not docs-only.
docs_only=1
[ -z "$changed" ] && docs_only=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in
    tool/*|light-sdk*|scripts/*|*.gradle.kts|gradle*|mise.toml|.github/workflows/*) docs_only=0 ;;
    *.md|docs/*|.github/PULL_REQUEST_TEMPLATE*|.github/ISSUE_TEMPLATE/*) ;;
    *) docs_only=0 ;;
  esac
done <<<"$changed"
note "commit \`${head:0:12}\`; files changed vs main: $(grep -c . <<<"$changed")"

fail_ctx() {  # context, step description
  echo "ci: FAIL [$1] $2" >&2
  [ "$post" = 1 ] && gh signoff fail "$1" --commit "$head" --description "local ci: $2" >/dev/null
  exit 1
}

# Font round trip: page forward, cycle A+ A+ A- A-, require the identical Page and first words.
# Every read must succeed and show a real footer ("p N/M"), and the first A+ must change the layout,
# so an unresponsive screen or a lost device can never pass as "unchanged".
# COUPLING: state() reads the reading view's footer ("p N/M") and the Page's text semantics. ADR 0007
# replaces the "p N/M" label with Progress; the PR that removes it must update state() in the same change,
# or signoff/emulator can never go green again.
state() {
  local out
  out=$(ANDROID_SERIAL="$1" mise run ui 2>/dev/null \
    | awk '/^   ~/{top=substr($0,5,40)} /p [0-9]+\//{gsub(/^ +| +\(.*$/,""); foot=$0} END{print foot " | " top}')
  grep -qE 'p [0-9]+/[0-9]+' <<<"$out" || return 1
  echo "$out"
}
dismiss_anr() {  # serial: heavy builds can starve the emulator into a "System UI isn't responding" dialog
  if ANDROID_SERIAL="$1" mise run ui 2>/dev/null | grep -q '#aerr_wait'; then
    ANDROID_SERIAL="$1" mise run ui tap aerr_wait >/dev/null 2>&1
  fi
}
roundtrip() {  # serial -> prints "before => after" line, returns 1 if not identical
  local s=$1 before after trail=""
  dismiss_anr "$s"
  ANDROID_SERIAL=$s mise run ui wait "p 1/" >/dev/null 2>&1 || { dismiss_anr "$s"; ANDROID_SERIAL=$s mise run ui wait "p 1/" >/dev/null 2>&1; } \
    || { echo "reader never showed page 1"; return 1; }
  for _ in 1 2 3 4; do "$adb" -s "$s" shell input keyevent KEYCODE_VOLUME_DOWN || { echo "page turn failed"; return 1; }; done
  sleep 1
  before=$(state "$s") || { echo "could not read the page before the font cycle"; return 1; }
  local first="" now
  for key in "A+" "A+" "A−" "A−"; do
    ANDROID_SERIAL=$s mise run ui tap "$key" >/dev/null 2>&1 || { echo "tap $key failed"; return 1; }
    now=$(state "$s") || { echo "could not read the page after $key"; return 1; }
    [ -z "$first" ] && first=$now
    trail="$trail → $(cut -d'|' -f1 <<<"$now" | tr -d ' "')"
  done
  after=$now
  echo "$(cut -d'|' -f1 <<<"$before" | tr -d ' "')$trail"
  [ "$first" != "$before" ] || { echo " (A+ did not change the layout)"; return 1; }
  [ "$before" = "$after" ]
}
wake() {  # serial: the LP3 drops off USB while asleep; wake it, wait up to 30 s for adb, and clear
          # the lock screen (a phone with no PIN only; with a PIN the round trip fails and says so)
  for _ in $(seq 1 15); do
    if "$adb" -s "$1" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; then
      "$adb" -s "$1" shell wm dismiss-keyguard >/dev/null 2>&1
      return 0
    fi
    sleep 2
  done
  return 1
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

  lblog=$(mktemp)
  if ! scripts/light-build.sh >"$lblog" 2>&1; then
    tail -25 "$lblog" >&2
    fail_ctx emulator "Light-builder simulation"
  fi
  lb=$(grep '^light-build:' "$lblog"); rm -f "$lblog"
  note "Light-builder simulation (lightbuilder prepare + unsigned minified release): ${lb#light-build: }"

  emu=$("$adb" devices | awk '/^emulator-[0-9]+\tdevice/{print $1; exit}')
  [ -n "$emu" ] || fail_ctx emulator "no emulator running (mise run emu)"
  # The emulator talks to the LightOS emulator app, not LightOS: swap the server package for this build.
  scripts/emulator-build.sh ./gradlew -q --console=plain :tool:assembleDebug || fail_ctx emulator "assembleDebug"
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
  # The committed lighttool.toml already targets LightOS on the phone.
  ./gradlew -q --console=plain :tool:assembleDebug || fail_ctx lp3 "assembleDebug (device)"
  wake "$lp3" || fail_ctx lp3 "phone not reachable over adb"
  install_and_launch "$lp3" tool/build/outputs/apk/debug/tool-debug.apk || fail_ctx lp3 "install"
  wake "$lp3" || fail_ctx lp3 "phone not reachable over adb"
  line=$(roundtrip "$lp3") || fail_ctx lp3 "font round trip: $line"
  note "LP3 (TLP301, Android $android, LightOS $lightos) font round trip (identical Page): $line"
  lp3_ran=1
elif [ "$docs_only" = 0 ]; then
  note "LP3: no Light Phone III attached (signoff/lp3 not posted)"
fi

note "run: $(( $(date +%s) - started ))s by \`scripts/ci.sh\` (local CI, agent session)"

body=$(printf '**Local CI** for `%s`\n\n' "${head:0:12}"; printf -- '- %s\n' "${evidence[@]}")
if [ "$post" = 0 ]; then printf '\n%s\n' "$body"; exit 0; fi

# Attest to exactly what was tested: the tree must still be clean and HEAD unchanged, and statuses are
# pinned to the tested commit (--commit), not to whatever HEAD is at post time.
[ -z "$(git status --porcelain)" ] || die "working tree changed during the run; not posting"
[ "$(git rev-parse HEAD)" = "$head" ] || die "HEAD moved during the run; not posting"

url=""
if pr=$(gh pr view --json number -q .number 2>/dev/null); then
  url=$(gh pr comment "$pr" --body "$body" 2>/dev/null | grep -oE 'https://github.com/[^ ]+' | tail -1)
fi
gh signoff emulator --commit "$head" ${url:+--url "$url"} >/dev/null || die "posting signoff/emulator"
[ "$lp3_ran" = 1 ] && { gh signoff lp3 --commit "$head" ${url:+--url "$url"} >/dev/null || die "posting signoff/lp3"; }
echo "ci: posted signoff/emulator$([ "$lp3_ran" = 1 ] && echo ' + signoff/lp3') on ${head:0:12}${url:+ ($url)}"
