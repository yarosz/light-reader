#!/usr/bin/env bash
# Lists words that may be new domain language since the last domain pass (AGENTS.md "Domain language").
# Advisory: it always exits 0.
#
#   scripts/domain-drift.sh [base-ref]
#
# The base is the newest domain-pass/* tag, else the repo's first commit. A candidate is a name in the
# working tree (untracked files included) that the base didn't have, of three kinds:
#
#   type  a top-level Kotlin class, interface or object under tool/src/main
#   copy  a string literal in tool/src/main that has a letter followed by a space, or (in a file that
#         imports Compose) is one capitalised word. Skipped: imports, comments, logging, exceptions,
#         key=value strings, and tables (a third or more of the words are numbers)
#   bold  a **bolded** phrase of at most four words, starting with a letter, in docs/**/*.md,
#         DESIGN.md or LEDGER.md
#
# A candidate is resolved when it appears in CONTEXT.md outside an _Avoid_ line (case-insensitive,
# CamelCase split into words, plural allowed), when a copy string appears verbatim in DESIGN.md (UI copy lives there), or when it
# equals a line of docs/domain-ignore.txt. The last line of output is "N unresolved".
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 0

base=${1:-$(git tag -l 'domain-pass/*' --sort=-creatordate | head -n1)}
[ -n "$base" ] || base=$(git rev-list --max-parents=0 HEAD | tail -n1)

kotlin_root=tool/src/main
is_doc() { case "$1" in docs/*.md|DESIGN.md|LEDGER.md) return 0 ;; *) return 1 ;; esac; }

types() {
  grep -E '^((public|internal|private|data|sealed|enum|value|abstract|open|annotation|inline|fun) )*(class|interface|object) [A-Z]' \
    | sed -E 's/.*(class|interface|object) ([A-Za-z0-9_]+).*/\2/'
}

copy() {  # $1 = 1 when the file imports Compose
  grep -vE '^[[:space:]]*(import|package|//|\*|/\*)' \
    | grep -vE 'Log\.[a-z]\(|Exception\(|error\(|require\(|check\(|throw ' \
    | grep -oE '"([^"\\]|\\.)*"' | sed -E 's/^"//; s/"$//' \
    | if [ "$1" = 1 ]; then grep -E '[A-Za-z] |^[A-Z][a-z]+$'; else grep -E '[A-Za-z] '; fi \
    | grep -v '=' | awk '{ n = 0; for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+$/) n++; if (n * 3 < NF) print }' \
    | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

bold() {
  grep -oE '\*\*[^*]+\*\*' | sed -E 's/^\*\*//; s/\*\*$//; s/[.:,;]+$//' \
    | grep -E '^[A-Za-z]' | awk 'NF <= 4'
}

candidates() {  # $1 = "work" for the working tree, else a commit; prints kind<TAB>candidate<TAB>path
  local f text compose
  { if [ "$1" = work ]; then
      find "$kotlin_root" -name '*.kt'; find docs -name '*.md'; ls DESIGN.md LEDGER.md
    else
      git ls-tree -r --name-only "$1" -- "$kotlin_root" docs DESIGN.md LEDGER.md
    fi 2>/dev/null; } | sort -u | while IFS= read -r f; do
    if [ "$1" = work ]; then text=$(cat "$f"); else text=$(git show "$1:$f"); fi
    case "$f" in
      *.kt)
        types <<<"$text" | sed "s|^|type	|; s|\$|	$f|"
        compose=0; grep -q '^import androidx\.compose' <<<"$text" && compose=1
        copy "$compose" <<<"$text" | awk -v f="$f" '{ print "copy\t" $0 "\t" f }'
        ;;
      *) is_doc "$f" && bold <<<"$text" | awk -v f="$f" '{ print "bold\t" $0 "\t" f }' ;;
    esac
  done
}

flat() { tr '\n' ' ' <"$1" | tr -s ' '; }
context=$(grep -v '^_Avoid_' CONTEXT.md | tr '\n' ' ' | tr -s ' ' | tr '[:upper:]' '[:lower:]')
design=$(flat DESIGN.md)
ignored=$(grep -vE '^[[:space:]]*(#|$)' docs/domain-ignore.txt 2>/dev/null | sed -E 's/[[:space:]]+$//')

in_context() {
  local words stem
  words=$(sed -E 's/([a-z0-9])([A-Z])/\1 \2/g; s/([A-Z]+)([A-Z][a-z])/\1 \2/g' <<<"$1" | tr '[:upper:]' '[:lower:]')
  stem=$(sed -E 's/[^a-z0-9 ]//g; s/s$//' <<<"$words")
  [ -n "$stem" ] && grep -qE "(^|[^a-z])${stem}(s|es)?([^a-z]|$)" <<<"$context"
}

resolved() {  # kind candidate
  grep -qxF -- "$2" <<<"$ignored" && return 0
  in_context "$2" && return 0
  [ "$1" = copy ] && grep -qF -- "$2" <<<"$design" && return 0
  return 1
}

before=$(candidates "$base" | cut -f1,2 | sort -u)
count=0
echo "domain-drift: candidates since $(git describe --tags --always "$base" 2>/dev/null || echo "$base")"
while IFS=$'\t' read -r kind name path; do
  [ -z "$kind" ] && continue
  grep -qxF -- "$kind	$name" <<<"$before" && continue
  resolved "$kind" "$name" && continue
  printf '  %-4s  %s  (%s)\n' "$kind" "$name" "$path"
  count=$((count + 1))
done < <(candidates work | sort -t$'\t' -k1,1 -k2,2 -u)
echo "$count unresolved"
exit 0
