package ais.tee

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TestExpandedWidth

@TestExpandedWidth
@RunWith(AndroidJUnit4::class)
class WideWebProviderNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun wideProviderPanelCollapsesAndExpandsWithoutReplacingWebView() {
        composeRule.onNodeWithTag("tab_web_service_claude").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("btn_web_provider_drawer").fetchSemanticsNodes().isEmpty())
        composeRule.waitUntil(5_000) { webViews().isNotEmpty() }
        val before = webViews()
        val expandedWidth = visibleWebViewWidth()
        assertTrue("Expected a laid-out WebView", expandedWidth > 0)
        val toggle = composeRule.onNodeWithTag("btn_web_provider_panel")
        toggle.assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tab_web_service_claude").assertDoesNotExist()
        assertEquals(before, webViews())
        assertTrue("Collapsing should widen the chat", visibleWebViewWidth() > expandedWidth)
        toggle.assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tab_web_service_claude").assertIsDisplayed()
        assertEquals(before, webViews())
        assertEquals(expandedWidth, visibleWebViewWidth())

        toggle.performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("tab_web_service_claude").assertDoesNotExist()
        composeRule.onNodeWithTag("btn_web_provider_panel").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tab_web_service_claude").assertIsDisplayed()
    }

    private fun visibleWebViewWidth(): Int {
        var width = 0
        composeRule.runOnIdle {
            width = collectWebViews(composeRule.activity.window.decorView)
                .filter { it.isShown }.maxOfOrNull { it.width } ?: 0
        }
        return width
    }

    private fun webViews(): Set<WebView> {
        var views = emptySet<WebView>()
        composeRule.runOnIdle { views = collectWebViews(composeRule.activity.window.decorView) }
        return views
    }

    private fun collectWebViews(view: View): Set<WebView> = buildSet {
        if (view is WebView) add(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) addAll(collectWebViews(view.getChildAt(index)))
        }
    }
}
