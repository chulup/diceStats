package xyz.chulup.dicestats.data.photo

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    fun newPhotoFile(): File {
        val stamp = uniqueStamp(rollsDir, "roll_", listOf("jpg"))
        return File(rollsDir, "roll_$stamp.jpg")
    }

    /** Compresses [bitmap] to a new JPEG in the rolls dir and returns it. */
    fun writePhoto(bitmap: Bitmap): File {
        val file = newPhotoFile()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file
    }

    /**
     * Files a badly-recognized capture for later analysis: copies [source] into the
     * reports dir alongside a sibling `.json` holding [metadataJson]. Returns the copy.
     */
    fun saveReport(source: File, metadataJson: String): File {
        // The jpg and its sibling json must share one stamp; guard against an
        // existing pair so neither half clobbers a prior report.
        val stamp = uniqueStamp(reportsDir, "unrecognized_", listOf("jpg", "json"))
        val photo = File(reportsDir, "unrecognized_$stamp.jpg")
        source.copyTo(photo, overwrite = true)
        File(reportsDir, "unrecognized_$stamp.json").writeText(metadataJson)
        return photo
    }

    /** Writes the phone-sensor snapshot for [photo] as its `.sensors.json` sidecar. */
    fun writeSensors(photo: File, json: String) {
        runCatching { sensorsFile(photo.path).writeText(json) }
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
            if (file.parentFile == dir) {
                file.delete()
                sensorsFile(path).delete()
            }
        }
    }

    /**
     * Returns a `YYYY-MM-dd_HH:mm:ss` stamp for [prefix]-named files in [dir] that is
     * free of collisions across every extension in [extensions]: second-precision stamps
     * are not unique, so if any sibling already exists a `_2`, `_3`, … suffix is appended.
     */
    private fun uniqueStamp(dir: File, prefix: String, extensions: List<String>): String {
        val base = formatStamp(Date())
        if (extensions.none { File(dir, "$prefix$base.$it").exists() }) return base
        var n = 2
        while (extensions.any { File(dir, "$prefix${base}_$n.$it").exists() }) n++
        return "${base}_$n"
    }

    private companion object {
        const val DIR_NAME = "rolls"
        const val REPORTS_DIR_NAME = "unrecognized"
    }
}

/** The `.sensors.json` sidecar of a roll photo ([xyz.chulup.dicestats.data.sensor.SensorRecorder]). */
fun sensorsFile(photoPath: String): File = File(photoPath.substringBeforeLast('.') + ".sensors.json")

/** Formats [date] as a `YYYY-MM-dd_HH:mm:ss` filename stamp (local time, fixed Locale). */
internal fun formatStamp(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(date)
