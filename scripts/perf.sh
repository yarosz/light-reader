#!/usr/bin/env bash
# Measure layout latency on a device against ADR 0007's bar (first Page and font change <= 300 ms P90,
# warm). Opens the EPUB's largest Chapter, then times N warm opens (force-stop + start) and N font
# changes from the app's "ReaderPerf" logcat lines.
#
#   scripts/perf.sh [-s serial] [-n runs] <epub path or URL>
#
# The serial defaults to the attached non-emulator device. Debuggable builds only: the EPUB and the
# start Chapter go into the app's files through `run-as`. The app's own book and the device's stay-awake
# setting are restored on exit.
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

cd "$(git rev-parse --show-toplevel)"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
pkg=com.yarosz.reader
die() { echo "perf: $1" >&2; exit 1; }

if [ -z "$serial" ]; then
  serial=$("$adb" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/{print $1; exit}')
  [ -n "$serial" ] || die "no device attached (pass -s for an emulator)"
fi
export ANDROID_SERIAL=$serial
a() { "$adb" -s "$serial" "$@"; }

work=$(mktemp -d)
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
a shell run-as $pkg sh -c "'[ -f files/alice.epub ] && cp files/alice.epub files/alice.epub.perf || true'"
restore() {
  a shell run-as $pkg sh -c "'rm -f files/dev-start; [ -f files/alice.epub.perf ] && mv files/alice.epub.perf files/alice.epub || rm -f files/alice.epub'" || true
  a shell settings put global stay_on_while_plugged_in "$stay_on" || true
  a shell am force-stop $pkg || true
  a shell am start -n "$activity" >/dev/null || true
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
reader_on_top() { a shell dumpsys activity activities | grep topResumedActivity | grep -q "$pkg"; }

open_reader
book=$(await ' book ')
largest=$(field largest <<<"$book")
echo "perf: $(field chapters <<<"$book") chapters; largest is index $largest, $(field largestChars <<<"$book") chars"
a shell run-as $pkg sh -c "'echo $largest > files/dev-start'"

samples=$work/samples
: >"$samples"
open_reader
await 'reason=open' >/dev/null
for i in $(seq 1 "$runs"); do
  open_reader
  await 'reason=open' >>"$samples"
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
    await 'reason=font' >>"$samples"
    i=$((i + 1))
    echo "perf: font $i/$runs"
  done
done

stats() {  # reason -> one row; P90 is nearest-rank
  grep "reason=$1 " "$samples" | awk -v r="$1" '
    function f(k,   i, s) { i = index($0, k "="); s = substr($0, i + length(k) + 1); sub(/ .*/, "", s); return s }
    { n++; m[n] = f("measureMs") + 0; p[n] = f("paginateMs") + 0; t[n] = m[n] + p[n]; c[f("chars")] = 1 }
    function sort(x,   i, j, v) { for (i = 2; i <= n; i++) { v = x[i]; for (j = i - 1; j > 0 && x[j] > v; j--) x[j + 1] = x[j]; x[j + 1] = v } }
    function pct(x, q,   k) { k = int(q * n + 0.9999); return x[k < 1 ? 1 : k] }
    function row(x) { sort(x); return sprintf("%7.1f %7.1f %7.1f", pct(x, 0.5), pct(x, 0.9), x[n]) }
    END {
      chars = ""; for (k in c) chars = chars (chars == "" ? "" : ",") k
      printf "%-6s %3d %9s  %s  %s  %s\n", r, n, chars, row(t), row(m), row(p)
    }'
}
model=$(a shell getprop ro.product.model | tr -d '\r')
echo
echo "Layout latency on $model, ms (bar: 300 P90)"
printf "%-6s %3s %9s  %23s  %23s  %23s\n" "" "n" "chars" "total P50/P90/max" "measure P50/P90/max" "paginate P50/P90/max"
stats open
stats font
