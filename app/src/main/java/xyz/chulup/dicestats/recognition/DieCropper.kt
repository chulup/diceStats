package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import xyz.chulup.dicestats.recognition.internal.ValueClassifierPipeline
import java.io.Closeable
import java.io.File

/**
 * Cuts die crops for the [ValueClassifier] out of the **full-resolution** photo at [path]: the
 * detector runs on a downscaled copy, but the value is read from native pixels (the training
 * crops were cut the same way, see [ValueClassifierPipeline]). Opens the JPEG once for all dice
 * of a photo; [close] when done.
 */
class DieCropper(path: String) : Closeable {

    /** A decoded crop, oriented for display, plus the padding that completes its square. */
    class Crop(val bitmap: Bitmap, val padding: ValueClassifierPipeline.Padding)

    @Suppress("DEPRECATION")
    private val decoder: BitmapRegionDecoder = File(path).inputStream().use { BitmapRegionDecoder.newInstance(it, false) }
        ?: error("cannot open $path")
    private val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    private val swap = rotation == 90 || rotation == 270

    /** Oriented (display) size of the full-resolution photo. */
    val width: Int = if (swap) decoder.height else decoder.width
    val height: Int = if (swap) decoder.width else decoder.height

    /** The crop for [box] (normalized, display coordinates); null when it has no area. */
    fun crop(box: BoundingBox): Crop? {
        val square = ValueClassifierPipeline.squareAround(box.left * width, box.top * height, box.right * width, box.bottom * height)
        val (inside, pad) = square.clipTo(width, height)
        if (inside[2] <= inside[0] || inside[3] <= inside[1]) return null
        // Decode no finer than needed: the classifier input is INPUT_SIZE wide.
        var sample = 1
        while (square.side / (sample * 2) >= ValueClassifierPipeline.INPUT_SIZE) sample *= 2
        val region = BoundingBox(
            inside[0] / width.toFloat(), inside[1] / height.toFloat(),
            inside[2] / width.toFloat(), inside[3] / height.toFloat(),
        )
        val raw = mapOrientedRegionToRaw(region, decoder.width, decoder.height, rotation)
        val rect = Rect(raw.left, raw.top, raw.right, raw.bottom)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val decoded = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val oriented = if (rotation == 0) decoded else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
                if (it != decoded) decoded.recycle()
            }
        }
        val scaled = ValueClassifierPipeline.Padding(pad.left / sample, pad.top / sample, pad.right / sample, pad.bottom / sample)
        return Crop(oriented, scaled)
    }

    override fun close() = decoder.recycle()
}
