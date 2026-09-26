package ais.tee.data.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSkillSandboxPathsTest {
    @Test
    fun onlyScriptFilesAreServed() {
        assertEquals("scripts/index.html", activeSkillBundlePath(""))
        assertEquals("scripts/lib/app.js", activeSkillBundlePath("lib/app.js"))
        listOf("../SKILL.md", "a/../../x", "./x", "a//b", "dir/", "%2e%2e/x", "a\\b").forEach {
            assertNull(it, activeSkillBundlePath(it))
        }
    }

    @Test
    fun digestCoversEveryFileAndPath() {
        val files = mapOf("SKILL.md" to "a".toByteArray(), "scripts/index.html" to "b".toByteArray())
        val digest = activeSkillBundleDigest(files)

        assertEquals(64, digest.length)
        assertEquals(digest, activeSkillBundleDigest(files.toList().reversed().toMap()))
        assertNotEquals(digest, activeSkillBundleDigest(files + ("scripts/index.html" to "c".toByteArray())))
        assertNotEquals(digest, activeSkillBundleDigest(mapOf("SKILL.m" to "da".toByteArray(), "scripts/index.html" to "b".toByteArray())))
    }

    @Test
    fun policyAllowsNoNetworkWorkersOrFrames() {
        val directives = ACTIVE_SKILL_CONTENT_SECURITY_POLICY.split(';').map { it.trim() }
        assertTrue("connect-src 'self'" in directives)
        assertTrue("worker-src 'none'" in directives)
        assertTrue("frame-src 'none'" in directives)
        assertTrue("form-action 'none'" in directives)
        assertFalse(ACTIVE_SKILL_CONTENT_SECURITY_POLICY.contains("https:"))
    }
}
