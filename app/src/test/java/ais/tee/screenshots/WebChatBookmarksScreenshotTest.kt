package ais.tee.screenshots

import ais.tee.data.model.WebAiService
import ais.tee.data.preferences.WebChatBookmarkPersistence
import ais.tee.data.preferences.WebChatBookmarkSaveResult
import ais.tee.data.preferences.WebChatBookmarkStore
import ais.tee.ui.screens.WebChatBookmarkBackupActions
import ais.tee.ui.screens.WebChatBookmarksDialog
import ais.tee.ui.theme.AisteeTheme
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Local-only dialog; no WebView, provider credentials or network.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class WebChatBookmarksScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun compactLargeType() = capture("web_bookmarks_compact_font150", 1.5f)

    @Test
    @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun mediumLandscape() = capture("web_bookmarks_medium")

    @Test
    @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expanded() = capture("web_bookmarks_expanded")

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
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
    @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun editorInShortLandscapeAtLargeFontScale() {
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
        compose.onNodeWithTag("web_bookmarks_editor")
            .captureRoboImage("build/outputs/roborazzi/web_bookmarks_editor_landscape_font150.png")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("web_bookmarks_editor").assertDoesNotExist()
        compose.onNodeWithTag("web_bookmarks_dialog").assertExists()
        assertEquals(saved, store.load().bookmarks.single())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
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

    private fun capture(name: String, fontScale: Float = 1f) {
        val store = WebChatBookmarkStore(MemoryPersistence())
        val chatUrl = "https://chatgpt.com/c/current"
        val storedUrl = "https://CHATGPT.COM.:443/c/current"
        assertTrue(store.save(WebAiService.CHATGPT, storedUrl, "Stable chat", "Work/Research")
            is WebChatBookmarkSaveResult.Saved)
        assertTrue(store.save(WebAiService.CHATGPT, "https://chatgpt.com/c/other",
            "Design ideas", "Work/Research") is WebChatBookmarkSaveResult.Saved)
        assertTrue(store.save(WebAiService.CLAUDE, "https://claude.ai/chat/other",
            "Other provider", "Private") is WebChatBookmarkSaveResult.Saved)
        var opened: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AisteeTheme(darkTheme = true) {
                    WebChatBookmarksDialog(
                        service = WebAiService.CHATGPT,
                        currentUrl = chatUrl,
                        onOpen = { opened = it },
                        onDismiss = {},
                        backup = WebChatBookmarkBackupActions(false, {}, {}),
                        storeOverride = store,
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Stable chat").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Other provider").assertDoesNotExist()
        compose.onNodeWithText("Edit this bookmark").assertExists()
        compose.onNodeWithTag("web_bookmarks_dialog")
            .captureRoboImage("build/outputs/roborazzi/$name.png")
        compose.onNodeWithTag("web_bookmarks_list").performScrollToIndex(3)
        compose.onNodeWithText("Stable chat").assertIsDisplayed().performClick()
        assertEquals(storedUrl, opened)
    }

    private class MemoryPersistence : WebChatBookmarkPersistence {
        private var raw: String? = null
        override fun read(): String? = raw
        override fun hasStoredData(): Boolean = raw != null
        override fun write(value: String): Boolean { raw = value; return true }
        override fun clear(): Boolean { raw = null; return true }
    }
}
