# Dice pools: interchangeable dice as one Die row with a count

- **Status:** Accepted
- **Date:** 2026-07-05
- **Related:** [DESIGN.md "Die Pools"](../DESIGN.md) (current-state spec),
  [DEVPLAN.md "Die pools"](../DEVPLAN.md) (implementation tasks)

## Context

Some games roll several visually identical dice whose individual identity does
not matter — Risk uses up to 3 red attacker dice and up to 2 blue defender dice.
The app's data model assumed one `DieEntity` per physical die, with the confirm
screen assigning each detected die to a specific registered die.

For identical dice that assignment is fiction: neither the colour-signature
matcher nor the user can tell three identical red dice apart in a photo, so
per-die assignment would be arbitrary and the resulting per-die statistics
meaningless. What the user actually wants is statistics per *group* ("the red
dice"), without identifying individuals.

## Decision

Relax what `DieEntity` means — from *one physical die* to *a class of
interchangeable dice* — by adding a `count` column (default 1). A **pool** is a
die with `count > 1`. Additionally, `GameEntity` gains a `usesDicePools` flag.

Key semantics:

1. **Storage is unchanged.** One `DieResultEntity` per detected die, as before;
   a pool's results share its `dieId`. Duplicate `dieId` within a roll is legal
   up to `count`. Rolls never collapse into sums or value lists.
2. **`count` is an upper bound, not an exact size.** A Risk attacker legally
   rolls 1–3 red dice, so fewer-than-count per roll is normal and unflagged;
   assigning more than `count` boxes to a pool blocks saving.
3. **Confirm-screen UX.** Palette entries at capacity for the current roll gray
   out (no "missing die" nudges). The active-die auto-advance stays on a pool
   until its per-roll capacity is used, then moves to the next palette entry.
4. **Auto-assignment is opt-in per game.** Only while the active game has
   `usesDicePools` are detections whose colour signature matches a pool
   auto-assigned. Outside such a game the flow stays fully manual.
5. **Statistics test the pool as a whole.** The pool's stats page pools all
   member throws through the existing per-die queries. Chi-square then tests
   "this pool is fair as a whole"; identifying a single bad die inside a pool
   is an explicit **non-goal** — flagging pool-level unfairness is enough.
   Sample counts are shown as "throws · rolls" (they diverge for pools), and
   per-roll derived stats (sum, highest-of-N) bucket by the *actual* number of
   pool dice in each roll, never by `count`.
6. **No migration** of previously registered individual dice; pools and
   individual dice coexist.

## Alternatives considered

- **Separate `DiePool` entity with a nullable `poolId` on `DieResultEntity`.**
  Rejected: creates two parallel foreign keys, forcing every stats query, the
  confirm-screen picker, the colour-signature learner, and the dice list to
  handle both cases. With count-on-die, all of these work unchanged, and colour
  identification actually improves — identical dice stop being ambiguous
  candidates, and the pool's fingerprint averages over every member's crops.
- **Keep individual dice and add a stats-only grouping layer.** Rejected:
  per-die assignment among identical dice is arbitrary at confirm time, so the
  underlying per-die data would be fiction; the grouping layer would launder it.
- **Uncapped "class die" (no count at all).** Simpler and sufficient for the
  statistics, but the cap catches the "assigned 4 boxes to a 3-die pool"
  mistake and drives the palette auto-advance, at the cost of one column.

## Consequences

- Single additive migration (`count` on `dice`, `usesDicePools` on `games`);
  `count = 1` preserves existing semantics exactly, no destructive change.
- Stats math (`DieStatistics`) is untouched; only labeling and interpretation
  change on the stats page.
- Permanently lost, by design: attribution of a throw to a specific physical
  die within a pool. A single biased die's signal is diluted N× in the pooled
  distribution (≈N× more photos to detect, and it can't be localised). Accepted;
  a future "More info" stats accordion will explain how to gather per-die
  evidence via calibration rolls (see DEVPLAN deferred follow-ups).
- Composes with the planned v2 Die Groups (a named set of dice rolled
  together): a group member may itself be a pool ("Risk attack" = {Red ×3}),
  so `DieGroupMember` needs no multiplicity of its own.
- Deferred follow-ups captured in DEVPLAN: bad-die "More info" accordion, roll
  editing/re-detection, `GameRules` entity (home of cross-pool analyses like
  attacker-vs-defender), die edit screen (count decrease only when no roll
  exceeds the new count), all stats scopable per game.
