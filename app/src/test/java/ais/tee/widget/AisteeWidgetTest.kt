package ais.tee.widget

import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import ais.tee.data.model.NATIVE_CHAT_WELCOME_MESSAGE_ID
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
    fun latestMessagesAreSortedAcrossChatsAndSkipUnsafeTransientEntries() {
        val archive = NativeChatArchive(
            activeConversationId = "newest",
            conversations = listOf(
                oldConversation.copy(
                    messages = listOf(
                        ModelChatMessage(
                            id = "user-old",
                            sender = "user",
                            text = "Older question",
                            timestamp = 100L,
                        ),
                        ModelChatMessage(
                            id = "partial",
                            sender = CHAT_ROLE_ASSISTANT,
                            text = "Streaming...",
                            timestamp = 400L,
                            isPartial = true,
                        ),
                    )
                ),
                newestConversation.copy(
                    messages = listOf(
                        ModelChatMessage(
                            id = NATIVE_CHAT_WELCOME_MESSAGE_ID,
                            sender = CHAT_ROLE_ASSISTANT,
                            text = "Welcome to the AI Chat Hub",
                            timestamp = 600L,
                        ),
                        ModelChatMessage(
                            id = "error",
                            sender = CHAT_ROLE_ASSISTANT,
                            text = "Provider failure",
                            timestamp = 500L,
                            isError = true,
                        ),
                        ModelChatMessage(
                            id = "assistant-new",
                            sender = CHAT_ROLE_ASSISTANT,
                            text = "Newest answer",
                            timestamp = 300L,
                        ),
                    )
                ),
            )
        )

        assertEquals(
            listOf(
                NativeChatWidgetMessage(
                    conversationId = "newest",
                    messageId = "assistant-new",
                    conversationTitle = "Newest",
                    sender = CHAT_ROLE_ASSISTANT,
                    text = "Newest answer",
                    timestamp = 300L,
                ),
                NativeChatWidgetMessage(
                    conversationId = "old",
                    messageId = "user-old",
                    conversationTitle = "Old",
                    sender = "user",
                    text = "Older question",
                    timestamp = 100L,
                ),
            ),
            latestNativeMessagesForWidget(archive),
        )
    }

    @Test
    fun latestMessageLookupStaysScopedToTheRequestedConversation() {
        val old = oldConversation.copy(
            messages = listOf(
                ModelChatMessage(
                    id = "old-answer",
                    sender = CHAT_ROLE_ASSISTANT,
                    text = "Old answer",
                    timestamp = 500L,
                )
            )
        )
        val newest = newestConversation.copy(
            messages = listOf(
                ModelChatMessage(
                    id = "newest-answer",
                    sender = CHAT_ROLE_ASSISTANT,
                    text = "Newest answer",
                    timestamp = 300L,
                )
            )
        )

        assertEquals("Newest answer", latestNativeMessageForConversationForWidget(newest)?.text)
        assertEquals("Old answer", latestNativeMessageForConversationForWidget(old)?.text)
        assertNull(latestNativeMessageForConversationForWidget(old.copy(messages = emptyList())))
    }

    @Test
    fun pinnedConversationMessagesStayScopedAndLatestFirst() {
        val pinned = oldConversation.copy(
            messages = listOf(
                ModelChatMessage(
                    id = "pinned-old",
                    sender = "user",
                    text = "Pinned question",
                    timestamp = 100L,
                ),
                ModelChatMessage(
                    id = "pinned-new",
                    sender = CHAT_ROLE_ASSISTANT,
                    text = "Pinned answer",
                    timestamp = 300L,
                ),
            )
        )
        val other = newestConversation.copy(
            messages = listOf(
                ModelChatMessage(
                    id = "other-newer",
                    sender = CHAT_ROLE_ASSISTANT,
                    text = "Other answer",
                    timestamp = 400L,
                )
            )
        )
        val archive = NativeChatArchive(
            activeConversationId = pinned.id,
            conversations = listOf(pinned, other),
        )
        val pinnedArchive = archive.copy(
            activeConversationId = pinned.id,
            conversations = listOf(pinned),
        )

        assertEquals(
            listOf("pinned-new", "pinned-old"),
            latestNativeMessagesForWidget(pinnedArchive, limit = 2).map { it.messageId },
        )
        assertEquals(
            listOf("other-newer", "pinned-new"),
            latestNativeMessagesForWidget(archive, limit = 2).map { it.messageId },
        )
    }

    @Test
    fun widgetFingerprintTracksPinnedCandidateMessagesOutsideGlobalTopThree() {
        fun conversation(id: String, timestamp: Long) = NativeChatConversation(
            id = id,
            title = id,
            createdAtEpochMs = timestamp,
            updatedAtEpochMs = timestamp,
            messages = listOf(
                ModelChatMessage(
                    id = "message-$id",
                    sender = CHAT_ROLE_ASSISTANT,
                    text = "message $id",
                    timestamp = timestamp,
                )
            ),
        )

        val archive = NativeChatArchive(
            activeConversationId = "newest",
            conversations = listOf(
                conversation("pinned", 1L),
                conversation("newest", 500L),
                conversation("second", 400L),
                conversation("third", 300L),
                conversation("fourth", 200L),
            ),
        )
        val updated = archive.copy(
            conversations = archive.conversations.map { conversation ->
                if (conversation.id == "pinned") {
                    conversation.copy(
                        messages = listOf(
                            ModelChatMessage(
                                id = "message-pinned-new",
                                sender = CHAT_ROLE_ASSISTANT,
                                text = "updated pinned",
                                timestamp = 2L,
                            )
                        )
                    )
                } else {
                    conversation
                }
            }
        )

        assertNotEquals(
            nativeChatWidgetArchiveFingerprint(archive).latestMessages,
            nativeChatWidgetArchiveFingerprint(updated).latestMessages,
        )
        assertNotEquals(
            nativeChatWidgetArchiveFingerprint(archive),
            nativeChatWidgetArchiveFingerprint(updated),
        )
    }

    @Test
    fun messageBodyIsRedactedUnlessPreviewIsExplicitlyEnabled() {
        assertEquals(
            "Assistant message",
            privacySafeWidgetMessagePreview(
                text = "Sensitive answer",
                hiddenText = "Assistant message",
                showMessagePreviews = false,
            ),
        )
        assertEquals(
            "Sensitive answer",
            privacySafeWidgetMessagePreview(
                text = "Sensitive answer",
                hiddenText = "Assistant message",
                showMessagePreviews = true,
            ),
        )
    }

    @Test
    fun widgetRefreshFingerprintTracksLatestMessages() {
        val conversation = newestConversation.copy(
            messages = listOf(
                ModelChatMessage(
                    id = "assistant-1",
                    sender = CHAT_ROLE_ASSISTANT,
                    text = "First answer",
                    timestamp = 100L,
                )
            )
        )
        val archive = NativeChatArchive(
            activeConversationId = conversation.id,
            conversations = listOf(conversation),
        )
        val updatedArchive = archive.copy(
            conversations = listOf(
                conversation.copy(
                    messages = conversation.messages + ModelChatMessage(
                        id = "assistant-2",
                        sender = CHAT_ROLE_ASSISTANT,
                        text = "Second answer",
                        timestamp = 200L,
                    )
                )
            )
        )

        assertNotEquals(
            nativeChatWidgetArchiveFingerprint(archive),
            nativeChatWidgetArchiveFingerprint(updatedArchive),
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
