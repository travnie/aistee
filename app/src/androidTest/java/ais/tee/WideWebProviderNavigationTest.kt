package ais.tee

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun providerNavigationStaysVisibleWithoutDrawerButton() {
        composeRule.onNodeWithTag("tab_web_service_claude").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("btn_web_provider_drawer").fetchSemanticsNodes().isEmpty())
    }
}
