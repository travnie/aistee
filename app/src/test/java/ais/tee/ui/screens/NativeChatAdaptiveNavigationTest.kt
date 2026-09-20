package ais.tee.ui.screens

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class NativeChatAdaptiveNavigationTest {
    @Test
    fun activeConversationStartsWithListThenDetail() {
        val history = nativeChatInitialDestinationHistory("conversation-1")

        assertEquals(2, history.size)
        assertEquals(ListDetailPaneScaffoldRole.List, history[0].pane)
        assertNull(history[0].contentKey)
        assertEquals(ListDetailPaneScaffoldRole.Detail, history[1].pane)
        assertEquals("conversation-1", history[1].contentKey)
    }

    @Test
    fun blankConversationStartsOnListOnly() {
        val history = nativeChatInitialDestinationHistory("   ")

        assertEquals(1, history.size)
        assertEquals(ListDetailPaneScaffoldRole.List, history.single().pane)
        assertNull(history.single().contentKey)
    }
}
