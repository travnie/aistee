package ais.tee.data.engine

import ais.tee.data.model.BenchToolPermission
import ais.tee.data.model.BuiltInBenchTool
import ais.tee.data.tokenizer.LocalTokenCounter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantTokenCountActionTest {
    private val docbench = setOf(BuiltInBenchTool.DOCBENCH_DOCUMENT)
    private val contentGrant = setOf(BenchToolPermission.READ_USER_SELECTED_CONTENT)

    @Test
    fun defaultStateAndNonUserRequestsAreBlocked() = runBlocking {
        assertEquals(
            SystemAssistantTokenCountResult.Blocked,
            SystemAssistantTokenCountAction.execute("private content"),
        )
        assertEquals(
            SystemAssistantTokenCountResult.Blocked,
            SystemAssistantTokenCountAction.execute(
                text = "private content",
                systemAssistantEnabled = true,
                enabledBenchTools = docbench,
                grantedPermissions = contentGrant,
            ),
        )
    }

    @Test
    fun optInCannotOverrideDisabledBenchOrMissingContentPermission() = runBlocking {
        assertEquals(
            SystemAssistantTokenCountResult.Blocked,
            SystemAssistantTokenCountAction.execute(
                text = "hello",
                systemAssistantEnabled = true,
                userInitiated = true,
                grantedPermissions = contentGrant,
            ),
        )
        assertEquals(
            SystemAssistantTokenCountResult.Blocked,
            SystemAssistantTokenCountAction.execute(
                text = "hello",
                systemAssistantEnabled = true,
                userInitiated = true,
                enabledBenchTools = setOf(BuiltInBenchTool.DOCBENCH_TEXT_INSPECTOR),
                grantedPermissions = contentGrant,
            ),
        )
        assertEquals(
            SystemAssistantTokenCountResult.Blocked,
            SystemAssistantTokenCountAction.execute(
                text = "hello",
                systemAssistantEnabled = true,
                userInitiated = true,
                enabledBenchTools = docbench,
            ),
        )
    }

    @Test
    fun explicitRequestReusesLocalDocbenchTokenizer() = runBlocking {
        val inline = "Hello from Aistee 👋"
        val result = SystemAssistantTokenCountAction.execute(
            text = inline,
            systemAssistantEnabled = true,
            userInitiated = true,
            enabledBenchTools = docbench,
            grantedPermissions = contentGrant,
        )
        assertEquals(
            SystemAssistantTokenCountResult.Count(
                tokens = LocalTokenCounter.count(inline),
                encoding = LocalTokenCounter.ENCODING_LABEL,
            ),
            result,
        )
        assertTrue((result as SystemAssistantTokenCountResult.Count).tokens > 0)
    }

    @Test
    fun rejectsEmptyOrOversizedInputAfterAuthorization() = runBlocking {
        for (text in listOf(null, "", "   ", "x".repeat(MAX_INLINE_TOOL_TEXT_CHARS + 1))) {
            assertEquals(
                SystemAssistantTokenCountResult.InvalidInput,
                SystemAssistantTokenCountAction.execute(
                    text = text,
                    systemAssistantEnabled = true,
                    userInitiated = true,
                    enabledBenchTools = docbench,
                    grantedPermissions = contentGrant,
                ),
            )
        }
    }

    @Test
    fun revokingOptInBlocksAPreviouslyAllowedRequest() = runBlocking {
        val inputs = listOf(true, false)
        val results = inputs.map { optedIn ->
            SystemAssistantTokenCountAction.execute(
                text = "hello",
                systemAssistantEnabled = optedIn,
                userInitiated = true,
                enabledBenchTools = docbench,
                grantedPermissions = contentGrant,
            )
        }
        assertTrue(results[0] is SystemAssistantTokenCountResult.Count)
        assertEquals(SystemAssistantTokenCountResult.Blocked, results[1])
    }
}
