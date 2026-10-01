#!/usr/bin/env bash
# Measure the Reader on a device against ADR 0007's bar (first Page at any Place, and any font change, <= 300 ms
# P90 on the SM4450, warm; page turns do no layout work), from the app's "ReaderPerf" logcat lines. At one Place
# (by default the start of the EPUB's largest Spine item) it times N opens (fresh process each: force-stop +
# start), N font changes, and two sessions of page turns from a fresh open, then reads the app's memory.
#
#   scripts/perf.sh [-s serial] [-n runs] [-r] [-i item] [-w | -o offset] [-c chars] <epub path or URL>
#
# The report's rows:
#   open, font      firstPageMs, the bar's own figure: a layout pass's start to the anchor Page ready to draw
#                   (the AnnotatedString build for the window(s), their measure, and packing). It excludes the
#                   EPUB parse, composition and the first frame.
#   next, retrace,  ms from a turn reaching the view model to its Page in state. syncWindows counts the windows
#   back            it measured on the main thread (the bar says none); raced, those a background measure was
#                   measuring at the time (a turn never waits for one: it measures the window again). Each
#                   session taps from a fresh open at the Place as soon as its Page is drawn, while neighbouring
#                   windows may still be measuring, about 0.5 s between taps: N forward (next), then N back over
#                   the same Pages (retrace, from the pass's cache); then, from a second fresh open, N back from
#                   the Place into earlier windows, packed backward (back).
#   drawn …         the same actions timed to the first draw of their Page (the draw phase, before the render
#                   thread and the display). An open's runs from the view model's open start, before the EPUB
#                   parse: process start, the activity and the Shelf aren't in it. Its parts' P50s are alongside:
#                   parseMs, wordsMs (the word index), then from the open start bookMs (the Book parsed, indexed
#                   and its Place found) and passStartMs (the view composed and bound: the layout pass starts),
#                   and firstPageMs.
#   window          each window measure logged within 1 s of an open or font pass.
#   memory          dumpsys meminfo's App Summary after the opens and after the turns: total PSS, the Java and
#                   native heaps, and Graphics, where decoded images will show.
#
# -i sets the Spine item (as the app counts them: its "book" line's chapters=), -o the character offset into it.
# -w puts the Place near a window seam instead: 150 characters before the Spine item's first window end, as the
# app cuts it, so the anchor Page straddles two windows and both are measured before it shows. The report then
# adds how many passes measured 0, 1, 2… windows synchronously. -c sets the window size for the run in place of
# WINDOW_CHARS, to see whether firstPageMs scales with it.
#
# -r times the minified release APK. A release build isn't debuggable, so `run-as` fails: each step writing the
# app's files (the EPUB, files/dev-start, the restore) runs with the debug APK installed, and the release APK goes
# back in over it before anything is timed. Both are signed with one key and share a versionCode, so the app's
# data survives each swap. Either way the run leaves the debug APK installed.
#
# The serial defaults to the attached non-emulator device. The EPUB and the start Place go into the app's files
# through `run-as`. The app's own book and the device's stay-awake setting are restored on exit, after a failure
# too. If that restore fails (say the device drops off), files/dev-start and the perf book can stay behind, with
# the real book kept in files/alice.epub.perf; the next successful run cleans both, except that a perf book left
# on a device that had no alice.epub stays as its book.
set -euo pipefail

serial=""
runs=10
release=false
item=""
seam=false
offset=""
chars=""
usage="usage: scripts/perf.sh [-s serial] [-n runs] [-r] [-i item] [-w | -o offset] [-c chars] <epub path or URL>"
while getopts "s:n:ri:wo:c:" opt; do
  case "$opt" in
    s) serial=$OPTARG ;;
    n) runs=$OPTARG ;;
    r) release=true ;;
    i) item=$OPTARG ;;
    w) seam=true ;;
    o) offset=$OPTARG ;;
    c) chars=$OPTARG ;;
    *) echo "$usage" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
