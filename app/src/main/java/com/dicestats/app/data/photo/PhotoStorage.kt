package com.dicestats.app.data.photo

import android.content.Context
import java.io.File

/**
 * Stores roll photos in a dedicated, app-specific on-device directory
 * (`<externalFilesDir>/rolls`). No storage permission is required, and the
 * directory is removed when the app is uninstalled.
 */
class PhotoStorage(context: Context) {

    private val appContext = context.applicationContext

    private val rollsDir: File
        get() = File(appContext.getExternalFilesDir(null), DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    /** A new, not-yet-written file for the next capture. */
    fun newPhotoFile(): File = File(rollsDir, "roll_${System.currentTimeMillis()}.jpg")

    /** All stored roll photos, newest first. */
    fun listPhotos(): List<File> =
        rollsDir.listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private companion object {
        const val DIR_NAME = "rolls"
    }
}
