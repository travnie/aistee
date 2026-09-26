package ais.tee.navigation

import ais.tee.ui.viewmodel.NavigationTab
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

    @Test
    fun quickActionDestinationsCoverTabsNewChatAndLibrary() {
        assertEquals(
            QuickActionDestination.NewNativeChat,
            AisteeQuickActionNavigation.quickActionDestination(AisteeQuickActionNavigation.DESTINATION_NEW_NATIVE_CHAT),
        )
        assertEquals(
            QuickActionDestination.ProjectLibrary,
            AisteeQuickActionNavigation.quickActionDestination(AisteeQuickActionNavigation.DESTINATION_LIBRARY),
        )
        assertEquals(
            QuickActionDestination.Tab(NavigationTab.WEB_CHATS),
            AisteeQuickActionNavigation.quickActionDestination(AisteeQuickActionNavigation.DESTINATION_WEB_AI),
        )
        assertEquals(
            QuickActionDestination.Tab(NavigationTab.COMPARE_HUB),
            AisteeQuickActionNavigation.quickActionDestination(AisteeQuickActionNavigation.DESTINATION_COMPARE),
        )
        assertNull(AisteeQuickActionNavigation.quickActionDestination("unknown"))
        assertNull(AisteeQuickActionNavigation.quickActionDestination(null))
    }

    @Test
    fun newDestinationsResolveFromTheirDeepLinkPathSegment() {
        listOf(
            AisteeQuickActionNavigation.DESTINATION_NEW_NATIVE_CHAT,
            AisteeQuickActionNavigation.DESTINATION_LIBRARY,
        ).forEach { destination ->
            val resolved = AisteeQuickActionNavigation.destinationId(
                currentExtra = null,
                legacyExtra = null,
                dataLastPathSegment = destination,
            )
            assertEquals(destination, resolved)
        }
    }
}