[ $# -eq 1 ] || { echo "$usage" >&2; exit 2; }
src=$1
die() { echo "perf: $1" >&2; exit 1; }
[[ $runs =~ ^[1-9][0-9]*$ ]] || die "-n wants a positive integer, got '$runs'"
[ -z "$item" ] || [[ $item =~ ^(0|[1-9][0-9]{0,8})$ ]] || die "-i wants a Spine item index, got '$item'"
[ -z "$offset" ] || [[ $offset =~ ^(0|[1-9][0-9]{0,8})$ ]] || die "-o wants a character offset, got '$offset'"
[ -z "$chars" ] || [[ $chars =~ ^[1-9][0-9]{0,8}$ ]] || die "-c wants a positive integer, got '$chars'"
if $seam && [ -n "$offset" ]; then die "-w and -o both set the offset; pass one"; fi
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

debug_apk=tool/build/outputs/apk/debug/tool-debug.apk
measured_apk=$debug_apk
build="debug"
tasks=(:tool:assembleDebug)
if $release; then
  measured_apk=tool/build/outputs/apk/release/tool-release.apk
  build="release (minified)"
  tasks+=(:tool:assembleRelease)
fi
case "$serial" in
  emulator-*) scripts/emulator-build.sh mise exec -- ./gradlew -q --console=plain "${tasks[@]}" ;;
  *) mise exec -- ./gradlew -q --console=plain "${tasks[@]}" ;;
esac
installed=""
put_apk() {  # apk: installs it over whichever is in, keeping the app's data; nothing when it already is
  [ "$installed" = "$1" ] && return
  a install -r "$1" >/dev/null
  installed=$1
}

# The LP3 drops off USB while asleep: wake it, wait up to 30 s for adb, clear a PIN-less lock screen.
for _ in $(seq 1 15); do
  if a shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; then break; fi
  sleep 2
done
a shell wm dismiss-keyguard >/dev/null 2>&1 || die "device not reachable over adb"
put_apk "$debug_apk"
activity=$(a shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg | tail -1 | tr -d '\r')

