#!/usr/bin/env bash
# Lists words that may be new domain language since the last domain pass (AGENTS.md "Domain language").
# Advisory: it exits 0, or 2 when the base-ref argument isn't a commit.
#
#   scripts/domain-drift.sh [base-ref]
#
# The base is the newest domain-pass/* tag reachable from HEAD, else the repo's first commit. A
# candidate is a name in the working tree (untracked files included) that the base didn't have, of
# three kinds:
#
#   type  a Kotlin class, interface, object or typealias under tool/src/main, declared on a line that
#         starts in column 0 after optional annotations and modifiers; a declaration later on the
#         same line (after "{" or ";") counts too
#   copy  a string literal in tool/src/main that has a letter followed by a space, or (in a file that
#         imports Compose) is one capitalised word. Skipped: imports, comments (whole-line, and a
#         trailing " //"), logging (including a string that opens the line after "Log.x(TAG,"),
#         exceptions and throwing calls (error, check, require, checkNotNull, requireNotNull,
#         corrupt), key=value strings, and tables (a third or more of the words are numbers)
#   bold  a **bolded** phrase of at most four words, starting with a letter, in docs/**/*.md,
#         DESIGN.md or LEDGER.md
#
# CONTEXT.md defines a term on a "**Term**:" line (a parenthetical after the term is dropped), and
# the term's _Avoid_ line lists the words it replaces. A candidate is split into words (CamelCase
# too) and read left to right, matching each word, or a run of up to three, against the defined
# terms and then the avoid words, plural allowed. Words found only in the definitions' prose match
# nothing. A candidate is:
#
#   resolved  when it equals a line of docs/domain-ignore.txt, when every word is part of a defined
#             term ("CatalogueEntry", "Books"), or when it is copy that DESIGN.md quotes whole
#             ("<copy>"; UI copy lives there)
#   avoid     otherwise, when a word is an avoid word; printed with the term to use instead
#
# Anything else is unresolved, as is every avoid. Names present at the base are never candidates,
# so docs/domain-ignore.txt mostly matters for the first run, before any domain-pass tag exists.
#
# Known misses: a one-word string in a file that doesn't import Compose, a **bold** phrase wrapped
# across lines, a string built across lines, and a declaration indented inside another type.
# The last line of output is "N unresolved".
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 0

