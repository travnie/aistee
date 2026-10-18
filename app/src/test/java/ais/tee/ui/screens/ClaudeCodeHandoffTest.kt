package ais.tee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeHandoffTest {
    @Test
    fun officialLinkEncodesTaskAndGitHubContextWithoutAnyCredentials() {
        val link = ClaudeCodeHandoff.newSessionUrl(
            task = "Fix failing CI & add tests",
            repository = "travnie/aistee",
            branch = "feat/agent-lab",
        )
        assertEquals(
            "https://claude.ai/code/new?q=Fix%20failing%20CI%20%26%20add%20tests" +
                "&mode=plan&repo=travnie%2Faistee&branch=feat%2Fagent-lab",
            link,
        )
        assertFalse(link.contains("token"))
        assertFalse(link.contains("api_key"))
    }

    @Test
    fun codeModeAndNoRepositoryRemainValid() {
        assertEquals(
            "https://claude.ai/code/new?q=Review%20a%20patch&mode=code",
            ClaudeCodeHandoff.newSessionUrl("  Review a patch  ", mode = ClaudeCodeHandoff.Mode.CODE),
        )
        assertEquals("claude://code", ClaudeCodeHandoff.SESSIONS_URL)
        assertEquals("https://claude.ai/code", ClaudeCodeHandoff.WEB_URL)
    }

    @Test
    fun invalidTaskAndReferencesCannotOpen() {
        assertFalse(ClaudeCodeHandoff.canOpen(" ", "", ""))
        assertFalse(ClaudeCodeHandoff.canOpen("x".repeat(2_001), "", ""))
        assertFalse(ClaudeCodeHandoff.canOpen("task", "invalid-repo", ""))
        assertFalse(ClaudeCodeHandoff.canOpen("task", "", "main"))
        assertFalse(ClaudeCodeHandoff.canOpen("task", "trvny/repo", "bad branch?name"))
        assertThrows(IllegalArgumentException::class.java) {
            ClaudeCodeHandoff.newSessionUrl("task", "bad/repo/extra")
        }
        assertTrue(ClaudeCodeHandoff.canOpen("task", "trvny/repo", "feature/task"))
        assertTrue(ClaudeCodeHandoff.canOpen("task", "trvny/repo", "release+hotfix@2"))
        assertFalse(ClaudeCodeHandoff.canOpen("task", "trvny/repo", "bad..ref"))
        assertFalse(ClaudeCodeHandoff.canOpen("task", "trvny/repo", "dir/.hidden"))
    }
}