stay_on=$(a shell settings get global stay_on_while_plugged_in | tr -d '\r')
a shell run-as $pkg sh -c "'[ -f files/alice.epub.perf ] || [ ! -f files/alice.epub ] || cp files/alice.epub files/alice.epub.perf'"
restore() {
  {
    if $release; then a install -r "$debug_apk" >/dev/null || true; fi
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
await() {  # pattern [poll seconds] -> prints the first ReaderPerf line matching it, within 60 s
  local line end=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$end" ]; do
    line=$(a logcat -d -s ReaderPerf:I | grep -m1 -E "$1" || true)
    [ -n "$line" ] && { echo "$line"; return 0; }
    sleep "${2:-0.5}"
  done
  die "no ReaderPerf line matching '$1' within 60 s"
}
field() { grep -oE "(^| )$1=[^ ]+" | head -1 | cut -d= -f2; }
# The focused window, not the top activity: an ANR dialog ("Application Not Responding: <pkg>") or the
# notification shade takes focus while Reader stays the top resumed activity.
reader_on_top() {
  local focus
  focus=$(a shell dumpsys window | grep -m1 mCurrentFocus) || true
  [[ $focus == *"$pkg/"* ]]
}
tap() {  # "x y"
  reader_on_top || die "Reader is not the foreground app; not sending input"
  # shellcheck disable=SC2086
  a shell input tap $1
}

# The start Place goes in with the debug APK in (run-as), and the APK being timed goes back in after it.
dev_start() {
  put_apk "$debug_apk"
  a shell run-as $pkg sh -c "'echo $* > files/dev-start'"
  put_apk "$measured_apk"
}
# Without dev-start the Tool opens on the Shelf. The first open finds the largest Spine item (and checks -i); the
# Spine item's windows line then gives its length and window ends.
probe=${item:-0}
dev_start "$probe" 0 ${chars:+"$chars"}
open_reader
book=$(await ' book ')
spine_items=$(field chapters <<<"$book")
largest=$(field largest <<<"$book")
echo "perf: $spine_items Spine items; largest is index $largest, $(field largestChars <<<"$book") chars; parsed in $(field parseMs <<<"$book") ms, words indexed in $(field wordsMs <<<"$book") ms"
[ "${item:-0}" -lt "$spine_items" ] || die "-i $item: the Book's Spine items are 0 to $((spine_items - 1))"
item=${item:-$largest}
if [ "$item" != "$probe" ]; then
  dev_start "$item" 0 ${chars:+"$chars"}
  open_reader
fi
cut=$(await ' windows ')
ends=$(field ends <<<"$cut")
length=${ends##*,}
place_note=""
if $seam; then
  [[ $ends == *,* ]] || die "Spine item $item is one window at $(field windowChars <<<"$cut") chars; no seam to measure"
  first_end=${ends%%,*}
  offset=$((first_end > 150 ? first_end - 150 : 0))
  place_note=" (150 chars before the first window end)"
elif [ -n "$offset" ]; then
  [ "$offset" -lt "$length" ] || die "-o $offset: Spine item $item has $length chars"
else
  offset=0
fi
echo "perf: Spine item $item, $length chars, windows of $(field windowChars <<<"$cut") chars end at $ends; Place at offset $offset"
dev_start "$item" "$offset" ${chars:+"$chars"}

samples=$work/samples
windows=$work/windows
drawn=$work/drawn
memory=$work/memory
: >"$samples"
: >"$windows"
: >"$drawn"
: >"$memory"
pass() {  # reason: record its pass and shown lines, then the window lines, which trail them by up to a second
  await "pass reason=$1 " >>"$samples"
  await "shown reason=$1 " >>"$drawn"
  sleep 1
  a logcat -d -s ReaderPerf:I | grep ' window ' >>"$windows" || true
}
mem() {  # when -> one line from dumpsys meminfo's App Summary, in MB
  a shell dumpsys meminfo $pkg | tr -d '\r' | LC_ALL=C awk -v when="$1" '
    function kb(name,   s) { s = $0; sub(".*" name ":[ \t]*", "", s); return s + 0 }
    /App Summary/ { on = 1 }
    on && /Java Heap:/ { java = kb("Java Heap") }
    on && /Native Heap:/ { native = kb("Native Heap") }
    on && /Graphics:/ { gfx = kb("Graphics") }
    on && /TOTAL PSS:/ { pss = kb("TOTAL PSS") }
    on && /^ *TOTAL:/ && pss == "" { pss = kb("TOTAL") }
    END {
      if (pss == "") { printf "%-11s %-12s no App Summary (is the app running?)\n", "memory", when; exit }
      printf "%-11s %-12s PSS %6.1f MB  Java heap %5.1f  native heap %5.1f  graphics %5.1f\n", "memory", when, pss / 1024, java / 1024, native / 1024, gfx / 1024
    }' >>"$memory"
}

open_reader
await 'pass reason=open ' >/dev/null
for i in $(seq 1 "$runs"); do
  open_reader
  pass open
  echo "perf: open $i/$runs"
done
mem "after opens"

# The controls start hidden: the tap zones are on screen, and the middle one shows the controls with A+/A−.
coords() { grep -F -- "$1" <<<"$tree" | grep -oE '\([0-9]+,[0-9]+\)' | tail -1 | tr -d '()' | tr , ' '; }
tree=$(mise run ui)
back_zone=$(coords "~Previous page")
show_zone=$(coords "~Show controls")
next_zone=$(coords "~Next page")
[ -n "$back_zone" ] && [ -n "$show_zone" ] && [ -n "$next_zone" ] || die "the tap zones are not on screen"
tap "$show_zone"
sleep 1.5
tree=$(mise run ui)
plus=$(coords '"A+"')
minus=$(coords '"A−"')
[ -n "$plus" ] && [ -n "$minus" ] || die "A+/A− not on screen"
i=0
while [ "$i" -lt "$runs" ]; do
  for button in "$plus" "$plus" "$minus" "$minus"; do
    [ "$i" -lt "$runs" ] || break
    a logcat -c
    tap "$button"
    pass font
    i=$((i + 1))
    echo "perf: font $i/$runs"
  done
done

turns() {  # file, then the zones to tap: a fresh open at the Place, tapped once its Page is drawn, ~0.5 s apart
  local out=$1 zone first=true
  shift
  open_reader
  await 'shown reason=open ' 0.1 >/dev/null
  for zone in "$@"; do
    $first || sleep 0.5
    first=false
    tap "$zone"
  done
  sleep 1
  a logcat -d -s ReaderPerf:I | grep -E ' (turn|shown|pass) ' >"$out" || true
}
forward=()
backward=()
for _ in $(seq 1 "$runs"); do
  forward+=("$next_zone")
  backward+=("$back_zone")
done
turns "$work/turns" "${forward[@]}" "${backward[@]}"
echo "perf: $runs turns forward, $runs back"
turns "$work/back" "${backward[@]}"
echo "perf: $runs turns back from the Place"
mem "after turns"

row() {  # label, the field timed, mode (pass, turn, drawn, window), grep pattern, files -> one row; P90 is nearest-rank
  local label=$1 key=$2 mode=$3 pattern=$4
  shift 4
  { grep -h -- "$pattern" "$@" || true; } | LC_ALL=C awk -v label="$label" -v key="$key" -v mode="$mode" -v seam="$seam" '
    function f(k,   i, s) { i = index($0, " " k "="); if (i == 0) return ""; s = substr($0, i + length(k) + 2); sub(/ .*/, "", s); return s }
    function sort(x, m,   i, j, v) { for (i = 2; i <= m; i++) { v = x[i]; for (j = i - 1; j > 0 && x[j] > v; j--) x[j + 1] = x[j]; x[j + 1] = v } }
    function pct(x, m, q,   k) { k = int(q * m + 0.9999); return x[k < 1 ? 1 : k] }
    function spread(x, m, fmt) { sort(x, m); return sprintf(fmt " " fmt " " fmt, pct(x, m, 0.5), pct(x, m, 0.9), x[m]) }
    function median(x, m) { sort(x, m); return pct(x, m, 0.5) }
    {
      n++; t[n] = f(key) + 0
      w = f("syncWindows") + 0; d[w]++; if (w > sw) sw = w; if (w > 0) synced++
      if (f("raced") + 0 > 0) raced++
      if (f("sync") == "true") sync++
      c[f("chars")] = 1; ch[n] = f("chars") + 0
      if (f("parseMs") != "") { np++; pm[np] = f("parseMs") + 0; wm[np] = f("wordsMs") + 0; bm[np] = f("bookMs") + 0; sm[np] = f("passStartMs") + 0; fp[np] = f("firstPageMs") + 0 }
    }
    END {
      if (n == 0) { printf "%-11s %3d\n", label, 0; exit }
      printf "%-11s %3d  %s  ", label, n, spread(t, n, "%7.1f")
      if (mode == "pass") {
        chars = ""; for (k in c) chars = chars (chars == "" ? "" : ",") k
        printf "firstPageMs; chars %s; syncWindows max %d", chars, sw
      } else if (mode == "turn") {
        printf "to the Page in state; syncWindows>0 in %d (max %d), raced in %d", synced, sw, raced
      } else if (mode == "drawn") {
        printf "to the first draw"
        if (np) printf "; P50 parseMs %.1f, wordsMs %.1f, bookMs %.1f, passStartMs %.1f, firstPageMs %.1f", median(pm, np), median(wm, np), median(bm, np), median(sm, np), median(fp, np)
      } else {
        printf "measureMs; sync %d; chars %s", sync, spread(ch, n, "%d")
      }
      printf "\n"
      if (mode == "pass" && seam == "true") {
        printf "%-11s syncWindows:passes", ""; for (w = 0; w <= sw; w++) if (w in d) printf "  %d:%d", w, d[w]; printf "\n"
      }
    }'
}
model=$(a shell getprop ro.product.model | tr -d '\r')
compiled=$(a shell dumpsys package dexopt | tr -d '\r' | grep -A3 -F "[$pkg]" | grep -oE 'status=[^]]+' | head -1 || true)
echo
echo "Reader on $model, $build build${compiled:+, $compiled}; ADR 0007 bar: firstPageMs 300 P90 warm, and turns do no layout work (syncWindows 0)"
echo "Place: Spine item $item offset $offset$place_note; window size ${chars:-WINDOW_CHARS}; $runs runs, turns $runs each way"
echo "window n and sync are lower bounds: window lines are read 1 s after each pass, so later background measures are missed"
printf "%-11s %3s  %7s %7s %7s  %s\n" "" "n" "P50" "P90" "max" "ms; P50/P90/max where three"
row open firstPageMs pass 'pass reason=open ' "$samples"
row font firstPageMs pass 'pass reason=font ' "$samples"
row next ms turn ' turn dir=next ' "$work/turns"
row retrace ms turn ' turn dir=back ' "$work/turns"
row back ms turn ' turn dir=back ' "$work/back"
row "drawn open" ms drawn 'shown reason=open ' "$drawn"
row "drawn font" ms drawn 'shown reason=font ' "$drawn"
row "drawn turn" ms drawn 'shown reason=turn ' "$work/turns" "$work/back"
row window measureMs window ' window ' "$windows"
cat "$memory"
