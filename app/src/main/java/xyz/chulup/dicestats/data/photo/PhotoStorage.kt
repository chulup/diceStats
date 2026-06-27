package xyz.chulup.dicestats.data.photo

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

    /** Dir for badly-recognized captures kept for later analysis. */
    private val reportsDir: File
        get() = File(appContext.getExternalFilesDir(null), REPORTS_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    /** A new, not-yet-written file for the next capture. */
    fun newPhotoFile(): File = File(rollsDir, "roll_${System.currentTimeMillis()}.jpg")

    /**
     * Files a badly-recognized capture for later analysis: copies [source] into the
     * reports dir alongside a sibling `.json` holding [metadataJson]. Returns the copy.
     */
    fun saveReport(source: File, metadataJson: String): File {
        val stamp = System.currentTimeMillis()
        val photo = File(reportsDir, "unrecognized_$stamp.jpg")
        source.copyTo(photo, overwrite = true)
        File(reportsDir, "unrecognized_$stamp.json").writeText(metadataJson)
        return photo
    }

    /** All stored roll photos, newest first. */
    fun listPhotos(): List<File> =
        rollsDir.listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Deletes the given photos. Only files inside the rolls directory are touched. */
    fun deletePhotos(paths: Collection<String>) {
        val dir = rollsDir
        for (path in paths) {
            val file = File(path)
            if (file.parentFile == dir) file.delete()
        }
    }

    private companion object {
        const val DIR_NAME = "rolls"
        const val REPORTS_DIR_NAME = "unrecognized"
    }
}
