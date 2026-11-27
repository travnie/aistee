package ais.tee.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.model.WebAiService
import ais.tee.ui.screens.WebProviderDrawer
import ais.tee.ui.theme.AisteeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebProviderDrawerSearchTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recentShortcutsExcludeFavoritesAndCurrentProvider() {
        val selections = mutableListOf<WebAiService>()
        composeRule.setContent {
            AisteeTheme {
                WebProviderDrawer(
                    persistent = true,
                    selectedService = WebAiService.GEMINI,
                    activityStatuses = emptyMap(),
                    providerFavicons = emptyMap(),
                    favoriteServices = setOf(WebAiService.CHATGPT),
                    recentServices = listOf(
                        WebAiService.GEMINI, WebAiService.CLAUDE,
                        WebAiService.CHATGPT, WebAiService.GROK
                    ),
                    onToggleFavorite = {},
                    onSelectService = { selections.add(it) },
                    onOpenNativeCompare = {},
                    onOpenStudio = {},
                )
            }
        }
        composeRule.onNodeWithTag("btn_recent_web_service_gemini").assertDoesNotExist()
        composeRule.onNodeWithTag("btn_recent_web_service_chatgpt").assertDoesNotExist()
        composeRule.onNodeWithTag("btn_recent_web_service_claude").assertExists()
        composeRule.onNodeWithTag("btn_recent_web_service_grok").assertExists()
        composeRule.onNodeWithTag("btn_recent_web_service_claude").performClick()
        composeRule.runOnIdle { assertEquals(listOf(WebAiService.CLAUDE), selections) }
    }

    @Test
    fun switchingDrawerVariantsSharesFilterAndSelectionClearsIt() {
        val persistent = mutableStateOf(true)
        val selected = mutableStateOf(WebAiService.CLAUDE)
        composeRule.setContent {
            AisteeTheme {
                val query = rememberSaveable { mutableStateOf("") }
                val scroll = rememberScrollState()
                val recent = rememberScrollState()
                key(persistent.value) {
                    WebProviderDrawer(
                        persistent = persistent.value,
                        selectedService = selected.value,
                        activityStatuses = emptyMap(),
                        providerFavicons = emptyMap(),
                        favoriteServices = setOf(WebAiService.CHATGPT),
                        searchQuery = query,
                        listScroll = scroll,
                        recentScroll = recent,
                        onToggleFavorite = {},
                        onSelectService = { selected.value = it },
                        onOpenNativeCompare = {},
                        onOpenStudio = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("web_provider_search").performTextInput("grok")
        composeRule.onNodeWithTag("tab_web_service_chatgpt").assertDoesNotExist()
        composeRule.runOnIdle { persistent.value = false }
        composeRule.onNodeWithTag("tab_web_service_grok").assertExists()
        composeRule.onNodeWithTag("tab_web_service_chatgpt").assertDoesNotExist()
        composeRule.onNodeWithTag("btn_clear_web_provider_search").performClick()
        composeRule.onNodeWithTag("web_provider_search").performTextInput("chatgpt")
        composeRule.onNodeWithTag("tab_web_service_grok").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_web_service_chatgpt").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(WebAiService.CHATGPT, selected.value)
            persistent.value = true
        }
        composeRule.onNodeWithTag("tab_web_service_grok").assertExists()
        composeRule.onNodeWithTag("btn_clear_web_provider_search").assertDoesNotExist()
    }

    @Test
    fun filterAndClearDoNotSwitchProviderUntilSelection() {
        val selections = mutableListOf<WebAiService>()
        composeRule.setContent {
            AisteeTheme {
                WebProviderDrawer(
                    persistent = true,
                    selectedService = WebAiService.CLAUDE,
                    activityStatuses = emptyMap(),
                    providerFavicons = emptyMap(),
                    favoriteServices = setOf(WebAiService.CHATGPT),
                    onToggleFavorite = {},
                    onSelectService = { selections.add(it) },
                    onOpenNativeCompare = {},
                    onOpenStudio = {},
                )
            }
        }

        val search = composeRule.onNodeWithTag("web_provider_search")
        search.performTextInput("mistral")
        composeRule.onNodeWithTag("tab_web_service_chatgpt").assertDoesNotExist()
        composeRule.onNodeWithTag("tab_web_service_vibe").assertExists()
        composeRule.runOnIdle { assertEquals(emptyList<WebAiService>(), selections) }

        composeRule.onNodeWithTag("btn_clear_web_provider_search").performClick()
        composeRule.onNodeWithTag("tab_web_service_chatgpt").assertExists()
        search.performTextInput("grok")
        composeRule.onNodeWithTag("tab_web_service_grok").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf(WebAiService.GROK), selections) }
        composeRule.onNodeWithTag("tab_web_service_chatgpt").assertExists()
        composeRule.onNodeWithTag("btn_clear_web_provider_search").assertDoesNotExist()
    }
}
