package ais.tee.ui

import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatBookmarkPersistence
import ais.tee.data.preferences.WebChatBookmarkSaveResult
import ais.tee.data.preferences.WebChatBookmarkStore
import ais.tee.ui.screens.WebChatBookmarkBackupActions
import ais.tee.ui.screens.WebChatBookmarksDialog
import ais.tee.ui.theme.AisteeTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Dialog text-input transitions need Android's real window/IME behavior.
@RunWith(AndroidJUnit4::class)
class WebChatBookmarkEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editSavedBookmarkWithoutOpeningConversation() {
        val store = WebChatBookmarkStore(MemoryPersistence())
        val saved = (store.save(WebAiService.CHATGPT, "https://chatgpt.com/c/saved",
            "Saved conversation", "Work") as WebChatBookmarkSaveResult.Saved).bookmark
        var opened = false
        compose.setContent {
            AisteeTheme(darkTheme = true) {
                WebChatBookmarksDialog(
                    service = WebAiService.CHATGPT,
                    currentUrl = "https://chatgpt.com/",
                    onOpen = { opened = true },
                    onDismiss = {},
                    backup = WebChatBookmarkBackupActions(false, {}, {}),
                    storeOverride = store,
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("web_bookmark_edit").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("web_bookmark_edit").performClick()
        compose.onNodeWithTag("web_bookmarks_title").performTextReplacement("Renamed")
        compose.onNodeWithTag("web_bookmarks_folder").performTextReplacement("Personal/AI")
        compose.onNodeWithTag("web_bookmarks_save").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("web_bookmarks_editor").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(saved.copy(title = "Renamed", folder = "Personal/AI"), store.load().bookmarks.single())
        assertTrue(!opened)
    }

    @Test
    fun editorAtLargeFontScale() {
        val store = WebChatBookmarkStore(MemoryPersistence())
        val saved = (store.save(WebAiService.CHATGPT, "https://chatgpt.com/c/saved",
            "Saved conversation", "Work") as WebChatBookmarkSaveResult.Saved).bookmark
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AisteeTheme(darkTheme = true) {
                    WebChatBookmarksDialog(
                        service = WebAiService.CHATGPT,
                        currentUrl = "https://chatgpt.com/c/saved",
                        onOpen = {},
                        onDismiss = {},
                        backup = WebChatBookmarkBackupActions(false, {}, {}),
                        storeOverride = store,
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Edit this bookmark").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Edit this bookmark").performScrollTo().performClick()
        compose.onNodeWithTag("web_bookmarks_title").performScrollTo().performTextReplacement("Unsaved")
        compose.onNodeWithTag("web_bookmarks_folder").performScrollTo().performTextReplacement("Personal/AI")
        compose.onNodeWithTag("web_bookmarks_save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("web_bookmarks_editor").assertDoesNotExist()
        compose.onNodeWithTag("web_bookmarks_dialog").assertExists()
        assertEquals(saved, store.load().bookmarks.single())
    }

    @Test
    fun cancelEditorKeepsBookmarkListPosition() {
        val store = WebChatBookmarkStore(MemoryPersistence())
        repeat(12) { index ->
            val name = "Chat " + index.toString().padStart(2, '0')
            assertTrue(store.save(WebAiService.CHATGPT, "https://chatgpt.com/c/chat-$index",
                name, "") is WebChatBookmarkSaveResult.Saved)
        }
        compose.setContent {
            AisteeTheme(darkTheme = true) {
                WebChatBookmarksDialog(
                    service = WebAiService.CHATGPT,
                    currentUrl = "https://chatgpt.com/",
                    onOpen = {},
                    onDismiss = {},
                    backup = WebChatBookmarkBackupActions(false, {}, {}),
                    storeOverride = store,
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("web_bookmarks_list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("web_bookmarks_list").performScrollToIndex(12)
        compose.onNodeWithContentDescription("Edit Chat 11").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("web_bookmarks_editor").assertDoesNotExist()
        compose.onNodeWithText("Chat 11").assertIsDisplayed()
    }


    @Test
    fun backFromEditorReturnsToListWithoutSaving() {
        val store = WebChatBookmarkStore(MemoryPersistence())
        val saved = (store.save(WebAiService.CHATGPT, "https://chatgpt.com/c/saved",
            "Saved conversation", "Work") as WebChatBookmarkSaveResult.Saved).bookmark
        compose.setContent {
            AisteeTheme {
                WebChatBookmarksDialog(
                    service = WebAiService.CHATGPT,
                    currentUrl = "https://chatgpt.com/c/saved",
                    onOpen = {},
                    onDismiss = { error("Back from editor must not dismiss the list") },
                    backup = WebChatBookmarkBackupActions(false, {}, {}),
                    storeOverride = store,
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Edit this bookmark").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Edit this bookmark").performScrollTo().performClick()
        compose.onNodeWithTag("web_bookmarks_editor").assertExists()
        pressBack()
        compose.onNodeWithTag("web_bookmarks_editor").assertDoesNotExist()
        compose.onNodeWithTag("web_bookmarks_dialog").assertExists()
        assertEquals(saved, store.load().bookmarks.single())
    }

    private class MemoryPersistence : WebChatBookmarkPersistence {
        private var raw: String? = null
        override fun read(): String? = raw
        override fun hasStoredData(): Boolean = raw != null
        override fun write(value: String): Boolean { raw = value; return true }
        override fun clear(): Boolean { raw = null; return true }
    }
}
