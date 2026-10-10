package ais.tee

import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.ui.screens.ProjectLibrarySourceChips
import ais.tee.ui.theme.AisteeTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectLibrarySourceChipsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emulatorRendersAndCapturesLocalSourceChip() {
        val asset = ProjectLibraryAsset(
            id = "example", projectId = "inbox", title = "example.py",
            mediaType = "text/plain", fileName = "example.py",
            sizeBytes = 48, createdAtEpochMs = 1,
        )
        var opened = ""
        composeRule.setContent {
            AisteeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ProjectLibrarySourceChips(
                    sourceIds = listOf("example", "removed"),
                    archive = ProjectLibraryArchive(assets = listOf(asset)),
                        onOpenAsset = { opened = it.id },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("library_source_chip_0").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("example", opened) }
        composeRule.onNodeWithTag("library_source_chip_1").assertIsNotEnabled()
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = requireNotNull(context.getExternalFilesDir(null))
        File(dir, "library-sources-smoke.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
