#!/usr/bin/env bash
# Local CI for checks that only run on this machine, posted as GitHub commit statuses (signoff/*).
# The ONLY supported way to post signoff/* statuses: never post them by hand.
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
drift() {  # advisory (AGENTS.md "Domain language"): never fails the run
  note "domain drift: $(scripts/domain-drift.sh 2>/dev/null | tail -1 | grep -oE '^[0-9]+' || echo '?') unresolved"
}

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

provenance="local ci via scripts/ci.sh (agent session)"

post_status() {  # state, context, description [, url]; GitHub rejects descriptions over 140 characters
  local desc=$3
  [ "${#desc}" -le 140 ] || desc="${desc:0:139}…"
  gh api -X POST "repos/{owner}/{repo}/statuses/$head" -f state="$1" -f context="signoff/$2" \
    -f description="$desc" ${4:+-f target_url="$4"} >/dev/null
}

fail_ctx() {  # context, step description
  echo "ci: FAIL [$1] $2" >&2
  if [ "$post" = 1 ]; then
    post_status failure "$1" "local ci: $2" || echo "ci: could not post the failure status for signoff/$1" >&2
  fi
  exit 1
}

# Font round trip: page forward, cycle A+ A+ A- A-, require the identical Page.
# A Page is its chapter indicator ("1/12") plus its text from the Canvas's semantics: the first 40
# characters and the length. A bigger font keeps the Page's start (the Place) but moves its end, so the
# length is what makes the first A+ change the state. Every read must find both the indicator and the
# Page, so an unresponsive screen or a lost device can never pass as "unchanged".
state() {  # serial -> "c/C|first 40 chars|length", or return 1
  # The Page's text holds newlines, so its "   ~" node spans lines up to the one ending in "(x,y)";
  # it is by far the longest label on screen. C locale: the counts only have to agree between reads.
  ANDROID_SERIAL="$1" mise run ui 2>/dev/null | LC_ALL=C awk '
    inb { blk = blk "\n" $0 }
    !inb && /^   ~/ { inb = 1; blk = substr($0, 5) }
    inb && /  \([0-9]+,[0-9]+\)$/ {
      inb = 0; sub(/  \([0-9]+,[0-9]+\)$/, "", blk)
      if (length(blk) > length(page)) page = blk
      next
    }
    !inb && match($0, /^[^"]* "[0-9]+\/[0-9]+"  \(/) { chap = substr($0, RSTART, RLENGTH); gsub(/[^0-9\/]/, "", chap) }
    END {
      if (chap == "" || page == "") exit 1
      top = substr(page, 1, 40); gsub(/\n/, " ", top)
      printf "%s|%s|%d\n", chap, top, length(page)
    }'
}
brief() {  # state -> 'c/C "first words" Nch' for the evidence, cut at a space so no glyph is split
  local chap=${1%%|*} count=${1##*|} top=${1#*|}
  top=$(T=${top%|*} LC_ALL=C awk 'BEGIN { t = ENVIRON["T"]; s = substr(t, 1, 24)
    if (length(t) > 24 && substr(t, 25, 1) != " ") { if (index(s, " ")) sub(/ [^ ]*$/, "", s); else s = "" }
    print s }')
  echo "$chap \"$top\" ${count}ch"
}
dismiss_anr() {  # serial: heavy builds can starve the emulator into a "System UI isn't responding" dialog
  if ANDROID_SERIAL="$1" mise run ui 2>/dev/null | grep -q '#aerr_wait'; then
    ANDROID_SERIAL="$1" mise run ui tap aerr_wait >/dev/null 2>&1
  fi
}
roundtrip() {  # serial -> prints "before => after" line, returns 1 if not identical
  local s=$1 before after trail=""
  dismiss_anr "$s"
  ANDROID_SERIAL=$s mise run ui wait "A+" >/dev/null 2>&1 || { dismiss_anr "$s"; ANDROID_SERIAL=$s mise run ui wait "A+" >/dev/null 2>&1; } \
    || { echo "reader never showed a Page"; return 1; }
  for _ in 1 2 3 4; do "$adb" -s "$s" shell input keyevent KEYCODE_VOLUME_DOWN || { echo "page turn failed"; return 1; }; done
  sleep 1
  before=$(state "$s") || { echo "could not read the page before the font cycle"; return 1; }
  local first="" now
  for key in "A+" "A+" "A−" "A−"; do
    ANDROID_SERIAL=$s mise run ui tap "$key" >/dev/null 2>&1 || { echo "tap $key failed"; return 1; }
    now=$(state "$s") || { echo "could not read the page after $key"; return 1; }
    [ -z "$first" ] && first=$now
    trail="$trail → $(brief "$now")"
  done
  after=$now
  echo "$(brief "$before")$trail"
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
data_hash() {  # serial -> sha256 of files/reading-data.json, or "none" when there is no such file
  "$adb" -s "$1" shell run-as $pkg sh -c "'sha256sum files/reading-data.json 2>/dev/null || echo none'" | tr -d '\r' | awk '{print $1}'
}
install_and_launch() {  # serial apk; dev-start opens files/alice.epub past the Shelf, at chapter 1 and the
                        # default font, and saves nothing, so A+ A+ A- A- always cycles and the device's
                        # reading data is left as it was (data_before, checked by shelf_check). A device
                        # with no alice.epub gets the test fixture, removed again on exit. The fixture is
                        # pushed under a temp name and renamed, and the device recorded before the push,
                        # so a push cut short never leaves a partial alice.epub that a later run accepts.
  dev_started="$dev_started $1"
  "$adb" -s "$1" install -r "$2" >/dev/null && "$adb" -s "$1" shell am force-stop $pkg \
    && data_before=$(data_hash "$1") && [ -n "$data_before" ] \
    && "$adb" -s "$1" shell run-as $pkg sh -c "'mkdir -p files && echo 0 > files/dev-start'" \
    && { "$adb" -s "$1" shell run-as $pkg test -f files/alice.epub \
      || { pushed_alice="$pushed_alice $1" \
        && "$adb" -s "$1" shell run-as $pkg sh -c "'cat > files/alice.epub.ci && mv files/alice.epub.ci files/alice.epub'" \
          <tool/src/test/fixtures/alice.epub; }; } \
    && "$adb" -s "$1" shell monkey -p $pkg 1 >/dev/null 2>&1
}
shelf_check() {  # serial: after the round trip, the reading data must be byte-identical (Home pauses the
                 # Reader, which flushes any save), and a launch without dev-start must render the Shelf:
                 # a "shelf rows=" line logged after this run's marker, so an earlier launch's can't pass
  local mark="ci-shelf-check-$$-$RANDOM-$(date +%s)"
  "$adb" -s "$1" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 2
  [ "$(data_hash "$1")" = "$data_before" ] || { echo "the dev-start session changed files/reading-data.json"; return 1; }
  "$adb" -s "$1" shell run-as $pkg rm -f files/dev-start && "$adb" -s "$1" shell am force-stop $pkg \
    || { echo "could not stop the Reader to relaunch it without dev-start"; return 1; }
  "$adb" -s "$1" shell log -p i -t Reader "$mark" || { echo "could not write the logcat marker"; return 1; }
  "$adb" -s "$1" shell monkey -p $pkg 1 >/dev/null 2>&1 || { echo "could not launch without dev-start"; return 1; }
  for _ in $(seq 1 30); do
    "$adb" -s "$1" logcat -d -s Reader:I | sed -n "/$mark/,\$p" | grep -q 'shelf rows=' && return 0
    sleep 1
  done
  echo "the Shelf never rendered"
  return 1
}
clear_starts() {  # on any exit: a dev-start left behind would open every later launch at chapter 1, and
                  # the Reader it launched saves nothing, so stop it before anyone reads in it. The Alice
                  # fixture goes only from devices this run pushed it to.
  for s in $dev_started; do
    "$adb" -s "$s" shell run-as $pkg rm -f files/dev-start || echo "ci: could not remove files/dev-start on $s" >&2
    "$adb" -s "$s" shell am force-stop $pkg || echo "ci: could not stop $pkg on $s" >&2
  done
  for s in $pushed_alice; do
    "$adb" -s "$s" shell run-as $pkg rm -f files/alice.epub files/alice.epub.ci \
      || echo "ci: could not remove the Alice fixture on $s" >&2
  done
}
dev_started=""
pushed_alice=""
data_before=""
trap clear_starts EXIT

# --- signoff/emulator
if [ "$docs_only" = 1 ]; then
  note "emulator: not applicable (docs-only change)"
  drift
else
  ./gradlew -q --console=plain :tool:testDebugUnitTest || fail_ctx emulator "unit tests"
  tests=$(cat tool/build/test-results/testDebugUnitTest/*.xml | grep -oE '<testsuite [^>]*tests="[0-9]+"' | grep -oE 'tests="[0-9]+"' | grep -oE '[0-9]+' | paste -sd+ - | bc)
  note "unit + property tests: $tests passed"
  drift

  lblog=$(mktemp)
  if ! scripts/light-build.sh >"$lblog" 2>&1; then
    tail -25 "$lblog" >&2
    fail_ctx emulator "Light-builder simulation"
  fi
  lb=$(grep '^light-build:' "$lblog"); rm -f "$lblog"
  note "Light-builder simulation (lightbuilder prepare + unsigned minified release): ${lb#light-build: }"

  emu=$("$adb" devices | awk '/^emulator-[0-9]+\tdevice/{print $1; exit}')
  [ -n "$emu" ] || fail_ctx emulator "no emulator running (mise run emu)"
  # Android letterboxes a portrait-locked app whose area is shorter than wide (DESIGN.md); the LP3
  # gives 1080x1168, so an emulator that differs lays out Pages no phone shows.
  disp=$("$adb" -s "$emu" shell dumpsys window displays | grep -m1 ' app=' | tr -d '\r')
  app=$(grep -oE 'app=[0-9]+x[0-9]+' <<<"$disp")
  dpi=$("$adb" -s "$emu" shell wm density | tr -d '\r' | tail -1 | grep -oE '[0-9]+$')
  if [ "$app" != app=1080x1168 ] || [ "$dpi" != 480 ]; then
    echo "ci: emulator shows ${app:-app=?} at ${dpi:-?}dpi; the LP3 is app=1080x1168 at 480dpi" >&2
    echo "ci: fix: adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural (three-button nav bar), or adb shell wm density 480 (wrong density)" >&2
    fail_ctx emulator "emulator app area is not the LP3's (need 1080x1168 at 480 dpi)"
  fi
  # The emulator talks to the LightOS emulator app, not LightOS: swap the server package for this build.
  scripts/emulator-build.sh ./gradlew -q --console=plain :tool:assembleDebug || fail_ctx emulator "assembleDebug"
  install_and_launch "$emu" tool/build/outputs/apk/debug/tool-debug.apk || fail_ctx emulator "install"
  line=$(roundtrip "$emu") || fail_ctx emulator "font round trip: $line"
  note "emulator font round trip (identical Page): $line"
  why=$(shelf_check "$emu") || fail_ctx emulator "$why"
  note "emulator: reading data byte-identical after the round trip; a plain launch renders the Shelf"
fi

# --- signoff/lp3 (only when a Light Phone III is attached)
lp3=$("$adb" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/{print $1}' | while read -r s; do
  [ "$("$adb" -s "$s" shell getprop ro.product.model | tr -d '\r')" = TLP301 ] && echo "$s"; done | head -1)
lp3_ran=0
if [ -n "$lp3" ] && [ "$docs_only" = 0 ]; then
  lightos=$("$adb" -s "$lp3" shell dumpsys package com.lightos | grep -m1 -oE 'versionName=[^ ]+' | cut -d= -f2)
  # The committed lighttool.toml already targets LightOS on the phone.
  ./gradlew -q --console=plain :tool:assembleDebug || fail_ctx lp3 "assembleDebug (device)"
  wake "$lp3" || fail_ctx lp3 "phone not reachable over adb"
  android=$("$adb" -s "$lp3" shell getprop ro.build.version.release | tr -d '\r')
  install_and_launch "$lp3" tool/build/outputs/apk/debug/tool-debug.apk || fail_ctx lp3 "install"
  wake "$lp3" || fail_ctx lp3 "phone not reachable over adb"
  line=$(roundtrip "$lp3") || fail_ctx lp3 "font round trip: $line"
  note "LP3 (TLP301, Android $android, LightOS $lightos) font round trip (identical Page): $line"
  why=$(shelf_check "$lp3") || fail_ctx lp3 "$why"
  note "LP3: reading data byte-identical after the round trip; a plain launch renders the Shelf"
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
post_status success emulator "$provenance" "$url" || die "posting signoff/emulator"
[ "$lp3_ran" = 1 ] && { post_status success lp3 "$provenance" "$url" || die "posting signoff/lp3"; }
echo "ci: posted signoff/emulator$([ "$lp3_ran" = 1 ] && echo ' + signoff/lp3') on ${head:0:12}${url:+ ($url)}"
