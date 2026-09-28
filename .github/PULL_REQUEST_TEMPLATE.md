## What and why

## Checks

- [ ] `build` green (GitHub Actions: unit tests + Light-builder simulation)
- [ ] `signoff/emulator` posted by `mise run ci` (never by hand)
- [ ] Layout or rendering change? Run `mise run ci` with a Light Phone III attached so `signoff/lp3` is posted
- [ ] `scripts/domain-drift.sh` reports 0 unresolved, or this PR adds the new terms to `CONTEXT.md` / `docs/domain-ignore.txt`
- [ ] Hard-to-reverse decisions have an ADR
- [ ] Nothing personal in the diff or in PR comments
