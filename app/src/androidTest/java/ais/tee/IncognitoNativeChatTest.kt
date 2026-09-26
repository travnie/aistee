package ais.tee

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.preferences.NativeChatStore
import ais.tee.data.preferences.NativeChatWriter
import ais.tee.notifications.NativeChatNotificationVisibility
import ais.tee.notifications.nativeChatConversationShortcutId
import ais.tee.ui.viewmodel.StudioViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncognitoNativeChatTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val viewModel: StudioViewModel
        get() = ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]

    @Test
    fun incognitoTurnIsNeverPersistedNotifiedOrShownToWidgets() {
        composeRule.waitUntil(10_000) { viewModel.uiState.value.isNativeConversationStoreReady }
        var incognitoId = ""
        composeRule.runOnIdle {
            viewModel.newIncognitoConversation()
            viewModel.setChatProvider(AiProvider.GEMINI)
            incognitoId = viewModel.uiState.value.nativeChat.activeConversationId
            assertTrue(viewModel.uiState.value.isActiveConversationIncognito)
            NativeChatNotificationVisibility.setAppVisible(false)
            assertTrue(viewModel.sendChatMessage("incognito probe"))
        }

        composeRule.waitUntil(30_000) {
            val state = viewModel.uiState.value
            !state.isChatGenerating && state.chatMessages.any { it.sender == CHAT_ROLE_USER }
        }
        composeRule.runOnIdle {
            NativeChatNotificationVisibility.setAppVisible(true)
            // The widgets and Direct Reply read the persisted archive only.
            assertFalse(NativeChatWriter.currentArchive()!!.conversations.any { it.id == incognitoId })
            val manager = context.getSystemService(NotificationManager::class.java)
            assertFalse(manager.activeNotifications.any { it.tag?.contains(incognitoId) == true })
            val shortcutId = nativeChatConversationShortcutId(incognitoId)
            assertFalse(ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == shortcutId })

            viewModel.newNativeConversation()
            assertFalse(viewModel.uiState.value.nativeChat.conversations.any { it.id == incognitoId })
            viewModel.deleteNativeConversation(viewModel.uiState.value.nativeChat.activeConversationId)
        }
        composeRule.waitUntil(10_000) {
            NativeChatStore(context.noBackupFilesDir).load()?.conversations?.none { it.id == incognitoId } == true
        }
    }
}
