package xyz.chulup.dicestats.recognition

/** A pixel rectangle in the raw (un-rotated) image, for `BitmapRegionDecoder`. */
data class RawRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Maps a [region] (normalized 0..1, expressed in **display/oriented** coordinates) to a
 * pixel rectangle in the **raw**, un-rotated image — the inverse of the EXIF display
 * rotation. `BitmapRegionDecoder` works in raw pixel coordinates, so a region the user
 * selected on the (rotated) confirm photo must be transformed back before decoding.
 *
 * [rawWidth]/[rawHeight] are the decoder's reported (raw) dimensions. [rotationDegrees]
 * is the clockwise rotation applied to display the image (0/90/180/270); for 90/270 the
 * oriented image swaps width and height.
 *
 * Pure Kotlin (returns [RawRect], not `android.graphics.Rect`) so it stays JVM-testable.
 */
fun mapOrientedRegionToRaw(
    region: BoundingBox,
    rawWidth: Int,
    rawHeight: Int,
    rotationDegrees: Int,
): RawRect {
    // Oriented (displayed) pixel dimensions: 90/270 swap the axes.
    val swap = rotationDegrees == 90 || rotationDegrees == 270
    val orientedW = if (swap) rawHeight else rawWidth
    val orientedH = if (swap) rawWidth else rawHeight

    val oL = (region.left * orientedW).toInt()
    val oT = (region.top * orientedH).toInt()
    val oR = (region.right * orientedW).toInt()
    val oB = (region.bottom * orientedH).toInt()

    val rect = when (rotationDegrees) {
        // Raw rotated 90° CW to display: oriented (xo,yo) <- raw (xr=yo, yr=rawH-1-xo).
        90 -> RawRect(left = oT, top = rawHeight - oR, right = oB, bottom = rawHeight - oL)
        180 -> RawRect(
            left = rawWidth - oR,
            top = rawHeight - oB,
            right = rawWidth - oL,
            bottom = rawHeight - oT,
        )
        // Raw rotated 90° CCW to display: oriented (xo,yo) <- raw (xr=rawW-1-yo, yr=xo).
        270 -> RawRect(left = rawWidth - oB, top = oL, right = rawWidth - oT, bottom = oR)
        else -> RawRect(left = oL, top = oT, right = oR, bottom = oB)
    }

    // Clamp to image bounds and keep left<top<right ordering sane.
    return RawRect(
        left = rect.left.coerceIn(0, rawWidth),
        top = rect.top.coerceIn(0, rawHeight),
        right = rect.right.coerceIn(0, rawWidth),
        bottom = rect.bottom.coerceIn(0, rawHeight),
    )
}
