#!/usr/bin/env bash
# Measure the Reader on a device against ADR 0007's bar (first Page at any Place, and any font change, <= 300 ms
# P90 on the SM4450, warm; page turns do no layout work) and ADR 0009's (the drawn open <= 300 ms P90 when the
# Place's Spine item is at most 200 K characters), from the "ReaderPerf" logcat lines a dev-start session logs. At
# one Place (by default the start of the EPUB's largest Spine item) it times N opens (fresh process each:
# force-stop + start) and N font changes; then two sessions of page turns across a window seam of that Spine item,
# reading the app's memory after the font changes and after each session.
#
# Opens are lazy, as a reader's are (ADR 0009): the Place's Spine item is parsed and shown first, and the rest of
# the Book behind it. A dev-start file names its Spine item by its index in the whole Book, which only a whole parse
# knows, so the probe opens below (finding the largest Spine item, checking -i, cutting its windows) name no Spine
# item id and parse the whole Book first. Their windows line gives the Spine item's id, and every timed open and
# turn session names it ("spine=<id>" in files/dev-start), so those are lazy. Nothing else goes in the app's files.
#
#   scripts/perf.sh [-s serial] [-n runs] [-r [-u]] [-i item] [-w | -o offset] [-c chars] <epub path or URL>
#
# The report's rows:
#   open, font      firstPageMs, the bar's own figure: a layout pass's start to the anchor Page ready to draw
#                   (the AnnotatedString build for the window(s), their measure, and packing). It excludes the
#                   EPUB parse, composition and the first frame.
#   next, retrace,  turns within the Spine item: ms from the turn reaching the view model to its main-thread work
#   back            done (the Page in state, the running head, Progress and hint published, the Place stamped;
#                   P50 stateMs, to the Page in state, alongside). syncWindows counts the windows a turn measured
#                   on the main thread (the bar says none); raced, those of them a background measure was measuring
#                   at that moment (the same window of the same pass: the turn measures it again rather than wait;
#                   background measures of other windows compete for the CPU but aren't counted). "windows" is the
#                   range of windows the Pages turned to start in. The forward session opens ~1,500 characters
#                   (about 3 Pages) before the end of a window and turns forward over the seam (next), then back
#                   over the same Pages, from the pass's cache (retrace); the back session opens at the next
#                   window's start and turns back, packing backward into the earlier window (back).
#   cross           turns from either session whose Spine item differs from the turn before's (the session's
#                   Place's for its first turn), in either direction. Those start a fresh pass, which measures its
#                   window on the main thread by design (prefetch covers only the shown pass): syncWindows > 0 there
#                   is no regression. Turns after a crossing that stay in the other Spine item are in no row: the
#                   report counts them. next, retrace and back hold only turns within the Place's Spine item.
#   repack          a background window landing re-packs its pass on the main thread (repackMs); a tap arriving
#                   meanwhile waits in the input queue, which no other row shows.
#   drawn …         the same actions timed to the first draw of their Page (the draw phase, before the render
#                   thread and the display); drawn turn and drawn cross split the turns as next/retrace/back and
#                   cross do. An open's runs from the view model's open start, before the EPUB parse: process start,
#                   the activity and the Shelf aren't in it. Its parts' P50s are alongside: parseMs, wordsMs (the
#                   word index), then from the open start bookMs (the Book parsed, indexed and its Place found) and
#                   passStartMs (the view composed and bound: the layout pass starts), and firstPageMs. A lazy open's
#                   parseMs is the parse before its first Page, the package, tables of contents and the Place's
#                   Spine item, and its wordsMs is 0: the word index is built behind the Page. The open starts once
#                   the Shelf has drawn, after LightActivity's splash (which cancels every draw, and with it
#                   Compose's layout of new content), so it costs about what a tap on the Shelf does.
#   loaded          each timed open's `book` line: loadedMs, from the open's start to the whole Book being in, which
#                   is how long Contents, the Progress line, turns off the Place's Spine item and Place writes wait.
#                   P50s alongside: placedMs (the parse before the first Page), restMs (the rest of the Book, parsed
#                   behind it, sharing the CPU with the first layout and draw) and parsedChars (the characters parsed
#                   before the first Page).
#   window          each window measure logged within 1 s of an open or font pass.
#   memory          dumpsys meminfo's App Summary, in MiB: total PSS, the Java and native heaps, and Graphics (GL and
#                   gfx buffers). Since Android 8 decoded bitmaps live in the native heap, so images show in native
#                   heap + graphics.
#
# Each turn session starts from a fresh open at its Place and taps as soon as the Page is drawn, while neighbouring
# windows may still be measuring: max(N, 6) taps each way, sent in one burst from a device-side shell ("input tap;
# sleep 0.3"). `input` starts a process per tap, so taps land further apart than 0.3 s; the report gives the real
# spacing, and each session's turns logged against the taps sent. The seam is the first window end, but the last,
# with room before it for the back session's taps at ~550 characters a Page (and at least 2,100 characters in);
# without one, the first end 2,100+ characters in, and the report says how many Pages the back session has before
# it reaches the previous Spine item. A Spine item of one window has no seam, and the turn sessions are skipped.
#
# -i sets the Spine item (as the app counts them, in the whole Book: its "book" line's chapters=), -o the character
# offset into it.
# -w puts the Place near a window seam instead: 150 characters before the Spine item's first window end, as the
# app cuts it, so the anchor Page straddles two windows and both are measured before it shows. The report then
# adds how many passes measured 0, 1, 2… windows synchronously. -c sets the window size for the run in place of
# WINDOW_CHARS, to see whether firstPageMs scales with it.
#
# -r times the minified release APK. A release build isn't debuggable, so `run-as` fails: each step writing the
# app's files (the EPUB, files/dev-start, the restore) runs with the debug APK installed, and the release APK goes
# back in over it before anything is timed. Both are signed with one key and share a versionCode, so the app's
# data survives each swap. Each time the release APK goes in, its bundled baseline profile is installed and the
# app compiled to it (`cmd package compile -m speed-profile`), the state a phone reaches after its idle background
# compile; -u skips that, to time the APK as installed. The report gives the dexopt status.
#
# The debug build's versionCode must be at least the installed APK's, or its install fails before anything is
# touched. The installed APK may have a lower one: the restore puts it back with `install -r -d` (a downgrade,
# which Android allows over the debuggable debug APK installed at that point).
#
# The serial defaults to the attached non-emulator device. The EPUB and the start Place go into the app's files
# through `run-as`, the real book kept in files/alice.epub.perf meanwhile. On exit, after a failure or Ctrl-C too
# (a second Ctrl-C doesn't stop it), the restore wakes the device, removes files/dev-start, puts the real book
# back, reinstalls the APK that was installed before the run (a copy is pulled first; the debug APK stays when
# none was) and restores the stay-awake setting. If a step fails, it prints the commands to finish by hand. A
# perf book left on a device that had no alice.epub, by a restore that failed, stays as its book.
set -euo pipefail

