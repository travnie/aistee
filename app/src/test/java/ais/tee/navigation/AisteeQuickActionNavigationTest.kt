package ais.tee.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AisteeQuickActionNavigationTest {
    @Test
    fun webDraftReplyPrefersExplicitProviderId() {
        assertEquals(
            "claude",
            AisteeQuickActionNavigation.webServiceId(
                currentExtra = " claude ",
                dataScheme = "other",
                dataHost = "other",
                dataLastPathSegment = "chatgpt",
            )
        )
    }

    @Test
    fun webDraftReplyAcceptsOnlyCanonicalDeepLinkShape() {
        assertEquals(
            "chatgpt",
            AisteeQuickActionNavigation.webServiceId(
                currentExtra = null,
                dataScheme = "aistee",
                dataHost = "web-chat",
                dataLastPathSegment = "chatgpt",
            )
        )
        assertNull(
            AisteeQuickActionNavigation.webServiceId(
                currentExtra = null,
                dataScheme = "https",
                dataHost = "web-chat",
                dataLastPathSegment = "chatgpt",
            )
        )
    }
}
