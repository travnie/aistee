package ais.tee

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.notifications.nativeChatConversationShortcutId
import ais.tee.ui.viewmodel.StudioViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChatDirectShareTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val createdConversationIds = mutableListOf<String>()

    private val viewModel: StudioViewModel
        get() = ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]

    @After
    fun tearDown() {
        composeRule.runOnIdle {
            viewModel.dismissIncomingShare()
            createdConversationIds.forEach(viewModel::deleteNativeConversation)
        }
    }

    @Test
    fun sendWithNativeChatShortcutStagesTextInThatConversationWithoutSending() {
        val targetId = createConversation()
        val otherId = createConversation()
        composeRule.runOnIdle { assertEquals(otherId, viewModel.uiState.value.nativeChat.activeConversationId) }

        shareText("Shared note", nativeChatConversationShortcutId(targetId))

        composeRule.waitUntil(10_000) {
            val state = viewModel.uiState.value
            state.nativeChat.activeConversationId == targetId &&
                state.activeNativeConversation?.draft == "Shared note"
        }
        composeRule.runOnIdle {
            val state = viewModel.uiState.value
            assertFalse(state.isChatGenerating)
            assertEquals(null, state.incomingShare)
            assertFalse(state.activeNativeConversation!!.messages.any { it.sender == CHAT_ROLE_USER })
        }
    }

    @Test
    fun unknownShortcutFallsBackToTheNormalShareFlow() {
        val activeId = createConversation()

        shareText("Fallback note", "native-chat:unknown")

        composeRule.waitUntil(10_000) { viewModel.uiState.value.incomingShare?.text == "Fallback note" }
        composeRule.runOnIdle {
            val state = viewModel.uiState.value
            assertEquals(activeId, state.nativeChat.activeConversationId)
            assertNotEquals("Fallback note", state.activeNativeConversation?.draft)
        }
    }

    private fun createConversation(): String {
        composeRule.waitUntil(10_000) { viewModel.uiState.value.isNativeConversationStoreReady }
        var id = ""
        composeRule.runOnIdle {
            viewModel.newNativeConversation()
            id = viewModel.uiState.value.nativeChat.activeConversationId
        }
        createdConversationIds += id
        return id
    }

    private fun shareText(text: String, shortcutId: String?) {
        val intent = Intent(Intent.ACTION_SEND)
            .setClass(context, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, shortcutId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