serial=""
runs=10
release=false
warm=true
item=""
seam=false
offset=""
chars=""
usage="usage: scripts/perf.sh [-s serial] [-n runs] [-r [-u]] [-i item] [-w | -o offset] [-c chars] <epub path or URL>"
while getopts "s:n:rui:wo:c:" opt; do
  case "$opt" in
    s) serial=$OPTARG ;;
    n) runs=$OPTARG ;;
    r) release=true ;;
    u) warm=false ;;
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
if ! $warm && ! $release; then die "-u leaves the release APK uncompiled; pass it with -r"; fi
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

# What the restore undoes: each flag is set before the step it covers.
work=""
swapped=false
files_touched=false
wrote_book=false
stay_set=false
orig_apk=""
orig_debuggable=""
orig_version=""
stay_on=""
activity=""
debug_apk=tool/build/outputs/apk/debug/tool-debug.apk

installed_flags() {  # -> the installed package's flags=[ … ] and versionName=…, one per line
  { a shell dumpsys package $pkg | tr -d '\r' | grep -m2 -oE '(^ +flags=\[[^]]*\]|versionName=[^ ]+)' || true; } | sed 's/^ *//'
}

# The LP3 drops off USB while asleep: wake it, wait up to 30 s for adb, clear a PIN-less lock screen.
wake() {  # -> fails when adb never answered; the keyguard dismissal's status doesn't count
  local awake=false
  for _ in $(seq 1 15); do
    if a shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; then awake=true; break; fi
    sleep 2
  done
  a shell wm dismiss-keyguard >/dev/null 2>&1 || true
  $awake
}

