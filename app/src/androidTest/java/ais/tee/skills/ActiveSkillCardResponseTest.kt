package ais.tee.skills

import androidx.test.ext.junit.runners.AndroidJUnit4
import ais.tee.data.skills.ACTIVE_SKILL_CONTENT_SECURITY_POLICY
import ais.tee.data.skills.activeSkillCardResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveSkillCardResponseTest {
    @Test
    fun onlyTheCardPageIsServedWithTheSandboxPolicy() {
        val page = activeSkillCardResponse("index.html", "<p>card</p>")
        assertEquals(200, page.statusCode)
        assertEquals(ACTIVE_SKILL_CONTENT_SECURITY_POLICY, page.responseHeaders["Content-Security-Policy"])
        assertEquals("<p>card</p>", page.data.readBytes().decodeToString())

        assertEquals(404, activeSkillCardResponse("other.js", "<p>card</p>").statusCode)
        assertEquals(404, activeSkillCardResponse("../index.html", "<p>card</p>").statusCode)
    }
}
