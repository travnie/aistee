package ais.tee.data.model

import ais.tee.data.skills.ActiveSkillCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SkillCardMessageTest {
    private val card = ActiveSkillCard("Trip plan", "<p>PRIVATE CARD HTML</p>")
    private val reply = ModelChatMessage(
        id = "m1",
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.CLAUDE,
        text = "Here you go",
        skillCards = listOf(card),
    )

    @Test
    fun cardsStayWithTheStoredReply() {
        val conversation = NativeChatConversation(
            id = "c1",
            createdAtEpochMs = 1,
            messages = listOf(ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "plan"), reply),
        )
        val encoded = NativeChatArchiveCodec.encode(NativeChatArchive(activeConversationId = conversation.id, conversations = listOf(conversation)))
        val decoded = assertNotNull(NativeChatArchiveCodec.decode(encoded))

        assertEquals(listOf(card), decoded.activeConversation?.messages?.last()?.skillCards)
    }

    @Test
    fun cardsAreNeitherExportedNorPrinted() {
        val markdown = assertNotNull(
            renderChatMarkdown(listOf(ModelChatMessage(id = "u1", sender = CHAT_ROLE_USER, text = "plan"), reply))
        )

        assertFalse("PRIVATE CARD HTML" in markdown)
        assertFalse("PRIVATE CARD HTML" in reply.toString())
        assertFalse("Trip plan" in reply.toString())
    }
}
