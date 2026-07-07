# Confirm-screen manual die recovery: long-press to add, drag to reposition

- **Status:** Accepted
- **Date:** 2026-07-07
- **Related:** [DEVPLAN.md "re-process reported photos"](../DEVPLAN.md) (measures
  the recovery), `feature/detection/DetectionViewModel.kt`,
  `feature/detection/DetectionScreen.kt`

## Context

Detection favours recall — every proposed region is surfaced so the user prunes
false positives rather than us dropping real dice — yet the classical detector
still *misses* dice, most reliably low-saturation/white dice on light surfaces
(the known hard case in the recognition roadmap). Before this change the only
recourse when a die was never boxed was `detectInRegion`, which crops to a
framed region and **replaces the whole photo and results** with that crop, or
filing the frame via `saveReport`. Neither adds a single missing die to the
existing full-frame result set, so a genuine miss meant a dead end — a large
share of the "reported" (unrecognized) photos.

## Decision

Add a manual recovery path on the confirm screen, layered on top of automatic
detection, with two gestures.

1. **Long-press to add.** A long-press re-runs detection on a small window around
   the tapped point, decoded from the original file at **native resolution**
   (reusing `decodeRegion`), and merges the found die back into the full-frame
   results, its box mapped from window-local to image coordinates. The
   resolution recovery is the point: a die that is ~256 px in the downscaled
   1280 full frame is ~1280 px in its own window, so a tighter pass can read what
   the full frame could not.
2. **A long-press always adds a die.** If the window yields nothing usable, drop
   a die-sized **placeholder** box on the tap (amber, no value) for the user to
   size and set a value on. Recovery must never silently do nothing — that is
   exactly the case (an undetectable die) where the user needs it most.
3. **Pick the die, not its pips.** On a tight crop the classical detector
   over-segments and emits a die's own pips as separate boxes (observed in device
   logs on white dice). The pick therefore rejects candidates smaller than a
   fraction of the median existing die's area, prefers a detection that read a
   value over an unread blob, then prefers a box containing the tap, then nearest.
4. **Drag to reposition.** Any box (detected or placeholder) is draggable so the
   user can centre it. A single per-box gesture **consumes the initial touch** so
   the photo's pan/zoom underneath cannot steal a drag that starts on a box, and
   distinguishes tap (identify as active die) from drag (move) itself by
   accumulating travel from the down point past touch-slop.
5. **Pure + observable.** Window sizing, coordinate mapping, and the dedup /
   size / readable pick are extracted as framework-free functions and unit-tested;
   `addDieAt`/`removeDie` log their decisions under tag `DetectionViewModel` for
   on-device diagnosis.

## Alternatives considered

- **Extend `detectInRegion` (crop-and-replace).** Rejected: it discards the full
  frame and every other die to drill into one region — the wrong model for
  "add the one die that was missed." Merge-into-full-frame keeps the roll intact.
- **Manual box drawing only, no re-detection.** Rejected as the primary path
  (slower, no benefit from the native-res re-read) but retained as the
  placeholder fallback, which is what guarantees recovery.
- **Two independent gestures — child `detectTapGestures` + `detectDragGestures`
  over the photo's `detectTransformGestures`.** Rejected: when zoomed, the
  parent pan won the touch-slop race and a box drag panned the photo. A single
  arbiter that consumes the down is deterministic at any zoom.
- **Fix the detector to find white dice instead.** Deferred: genuinely
  segmenting low-contrast dice is a separate, open problem (recognition roadmap).
  Manual recovery is needed regardless of detector quality.

## Consequences

- The volume of "reported" photos should fall; the reduction is measurable once
  the deferred "re-process reported photos" workflow lands.
- The DB recognition benchmark's 100 % recall is survivorship-biased — it only
  scores dice that reached the DB — so it structurally **cannot** show the value
  of this feature. That gap is why the reported-photo re-processing item exists.
- For a die the detector cannot segment, the die-sized placeholder is the
  intended outcome, not a failure — the pick returning null is by design.
- Deliberate gesture trade-off: a two-finger pinch whose first finger lands
  exactly on a box moves the box instead of zooming; a pinch starting on empty
  photo zooms normally.
