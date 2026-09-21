package ais.tee.widget

import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AisteeWidgetTest {
    private val oldConversation = NativeChatConversation(
        id = "old",
        title = "Old",
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 10L,
    )
    private val newestConversation = NativeChatConversation(
        id = "newest",
        title = "Newest",
        createdAtEpochMs = 3L,
        updatedAtEpochMs = 30L,
    )
    private val middleConversation = NativeChatConversation(
        id = "middle",
        title = "Middle",
        createdAtEpochMs = 2L,
        updatedAtEpochMs = 20L,
    )
    private val fourthConversation = NativeChatConversation(
        id = "fourth",
        title = "Fourth",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 5L,
    )

    @Test
    fun recentConversationSummaryIsSortedAndLimited() {
        val archive = NativeChatArchive(
            activeConversationId = "old",
            conversations = listOf(
                oldConversation,
                newestConversation,
                middleConversation,
                fourthConversation,
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
    fun pinnedConversationUsesStableIdAndRejectsMissingTargets() {
        val archive = NativeChatArchive(
            activeConversationId = "newest",
            conversations = listOf(oldConversation, newestConversation),
        )

        assertEquals(
            NativeChatWidgetConversation("old", "Old"),
            pinnedNativeConversationForWidget(archive, " old "),
        )
        assertNull(pinnedNativeConversationForWidget(archive, "missing"))
        assertNull(pinnedNativeConversationForWidget(archive, "   "))
    }

    @Test
    fun widgetRefreshFingerprintTracksPinnedCandidatesOutsideRecentList() {
        val archive = NativeChatArchive(
            activeConversationId = "newest",
            conversations = listOf(
                oldConversation,
                newestConversation,
                middleConversation,
                fourthConversation,
            ),
        )
        val renamedArchive = archive.copy(
            conversations = archive.conversations.map { conversation ->
                if (conversation.id == "fourth") {
                    conversation.copy(title = "Renamed pinned chat")
                } else {
                    conversation
                }
            }
        )

        assertEquals(
            nativeChatWidgetArchiveFingerprint(archive).recentConversations,
            nativeChatWidgetArchiveFingerprint(renamedArchive).recentConversations,
        )
        assertNotEquals(
            nativeChatWidgetArchiveFingerprint(archive),
            nativeChatWidgetArchiveFingerprint(renamedArchive),
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
