<!--
Draft fdroiddata MR description — see issue #18.
MR title: New app: 500 - Card game
Branch: io.github.rotundtapir.fivehundred (fork of gitlab.com/fdroid/fdroiddata, public, unprotected)
Files: metadata/io.github.rotundtapir.fivehundred.yml (comment header trimmed)
       metadata/io.github.rotundtapir.fivehundred/signatures/11/
Commit message: New app: 500 - Card game
-->

500 is the classic Australian trick-taking card game, four players in fixed partnerships bidding over a 43-card deck. Play offline against bots (including an interactive tutorial), or online with friends — cross-play with the web build at <https://rotundtapir.github.io/500/>. GPL-3.0-or-later; no ads, no tracking, no proprietary dependencies in this flavor.

I'm the app's author. The `foss` flavor built here exists specifically so the F-Droid build graph is provably free of non-free code: the Play-Store flavor's ads/billing live in a separate Gradle module that only the `play` flavor depends on, so this build can never resolve it.

## Required

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [x] The original app author has been notified (and does not oppose the inclusion) — I am the author
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request — none exist
* [x] Builds with `fdroid build` and all pipelines pass
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author — <https://github.com/rotundtapir/500/issues>

## Strongly Recommended

* [x] The upstream app source code repo contains the app metadata _(summary/description/images/changelog/etc)_ in a [Fastlane](https://gitlab.com/snippets/1895688) folder structure — `fastlane/metadata/android/en-US/`, including per-versionCode changelogs and screenshots
* [x] Releases are tagged and auto update is enabled — `v*` tags, `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`

## Suggested

* [x] External repos are added as git submodules instead of srclibs — `cardkit` (shared card-game infrastructure, same author, same license) is a submodule; `submodules: true`
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds)
* [ ] Multiple apks for native code — n/a: no native code, single small APK

---------------------

### Notes for review

**License.** The repo is `GPL-3.0-or-later` with an additional GPLv3 §7 permission ([LICENSE-EXCEPTION.md](https://github.com/rotundtapir/500/blob/main/LICENSE-EXCEPTION.md)) whose only effect is to let the *play* flavor link Google Ads/Billing. The `foss` flavor built here contains none of that code, so plain `GPL-3.0-or-later` is the accurate license id. The quarantine module (`cardkit/cardkit-monetization-play`) is `scandelete`d, so the scanner confirms the tree it builds from is clean.

**Anti-features: none.** No ads or tracking in this flavor. Online multiplayer is optional and off by default (the app is fully playable offline); the game server is GPL and self-hostable ([docs/self-hosting.md](https://github.com/rotundtapir/500/blob/main/docs/self-hosting.md)), with the default instance operated by me — so I believe `NonFreeNet` does not apply.

**Reproducible builds — enabled from this first submission.** `Binaries:` + `AllowedAPKSigningKeys` + `signatures/11/` are included. Verified locally with fdroidserver 2.4.5: the `fdroid build` rebuild matched the v0.4.1 GitHub release asset with the allowed signer. Upstream release CI also gates publishing on reproducibility: every release APK must match a fresh-runner rebuild (full clone, no build cache, `apksigcopier compare`) *before* it is published, so future `Binaries:` references are pre-verified.

**Recipe notes.**

- **JDK 21**: AGP fails on newer JDKs; the Gradle daemon toolchain is pinned vendor-agnostically via `gradle/gradle-daemon-jvm.properties`, and the `sudo:` block installs `openjdk-21-jdk-headless` from bookworm-backports. I could not exercise the `sudo:` block itself locally (host already on JDK 21) — happy to adjust it to the buildserver image if needed.
- **`prebuild`** drops the foojay toolchain resolver from `settings.gradle.kts` so no JDK is ever downloaded at build time.
- **`scandelete`**: `cardkit/cardkit-monetization-play` (proprietary ads/billing, unreachable from this flavor's build graph) and `docs/fdroid` (the in-repo staging copy of this recipe — its `signatures/` dir contains APK signing-block binaries the scanner would flag).
- **`UpdateCheckData`** points at `gradle.properties`, where `versionCode`/`versionName` live (the build script reads them via `providers.gradleProperty`).

Summary, description, screenshots and changelogs are intentionally not in this MR — they're Fastlane metadata maintained in the app repo.

/label ~"New App"
