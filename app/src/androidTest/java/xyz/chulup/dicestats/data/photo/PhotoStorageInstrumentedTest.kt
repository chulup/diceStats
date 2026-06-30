package xyz.chulup.dicestats.data.photo

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies that the `YYYY-MM-dd_HH:mm:ss` filename (containing `:`) can actually be
 * written to and read back from the device's external files dir. `:` is rejected by
 * some filesystems, so this is a real-device smoke test rather than a JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStorageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun colonStampedPhoto_writesAndReadsBack() {
        val storage = PhotoStorage(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        val file = storage.writePhoto(bitmap)

        assertTrue("file should exist on disk: ${file.absolutePath}", file.exists())
        assertTrue("name should match roll_<stamp>.jpg: ${file.name}",
            Regex("""roll_\d{4}-\d{2}-\d{2}_\d{2}:\d{2}:\d{2}(_\d+)?\.jpg""").matches(file.name))
        assertTrue("file should be non-empty", file.length() > 0)

        // Re-open via a fresh File handle (path round-trip through the filesystem).
        val reopened = File(file.absolutePath)
        assertTrue("reopened file should exist", reopened.exists())
        assertTrue("reopened file should be readable", reopened.readBytes().isNotEmpty())

        assertTrue("listPhotos should include the new file",
            storage.listPhotos().any { it.absolutePath == file.absolutePath })
    }
}
