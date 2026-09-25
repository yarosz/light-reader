# Contributing

Thanks for helping. Issues and pull requests are welcome.

## Before you start

- Read `AGENTS.md` (how the project is laid out and how changes land), `CONTEXT.md` (the vocabulary), and
  the ADRs in `docs/adr/`. Proposals that reverse an ADR should say why its "revisit if" condition is met.
- For anything larger than a fix, open an issue first so we can agree on the shape.

## Platform rules

Light's build plugin enforces these, and Light builds every release from source:

- Kotlin only, Compose UI, inside the SDK's `LightScreen` / `LightViewModel`.
- Only allowlisted dependencies and permissions (see `light-sdk/plugin/`). No reflection, no native code.
- The Reader does one thing: reading DRM-free EPUBs. Features that add accounts, tracking, feeds of
  recommendations, or anything attention-seeking won't be merged.

## What we won't accept

- **Anything that removes, bypasses, or works around DRM**, including "import" helpers that strip it.
  The Reader opens DRM-free EPUBs only (ADR 0005).
- Code that scrapes websites instead of using a Catalogue feed (ADR 0001).

## Pull requests

- Keep them small and focused; the title becomes the commit subject.
- `build` (GitHub Actions) must be green. Maintainers run the local checks (`mise run ci`: emulator, and
  a real Light Phone III for layout changes) before merging. For a pull request from a fork, a maintainer
  pushes your branch to this repository to run them, since the checks attest to commits on this repo.
- New or changed terms go in `CONTEXT.md`; hard-to-reverse decisions get an ADR.

By contributing, you agree your work is released under the MIT license.
