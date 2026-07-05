# DiceStats — Design

An offline Android app for logging physical dice rolls from photos and reporting
per-die statistics. All processing is on-device — no network, no cloud.

## Concept

```
[Take photo] → [Detect d6 dice + count pips per die]
            → [Confirm screen: per die, show value + user assigns which Die
               (pick from list / register new) and may correct the value]
            → [Save Roll + DieResults] → [Stats]
```

The user-confirmation step is intentional: on-device recognition is imperfect, so
the app proposes results and the user confirms/corrects before saving.

## Scope & roadmap

| Ver | Adds |
|---|---|
| **MVP** | d6 + pip counting, multi-die detection per photo, manual die assignment, per-die stats |
| **v2** | Games (timed sessions), Die Pools (interchangeable dice), Die Groups (joint/sum distributions) |
| **v3** | Players + per-player stats within a game |

Each version is additive. Forward-looking foreign keys are introduced as nullable
in the MVP schema so later versions need no destructive migrations.

## Tech stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin + Jetpack Compose |
| Architecture | MVVM, unidirectional data flow, repository pattern |
| DI | Hilt |
| Async | Coroutines + Flow |
| Camera | CameraX |
| DB | Room (SQLite) |
| On-device ML / CV | LiteRT (TFLite) for detection + OpenCV for pip counting |
| Image loading | Coil |
| Charts | Vico (or MPAndroidChart) |

No network, location, or cloud SDKs.

## Module structure (multi-module Gradle)

```
:app                  — entry point, navigation host, DI wiring
:core:model           — domain models (Die, Roll, DieResult…)
:core:database        — Room entities, DAOs, DB
:core:data            — repositories, photo file storage
:core:designsystem    — Compose theme, shared UI
:feature:capture      — camera, recognition, confirm screen
:feature:dice         — register/manage dice
:feature:stats        — distribution & fairness views
:recognition          — on-device CV/ML pipeline behind a clean interface
```

`:recognition` is isolated behind an interface so the detection/counting strategy
can evolve without touching features.

## Recognition pipeline (MVP — `:recognition`)

d6 + pips only; pure computer vision, no identity matching:

1. **Detect dice** — locate every d6 in the photo (bounding boxes). Multi-die from
   the start.
2. **Count pips** — OpenCV blob/contour detection on each die's top face → value 1–6.
3. **Score** — confidence per die; low confidence is flagged, but the user confirms
   everything regardless.

Runs off the main thread (`Dispatchers.Default`) with a loading state in the UI.

### Recognition output contract

The pipeline emits one record per detected die:

```
DetectedDie
  value            Int       1..6  (recognized pip count)
  valueConfidence  Float     0..1
  boundingBox      Rect      location in the photo
  // dieId: NOT produced in MVP — identity is always user-assigned.
```

- **MVP:** no `dieId` is emitted. The confirm screen requires the user to assign
  every detected die. `valueConfidence` is informational — low values are flagged
  for attention, but the user confirms all dice regardless.
- `valueConfidence` is deliberately named to avoid clashing with the `Roll` entity
  (it is the confidence of the pip *value*, not of a capture).

### Forward-compatible extension (identity matching, future)

When on-device die identification is added, `DetectedDie` gains an **optional**
guessed identity — the output type is designed to carry it without restructuring:

```
DetectedDie (extended)
  …
  dieId?           Long      guessed physical die (null when no confident match)
  dieIdConfidence  Float?    0..1
```

Confirmation rule at that point: **require the user to pick a die when
`dieId == null` OR `dieIdConfidence < IDENTITY_CONFIRM_THRESHOLD`**.

- The value and identity thresholds are **separate, tunable constants** — never
  hardcoded literals — and should be calibrated against real labeled rolls rather
  than guessed. (A `0.95` figure is a starting hypothesis to validate, not a fixed
  rule.)

## Data model

```
Die                       (a registered physical die — or a pool of identical dice)
  id, name                e.g. "Red d6"
  faces = 6               fixed for MVP; kept for future die types
  kind = PIPPED
  count = 1               upper bound of identical dice; >1 = a pool (see Die Pools)
  referencePhotoPath?
  createdAt

Roll                      (one photo capture)
  id, photoPath, capturedAt, notes?
  gameId?      (FK, null in MVP)   ← v2: Games
  dieGroupId?  (FK, null in MVP)   ← v2: Die Groups
  playerId?    (FK, null in MVP)   ← v3: Players

DieResult                 (one die within a roll)
  id, rollId (FK), dieId (FK, set by user), value, confidence,
  boundingBox, wasCorrected
```

A `Roll` has many `DieResult`s (multiple dice per photo). Stats query `DieResult`
grouped by `dieId`.

## Die Pools (interchangeable dice, no individual identity)

Some games roll several identical dice whose individual identity doesn't matter —
Risk: up to 3 red attacker dice + up to 2 blue defender dice. A **pool** is a `Die`
row with `count > 1`: the entry stands for a *class* of interchangeable physical
dice, not one die. Design decisions (settled 2026-07):

