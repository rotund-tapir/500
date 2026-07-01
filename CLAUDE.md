# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The Android app for the card game **500** (4-player Australian rules), and the first consumer of the
shared **`cardkit`** library. `cardkit` lives in its own repo and is included here as a git submodule
at `./cardkit`, wired into Gradle as a composite build. Only 500-specific code lives in this repo;
game-agnostic infrastructure lives in `cardkit`.

## Toolchain (read first — non-obvious and will waste time otherwise)

- **Gradle must run on JDK 21** (the machine default `java` is JDK 25, and the Android Gradle Plugin
  fails on JDK 25+). `gradle/gradle-daemon-jvm.properties` pins the daemon to a version-21 toolchain
  (vendor-agnostic — do NOT let Android Studio regenerate it with `toolchainVendor=JETBRAINS`; that
  breaks CI, which has Temurin). So `JAVA_HOME` is no longer required for `./gradlew`, but every
  invocation still needs:
  ```bash
  export ANDROID_HOME="$HOME/Android/Sdk"
  ```
  Kotlin/JVM modules pin `jvmToolchain(21)`; Android modules pin `jvmTarget = 17`.
- `gradle` is not on PATH; use the committed wrapper `./gradlew` (or `source ~/.sdkman/bin/sdkman-init.sh`).
- The Android SDK here has only `platforms;android-35` + `build-tools;35.0.0`.

## Common commands

```bash
# Pure-Kotlin logic (fast, no Android SDK needed) — the engine is the important part
./gradlew :engine:test
./gradlew :ai:test

# A single test class (JUnit 5 platform)
./gradlew :engine:test --tests "io.github.rotundtapir.fivehundred.engine.TrickEvaluatorTest"

# Build both distribution flavors
./gradlew assembleFossDebug assemblePlayDebug

# Lint (this is what CI runs via `build`; a pre-commit hook runs `lint test` too)
./gradlew lint

# CRITICAL F-Droid gate: the FOSS build must contain NO proprietary dependency.
./gradlew :app:dependencies --configuration fossDebugRuntimeClasspath \
  | grep -Ei 'gms|billing|firebase|monetization-play'   # must print nothing
```

Enable the pre-commit hook once per clone: `git config core.hooksPath scripts/hooks` (runs
`./gradlew lint test`; skips doc-only commits; auto-selects JDK 21; bypass with `--no-verify`).

## Architecture

### Module layout (this repo + the submodule)
- `cardkit/` (submodule) — reusable infra: `cardkit-core` (pure Kotlin), `cardkit-ui` (Compose),
  `cardkit-monetization` (interface + FOSS no-op), `cardkit-monetization-play` (Google Ads + Billing).
- `engine/` — pure-Kotlin 500 rules. **No Android imports** (it's a `kotlin("jvm")` module so a leak
  won't compile). This keeps the authoritative engine runnable server-side for future online play.
- `ai/` — heuristic bot, pure Kotlin, depends on `engine`.
- `app/` — Jetpack Compose UI, depends on `engine` + `ai` + cardkit modules.

### The engine is a pure state machine (the core idea)
`FiveHundredRules : GameRules<GameState, Action, PlayerView>` (cardkit-core interface) — `apply(state,
seat, action)` is a pure reducer; `GameDriver` loops it, asking each seat's `Player` to decide.
- **Determinism:** the whole match derives from `rngSeed` in `GameState`, which evolves per deal. Same
  seed ⇒ identical match. Tests and reproducibility depend on this — don't introduce nondeterminism.
- **`PlayerView` is redacted per-seat** (own hand + public info only). This is the multiplayer seam:
  `Player` is `suspend`, so a local AI (`StrategyPlayer`), a human (`ChannelPlayer`, driven by the UI),
  or a future `RemotePlayer` are interchangeable with no engine change. Never widen `PlayerView` to
  expose hidden hands.
- **Bidding** ranks via `ScoreSchedule.ladder` (an ordered list), NOT by point value — Misère (250)
  sits between 8♠ and 8♣, and Open Misère outranks 10NT despite tying its 500 points.
- **`TrickEvaluator`** owns all card-strength logic: trumps, both bowers (the left bower — Jack of the
  same-colour suit — counts as trump), the Joker, no-trump, and follow-suit legality. Cards are
  deliberately not `Comparable`; strength is always relative to (trump, ledSuit).

### Distribution flavors & monetization (the reason for the module split)
Two flavors on dimension `distribution`: **`foss`** (no ads, donation link; what F-Droid builds) and
**`play`** (Google Ads + a remove-ads IAP). Shared code only references the `Monetization` interface;
the concrete impl is chosen by a **flavor-specific `MonetizationProvider`** in `app/src/foss` vs
`app/src/play`. All proprietary code is quarantined in the `cardkit-monetization-play` module, which
**only the `play` flavor depends on** (`"playImplementation"(...)`), so the FOSS build graph is
provably free of non-free code. Do not add GMS/Billing/Firebase anywhere the `foss` build can reach.

## Working across the submodule

Editing shared/infra behaviour means changing files under `cardkit/`, which is a **separate repo**:
1. Commit inside `cardkit/` (its own history, license, and pre-commit hook).
2. Advance the submodule pointer in this repo: the submodule's origin is the (not-yet-pushed) GitHub
   URL, so to pull a locally-made cardkit commit into `./cardkit` use
   `git -C cardkit fetch /path/to/cardkit main && git -C cardkit checkout <sha>`, then
   `git add cardkit` and commit here. Push `cardkit` before pushing this repo so the referenced commit
   exists remotely.

## Conventions

- New source files get the SPDX header:
  `// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception`
- Commits require a DCO sign-off (`git commit -s`); no CLA.
- Namespace is `io.github.rotundtapir.*` (final — the sanitized `io.github.<org>` form of the
  `rotund-tapir` GitHub org; hyphens are illegal in package names). `applicationId` is
  `io.github.rotundtapir.fivehundred`.
- Placeholders still to set before release (intentionally left): real AdMob ad-unit/app ids and the
  `remove_ads` product (currently Google test ids), and the donation URL in
  `app/src/foss/.../MonetizationProvider.kt`.
