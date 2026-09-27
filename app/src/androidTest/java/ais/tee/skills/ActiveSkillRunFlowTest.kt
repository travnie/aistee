package ais.tee.skills

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.preferences.ActiveSkillsPreferencesStore
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.ui.screens.ActiveSkillRunFlow
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveSkillRunFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "active-skill-run-flow-test").apply { deleteRecursively() }
    private val store = LocalSkillLibraryStore(root)
    private val preferences = ActiveSkillsPreferencesStore(context).apply { setEnabled(true) }

    @After
    fun cleanUp() {
        preferences.revokeTrust(SKILL)
        preferences.setEnabled(false)
        root.deleteRecursively()
    }

    private fun addSkill(tools: String, script: String) = runBlocking {
        store.add(
            "---\nname: $SKILL\ndescription: Echo.\nmetadata:\n  aistee-runtime: webview-v1\n  aistee-tools: $tools\n---\nEcho.\n",
            scripts = mapOf("scripts/index.html" to "<!doctype html><script>$script</script>".toByteArray()),
        )
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(20_000) {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun trustThenRunShowsTheResult() {
        addSkill("current_datetime", "window.aistee_skill_run = (r) => JSON.stringify({ result: JSON.parse(r).input });")
        composeRule.setContent { ActiveSkillRunFlow(skillName = SKILL, store = store, onClose = {}) }

        waitForTag("active_skill_trust_dialog")
        composeRule.onNodeWithTag("btn_active_skill_trust").performClick()
        waitForTag("active_skill_input")
        composeRule.onNodeWithTag("active_skill_input").performTextInput("hello")
        composeRule.onNodeWithTag("btn_active_skill_run").performClick()

        waitForTag("active_skill_result")
        composeRule.onNodeWithTag("active_skill_result").assertTextContains("hello", substring = true)
    }

    @Test
    fun sideEffectingToolsWaitForConsent() {
        addSkill(
            "copy_to_clipboard",
            "window.aistee_skill_run = () => JSON.stringify({ tools: [{ name: 'copy_to_clipboard', arguments: { text: 'from skill' } }] });",
        )
        composeRule.setContent { ActiveSkillRunFlow(skillName = SKILL, store = store, onClose = {}) }

        waitForTag("btn_active_skill_trust")
        composeRule.onNodeWithTag("btn_active_skill_trust").performClick()
        waitForTag("btn_active_skill_run")
        composeRule.onNodeWithTag("btn_active_skill_run").performClick()

        waitForTag("active_skill_consent")
        composeRule.onNodeWithTag("active_skill_consent").assertTextContains("from skill", substring = true)
        composeRule.onNodeWithTag("btn_active_skill_allow").performClick()
        waitForTag("active_skill_result")
        composeRule.onNodeWithTag("active_skill_result").assertTextContains("clipboard", substring = true)
    }

    @Test
    fun cardsOfferAnOpenButtonNextToTheResult() {
        addSkill(
            "current_datetime",
            "window.aistee_skill_run = () => JSON.stringify({ result: 1, card: { title: 'Trip plan', html: '<p>hi</p>' } });",
        )
        composeRule.setContent { ActiveSkillRunFlow(skillName = SKILL, store = store, onClose = {}) }

        waitForTag("btn_active_skill_trust")
        composeRule.onNodeWithTag("btn_active_skill_trust").performClick()
        waitForTag("btn_active_skill_run")
        composeRule.onNodeWithTag("btn_active_skill_run").performClick()

        waitForTag("btn_active_skill_open_card")
        composeRule.onNodeWithTag("btn_active_skill_open_card").assertTextContains("Trip plan", substring = true)
    }

    private companion object {
        const val SKILL = "echo-skill"
    }
}
