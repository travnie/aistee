package ais.tee.screenshots

import ais.tee.data.skills.LocalSkillSummary
import ais.tee.ui.screens.LocalSkillCard
import ais.tee.ui.theme.AisteeTheme
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class LocalSkillOneShotScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun compact() = render("skill_once_compact")

    @Test
    @Config(qualifiers = "w610dp-h500dp-xxhdpi")
    fun medium() = render("skill_once_medium")

    @Test
    @Config(qualifiers = "w900dp-h1000dp-xxhdpi")
    fun expanded() = render("skill_once_expanded")

    private fun render(name: String) {
        var selected = false
        compose.setContent {
            AisteeTheme(darkTheme = true) {
                LocalSkillCard(
                    skill = LocalSkillSummary("Sample", "Useful when requested", false),
                    actionsEnabled = true,
                    onActivationChanged = {},
                    onView = {},
                    onEdit = {},
                    onExport = {},
                    onRemove = {},
                    onUseOnce = { selected = true },
                )
            }
        }
        compose.onNodeWithTag("skill_use_once_Sample")
            .assertIsDisplayed()
            .captureRoboImage("build/outputs/roborazzi/$name.png")
        compose.onNodeWithTag("skill_use_once_Sample").performClick()
        assertTrue(selected)
    }
}