# The restore: on any exit, interrupts ignored. The app's files are cleaned with the debug APK in (run-as) and
# checked, and only then does the APK installed before the run go back in. A step failing skips the relaunch and
# prints the commands to finish by hand; $work is then kept for the pulled APK.
cleanup() {  # -> the run-as script putting the real book back; the perf book is deleted only once it was written
  printf '%s' "rm -f files/dev-start files/alice.epub.perf.tmp; if [ -f files/alice.epub.perf ]; then mv files/alice.epub.perf files/alice.epub"
  if $wrote_book; then printf '%s' "; else rm -f files/alice.epub"; fi
  printf '%s' "; fi"
}
retry() {  # command…: up to 3 tries
  local _
  for _ in 1 2 3; do "$@" && return 0; sleep 2; done
  return 1
}
restore() {
  trap '' INT TERM HUP
  set +e
  local failed=() cleaned=true debuggable
  if $swapped || $files_touched || $stay_set; then
    echo "perf: restoring the device" >&2
    wake 2>/dev/null || failed+=("the device isn't reachable over adb")
  fi
  if $files_touched; then
    cleaned=false
    if ! retry a install -r "$debug_apk" >/dev/null 2>&1; then
      failed+=("installing the debug APK for run-as")
    elif ! a shell run-as $pkg sh -c "'$(cleanup)'" 2>/dev/null ||
        ! a shell run-as $pkg sh -c "'[ ! -f files/dev-start ] && [ ! -f files/alice.epub.perf ]'" 2>/dev/null; then
      failed+=("removing files/dev-start and putting the real book back")
    else
      cleaned=true
    fi
  fi
  if $swapped && $cleaned && [ -n "$orig_apk" ] && ! cmp -s "$orig_apk" "$debug_apk"; then
    if ! retry a install -r -d "$orig_apk" >/dev/null 2>&1; then
      failed+=("reinstalling the APK installed before the run")
    else
      debuggable=no
      [[ $(installed_flags 2>/dev/null) == *DEBUGGABLE* ]] && debuggable=yes
      if [ "$debuggable" != "$orig_debuggable" ]; then
        failed+=("the reinstalled APK is debuggable: $debuggable, but was $orig_debuggable before the run")
      fi
    fi
  fi
  if $stay_set; then
    if [ "$stay_on" = null ]; then
      a shell settings delete global stay_on_while_plugged_in >/dev/null 2>&1
    else
      a shell settings put global stay_on_while_plugged_in "$stay_on" 2>/dev/null
    fi || failed+=("restoring stay_on_while_plugged_in")
  fi
  if [ ${#failed[@]} -eq 0 ]; then
    if $swapped && [ -n "$activity" ]; then
      a shell am force-stop $pkg 2>/dev/null
      a shell am start -n "$activity" >/dev/null 2>&1
    fi
    [ -z "$work" ] || rm -rf "$work"
    return
  fi
  local stay_cmd="settings put global stay_on_while_plugged_in $stay_on"
  [ "$stay_on" = null ] && stay_cmd="settings delete global stay_on_while_plugged_in"
  {
    echo
    echo "perf: RESTORE FAILED:"
    printf 'perf:   %s\n' "${failed[@]}"
    echo "perf: Reader isn't relaunched. To finish the restore by hand, with the device awake:"
    echo "  $adb -s $serial install -r $PWD/$debug_apk"
    echo "  $adb -s $serial shell run-as $pkg sh -c \"'$(cleanup)'\""
    if [ -n "$orig_apk" ]; then echo "  $adb -s $serial install -r -d $orig_apk   # Reader $orig_version as installed before the run"; fi
    if $stay_set; then echo "  $adb -s $serial shell $stay_cmd"; fi
    echo "perf: $work is kept for the APKs above; delete it afterwards"
  } >&2
}

trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
trap restore EXIT
work=$(mktemp -d)

epub=$work/book.epub
case "$src" in
  http://*|https://*) curl -fsSL -o "$epub" "$src" || die "download failed" ;;
  *) [ -f "$src" ] || die "no such file: $src"; cp "$src" "$epub" ;;
esac

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
  local out
  [ "$installed" = "$1" ] && return
  out=$(a install -r "$1" 2>&1) || die "installing $1 failed: $out"
  installed=$1
  if [ "$1" = "$measured_apk" ] && $release && $warm; then
    # The bundled baseline profile in (androidx.profileinstaller does this on a first start), then compiled to it.
    a shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n $pkg/androidx.profileinstaller.ProfileInstallReceiver >/dev/null
    a shell cmd package compile -f -m speed-profile $pkg >/dev/null
  fi
}

