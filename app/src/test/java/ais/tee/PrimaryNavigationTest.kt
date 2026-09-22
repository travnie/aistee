package ais.tee

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import ais.tee.navigation.AisteeQuickActionNavigation
import ais.tee.ui.viewmodel.NavigationTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryNavigationTest {
    @Test
    fun studioToolsStayUnderOnePrimaryDestination() {
        listOf(
            NavigationTab.STUDIO,
            NavigationTab.INSTRUCTIONS,
            NavigationTab.YAML,
            NavigationTab.PLAYGROUND,
            NavigationTab.SKILLS
        ).forEach { tab -> assertTrue(tab.belongsToStudioSection()) }
    }

    @Test
    fun dailyChatDestinationsRemainIndependent() {
        assertFalse(NavigationTab.WEB_CHATS.belongsToStudioSection())
        assertFalse(NavigationTab.COMPARE_HUB.belongsToStudioSection())
    }

    @Test
    fun webChatUsesImmersiveNavigationShell() {
        assertFalse(showPrimaryNavigation(NavigationTab.WEB_CHATS))
        assertTrue(showPrimaryNavigation(NavigationTab.COMPARE_HUB))
        assertTrue(showPrimaryNavigation(NavigationTab.STUDIO))
    }

    @Test
    fun bottomNavigationTypesDoNotNeedExtraSystemInset() {
        assertTrue(navigationSuiteUsesBottomBar(NavigationSuiteType.NavigationBar))
        assertTrue(navigationSuiteUsesBottomBar(NavigationSuiteType.ShortNavigationBarCompact))
        assertTrue(navigationSuiteUsesBottomBar(NavigationSuiteType.ShortNavigationBarMedium))
        assertFalse(navigationSuiteUsesBottomBar(NavigationSuiteType.NavigationRail))
        assertFalse(navigationSuiteUsesBottomBar(NavigationSuiteType.WideNavigationRailCollapsed))
        assertFalse(navigationSuiteUsesBottomBar(NavigationSuiteType.WideNavigationRailExpanded))
    }

    @Test
    fun renderedPromptTestChatOpensProfilePlayground() {
        assertEquals(NavigationTab.PLAYGROUND, profilePlaygroundDestination())
    }

    @Test
    fun nativeConversationRoutingAcceptsOnlyDedicatedAction() {
        assertTrue(
            AisteeQuickActionNavigation.isOpenNativeConversationAction(
                AisteeQuickActionNavigation.ACTION_OPEN_NATIVE_CONVERSATION
            )
        )
        assertFalse(
            AisteeQuickActionNavigation.isOpenNativeConversationAction(
                AisteeQuickActionNavigation.ACTION_OPEN_DESTINATION
            )
        )
    }

    @Test
    fun nativeConversationIdPrefersExtraAndValidatesUriFallback() {
        assertEquals(
            "conversation-extra",
            AisteeQuickActionNavigation.nativeConversationId(
                currentExtra = " conversation-extra ",
                dataScheme = null,
                dataHost = null,
                dataLastPathSegment = null,
            )
        )
        assertEquals(
            "conversation-uri",
            AisteeQuickActionNavigation.nativeConversationId(
                currentExtra = null,
                dataScheme = "aistee",
                dataHost = "native-chat",
                dataLastPathSegment = "conversation-uri",
            )
        )
        assertNull(
            AisteeQuickActionNavigation.nativeConversationId(
                currentExtra = null,
                dataScheme = "https",
                dataHost = "native-chat",
                dataLastPathSegment = "conversation-uri",
            )
        )
    }

    @Test
    fun quickActionRoutingAcceptsCurrentAndLegacyActions() {
        assertTrue(
            AisteeQuickActionNavigation.isOpenDestinationAction(
                AisteeQuickActionNavigation.ACTION_OPEN_DESTINATION
            )
        )
        assertTrue(
            AisteeQuickActionNavigation.isOpenDestinationAction(
                AisteeQuickActionNavigation.LEGACY_WIDGET_ACTION_OPEN_DESTINATION
            )
        )
        assertFalse(AisteeQuickActionNavigation.isOpenDestinationAction("not-a-quick-action"))
    }

    @Test
    fun quickActionDestinationIdFallsBackToIntentData() {
        assertEquals(
            AisteeQuickActionNavigation.DESTINATION_COMPARE,
            AisteeQuickActionNavigation.destinationId(
                currentExtra = null,
                legacyExtra = null,
                dataLastPathSegment = AisteeQuickActionNavigation.DESTINATION_COMPARE,
            )
        )
    }

    @Test
    fun quickActionDestinationsMapToPrimaryTabs() {
        assertEquals(
            NavigationTab.WEB_CHATS,
            AisteeQuickActionNavigation.destination(AisteeQuickActionNavigation.DESTINATION_WEB_AI)
        )
        assertEquals(
            NavigationTab.COMPARE_HUB,
            AisteeQuickActionNavigation.destination(AisteeQuickActionNavigation.DESTINATION_COMPARE)
        )
        assertEquals(
            NavigationTab.STUDIO,
            AisteeQuickActionNavigation.destination(AisteeQuickActionNavigation.DESTINATION_STUDIO)
        )
        assertNull(AisteeQuickActionNavigation.destination("unknown"))
    }
}
