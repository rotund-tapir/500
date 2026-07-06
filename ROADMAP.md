<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception -->

# Roadmap

Direction, not commitment — items land when they're ready. Feedback and votes:
[issues](https://github.com/rotundtapir/500/issues).

## v0.1 — first release (in progress)

Offline 500 against bots: 2/4/6 players, misère & no-trumps house rules,
interactive tutorial, FOSS + Play flavors. Play internal testing underway;
F-Droid submission follows the first stable tag.

## Towards v1.0

- **Remove-ads purchase fully disables the ads SDK** (play flavor). Today the
  purchase stops ads from loading or showing, but the Google Mobile Ads SDK is
  still initialised after consent, so initialisation traffic still flows.
  Resequence startup billing-first: check the `remove_ads` entitlement before
  gathering consent or touching the SDK, so paying users' devices never talk to
  ad servers at all. Touches the consent-before-ads and exactly-once
  interstitial invariants in `cardkit-monetization-play` — needs careful
  emulator verification of first-launch, purchase, and reinstall flows.

- **Online multiplayer.** The engine was built for this: `GameState` is a pure
  reducer, `PlayerView` is redacted per seat, and `Player` is a suspend seam, so
  a remote player slots in without engine changes. Architecture analysis (on the
  `multiplayer` branch, `docs/multiplayer-architecture.md`) leans towards an
  authoritative hosted server — Ktor/WebSockets reusing `GameDriver`,
  self-hostable JAR — over P2P, because a P2P host peer would see every hand and
  the kitty (fatal for misère). Final decision deferred until after v0.1.
  Likely split: generic `cardkit-server` + `cardkit-net-client` modules in
  cardkit; the 500-specific server binary and lobby UI here.

- **Stronger bot AI.** The current `FiveHundredBot` is heuristic (bid
  estimation, misère defence, simple card-play rules). Candidates, roughly in
  order of value: card counting / inference from the auction and discards,
  smarter partner cooperation on defence, Monte Carlo simulation of hidden
  hands for play decisions, and difficulty levels so the default stays
  approachable. Must stay deterministic per seed (the engine's reproducibility
  guarantee) and fast enough for 6-player games on modest phones.

## Unscheduled ideas

- More games on the shared `cardkit` base (Euchre is the natural next — the
  bower logic already generalises).
- Statistics / match history.
- Tablet layout polish (`fivehundred_tablet` AVD exists for testing).
