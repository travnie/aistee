package ais.tee.ui.screens

import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_ASSISTANT
import ais.tee.data.model.ModelChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseMarkdownEligibilityTest {
    private val completeResponse = ModelChatMessage(
        id = "a1",
        sender = CHAT_ROLE_ASSISTANT,
        provider = AiProvider.CHATGPT,
        text = "Useful answer"
    )

    @Test
    fun completedResponseIsEligibleWhenWorkspaceIsReady() {
        assertTrue(
            canOpenResponseAsMarkdown(
                message = completeResponse,
                isPreparingChatMarkdown = false,
                isWorkspaceBusy = false
            )
        )
    }

    @Test
    fun workspaceStateCanGateCompletedResponse() {
        assertFalse(canOpenResponseAsMarkdown(completeResponse, true, false))
        assertFalse(canOpenResponseAsMarkdown(completeResponse, false, true))
    }

    @Test
    fun autoScrollFollowsOnlyWhenUserWasNearLatestTurn() {
        assertTrue(shouldAutoScrollChat(previousMessageCount = 10, lastVisibleItemIndex = 9))
        assertTrue(shouldAutoScrollChat(previousMessageCount = 10, lastVisibleItemIndex = 8))
        assertFalse(shouldAutoScrollChat(previousMessageCount = 10, lastVisibleItemIndex = 7))
    }

    @Test
    fun emptyChatCanFollowFirstMessage() {
        assertTrue(shouldAutoScrollChat(previousMessageCount = 0, lastVisibleItemIndex = -1))
    }

    @Test
    fun jumpToLatestAppearsOnlyWhenLatestRowsAreOffscreen() {
        assertTrue(shouldShowJumpToLatest(totalItemCount = 10, lastVisibleItemIndex = 7))
        assertFalse(shouldShowJumpToLatest(totalItemCount = 10, lastVisibleItemIndex = 8))
        assertFalse(shouldShowJumpToLatest(totalItemCount = 0, lastVisibleItemIndex = -1))
    }

    @Test
    fun onlyCompletedAssistantAnswersRenderMarkdown() {
        assertTrue(shouldRenderChatMarkdown(completeResponse))
        assertFalse(shouldRenderChatMarkdown(completeResponse.copy(isPartial = true)))
        assertFalse(shouldRenderChatMarkdown(completeResponse.copy(isError = true)))
        assertFalse(shouldRenderChatMarkdown(completeResponse.copy(sender = "user")))
    }
}
