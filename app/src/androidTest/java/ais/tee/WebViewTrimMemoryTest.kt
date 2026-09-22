package ais.tee

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewTrimMemoryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun uiHiddenKeepsWarmProviderWebViews() {
        switchProvider("claude")
        switchProvider("chatgpt")
        awaitWebViewCount(2)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        sendTrimMemory("HIDDEN")
        Thread.sleep(300)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertEquals(2, webViewCount())
    }

    private fun switchProvider(id: String) {
        composeRule.onNodeWithTag("btn_web_provider_drawer").performClick()
        composeRule.onNodeWithTag("tab_web_service_$id").performClick()
        composeRule.waitForIdle()
    }

    private fun awaitWebViewCount(expected: Int) {
        repeat(50) {
            if (webViewCount() == expected) return
            Thread.sleep(100)
        }
        assertEquals(expected, webViewCount())
    }

    private fun webViewCount(): Int {
        var count = 0
        composeRule.runOnIdle {
            count = countWebViews(composeRule.activity.window.decorView)
        }
        return count
    }

    private fun sendTrimMemory(level: String) {
        val command = "am send-trim-memory ${composeRule.activity.packageName} $level"
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }

    private fun countWebViews(view: View): Int {
        val self = if (view is WebView) 1 else 0
        if (view !is ViewGroup) return self
        return self + (0 until view.childCount).sumOf { index ->
            countWebViews(view.getChildAt(index))
        }
    }
}
