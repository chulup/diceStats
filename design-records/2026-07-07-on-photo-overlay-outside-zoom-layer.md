# On-photo annotations drawn outside the pinch-zoom layer

- **Status:** Accepted
- **Date:** 2026-07-07
- **Related:** `feature/detection/DetectionScreen.kt`,
  [confirm-screen manual die recovery](2026-07-07-confirm-screen-manual-die-recovery.md)

## Context

The confirm screen shows the captured photo inside a `graphicsLayer` that applies
pinch-zoom `scale` and pan `offset`, so the user can zoom in to inspect or frame a
region. Originally the entire annotation set — box outlines, value/name labels,
remove badges, and the invisible per-die tap/drag targets — lived **inside** that
same layer, chosen so the targets "stay aligned at any zoom without per-box math."

The side effect: everything inside the layer is scaled by the zoom, so a 3 dp
outline became a fat band, 15 sp labels became huge, and the remove badge ballooned
as the user zoomed in. Line weight and text are screen-space affordances; they
should not scale with the photo.

## Decision

Split the confirm-screen visuals into two layers by whether they should scale with
the photo:

- **The photo** stays inside the `graphicsLayer` (scales and pans).
- **Visible annotations** — outlines, labels, remove badges — are drawn and
  positioned in **untransformed screen space**, at constant pixel sizes (`3.dp`,
  `15.sp`, fixed badge size). Each normalized box coordinate is mapped to a screen
  pixel by applying the *same* transform the layer uses (scale about the centre,
  then pan) via shared `screenX` / `screenY` lambdas, so the annotation stays
  pinned to its die while keeping a constant on-screen size.
- **Invisible tap/drag hit targets** stay **inside** the `graphicsLayer`. They
  transform identically to the photo, so they coincide with the screen-space
  outlines for free — gesture code needs no per-box transform math, only the
  drawing does.

## Alternatives considered

- **Counter-scale stroke width and text by `1/zoom` inside the layer.** Tried
  first and rejected: a hack that fights the layer transform, couples every drawn
  size to the zoom, and does not cleanly cover interactive Composables like the
  remove badge (which is not a Canvas draw).
- **Move everything, including hit targets, into screen space.** Rejected as
  unnecessary work: the invisible targets align perfectly when left in the layer,
  so only the visible layer needs the per-box transform.

## Consequences

- Outlines, labels and badges hold a constant on-screen size at any zoom while
  tracking their dice — the reported defect is gone.
- There is a single source of truth for "map a die coordinate to a screen pixel"
  (`screenX`/`screenY`), reused by the overlay Canvas and the badges.
- Drawn outlines and hit targets are derived from two transforms that must agree;
  they read the same `scale`/`offset`, so they cannot drift. Any future change to
  the zoom transform must update both call sites.
