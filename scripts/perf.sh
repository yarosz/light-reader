#!/usr/bin/env bash
# Measure time to the first Page on a device against ADR 0007's bar (first Page at any Place, and any
# font change, <= 300 ms P90 on the SM4450, warm). firstPageMs runs from a layout pass's start to the
# anchor Page being ready to draw: the AnnotatedString build for the window(s), their measure, and
# packing. It excludes the EPUB parse, composition and the first frame. Opens the EPUB's largest
# Chapter, then times N opens (fresh process each: force-stop + start) and N font changes from the
# app's "ReaderPerf" logcat lines, and reports each window measure alongside.
#
#   scripts/perf.sh [-s serial] [-n runs] <epub path or URL>
#
# The serial defaults to the attached non-emulator device. Debuggable builds only: the EPUB and the
# start Chapter go into the app's files through `run-as`. The app's own book and the device's stay-awake
# setting are restored on exit. If that restore fails (say the device drops off), files/dev-start and the
# perf book can stay behind, with the real book kept in files/alice.epub.perf; the next successful run
# cleans both, except that a perf book left on a device that had no alice.epub stays as its book.
set -euo pipefail

serial=""
runs=10
while getopts "s:n:" opt; do
  case "$opt" in
    s) serial=$OPTARG ;;
    n) runs=$OPTARG ;;
    *) echo "usage: scripts/perf.sh [-s serial] [-n runs] <epub path or URL>" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
[ $# -eq 1 ] || { echo "usage: scripts/perf.sh [-s serial] [-n runs] <epub path or URL>" >&2; exit 2; }
src=$1
die() { echo "perf: $1" >&2; exit 1; }
[[ $runs =~ ^[1-9][0-9]*$ ]] || die "-n wants a positive integer, got '$runs'"
case "$src" in
  http://*|https://*|/*) ;;
  *) src=$PWD/$src ;;
esac

cd "$(git rev-parse --show-toplevel)"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
pkg=com.yarosz.reader

if [ -z "$serial" ]; then
  serial=$("$adb" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/{print $1; exit}')
  [ -n "$serial" ] || die "no device attached (pass -s for an emulator)"
fi
export ANDROID_SERIAL=$serial
a() { "$adb" -s "$serial" "$@"; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
epub=$work/book.epub
case "$src" in
  http://*|https://*) curl -fsSL -o "$epub" "$src" || die "download failed" ;;
  *) [ -f "$src" ] || die "no such file: $src"; cp "$src" "$epub" ;;
esac

case "$serial" in
  emulator-*) scripts/emulator-build.sh mise exec -- ./gradlew -q --console=plain :tool:assembleDebug ;;
  *) mise exec -- ./gradlew -q --console=plain :tool:assembleDebug ;;
esac

# The LP3 drops off USB while asleep: wake it, wait up to 30 s for adb, clear a PIN-less lock screen.
for _ in $(seq 1 15); do
  if a shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; then break; fi
  sleep 2
done
a shell wm dismiss-keyguard >/dev/null 2>&1 || die "device not reachable over adb"
a install -r tool/build/outputs/apk/debug/tool-debug.apk >/dev/null
activity=$(a shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg | tail -1 | tr -d '\r')

stay_on=$(a shell settings get global stay_on_while_plugged_in | tr -d '\r')
a shell run-as $pkg sh -c "'[ -f files/alice.epub.perf ] || [ ! -f files/alice.epub ] || cp files/alice.epub files/alice.epub.perf'"
restore() {
  {
    a shell run-as $pkg sh -c "'rm -f files/dev-start; [ -f files/alice.epub.perf ] && mv files/alice.epub.perf files/alice.epub || rm -f files/alice.epub'" || true
    if [ "$stay_on" = null ]; then
      a shell settings delete global stay_on_while_plugged_in >/dev/null || true
    else
      a shell settings put global stay_on_while_plugged_in "$stay_on" || true
    fi
    a shell am force-stop $pkg || true
    a shell am start -n "$activity" >/dev/null || true
  } 2>/dev/null
  rm -rf "$work"
}
trap restore EXIT
a shell settings put global stay_on_while_plugged_in 7
a shell run-as $pkg sh -c "'cat > files/alice.epub'" <"$epub"
a shell run-as $pkg rm -f files/dev-start

