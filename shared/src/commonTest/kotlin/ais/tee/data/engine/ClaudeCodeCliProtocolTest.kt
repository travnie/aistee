package ais.tee.data.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeCodeCliProtocolTest {
    @Test
    fun subscriptionPreflightNeverTreatsConsoleOrBareOAuthAsPlanAllowance() {
        assertEquals(listOf("claude", "auth", "status"), ClaudeCodeCliProtocol.authenticationStatusCommand())
        assertTrue(ClaudeCodeCliProtocol.usesSubscription(
            """{"loggedIn":true,"authMethod":"claude.ai"}"""
        ))
        for (method in listOf("api_key", "oauth_token", "third_party", "none", "api_key_helper")) {
            assertFalse(ClaudeCodeCliProtocol.usesSubscription(
                """{"loggedIn":true,"authMethod":"$method"}"""
            ))
        }
        assertFalse(ClaudeCodeCliProtocol.usesSubscription(
            """{"loggedIn":false,"authMethod":"claude.ai"}"""
        ))
        assertTrue(ClaudeCodeCliProtocol.hasMeteredEnvironmentOverrides(setOf("ANTHROPIC_API_KEY")))
        assertTrue(ClaudeCodeCliProtocol.hasMeteredEnvironmentOverrides(setOf("ANTHROPIC_BASE_URL")))
        assertTrue(ClaudeCodeCliProtocol.hasMeteredEnvironmentOverrides(setOf("CLAUDE_CODE_USE_MANTLE")))
        assertTrue(ClaudeCodeCliProtocol.hasMeteredEnvironmentOverrides(setOf("CLAUDE_CODE_USE_ANTHROPIC_AWS")))
        assertFalse(ClaudeCodeCliProtocol.usesSubscription("not json"))
        assertFalse(ClaudeCodeCliProtocol.hasMeteredEnvironmentOverrides(setOf("PATH", "HOME")))
    }

    @Test
    fun launchArgumentsAreReadOnlyAndNeverPassedThroughShell() {
        val prompt = "Review git status; echo unsafe"
        val command = ClaudeCodeCliProtocol.readOnlyCommand(prompt, "12345678-1234-4123-8123-123456789abc")
        assertEquals("claude", command.first())
        assertEquals(prompt, command.last())
        assertTrue(command.contains("--restricted"))
        assertTrue(command.contains("--setting-sources"))
        assertEquals("", command[command.indexOf("--setting-sources") + 1])
        assertTrue(command.contains("--strict-mcp-config"))
        assertTrue(command.contains("--settings"))
        assertTrue(command.contains("{\"disableAllHooks\":true}"))
        assertTrue(command.contains("plan"))
        assertTrue(command.contains("Read,Glob,Grep"))
        assertTrue(command.contains("mcp__*"))
        assertTrue(command.contains("--resume"))
        assertFalse(command.contains("--dangerously-skip-permissions"))
        assertFailsWith<IllegalArgumentException> { ClaudeCodeCliProtocol.readOnlyCommand("") }
        assertFailsWith<IllegalArgumentException> {
            ClaudeCodeCliProtocol.readOnlyCommand("Task", "--bad-flag")
        }
        assertFailsWith<IllegalArgumentException> {
            ClaudeCodeCliProtocol.readOnlyCommand("Task", "review")
        }
    }

    @Test
    fun eventsExposeOnlyBoundSessionDeltasAndOutcomes() {
        val init = """{"type":"system","subtype":"init","session_id":"12345678-1234-4123-8123-123456789abc"}"""
        assertEquals(
            ClaudeCodeCliEvent.SessionStarted("12345678-1234-4123-8123-123456789abc"),
            ClaudeCodeCliProtocol.event(init),
        )
        val delta = """{"type":"stream_event","session_id":"12345678-1234-4123-8123-123456789abc","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}}"""
        assertEquals(
            ClaudeCodeCliEvent.TextDelta("Hello"),
            ClaudeCodeCliProtocol.event(delta, "12345678-1234-4123-8123-123456789abc"),
        )
        assertEquals(ClaudeCodeCliEvent.Ignored, ClaudeCodeCliProtocol.event(delta, "87654321-4321-4432-8432-cba987654321"))
        assertEquals(ClaudeCodeCliEvent.Ignored, ClaudeCodeCliProtocol.event(delta))
        val anonymousDelta = """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}}"""
        assertEquals(
            ClaudeCodeCliEvent.TextDelta("Hello"),
            ClaudeCodeCliProtocol.event(anonymousDelta, "12345678-1234-4123-8123-123456789abc"),
        )
        assertEquals(ClaudeCodeCliEvent.Ignored, ClaudeCodeCliProtocol.event(anonymousDelta))
        assertEquals(ClaudeCodeCliEvent.Ignored, ClaudeCodeCliProtocol.event(
            """{"type":"stream_event","session_id":"12345678-1234-4123-8123-123456789abc","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"private"}}}""",
            "12345678-1234-4123-8123-123456789abc",
        ))
        val tool = """{"type":"assistant","session_id":"12345678-1234-4123-8123-123456789abc","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/private/path"}}]}}"""
        assertEquals(
            ClaudeCodeCliEvent.ToolsObserved(listOf("Read")),
            ClaudeCodeCliProtocol.event(tool, "12345678-1234-4123-8123-123456789abc"),
        )
        val parallel = """{"type":"assistant","session_id":"12345678-1234-4123-8123-123456789abc","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"secret"}},{"type":"tool_use","name":"Grep","input":{"pattern":"private"}}]}}"""
        assertEquals(
            ClaudeCodeCliEvent.ToolsObserved(listOf("Read", "Grep")),
            ClaudeCodeCliProtocol.event(parallel, "12345678-1234-4123-8123-123456789abc"),
        )
        val complete = """{"type":"result","subtype":"success","session_id":"12345678-1234-4123-8123-123456789abc","is_error":false,"total_cost_usd":2.5}"""
        assertEquals(
            ClaudeCodeCliEvent.Finished(ClaudeCodeCliOutcome.COMPLETED),
            ClaudeCodeCliProtocol.event(complete, "12345678-1234-4123-8123-123456789abc"),
        )
        assertEquals(
            ClaudeCodeCliEvent.Finished(ClaudeCodeCliOutcome.FAILED),
            ClaudeCodeCliProtocol.event(complete.replace("false", "true"), "12345678-1234-4123-8123-123456789abc"),
        )
    }
}