wake || die "device not reachable over adb"

# The APK installed now goes back in at the end; a split install can't be pulled whole. An adb error stops the run
# here, before anything is swapped: only a listing that worked and lacks the package means it isn't installed.
listed=$(a shell pm list packages $pkg | tr -d '\r') || die "couldn't list the device's packages over adb"
paths=""
if grep -qxF "package:$pkg" <<<"$listed"; then
  paths=$(a shell pm path $pkg | tr -d '\r') || die "couldn't find the installed APK over adb (pm path $pkg)"
  [ -n "$paths" ] || die "$pkg is listed as installed but pm path finds no APK"
  [ "$(grep -c . <<<"$paths")" -eq 1 ] || die "$pkg is installed as split APKs; perf.sh can't put it back afterwards"
  a pull "${paths#package:}" "$work/installed.apk" >/dev/null 2>&1 || die "couldn't copy the installed APK off the device"
  orig_apk=$work/installed.apk
  flags=$(installed_flags)
  orig_version=$(grep -oE 'versionName=[^ ]+' <<<"$flags" | cut -d= -f2 || true)
  if [[ $flags == *DEBUGGABLE* ]]; then orig_debuggable=yes; else orig_debuggable=no; fi
  echo "perf: Reader $orig_version (debuggable: $orig_debuggable) is installed; it goes back in at the end"
fi
swapped=true
put_apk "$debug_apk"
activity=$(a shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg | tail -1 | tr -d '\r')

