package ais.tee.data.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveSkillChatToolsTest {
    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun toolNamesArePrefixedAndBounded() {
        assertEquals("skill_unit-converter", activeSkillChatToolName("unit-converter"))
        assertEquals(64, activeSkillChatToolName("a".repeat(64))?.length)
        assertNull(activeSkillChatToolName(""))
        assertNull(activeSkillChatToolName("zażółć"))
    }

    @Test
    fun descriptionsFitTheToolLimit() {
        val description = activeSkillChatToolDescription("a".repeat(64), "word ".repeat(1_000))
        assertTrue(description.length <= 1_024)
        assertTrue(description.startsWith("Run the user's trusted offline skill"))
    }

    @Test
    fun inputMustBeOneBoundedString() {
        assertEquals("{\"km\":3}", activeSkillChatInput(args("""{"input":"{\"km\":3}"}""")))
        assertEquals("", activeSkillChatInput(args("""{"input":""}""")))
        assertNull(activeSkillChatInput(args("""{}""")))
        assertNull(activeSkillChatInput(args("""{"input":3}""")))
        assertNull(activeSkillChatInput(args("""{"input":"a","extra":"b"}""")))
        assertNull(activeSkillChatInput(args("""{"input":"${"a".repeat(ACTIVE_SKILL_MAX_CHAT_INPUT_CHARS + 1)}"}""")))
    }
}
