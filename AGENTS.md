# Reader

A LightOS tool: an eReader for DRM-free EPUBs on the Light Phone III, for any LP3 owner who chose the
phone to read more deliberately. One clear capability, nothing else.

- **Vocabulary:** `CONTEXT.md` is the glossary. Use its terms (Book, Shelf, Catalogue, Chapter, Spine
  item, Place, Page, Progress) in code, UI copy, and docs; update it when a term is settled.
- **Architecture decisions:** `docs/adr/`. Read the relevant ADR before changing Catalogues, how a Place
  is stored, network behaviour, layout vs navigation units, DRM handling, or typography.
- **Tunable values and typesetting rules:** `DESIGN.md` (type scale for the LP3's 480 dpi, page-break
  rules).
- **State and next work:** `LEDGER.md`. Read it before starting; update it before ending a session.

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
- Font and other Android resources under `tool/src/main/res/` are accepted.

## Verification loop

A change is done when all three are _green_:

1. `./gradlew :tool:testDebugUnitTest`: pure logic (pagination, parsing) has property tests.
2. Emulator: `mise run emu` (boot), `mise run tool` (build+install+launch), then drive and read the
   screen with `mise run ui` (`tap <label>`, `key volume_down`, `shot`; `mise run ui help`). Look at a
   screenshot for anything visual: tests cannot see rendering bugs.
3. Light's plugin checks pass (they run in every build).
