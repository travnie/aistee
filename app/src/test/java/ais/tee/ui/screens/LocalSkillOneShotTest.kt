package ais.tee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSkillOneShotTest {
    @Test fun blankSkillDoesNotStage() {
        assertNull(oneShotSkillPrompt(" \n"))
    }

    @Test fun fullSkillSourceStaysIntactInOneShotPrompt() {
        val source = "---\nname: analyze\n---\n# Instructions\nReview the user's code.\n"
        val prompt = requireNotNull(oneShotSkillPrompt(source))
        assertTrue(prompt.contains("only for this request"))
        assertTrue(prompt.endsWith(source))
        assertEquals(1, prompt.split("# Instructions").size - 1)
    }
}
