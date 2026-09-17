package ais.tee

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewNavigationRetentionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun webViewInstanceSurvivesNavigationAwayAndBack() {
        val before = awaitWebViewIdentities()

        composeRule.onNodeWithTag("btn_web_provider_drawer").performClick()
        composeRule.onNodeWithTag("btn_switch_to_studio").performClick()
        composeRule.waitForIdle()
        assertEquals(before, webViewIdentities())

        composeRule.onNodeWithTag("nav_tab_web_chats").performClick()
        composeRule.waitForIdle()
        assertEquals(before, webViewIdentities())
    }

    private fun awaitWebViewIdentities(): Set<Int> {
        repeat(50) {
            webViewIdentities().takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(100)
        }
        val identities = webViewIdentities()
        assertTrue("Expected at least one provider WebView", identities.isNotEmpty())
        return identities
    }

    private fun webViewIdentities(): Set<Int> {
        var identities = emptySet<Int>()
        composeRule.runOnIdle {
            identities = collectWebViewIdentities(composeRule.activity.window.decorView)
        }
        return identities
    }

    private fun collectWebViewIdentities(view: View): Set<Int> {
        val identities = mutableSetOf<Int>()
        if (view is WebView) identities += System.identityHashCode(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                identities += collectWebViewIdentities(view.getChildAt(index))
            }
        }
        return identities
    }
}