stay_on=$(a shell settings get global stay_on_while_plugged_in | tr -d '\r')
# The real book is copied aside atomically, and only when no earlier run left its copy there.
files_touched=true
aside=$(a shell run-as $pkg sh -c "'rm -f files/alice.epub.perf.tmp; if [ -f files/alice.epub.perf ]; then echo kept; elif [ -f files/alice.epub ]; then cp files/alice.epub files/alice.epub.perf.tmp && mv files/alice.epub.perf.tmp files/alice.epub.perf; fi'" | tr -d '\r')
if [ "$aside" = kept ]; then echo "perf: using existing alice.epub.perf from an earlier run" >&2; fi
stay_set=true
a shell settings put global stay_on_while_plugged_in 7
wrote_book=true
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
field() { grep -oE "(^| )$1=[^ ]+" | head -1 | cut -d= -f2 || true; }
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
spine_id=$(field spineId <<<"$cut")
# The id goes into files/dev-start through `sh -c '…'`; one that can't (no XML id can) leaves the timed opens eager.
if ! [[ $spine_id =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "perf: WARNING: Spine item $item's id '$spine_id' can't go in files/dev-start; the timed opens parse the whole Book first" >&2
  spine_id=""
fi
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
# The turn sessions' seam: a window end, but the last's, at least 2,100 characters in, so the forward session's
# Place, 1,500 characters before it, isn't in the Spine item's first Page; and the first with room before it for
# the back session's taps, at ~550 characters a Page. Without one, the first 2,100+ characters in, noted.
taps=$((runs > 6 ? runs : 6))
page_chars=550
room=$((taps * page_chars > 2100 ? taps * page_chars : 2100))
IFS=, read -r -a window_ends <<<"$ends"
seam_window=""
seam_note=""
for ((k = 0; k + 1 < ${#window_ends[@]}; k++)); do
  if [ "${window_ends[k]}" -ge "$room" ]; then seam_window=$k; break; fi
done
if [ -z "$seam_window" ]; then
  for ((k = 0; k + 1 < ${#window_ends[@]}; k++)); do
    if [ "${window_ends[k]}" -ge 2100 ]; then seam_window=$k; break; fi
  done
  if [ -n "$seam_window" ]; then
    seam_note="no window end has room for $taps back turns ($room chars); the back session has ~$((window_ends[seam_window] / page_chars)) Pages before the previous Spine item"
    echo "perf: $seam_note" >&2
  fi
fi
dev_start "$item" "$offset" ${chars:+"$chars"} ${spine_id:+"spine=$spine_id"}

samples=$work/samples
windows=$work/windows
records=$work/records
drawn=$work/drawn
books=$work/books
memory=$work/memory
: >"$samples"
: >"$windows"
: >"$records"
: >"$drawn"
: >"$books"
: >"$memory"
pass() {  # reason: record its pass and shown lines (and an open's book line, logged once the whole Book is in), then the window and record lines, which trail them by up to a second
  local lines
  await "pass reason=$1 " >>"$samples"
  await "shown reason=$1 " >>"$drawn"
  if [ "$1" = open ]; then await ' book ' >>"$books"; fi
  sleep 1
  lines=$(a logcat -d -s ReaderPerf:I)
  grep ' window ' <<<"$lines" >>"$windows" || true
  grep ' record ' <<<"$lines" >>"$records" || true
}
mem() {  # when -> one line from dumpsys meminfo's App Summary, in MiB, for Reader's one process
  local pids line
  pids=$(a shell pidof $pkg | tr -d '\r' || true)
  read -r -a pids <<<"$pids"
  [ ${#pids[@]} -gt 0 ] || die "memory $1: Reader isn't running"
  [ ${#pids[@]} -eq 1 ] || die "memory $1: ${#pids[@]} processes are named $pkg (${pids[*]})"
  line=$(a shell dumpsys meminfo "${pids[0]}" | tr -d '\r' | LC_ALL=C awk -v when="$1" '
    function kb(name,   s) { s = $0; sub(".*" name ":[ \t]*", "", s); return s + 0 }
    /App Summary/ { on = 1 }
    on && /Java Heap:/ { java = kb("Java Heap") }
    on && /Native Heap:/ { native = kb("Native Heap") }
    on && /Graphics:/ { gfx = kb("Graphics") }
    on && /TOTAL PSS:/ { pss = kb("TOTAL PSS") }
    on && /^ *TOTAL:/ && pss == "" { pss = kb("TOTAL") }
    END {
      if (pss == "") exit
      printf "%-11s %-13s PSS %6.1f MiB  Java heap %5.1f  native heap %5.1f  graphics %5.1f  native+graphics %5.1f\n", "memory", when, pss / 1024, java / 1024, native / 1024, gfx / 1024, (native + gfx) / 1024
    }' || true)
  [ -n "$line" ] || die "memory $1: no TOTAL PSS in dumpsys meminfo's App Summary for pid ${pids[0]}"
  echo "$line" >>"$memory"
}

open_reader
await 'pass reason=open ' >/dev/null
for i in $(seq 1 "$runs"); do
  open_reader
  pass open
  echo "perf: open $i/$runs"
done

# The controls start hidden: the tap zones are on screen, and the middle one shows the controls with A+/A−. A
# node's line starts with its flags, then its text in quotes or its label after "~" (the Page's label is its text).
coords() { grep -E -- "^ *[^ \"~#]* $1" <<<"$tree" | grep -oE '\([0-9]+,[0-9]+\)' | tail -1 | tr -d '()' | tr , ' ' || true; }
tree=$(mise run ui)
back_zone=$(coords '~Previous page  \(')
show_zone=$(coords '~Show controls  \(')
next_zone=$(coords '~Next page  \(')
[ -n "$back_zone" ] && [ -n "$show_zone" ] && [ -n "$next_zone" ] || die "the tap zones are not on screen"
tap "$show_zone"
sleep 1.5
tree=$(mise run ui)
plus=$(coords '"A\+"( |$)')
minus=$(coords '"A−"( |$)')
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
mem "after fonts"

session() {  # name, offset, then zone and taps, …: a fresh open at offset, then one burst of taps once its Page is drawn
  local name=$1 at=$2 burst="" zone count total=0 got _
  shift 2
  while [ $# -gt 0 ]; do
    zone=$1 count=$2
    shift 2
    for _ in $(seq 1 "$count"); do burst+="${burst:+; sleep 0.3; }input tap $zone"; done
    total=$((total + count))
  done
  dev_start "$item" "$at" ${chars:+"$chars"} ${spine_id:+"spine=$spine_id"}
  open_reader
  reader_on_top || die "Reader is not the foreground app; not sending input"
  await 'shown reason=open ' 0.1 >/dev/null
  a shell "$burst"
  sleep 1.5
  a logcat -d -v epoch -s ReaderPerf:I | grep -E ' (turn|shown|record) ' | classify >"$work/$name" || true
  grep ' record ' "$work/$name" >>"$records" || true
  got=$(grep -c ' turn ' "$work/$name" || true)
  echo "perf: $name session: $got of $total turns logged ($(turned "$name"))"
  if [ "$got" -lt "$total" ]; then
    echo "perf: WARNING: the $name session logged $got turn lines for $total taps: taps were lost, or turned to no Page" >&2
  fi
  mem "after $name"
}
# Tags each turn line, and the shown line of its draw after it, class=in (within the Place's Spine item), cross (its
# Spine item differs from the turn before's; the Place's for the first) or past (after a crossing, in the other one).
classify() {
  LC_ALL=C awk -v item="$item" '
    function f(k,   i, s) { i = index($0, " " k "="); if (i == 0) return ""; s = substr($0, i + length(k) + 2); sub(/ .*/, "", s); return s }
    BEGIN { prev = item }
    / turn / { cur = f("item"); class = (cur != prev) ? "cross" : ((cur == item) ? "in" : "past"); prev = cur; print $0 " class=" class; next }
    / shown reason=turn / && class != "" { print $0 " class=" class; next }
    { print }'
}
turned() {  # session -> its turns logged per direction, against the taps sent
  local next back
  next=$(grep -c ' turn dir=next ' "$work/$1" || true)
  back=$(grep -c ' turn dir=back ' "$work/$1" || true)
  if [ "$1" = forward ]; then echo "next $next/$taps, back $back/$taps"; else echo "back $back/$taps"; fi
}

seam_end=""
if [ -n "$seam_window" ]; then
  seam_end=${window_ends[seam_window]}
  session forward $((seam_end - 1500)) "$next_zone" "$taps" "$back_zone" "$taps"
  session back "$seam_end" "$back_zone" "$taps"
else
  echo "perf: Spine item $item has no window seam 2,100+ characters in (windows end at $ends); skipping the turn sessions" >&2
fi

row() {  # label, the field timed, mode (pass, turn, cross, drawn, loaded, window, repack): one row from the lines on stdin; P90 is nearest-rank
  local label=$1 key=$2 mode=$3
  LC_ALL=C awk -v label="$label" -v key="$key" -v mode="$mode" -v seam="$seam" '
    function f(k,   i, s) { i = index($0, " " k "="); if (i == 0) return ""; s = substr($0, i + length(k) + 2); sub(/ .*/, "", s); return s }
    function sort(x, m,   i, j, v) { for (i = 2; i <= m; i++) { v = x[i]; for (j = i - 1; j > 0 && x[j] > v; j--) x[j + 1] = x[j]; x[j + 1] = v } }
    function pct(x, m, q,   k) { k = int(q * m + 0.9999); return x[k < 1 ? 1 : k] }
    function spread(x, m, fmt) { sort(x, m); return sprintf(fmt " " fmt " " fmt, pct(x, m, 0.5), pct(x, m, 0.9), x[m]) }
    function median(x, m) { sort(x, m); return pct(x, m, 0.5) }
    f(key) == "" { next }
    {
      n++; t[n] = f(key) + 0
      w = f("syncWindows") + 0; d[w]++; if (w > sw) sw = w; if (w > 0) synced++
      if (f("raced") + 0 > 0) raced++
      if (f("sync") == "true") sync++
      if (f("stateMs") != "") { ns++; st[ns] = f("stateMs") + 0 }
      if (f("window") != "") { v = f("window") + 0; if (nw == 0 || v < wlo) wlo = v; if (nw == 0 || v > whi) whi = v; nw++ }
      c[f("chars")] = 1; ch[n] = f("chars") + 0
      if (f("item") != "" && !(f("item") in it)) { it[f("item")] = 1; items = items (items == "" ? "" : ",") f("item") }
      if (f("parseMs") != "") { np++; pm[np] = f("parseMs") + 0; wm[np] = f("wordsMs") + 0; bm[np] = f("bookMs") + 0; sm[np] = f("passStartMs") + 0; fp[np] = f("firstPageMs") + 0 }
      if (f("placedMs") != "") { nl++; pl[nl] = f("placedMs") + 0; rs[nl] = f("restMs") + 0; pc[nl] = f("parsedChars") + 0 }
    }
    END {
      if (n == 0) { printf "%-11s %3d\n", label, 0; exit }
      printf "%-11s %3d  %s  ", label, n, spread(t, n, "%7.1f")
      if (mode == "pass") {
        chars = ""; for (k in c) chars = chars (chars == "" ? "" : ",") k
        printf "firstPageMs; chars %s; syncWindows max %d", chars, sw
      } else if (mode == "turn") {
        printf "to the work done (P50 stateMs %.1f); syncWindows>0 in %d (max %d), raced in %d; windows %d-%d", median(st, ns), synced, sw, raced, wlo, whi
      } else if (mode == "cross") {
        printf "to the work done (P50 stateMs %.1f); syncWindows>0 in %d (max %d), raced in %d; into Spine items %s", median(st, ns), synced, sw, raced, items
      } else if (mode == "drawn") {
        printf "to the first draw"
        if (np) printf "; P50 parseMs %.1f, wordsMs %.1f, bookMs %.1f, passStartMs %.1f, firstPageMs %.1f", median(pm, np), median(wm, np), median(bm, np), median(sm, np), median(fp, np)
      } else if (mode == "loaded") {
        printf "loadedMs, from the open start to the whole Book in"
        if (nl) printf "; P50 placedMs %.1f, restMs %.1f, parsedChars %d", median(pl, nl), median(rs, nl), median(pc, nl)
      } else if (mode == "repack") {
        printf "repackMs, on the main thread as a background window lands"
      } else {
        printf "measureMs; sync %d; chars %s", sync, spread(ch, n, "%d")
      }
      printf "\n"
      if (mode == "pass" && seam == "true") {
        printf "%-11s syncWindows:passes", ""; for (w = 0; w <= sw; w++) if (w in d) printf "  %d:%d", w, d[w]; printf "\n"
      }
    }'
}
turn_lines() {  # session, dir -> its turn lines within the Place's Spine item
  { grep -h " turn dir=$2 " "$work/$1" || true; } | { grep -F " class=in" || true; }
}
spacing() {  # -> P50 ms between consecutive turn lines of the sessions, from logcat's epoch stamps
  LC_ALL=C awk '
    FNR == 1 { have = 0 }
    / turn / { t = $1 + 0; if (have && t > prev) { n++; g[n] = (t - prev) * 1000 } prev = t; have = 1 }
    / shown reason=open / { have = 0 }
    END {
      if (n == 0) exit
      for (i = 2; i <= n; i++) { v = g[i]; for (j = i - 1; j > 0 && g[j] > v; j--) g[j + 1] = g[j]; g[j + 1] = v }
      k = int(0.5 * n + 0.9999); printf "%.0f", g[k < 1 ? 1 : k]
    }' "$work/forward" "$work/back"
}
model=$(a shell getprop ro.product.model | tr -d '\r')
compiled=$(a shell dumpsys package dexopt | tr -d '\r' | grep -A6 -F "[$pkg]" | grep -oE 'status=[^]]+' | head -1 || true)
warmth=""
if $release && $warm; then
  warmth=", baseline profile installed and compiled"
elif $release; then
  warmth=", as installed (-u)"
fi
echo
echo "Reader on $model, $build build$warmth${compiled:+, dexopt $compiled}; ADR 0007 bar: firstPageMs 300 P90 warm, and turns do no layout work (syncWindows 0)"
echo "ADR 0009 bar: drawn open 300 P90 warm, when the Place's Spine item is at most 200 K chars (this one: $length)"
opens="lazy (Spine item id $spine_id)"
[ -n "$spine_id" ] || opens="eager (no Spine item id)"
echo "Place: Spine item $item offset $offset$place_note; window size ${chars:-WINDOW_CHARS}; $runs runs; opens $opens"
if [ -n "$seam_end" ]; then
  gap=$(spacing)
  past=$(grep -h ' turn ' "$work/forward" "$work/back" | grep -c ' class=past' || true)
  echo "Turns: $taps each way across the seam at $seam_end (end of window $seam_window): forward from offset $((seam_end - 1500)), back from $seam_end;"
  echo "  logged: forward session $(turned forward); back session $(turned back)${seam_note:+; $seam_note}"
  echo "  taps ${gap:-?} ms apart (P50 between turn lines: one device-side burst, 0.3 s sleeps plus each \`input\` start-up), the first as the open's Page draws"
  echo "  cross: turns whose Spine item differs from the turn before's start a fresh pass, measured on the main thread by design"
  echo "  (prefetch covers only the shown pass); $past turns after a crossing, within the other Spine item, are in no row"
fi
echo "window n and sync are lower bounds: window lines are read 1 s after each pass, so later background measures are missed"
printf "%-11s %3s  %7s %7s %7s  %s\n" "" "n" "P50" "P90" "max" "ms; P50/P90/max where three"
grep -h 'pass reason=open ' "$samples" | row open firstPageMs pass || true
grep -h 'pass reason=font ' "$samples" | row font firstPageMs pass || true
if [ -n "$seam_end" ]; then
  turn_lines forward next | row next ms turn
  turn_lines forward back | row retrace ms turn
  turn_lines back back | row back ms turn
  { grep -h ' turn ' "$work/forward" "$work/back" || true; } | { grep -F ' class=cross' || true; } | row cross ms cross
fi
row repack repackMs repack <"$records"
grep -h 'shown reason=open ' "$drawn" | row "drawn open" ms drawn || true
grep -h ' book ' "$books" | row loaded loadedMs loaded || true
grep -h 'shown reason=font ' "$drawn" | row "drawn font" ms drawn || true
if [ -n "$seam_end" ]; then
  { grep -h 'shown reason=turn ' "$work/forward" "$work/back" || true; } | { grep -F ' class=in' || true; } | row "drawn turn" ms drawn
  { grep -h 'shown reason=turn ' "$work/forward" "$work/back" || true; } | { grep -F ' class=cross' || true; } | row "drawn cross" ms drawn
fi
row window measureMs window <"$windows"
cat "$memory"
