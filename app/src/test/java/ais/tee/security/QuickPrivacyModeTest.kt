package ais.tee.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPrivacyModeTest {
    @Test
    fun disabledModePreservesPerSurfaceChoices() {
        val visibility = effectivePrivacySurfaceVisibility(
            showConversationTitles = true,
            showMessagePreviews = true,
            quickPrivacyEnabled = false,
        )

        assertTrue(visibility.showConversationTitles)
        assertTrue(visibility.showMessagePreviews)
    }

    @Test
    fun enabledModeForcesBothSensitiveSurfacesRedacted() {
        val visibility = effectivePrivacySurfaceVisibility(
            showConversationTitles = true,
            showMessagePreviews = true,
            quickPrivacyEnabled = true,
        )

        assertFalse(visibility.showConversationTitles)
        assertFalse(visibility.showMessagePreviews)
    }
}