if [ $# -gt 0 ]; then
  base=$1
  git rev-parse --quiet --verify "$base^{commit}" >/dev/null \
    || { echo "domain-drift: $base is not a commit" >&2; exit 2; }
else
  base=$(git describe --tags --match 'domain-pass/*' --abbrev=0 HEAD 2>/dev/null) \
    || base=$(git rev-list --max-parents=0 HEAD | tail -n1)
fi

kotlin_root=tool/src/main
annotations='(@[A-Za-z.]+(\([^)]*\))? )*'
modifiers='((public|internal|private|protected|data|sealed|enum|value|abstract|open|annotation|inline|inner|fun) )*'
declaration="${annotations}${modifiers}(class|interface|object|typealias) [A-Z]"

types() {
  grep -E "^${declaration}" | sed -E 's| //.*$||' \
    | grep -oE "(^|[{;] *)${declaration}[A-Za-z0-9_]*" | sed -E 's/.* //'
}

copy() {  # $1 = 1 when the file imports Compose
  awk '{ after_log = log_open; log_open = ($0 ~ /Log\.[a-z]\(TAG,[[:space:]]*$/) }
       !(after_log && $0 ~ /^[[:space:]]*"/)' \
    | grep -vE '^[[:space:]]*(import|package|//|\*|/\*)' \
    | grep -vE 'Log\.[a-z]\(|Exception\(|error\(|(check|require)(NotNull)?\(|corrupt\(|throw ' \
    | sed -E 's| //.*$||' \
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
      docs/*.md|DESIGN.md|LEDGER.md) bold <<<"$text" | awk -v f="$f" '{ print "bold\t" $0 "\t" f }' ;;
    esac
  done
}

# One row per defined term or avoid word: kind<TAB>lowercase phrase<TAB>Term, kind "term" or "avoid".
lexicon=$(awk '
  /^\*\*[^*]+\*\*( \([^)]*\))?:/ {
    term = $0; sub(/^\*\*/, "", term); sub(/\*\*.*/, "", term)
    print "term\t" tolower(term) "\t" term; next
  }
  /^_Avoid_:/ && term != "" {
    n = split(substr($0, 9), avoid, ",")
    for (i = 1; i <= n; i++) {
      w = avoid[i]; sub(/\(.*/, "", w); gsub(/^[[:space:]]+|[[:space:]]+$/, "", w)
      if (w != "") print "avoid\t" tolower(w) "\t" term
    }
  }' CONTEXT.md)
design=$(tr '\n' ' ' <DESIGN.md | tr -s ' ')
ignored=$(grep -vE '^[[:space:]]*(#|$)' docs/domain-ignore.txt 2>/dev/null | sed -E 's/[[:space:]]+$//')

glossary() {  # candidate -> "term", "avoid<TAB>Term, Term" or "none"
  sed -E 's/([a-z0-9])([A-Z])/\1 \2/g; s/([A-Z]+)([A-Z][a-z])/\1 \2/g' <<<"$1" \
    | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' ' ' \
    | LEXICON=$lexicon awk '
      function plural(w, p) {
        return w == p || w == p "s" || w == p "es" || (p ~ /y$/ && w == substr(p, 1, length(p) - 1) "ies")
      }
      function join(a, b) { return a == "" ? b : index(", " a ", ", ", " b ", ") ? a : a ", " b }
      function terms(want, i, n,   k, j, ok, found) {  # the Terms whose phrase of n words starts at word i
        found = ""
        for (k = 1; k <= rows; k++) {
          if (kind[k] != want || size[k] != n || i + n - 1 > nw) continue
          ok = plural(word[i + n - 1], part[k, n])
          for (j = 1; j < n && ok; j++) ok = word[i + j - 1] == part[k, j]
          if (ok) found = join(found, term[k])
        }
        return found
      }
      BEGIN {
        rows = split(ENVIRON["LEXICON"], row, "\n")
        for (k = 1; k <= rows; k++) {
          split(row[k], f, "\t"); kind[k] = f[1]; term[k] = f[3]; size[k] = split(f[2], p, " ")
          for (j = 1; j <= size[k]; j++) part[k, j] = p[j]
        }
      }
      {
        nw = split($0, word, " "); covered = nw > 0; avoided = ""
        for (i = 1; i <= nw; i += step) {
          step = 0
          for (n = 3; n >= 1 && !step; n--) if (terms("term", i, n) != "") step = n
          if (step) continue
          covered = 0
          for (n = 3; n >= 1 && !step; n--)
            if ((hit = terms("avoid", i, n)) != "") { step = n; avoided = join(avoided, hit) }
          if (!step) step = 1
        }
        if (covered) print "term"; else if (avoided != "") print "avoid\t" avoided; else print "none"
      }'
}

before=$(candidates "$base" | cut -f1,2 | sort -u)
count=0
echo "domain-drift: candidates since $(git describe --tags --always "$base" 2>/dev/null || echo "$base")"
while IFS=$'\t' read -r kind name path; do
  [ -z "$kind" ] && continue
  grep -qxF -- "$kind	$name" <<<"$before" && continue
  grep -qxF -- "$name" <<<"$ignored" && continue
  IFS=$'\t' read -r match avoid_for <<<"$(glossary "$name")"
  [ "$match" = term ] && continue
  if [ "$match" = avoid ]; then
    printf '  %-5s  %s  (%s) — Avoid for %s\n' avoid "$name" "$path" "$avoid_for"
  else
    [ "$kind" = copy ] && grep -qF -- "\"$name\"" <<<"$design" && continue
    printf '  %-5s  %s  (%s)\n' "$kind" "$name" "$path"
  fi
  count=$((count + 1))
done < <(candidates work | sort -t$'\t' -k1,1 -k2,2 -u)
echo "$count unresolved"
exit 0