- **Storage is unchanged.** One `DieResult` per detected die, exactly as for
  individual dice; a pool's results share its `dieId` (duplicate `dieId` within a
  roll is legal, up to `count`). Each result keeps its own value, bounding box,
  confidence, and `wasCorrected` — a pool roll never collapses into a sum or list.
- **`count` is an upper bound, not an exact size.** A Risk attacker legally rolls
  1–3 red dice, so fewer-than-count per roll is normal and unflagged. Assigning
  *more* than `count` boxes to a pool in one roll blocks saving.
- **Confirm-screen UX.** Palette entries already at capacity for the current roll
  are grayed out (no nudges about "missing" dice). The active-die auto-advance
  stays on a pool until its capacity in this roll is used, then moves on — so a
  Risk roll is tap-tap-tap red, auto-advance, tap-tap blue.
- **Auto-assignment is opt-in per game.** `Game.usesDicePools` (new flag): while
  the active game uses pools, detected dice whose colour signature matches a pool
  are auto-assigned to it. Outside such a game the flow stays fully manual. Pools
  make colour identity *more* reliable — identical dice are no longer ambiguous
  candidates, and the pool's fingerprint averages over every member's crops.
- **Stats semantics.** The pool's stats page pools all member throws (the existing
  per-die queries, unchanged). Chi-square then tests "**the pool is fair as a
  whole**"; a single biased die is diluted `count`× and *identifying* it is a
  non-goal — flagging pool-level unfairness is enough. The page shows "N throws ·
  M rolls" (per-photo vs per-die counts diverge for pools) and captions the
  verdict "pooled across N dice". Per-roll derived stats (sum, highest-of-N)
  bucket by the **actual** number of pool dice in each roll — never by `count`,
  since rolls legitimately vary (and detection can miss a die).
- **No migration** of previously-registered individual dice; pools and individual
  dice coexist freely.
- **Distinct from Die Groups** (v2 below — a named set of dice rolled together
  for joint stats): the concepts compose. A group member may itself be a pool
  ("Risk attack" = {Red ×3}); multiplicity lives on the die, so `DieGroupMember`
  needs no change.

## Screens (MVP)

| Screen | Purpose |
|---|---|
| **Roll log** | Recent rolls; FAB → capture |
| **Capture** | CameraX preview + shutter |
| **Confirm Results** | Photo with detected dice + pip values; user assigns each box to a Die (pick from list / register new) and can correct the value. To be refined after live testing. |
| **Dice list** | Registered dice |
| **Register die** | Name + optional reference photo |
| **Die stats** | Per-die 1–6 distribution, count, mean, chi-square fairness indicator |
| **Settings** | Storage info, export, clear data |

Single-activity, Compose Navigation.

## Statistics (per die)

- Frequency distribution (bar chart of faces 1–6).
- Total rolls, mean, expected vs observed.
- **Chi-square goodness-of-fit** → "looks fair / possibly biased".

## Photo storage

- App-specific external dir: `getExternalFilesDir("rolls")` — a separate directory,
  no storage permission required, removed on uninstall.
- DB stores the **relative path**, not the bitmap. One JPEG per roll.

## Permissions

- `CAMERA` (runtime). No storage, network, or location permissions.

---

## Deferred features (designed now, built later)

### v2 — Games (timed session)
- `Game(id, name, startedAt, endedAt?, usesDicePools)` — the flag gates
  pool auto-assignment on the confirm screen (see Die Pools).
- An active game is "open"; while open, new `Roll`s get its `gameId`. The user
  explicitly **starts** and **finishes** the game.
- **Game stats**: aggregate all rolls in the game, broken down per die and per die
  group.
- UI: active-game banner during capture, Games list, Game detail screen.

### v2 — Die Groups (dice rolled together)
- `DieGroup(id, name)` + join `DieGroupMember(groupId, dieId)` — a named set, e.g.
  "2d6 attack".
- A `Roll` may reference a `dieGroupId`; group stats track the **joint outcome
  (e.g. sum) distribution** across member dice, alongside each die's individual
  distribution.
- UI: Die Group list, group detail with sum-distribution chart.

### v3 — Players
- `Player(id, name)`.
- `Roll.playerId?` attributes a roll to a player.
- **Confirm screen (v3)**: add a "who rolled?" picker (pick player / register new),
  defaulting to the last-selected player for fast logging.
- A Game tracks participants via `GamePlayer(gameId, playerId)`.
- **Player stats**, scoped to a game: each player's roll distribution overall and
  per die.

A Game detail screen can ultimately show stats per die, per die group, and per
player within the session.

## Open items for the MVP build

- Confirm-screen UX for many dice in one photo — assign each box to a Die in turn;
  refine after live testing.
- Detection model choice for multi-die: small custom TFLite detector vs. classical
  CV segmentation — decide during MVP spike.
