package xyz.chulup.dicestats.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import org.junit.Test

class PhotoStorageTest {

    @Test
    fun formatStamp_usesIsoLikePattern() {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(2026, Calendar.JUNE, 30, 14, 23, 1)
        }
        assertEquals("2026-06-30_14:23:01", formatStamp(cal.time))
    }

    @Test
    fun formatStamp_zeroPadsAllFields() {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(2026, Calendar.JANUARY, 5, 3, 7, 9)
        }
        assertEquals("2026-01-05_03:07:09", formatStamp(cal.time))
    }

    @Test
    fun formatStamp_matchesExpectedShape() {
        val stamp = formatStamp(Date())
        assertTrue(stamp, Regex("""\d{4}-\d{2}-\d{2}_\d{2}:\d{2}:\d{2}""").matches(stamp))
    }
}
