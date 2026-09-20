package ais.tee.widget

import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AisteeWidgetTest {
    @Test
    fun recentConversationSummaryIsSortedAndLimited() {
        val archive = NativeChatArchive(
            activeConversationId = "old",
            conversations = listOf(
                NativeChatConversation(
                    id = "old",
                    title = "Old",
                    createdAtEpochMs = 1L,
                    updatedAtEpochMs = 10L,
                ),
                NativeChatConversation(
                    id = "newest",
                    title = "Newest",
                    createdAtEpochMs = 3L,
                    updatedAtEpochMs = 30L,
                ),
                NativeChatConversation(
                    id = "middle",
                    title = "Middle",
                    createdAtEpochMs = 2L,
                    updatedAtEpochMs = 20L,
                ),
                NativeChatConversation(
                    id = "fourth",
                    title = "Fourth",
                    createdAtEpochMs = 0L,
                    updatedAtEpochMs = 5L,
                ),
            ),
        )

        assertEquals(
            listOf(
                NativeChatWidgetConversation("newest", "Newest"),
                NativeChatWidgetConversation("middle", "Middle"),
                NativeChatWidgetConversation("old", "Old"),
            ),
            recentNativeConversationsForWidget(archive),
        )
    }

    @Test
    fun widgetConversationTitlesAreRedactedUnlessExplicitlyEnabled() {
        assertEquals(
            "Recent chat 1",
            privacySafeWidgetConversationTitle(
                title = "Sensitive project name",
                hiddenTitle = "Recent chat 1",
                showConversationTitles = false,
            ),
        )
        assertEquals(
            "Sensitive project name",
            privacySafeWidgetConversationTitle(
                title = "Sensitive project name",
                hiddenTitle = "Recent chat 1",
                showConversationTitles = true,
            ),
        )
    }

    @Test
    fun recentConversationSummaryDoesNotExposeMessageBodies() {
        val summary = recentNativeConversationsForWidget(
            NativeChatArchive(
                activeConversationId = "chat",
                conversations = listOf(
                    NativeChatConversation(
                        id = "chat",
                        title = "Safe title",
                        createdAtEpochMs = 1L,
                    )
                ),
            )
        )

        assertEquals(listOf(NativeChatWidgetConversation("chat", "Safe title")), summary)
        assertTrue(recentNativeConversationsForWidget(NativeChatArchive(), limit = 0).isEmpty())
    }
}
