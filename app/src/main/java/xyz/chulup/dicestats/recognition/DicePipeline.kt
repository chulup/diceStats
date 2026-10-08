package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap

/** A complete recognition pipeline that finds dice and reads their values, timed ([ModelRun]). */
interface DicePipeline {
    val name: String

    /** [bitmap] is the downscaled photo; [photoPath] the full-resolution original. Null if unavailable. */
    suspend fun run(bitmap: Bitmap, photoPath: String): ModelRun?
}
