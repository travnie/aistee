package ais.tee.data.skills

import ais.tee.data.model.CapabilityDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActiveSkillToolActionsTest {
    private fun request(tool: ActiveSkillTool, json: String) = ActiveSkillToolRequest(
        tool,
        Json.parseToJsonElement(json) as JsonObject,
        CapabilityDecision.REQUIRES_USER_INTERACTION,
    )

    private fun valid(tool: ActiveSkillTool, json: String) =
        assertIs<ActiveSkillToolValidation.Valid>(validateActiveSkillToolRequest(request(tool, json))).action

    private fun invalid(tool: ActiveSkillTool, json: String) =
        assertIs<ActiveSkillToolValidation.Invalid>(validateActiveSkillToolRequest(request(tool, json)))

    @Test
    fun calendarEventsNeedTitleAndOrderedTimes() {
        val event = assertIs<ActiveSkillToolAction.CalendarEvent>(
            valid(
                ActiveSkillTool.CREATE_CALENDAR_EVENT,
                """{"title":"Standup","start":"2026-09-27T09:00:00+02:00","end":"2026-09-27T09:15:00+02:00","location":"Room 1"}""",
            )
        )
        assertEquals(15 * 60 * 1000L, event.endEpochMs!! - event.startEpochMs)
        assertEquals("Room 1", event.location)

        invalid(ActiveSkillTool.CREATE_CALENDAR_EVENT, """{"start":"2026-09-27T09:00:00Z"}""")
        invalid(ActiveSkillTool.CREATE_CALENDAR_EVENT, """{"title":"x","start":"tomorrow"}""")
        invalid(ActiveSkillTool.CREATE_CALENDAR_EVENT, """{"title":"x","start":"2026-09-27T10:00:00Z","end":"2026-09-27T09:00:00Z"}""")
        invalid(ActiveSkillTool.CREATE_CALENDAR_EVENT, """{"title":"x","start":"2026-09-27T10:00:00Z","attendees":["a@b.c"]}""")
    }

    @Test
    fun emailRecipientsAreChecked() {
        val email = assertIs<ActiveSkillToolAction.Email>(
            valid(ActiveSkillTool.COMPOSE_EMAIL, """{"to":["a@example.com"],"subject":"Hi","body":"Text"}""")
        )
        assertEquals(listOf("a@example.com"), email.to)
        assertEquals(listOf("b@example.com"), (valid(ActiveSkillTool.COMPOSE_EMAIL, """{"to":"b@example.com"}""") as ActiveSkillToolAction.Email).to)
        invalid(ActiveSkillTool.COMPOSE_EMAIL, """{"to":"not an address"}""")
        invalid(ActiveSkillTool.COMPOSE_EMAIL, """{"to":["a@example.com,b@example.com"]}""")
        invalid(ActiveSkillTool.COMPOSE_EMAIL, """{"bcc":"a@example.com"}""")
    }

    @Test
    fun notificationClipboardAndDateTime() {
        assertIs<ActiveSkillToolAction.Notification>(
            valid(ActiveSkillTool.SCHEDULE_NOTIFICATION, """{"title":"Tea","at":"2026-09-27T16:00:00Z"}""")
        )
        invalid(ActiveSkillTool.SCHEDULE_NOTIFICATION, """{"title":"Tea"}""")
        assertEquals(ActiveSkillToolAction.Clipboard("abc"), valid(ActiveSkillTool.COPY_TO_CLIPBOARD, """{"text":"abc"}"""))
        invalid(ActiveSkillTool.COPY_TO_CLIPBOARD, """{"text":""}""")
        assertEquals(ActiveSkillToolAction.CurrentDateTime, valid(ActiveSkillTool.CURRENT_DATETIME, "{}"))
        invalid(ActiveSkillTool.CURRENT_DATETIME, """{"tz":"UTC"}""")
    }

    @Test
    fun userInputIsJsonWhenItParses() {
        assertEquals(Json.parseToJsonElement("""{"km":5}"""), activeSkillUserInput("""{"km":5}"""))
        assertEquals(JsonPrimitive("5 km to miles"), activeSkillUserInput("5 km to miles"))
    }
}