open_reader() {
  a shell am force-stop $pkg
  a logcat -c
  a shell am start -W -n "$activity" >/dev/null
}
await() {  # pattern -> prints the first ReaderPerf line matching it, within 60 s
  local line
  for _ in $(seq 1 120); do
    line=$(a logcat -d -s ReaderPerf:I | grep -m1 -E "$1" || true)
    [ -n "$line" ] && { echo "$line"; return 0; }
    sleep 0.5
  done
  die "no ReaderPerf line matching '$1' within 60 s"
}
field() { grep -oE "$1=[^ ]+" | head -1 | cut -d= -f2; }
# The focused window, not the top activity: an ANR dialog ("Application Not Responding: <pkg>") or the
# notification shade takes focus while Reader stays the top resumed activity.
reader_on_top() {
  local focus
  focus=$(a shell dumpsys window | grep -m1 mCurrentFocus) || true
  [[ $focus == *"$pkg/"* ]]
}

open_reader
book=$(await ' book ')
largest=$(field largest <<<"$book")
echo "perf: $(field chapters <<<"$book") chapters; largest is index $largest, $(field largestChars <<<"$book") chars"
a shell run-as $pkg sh -c "'echo $largest > files/dev-start'"

samples=$work/samples
windows=$work/windows
: >"$samples"
: >"$windows"
pass() {  # reason: record its pass line, then the window lines, which trail it by up to a second
  await "reason=$1" >>"$samples"
  sleep 1
  a logcat -d -s ReaderPerf:I | grep ' window ' >>"$windows" || true
}
open_reader
await 'reason=open' >/dev/null
for i in $(seq 1 "$runs"); do
  open_reader
  pass open
  echo "perf: open $i/$runs"
done

tree=$(mise run ui)
coords() { grep -F "\"$1\"" <<<"$tree" | grep -oE '\([0-9]+,[0-9]+\)' | tail -1 | tr -d '()' | tr , ' '; }
plus=$(coords "A+")
minus=$(coords "A−")
[ -n "$plus" ] && [ -n "$minus" ] || die "A+/A− not on screen"
i=0
while [ "$i" -lt "$runs" ]; do
  for button in "$plus" "$plus" "$minus" "$minus"; do
    [ "$i" -lt "$runs" ] || break
    reader_on_top || die "Reader is not the foreground app; not sending input"
    a logcat -c
    # shellcheck disable=SC2086
    a shell input tap $button
    pass font
    i=$((i + 1))
    echo "perf: font $i/$runs"
  done
done

stats() {  # reason (open, font) or "window" -> one row; P90 is nearest-rank
  if [ "$1" = window ]; then cat "$windows"; else grep "reason=$1 " "$samples"; fi | LC_ALL=C awk -v r="$1" '
    function f(k,   i, s) { i = index($0, k "="); s = substr($0, i + length(k) + 1); sub(/ .*/, "", s); return s }
    r == "window" { n++; ch[n] = f("chars") + 0; ms[n] = f("measureMs") + 0; if (f("sync") == "true") sync++; next }
    { n++; t[n] = f("firstPageMs") + 0; c[f("chars")] = 1; w = f("syncWindows") + 0; if (w > sw) sw = w }
    function sort(x,   i, j, v) { for (i = 2; i <= n; i++) { v = x[i]; for (j = i - 1; j > 0 && x[j] > v; j--) x[j + 1] = x[j]; x[j + 1] = v } }
    function pct(x, q,   k) { k = int(q * n + 0.9999); return x[k < 1 ? 1 : k] }
    function row(x, fmt) { sort(x); return sprintf(fmt " " fmt " " fmt, pct(x, 0.5), pct(x, 0.9), x[n]) }
    END {
      if (n == 0) { printf "%-6s %3d\n", r, 0; exit }
      if (r == "window") {
        printf "%-6s %3d  sync %d  chars P50/P90/max %s  measureMs P50/P90/max %s\n", r, n, sync, row(ch, "%6d"), row(ms, "%7.1f")
        exit
      }
      chars = ""; for (k in c) chars = chars (chars == "" ? "" : ",") k
      printf "%-6s %3d %9s  %s  %11d\n", r, n, chars, row(t, "%7.1f"), sw
    }'
}
model=$(a shell getprop ro.product.model | tr -d '\r')
echo
echo "First Page ms on $model; ADR 0007 bar: 300 P90, warm"
echo "firstPageMs: pass start to anchor Page ready (AnnotatedString build, measure, packing); not EPUB parse, composition or first frame"
printf "%-6s %3s %9s  %23s  %11s\n" "" "n" "chars" "firstPageMs P50/P90/max" "syncWin max"
stats open
stats font
stats window
