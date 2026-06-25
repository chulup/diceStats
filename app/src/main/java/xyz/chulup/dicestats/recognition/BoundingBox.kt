package xyz.chulup.dicestats.recognition

/**
 * A detected region within a photo, expressed in **normalized** coordinates
 * (0f..1f) relative to the source image. Normalized coordinates keep the box
 * independent of any downscaling done during detection and make overlaying it on
 * the displayed photo trivial.
 *
 * Step 4 maps this to the pixel `Rect` stored on `DieResult` (see DESIGN.md).
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
