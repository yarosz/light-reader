# Releasing

Light builds and signs community tools from a public commit (see
[light-sdk/builder](https://github.com/lightphone/light-sdk/tree/main/builder)). So a Reader release is a
tagged commit on `main`, not an APK we build.

## Versioning

- `tool/lighttool.toml` carries both numbers; bump them together in the release PR.
  - `versionName`: semver `X.Y.Z` (no pre-release suffixes; Light rejects them). `0.x` until v1.
  - `versionCode`: +1 every release. Light's build server rejects a `versionCode` that is not greater
    than the last published one, and Android refuses to install a lower one over a higher one.
- The tool id `com.yarosz.reader` is permanent from the first published build. Never change it.
- Tag the merged release commit `vX.Y.Z`. The GitHub Release carries notes only, no APK.
- Release notes users see are the ones entered in Light's portal: three lines, in the listing's voice.
  GitHub Releases are for developers (the phone has no browser).
- Bump the `light-sdk` submodule to Light's newest SDK tag in each release PR, so local development stays
  close to the SDK Light builds against.

## Why we never publish our own APKs

Local builds are signed with the SDK's shared development key, which is public. Anyone could sign an
"update" with it that Android would accept over ours. Only Light-signed builds are distributed.

Testers only ever get Light-signed builds. Anyone who has run a dev-signed build (maintainers, or people
who built from source) needs to uninstall it before installing a Light-signed one, because the signatures
differ; uninstalling deletes the Shelf and every Place.

## Rollback

Light rejects a lower `versionCode`, so there is no "go back". A rollback is a new release, with a higher
`versionCode`, carrying the old code. The reading-data store must therefore tolerate a newer
`schemaVersion` than the code knows (read leniently, never crash, keep the `.bak`).

## Checklist

Release PR (version bump + notes summarising every PR since the last tag):

- [ ] `build` (GitHub Actions) and `signoff/emulator` green, as for every PR
- [ ] `signoff/lp3` green on the release commit: `mise run ci` with a Light Phone III attached
- [ ] Upgrade path: install this build over the previous release with a populated Shelf; Shelf and Places
      survive
- [ ] Minified release runs: `./gradlew :tool:assembleRelease`, install it (dev-signed, emulator only),
      read a few pages and change the font
- [ ] `mise run light-build` against Light's newest SDK passes (the weekly `sdk-main` job, or run it by
      hand)
- [ ] Public-content check: nothing personal in the diff, notes, or comments

After merge and tag (these follow the plan Light announced in
[discussion #204](https://github.com/orgs/lightphone/discussions/204); the portal isn't live yet and the
steps may change):

- [ ] Submit the tagged commit hash in Light's developer portal
- [ ] When Light's signed build is ready: download it, confirm package id, `versionName` and
      `versionCode` (`aapt dump badging`), install it on a Light Phone III, run the hardware loop, and only
      then share it
- [ ] For v1: "Send for review" in the portal
