package ais.tee.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardSupportTest {
    @Test
    fun sensitivePlainTextClipMarksDescription() {
        val clip = plainTextClip(
            label = "Private content",
            text = "secret",
            sensitive = true,
        )

        assertTrue(
            clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        )
    }

    @Test
    fun ordinaryPlainTextClipStaysUnmarked() {
        val clip = plainTextClip(
            label = "Public content",
            text = "https://example.com",
            sensitive = false,
        )

        assertFalse(
            clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        )
    }
}
