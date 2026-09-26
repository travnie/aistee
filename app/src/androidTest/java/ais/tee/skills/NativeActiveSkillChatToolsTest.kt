package ais.tee.skills

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.engine.ActiveSkillChatAnswer
import ais.tee.data.engine.ActiveSkillChatStage
import ais.tee.data.engine.NativeActiveSkillChatTools
import ais.tee.data.model.NativeToolCall
import ais.tee.data.preferences.ActiveSkillsPreferencesStore
import ais.tee.data.skills.ActiveSkillTrust
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.data.skills.activeSkillBundleDigest
import ais.tee.security.QuickPrivacyModeStore
import ais.tee.ui.screens.ActiveSkillChatPromptChip
import ais.tee.ui.viewmodel.PendingActiveSkillPrompt
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeActiveSkillChatToolsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "active-skill-chat-tools-test").apply { deleteRecursively() }
    private val store = LocalSkillLibraryStore(root)
    private val preferences = ActiveSkillsPreferencesStore(context).apply { setEnabled(true) }
    private val asked = mutableListOf<ActiveSkillChatStage>()

    @After
    fun cleanUp() {
        preferences.revokeTrust(SKILL)
        preferences.setEnabled(false)
        QuickPrivacyModeStore.get(context).setEnabled(false)
        root.deleteRecursively()
    }

    private fun addTrustedSkill() = runBlocking {
        store.add(
            "---\nname: $SKILL\ndescription: Echo.\nmetadata:\n  aistee-runtime: webview-v1\n---\nEcho.\n",
            scripts = mapOf(
                "scripts/index.html" to
                    "<!doctype html><script>window.aistee_skill_run = (r) => JSON.stringify({ result: JSON.parse(r).input });</script>"
                        .toByteArray()
            ),
        )
        store.setEnabled(SKILL, true)
        val digest = activeSkillBundleDigest(checkNotNull(store.readBundleFiles(SKILL)))
        preferences.grantTrust(ActiveSkillTrust(SKILL, digest, emptyList()))
    }

    private fun tools(vararg answers: ActiveSkillChatAnswer): NativeActiveSkillChatTools {
        val queue = ArrayDeque(answers.toList())
        return NativeActiveSkillChatTools(context, store, ask = { _, stage ->
            asked += stage
            queue.removeFirst()
        })
    }

    private fun call(input: String) = NativeToolCall(
        "call-1",
        "skill_$SKILL",
        buildJsonObject { put("input", JsonPrimitive(input)) },
    )

    @Test
    fun approvedCallShowsInputAndResultBeforeReturningIt() = runBlocking {
        addTrustedSkill()
        val tools = tools(ActiveSkillChatAnswer.Approved(), ActiveSkillChatAnswer.Approved())
        assertEquals(listOf("skill_$SKILL"), tools.definitions().map { it.name })

        val result = tools.execute(call("{\"km\":3}"))

        assertFalse(result.isError)
        assertEquals("{\"km\":3}", result.output)
        assertEquals(ActiveSkillChatStage.Run("{\"km\":3}"), asked[0])
        assertEquals(ActiveSkillChatStage.Result("{\"km\":3}", isError = false), asked[1])
        assertTrue(tools.transcriptNotes.single().contains("{\"km\":3}"))
    }

    @Test
    fun declinedRunNeverReachesTheSandbox() = runBlocking {
        addTrustedSkill()
        val tools = tools(ActiveSkillChatAnswer.Declined)
        tools.definitions()

        val result = tools.execute(call("hello"))

        assertTrue(result.isError)
        assertEquals(1, asked.size)
    }

    @Test
    fun untrustedSwitchedOffOrQuickPrivacySkillsAreNotOffered() = runBlocking {
        addTrustedSkill()
        preferences.revokeTrust(SKILL)
        assertTrue(tools().definitions().isEmpty())

        addTrustedSkillTrustOnly()
        QuickPrivacyModeStore.get(context).setEnabled(true)
        assertTrue(tools().definitions().isEmpty())

        QuickPrivacyModeStore.get(context).setEnabled(false)
        preferences.setEnabled(false)
        assertTrue(tools().definitions().isEmpty())
    }

    @Test
    fun chipShowsTheExactInputAndAnswers() {
        var answer: ActiveSkillChatAnswer? = null
        composeRule.setContent {
            ActiveSkillChatPromptChip(
                prompt = PendingActiveSkillPrompt("chat", SKILL, ActiveSkillChatStage.Run("{\"km\":3}")),
                onAnswer = { answer = it },
            )
        }
        composeRule.onNodeWithTag("active_skill_chat_prompt_body").assertTextContains("{\"km\":3}")
        composeRule.onNodeWithTag("btn_active_skill_chat_allow").performClick()
        composeRule.runOnIdle { assertEquals(ActiveSkillChatAnswer.Approved(), answer) }
    }

    private fun addTrustedSkillTrustOnly() = runBlocking {
        val digest = activeSkillBundleDigest(checkNotNull(store.readBundleFiles(SKILL)))
        preferences.grantTrust(ActiveSkillTrust(SKILL, digest, emptyList()))
    }

    private companion object {
        const val SKILL = "echo-skill"
    }
}
