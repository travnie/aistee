package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeChatTimelineTest {
    private fun user(id: String, text: String = "question $id") =
        ModelChatMessage(id = id, sender = CHAT_ROLE_USER, text = text)

    private fun reply(id: String) =
        ModelChatMessage(id = id, sender = CHAT_ROLE_ASSISTANT, provider = AiProvider.CLAUDE, text = "answer $id")

    private val welcome = ModelChatMessage(
        id = NATIVE_CHAT_WELCOME_MESSAGE_ID,
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.ALL,
        text = "Welcome",
    )

    @Test
    fun marksEveryUserTurnAndStarredRepliesWithTheirListIndex() {
        val messages = listOf(welcome, user("u1"), reply("a1"), user("u2"), reply("a2"), reply("a2b"))

        val markers = nativeChatTimeline(messages, starredMessageIds = setOf("a2", "u1"))

        assertEquals(listOf(1, 3, 4), markers.map { it.messageIndex })
        assertEquals(listOf(1, 2, 2), markers.map { it.turnNumber })
        assertEquals(listOf(true, true, false), markers.map { it.isUserTurn })
        assertEquals(listOf(true, false, true), markers.map { it.isStarred })
    }

    @Test
    fun welcomeIsNeverAMarkerEvenWhenStarredIdsNameIt() {
        val markers = nativeChatTimeline(listOf(welcome, user("u1")), setOf(NATIVE_CHAT_WELCOME_MESSAGE_ID))
        assertEquals(listOf("u1"), markers.map { it.messageId })
    }

    @Test
    fun previewIsOneBoundedLine() {
        val marker = nativeChatTimeline(listOf(user("u1", "  line one\n\nline   two " + "x".repeat(200))), emptySet()).single()
        assertTrue(marker.preview.startsWith("line one line two"))
        assertEquals(80, marker.preview.length)
        assertFalse(marker.toString().contains("line one"))
    }

    @Test
    fun longChatsAreThinnedButKeepStarsAndBothEnds() {
        val messages = (1..100).flatMap { listOf(user("u$it"), reply("a$it")) }

        val markers = nativeChatTimeline(messages, starredMessageIds = setOf("a50", "u77"), maxMarkers = 10)

        assertEquals(10, markers.size)
        assertTrue(markers.any { it.messageId == "a50" && it.isStarred })
        assertTrue(markers.any { it.messageId == "u77" && it.isStarred })
        assertEquals("u1", markers.first().messageId)
        assertEquals("u100", markers.last().messageId)
        assertEquals(markers.sortedBy { it.messageIndex }, markers)
    }

    @Test
    fun emptyOrDisabledTimelines() {
        assertTrue(nativeChatTimeline(emptyList(), emptySet()).isEmpty())
        assertTrue(nativeChatTimeline(listOf(user("u1")), emptySet(), maxMarkers = 0).isEmpty())
    }
}
