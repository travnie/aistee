package ais.tee.data.skills

import ais.tee.data.model.CapabilityDecision
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveSkillRuntimeTest {
    private fun manifest(metadata: Map<String, String>) = AgentSkillManifest(
        name = "unit-convert",
        description = "Converts units.",
        instructions = "Use for unit conversion.",
        metadata = metadata,
    )

    private val declaration = ActiveSkillDeclaration(
        runtime = ACTIVE_SKILL_RUNTIME_WEBVIEW_V1,
        tools = setOf(ActiveSkillTool.CURRENT_DATETIME, ActiveSkillTool.COMPOSE_EMAIL),
        networkOrigins = listOf("https://api.example.com"),
    )

    @Test
    fun plainSkillsStayInert() {
        val result = activeSkillDeclaration(manifest(mapOf("author" to "x")))
        assertNull(result.declaration)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun parsesDeclaredToolsAndOrigins() {
        val result = activeSkillDeclaration(
            manifest(
                mapOf(
                    "aistee-runtime" to "webview-v1",
                    "aistee-tools" to "current_datetime  compose_email",
                    "aistee-network" to "https://API.example.com/ https://data.example.org:8443 https://api.example.com:443",
                )
            )
        )

        val parsed = result.declaration!!
        assertEquals(setOf(ActiveSkillTool.CURRENT_DATETIME, ActiveSkillTool.COMPOSE_EMAIL), parsed.tools)
        assertEquals(listOf("https://api.example.com", "https://data.example.org:8443"), parsed.networkOrigins)
    }

    @Test
    fun rejectsUnknownRuntimeToolsAndStrayFields() {
        val bad = activeSkillDeclaration(
            manifest(mapOf("aistee-runtime" to "native", "aistee-tools" to "run_shell"))
        )
        assertNull(bad.declaration)
        assertEquals(2, bad.issues.size)

        val stray = activeSkillDeclaration(manifest(mapOf("aistee-network" to "https://example.com")))
        assertNull(stray.declaration)
        assertEquals(1, stray.issues.size)
    }

    @Test
    fun networkOriginsMustBePublicHttps() {
        listOf(
            "http://example.com",
            "https://localhost",
            "https://127.0.0.1",
            "https://[::1]",
            "https://router",
            "https://printer.local",
            "https://svc.internal",
            "https://user@example.com",
            "https://example.com/path",
            "https://example.com:0",
            "https://1.2.3.4",
        ).forEach { assertNull(normalizeActiveSkillNetworkOrigin(it), it) }

        val tooMany = activeSkillDeclaration(
            manifest(
                mapOf(
                    "aistee-runtime" to "webview-v1",
                    "aistee-network" to (1..5).joinToString(" ") { "https://a$it.example.com" },
                )
            )
        )
        assertNull(tooMany.declaration)
    }

    @Test
    fun toolPolicyIsClosedAndSideEffectsNeedTheUser() {
        assertEquals(CapabilityDecision.ALLOW, activeSkillToolDecision("current_datetime", declaration))
        assertEquals(CapabilityDecision.REQUIRES_USER_INTERACTION, activeSkillToolDecision("compose_email", declaration))
        assertEquals(CapabilityDecision.DENY, activeSkillToolDecision("copy_to_clipboard", declaration))
        assertEquals(CapabilityDecision.DENY, activeSkillToolDecision("run_shell", declaration))
    }

    @Test
    fun invocationPolicyRunsOfflineSkillsOnly() {
        val ready = ActiveSkillInvocationContext(
            featureEnabled = true,
            executionTrusted = true,
            isIncognitoChat = false,
            isQuickPrivacyOn = false,
        )
        val offline = declaration.copy(networkOrigins = emptyList())
        ActiveSkillInvoker.entries.forEach { invoker ->
            assertEquals(CapabilityDecision.ALLOW, activeSkillInvocationDecision(invoker, offline, ready))
            // Declared network never grants access in v1; such a skill cannot run at all.
            assertEquals(CapabilityDecision.DENY, activeSkillInvocationDecision(invoker, declaration, ready))
        }
        listOf(
            ready.copy(featureEnabled = false),
            ready.copy(executionTrusted = false),
            ready.copy(isIncognitoChat = true),
        ).forEach { context ->
            ActiveSkillInvoker.entries.forEach { invoker ->
                assertEquals(CapabilityDecision.DENY, activeSkillInvocationDecision(invoker, offline, context))
            }
        }
        assertEquals(
            CapabilityDecision.DENY,
            activeSkillInvocationDecision(ActiveSkillInvoker.MODEL, offline, ready.copy(isQuickPrivacyOn = true)),
        )
        assertEquals(
            CapabilityDecision.ALLOW,
            activeSkillInvocationDecision(ActiveSkillInvoker.USER, offline, ready.copy(isQuickPrivacyOn = true)),
        )
    }

    @Test
    fun hostMessagesNeedMainFrameOriginAndCallId() {
        val origin = "https://appassets.androidplatform.net"
        fun accept(payload: String?, mainFrame: Boolean = true, source: String? = origin) =
            acceptActiveSkillHostMessage(payload, mainFrame, source, origin, expectedCallId = "c1")

        assertEquals(ActiveSkillHostMessage.Output("{\"result\":1}"), accept("""{"callId":"c1","output":"{\"result\":1}"}"""))
        assertEquals(ActiveSkillHostMessage.Failed("The skill failed: boom"), accept("""{"callId":"c1","error":"boom"}"""))
        listOf(
            accept("""{"callId":"c1","output":"x"}""", mainFrame = false),
            accept("""{"callId":"c1","output":"x"}""", source = "https://evil.example.com"),
            accept("""{"callId":"c1","output":"x"}""", source = null),
            accept("""{"callId":"c2","output":"x"}"""),
            accept("""{"callId":"c1","output":1}"""),
            accept("""{"callId":"c1","output":"x","extra":1}"""),
            accept("not json"),
            accept(null),
        ).forEach { assertEquals(ActiveSkillHostMessage.Rejected, it) }
        assertIs<ActiveSkillHostMessage.Failed>(accept("x".repeat(ACTIVE_SKILL_MAX_OUTPUT_BYTES * 3)))
    }

    @Test
    fun trustIsBoundToDigestAndOrigins() {
        val trust = ActiveSkillTrust("unit-convert", "abc", listOf("https://api.example.com"))
        assertTrue(isActiveSkillTrusted(trust, "unit-convert", "abc", declaration))
        assertFalse(isActiveSkillTrusted(trust, "unit-convert", "abd", declaration))
        assertFalse(isActiveSkillTrusted(trust, "other", "abc", declaration))
        assertFalse(
            isActiveSkillTrusted(trust, "unit-convert", "abc", declaration.copy(networkOrigins = listOf("https://x.example.com")))
        )
        assertFalse(isActiveSkillTrusted(null, "unit-convert", "abc", declaration))
    }

    @Test
    fun requestCarriesOnlyInputLocaleAndTime() {
        val json = activeSkillRequestJson(
            buildJsonObject { put("value", 3) },
            locale = "en-US",
            nowIso8601 = "2026-09-26T12:00:00Z",
        )
        assertEquals(
            """{"version":1,"input":{"value":3},"locale":"en-US","now":"2026-09-26T12:00:00Z"}""",
            json,
        )
    }

    @Test
    fun outputSchemaIsStrict() {
        val result = assertIs<ActiveSkillOutcome.Result>(
            parseActiveSkillOutput("""{"result":{"km":5},"card":{"title":"5 km","html":"<b>5</b>"}}""", declaration)
        )
        assertEquals("5 km", result.card!!.title)

        val tools = assertIs<ActiveSkillOutcome.ToolRequests>(
            parseActiveSkillOutput("""{"tools":[{"name":"compose_email","arguments":{"to":"a@b.c"}}]}""", declaration)
        )
        assertEquals(CapabilityDecision.REQUIRES_USER_INTERACTION, tools.requests.single().decision)

        assertEquals(
            ActiveSkillOutcome.Error("boom"),
            parseActiveSkillOutput("""{"error":"boom"}""", declaration),
        )

        listOf(
            null,
            "not json",
            "[1]",
            """{"result":1,"error":"x"}""",
            """{"result":1,"extra":true}""",
            """{"tools":[],"card":{"title":"t","html":"h"}}""",
            """{"result":1,"card":{"title":"","html":"h"}}""",
            """{"result":1,"card":{"title":"t","html":"h","js":"x"}}""",
            """{"tools":[{"name":"copy_to_clipboard","arguments":{}}]}""",
            """{"tools":[{"name":"compose_email","arguments":"x"}]}""",
            """{"error":1}""",
            """{"result":"${"x".repeat(ACTIVE_SKILL_MAX_OUTPUT_BYTES)}"}""",
        ).forEach { raw ->
            assertIs<ActiveSkillOutcome.Error>(parseActiveSkillOutput(raw, declaration), raw?.take(40))
        }
        assertEquals(JsonPrimitive(1), (parseActiveSkillOutput("""{"result":1}""", declaration) as ActiveSkillOutcome.Result).result)
    }
}
