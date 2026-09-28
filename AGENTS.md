# Reader

A LightOS tool: an eReader for DRM-free EPUBs on the Light Phone III, for any LP3 owner who chose the
phone to read more deliberately. One clear capability, nothing else.

- **Vocabulary:** `CONTEXT.md` is the glossary. Use its terms in code, UI copy, and docs; see "Domain
  language" below.
- **Architecture decisions:** `docs/adr/`. Read the relevant ADR before changing Catalogues, how a Place
  is stored, network behaviour, layout vs navigation units, DRM handling, or typography.
- **Tunable values and typesetting rules:** `DESIGN.md` (type scale for the LP3's 480 dpi, page-break
  rules).
- **State and next work:** `LEDGER.md`. Read it before starting; update it before ending a session.

## Domain language

- **In every PR:** a new domain concept enters `CONTEXT.md` in the same PR, or goes in
  `docs/domain-ignore.txt` as implementation or UI vocabulary (UI surfaces belong in `DESIGN.md`).
- **Domain pass, at two points in every milestone:** before its first PR and after its last PR merges,
  plus whenever `scripts/domain-drift.sh` lists more than a handful of candidates. A pass:
  1. Run `scripts/domain-drift.sh` (`mise run domain-drift`).
  2. Run the `grilling` and `domain-modeling` skills with an answering agent (Opus) grounded in
     `CONTEXT.md`, the ADRs, `DESIGN.md` and the code. `grill-with-docs` is user-invoked only.
  3. Resolve every candidate as a glossary term, a rename, UI vocabulary or an ignore entry. Offer an
     ADR only when a decision is hard to reverse, surprising, and a real trade-off.
  4. Done when `domain-drift.sh` reports 0 unresolved. Tag the merged commit `domain-pass/<milestone>`.

## Layout

- `light-sdk/`: Light's SDK as a pristine submodule, pinned to a release tag. Never edit it.
- `tool/`: the Reader. Code in `tool/src/main/kotlin/com/yarosz/reader/`, tests in `tool/src/test/`.
- `mise.toml`, `.mise/tasks/ui`: toolchain and commands; run `mise tasks`.

## Platform rules (enforced by Light's build plugin)

- Write Kotlin, Compose UI, inside `LightScreen`/`LightViewModel`. Java source files fail the build.
- Use only allowlisted dependencies and permissions: `light-sdk/plugin/src/main/kotlin/com/thelightphone/plugin/`
  (`LightSdkPlugin.kt`, `LightToolMetadata.kt`). Reach files through the screen's `filesDir`.
- Reflection is blocked, including `.javaClass`. Native code (NDK/JNI) is disallowed by Light policy.
- The plugin generates the manifest from `tool/lighttool.toml`; cleartext HTTP is therefore off.
- `serverPackage` in `tool/lighttool.toml` stays `"com.lightos"` (LightOS on the phone): Light builds
  releases from the committed file. Emulator builds swap it at build time via `scripts/emulator-build.sh`
  (`mise run tool` and `mise run ci` already do). A unit test and `light-build.sh` enforce this.
- Font and other Android resources under `tool/src/main/res/` are accepted.

## How changes land

Every change arrives as a pull request. Required to merge: the GitHub Actions `build` check (unit tests
+ the Light-builder simulation on a clean Linux machine) and `signoff/emulator`. Only `mise run ci`
posts `signoff/*` statuses (through the GitHub API, described as local CI); never post them by hand. It
posts `signoff/lp3` too when a Light Phone III is attached, and releases require a green `signoff/lp3`.

Every PR also needs review before merge: a product review for changes to user-facing behaviour, copy,
or docs, and a code review for code changes (a PR can need both). Merge only once each required review
approves on the PR. PRs touching the reading view, Shelf, or copy attach one or two `mise run ui shot`
screenshots. Re-request review after changes. Squash merges; the PR title is the commit subject.

Public evidence (PR comments, statuses) carries generic facts only: no serials, hostnames, or local
paths.

## Verification loop

A change is done when all three are _green_:

1. `./gradlew :tool:testDebugUnitTest`: pure logic (pagination, parsing) has property tests.
2. Emulator: `mise run emu` (boot), `mise run tool` (build+install+launch), then drive and read the
   screen with `mise run ui` (`tap <label>`, `key volume_down`, `shot`; `mise run ui help`). Look at a
   screenshot for anything visual: tests cannot see rendering bugs.
3. Light's plugin checks pass (they run in every build).
