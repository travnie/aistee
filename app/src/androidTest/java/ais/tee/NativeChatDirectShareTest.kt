package ais.tee

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.notifications.nativeChatConversationShortcutId
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChatDirectShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val createdConversationIds = mutableListOf<String>()

    @After
    fun tearDown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitState(scenario) { it.isNativeConversationStoreReady }
            scenario.onActivity { activity ->
                createdConversationIds.forEach(activity.studioViewModel()::deleteNativeConversation)
            }
        }
    }

    @Test
    fun sendWithNativeChatShortcutStagesTextInThatConversationWithoutSending() {
        val (targetId, otherId) = createConversations(2)

        ActivityScenario.launch<MainActivity>(shareIntent("Shared note", nativeChatConversationShortcutId(targetId))).use { scenario ->
            val state = awaitState(scenario) {
                it.nativeChat.activeConversationId == targetId && it.activeNativeConversation?.draft == "Shared note"
            }
            assertNotEquals(otherId, state.nativeChat.activeConversationId)
            assertFalse(state.isChatGenerating)
            assertEquals(null, state.incomingShare)
            assertFalse(state.activeNativeConversation!!.messages.any { it.sender == CHAT_ROLE_USER })
        }
    }

    @Test
    fun unknownShortcutFallsBackToTheNormalShareFlow() {
        val (activeId) = createConversations(1)

        ActivityScenario.launch<MainActivity>(shareIntent("Fallback note", "native-chat:unknown")).use { scenario ->
            val state = awaitState(scenario) { it.incomingShare?.text == "Fallback note" }
            assertEquals(activeId, state.nativeChat.activeConversationId)
            assertNotEquals("Fallback note", state.activeNativeConversation?.draft)
            scenario.onActivity { it.studioViewModel().dismissIncomingShare() }
        }
    }

    private fun createConversations(count: Int): List<String> =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitState(scenario) { it.isNativeConversationStoreReady }
            List(count) {
                var id = ""
                scenario.onActivity { activity ->
                    val viewModel = activity.studioViewModel()
                    viewModel.newNativeConversation()
                    id = viewModel.uiState.value.nativeChat.activeConversationId
                }
                createdConversationIds += id
                id
            }
        }

    private fun shareIntent(text: String, shortcutId: String?): Intent =
        Intent(Intent.ACTION_SEND)
            .setClass(context, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, shortcutId)

    private fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        condition: (StudioUiState) -> Boolean,
    ): StudioUiState {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var state: StudioUiState? = null
            scenario.onActivity { state = it.studioViewModel().uiState.value }
            if (condition(state!!)) return state!!
            assertTrue("Timed out waiting for native chat state", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun MainActivity.studioViewModel(): StudioViewModel =
        ViewModelProvider(this)[StudioViewModel::class.java]
}
