package ais.tee.widget

import ais.tee.navigation.AisteeQuickActionNavigation
import ais.tee.navigation.QuickActionDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AisteeQuickActionsWidgetTest {
    @Test
    fun toolbarButtonsAreTheFourQuickActionsAndAllResolve() {
        assertEquals(
            listOf("New chat", "Web AI", "Compare", "Library"),
            QUICK_ACTIONS_WIDGET_BUTTONS.map { it.label },
        )
        QUICK_ACTIONS_WIDGET_BUTTONS.forEach { button ->
            assertNotNull(button.label, AisteeQuickActionNavigation.quickActionDestination(button.destination))
        }
        assertEquals(
            QuickActionDestination.NewNativeChat,
            AisteeQuickActionNavigation.quickActionDestination(QUICK_ACTIONS_WIDGET_BUTTONS.first().destination),
        )
    }
}
