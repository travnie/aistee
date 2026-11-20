package ais.tee.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
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
