package ais.tee.web

import ais.tee.data.model.WebAiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDiagnosticsTest {
    @Test
    fun hostSummaryDropsPathQueryAndFragment() {
        assertEquals(
            "chatgpt.com",
            providerDiagnosticsHost("https://ChatGPT.com/c/secret-conversation?token=secret#message")
        )
        assertNull(providerDiagnosticsHost("not a url"))
    }

    @Test
    fun pickerHostIsRecordedOnlyForProviderOwnedPages() {
        assertEquals(
            "chatgpt.com",
            providerDiagnosticsPageHost(WebAiService.CHATGPT, "https://chatgpt.com/c/123")
        )
        assertEquals(
            "off-provider",
            providerDiagnosticsPageHost(WebAiService.CHATGPT, "https://accounts.example.com/upload")
        )
        assertEquals("off-provider", providerDiagnosticsPageHost(WebAiService.CHATGPT, null))
    }

    @Test
    fun documentMatchIgnoresFragmentsButRejectsNavigation() {
        assertTrue(
            providerDiagnosticsDocumentMatches(
                "https://chatgpt.com/c/123#first",
                "https://chatgpt.com/c/123#second"
            )
        )
        assertTrue(
            !providerDiagnosticsDocumentMatches(
                "https://chatgpt.com/c/123",
                "https://chatgpt.com/c/456"
            )
        )
        assertTrue(
            !providerDiagnosticsDocumentMatches(
                "https://chatgpt.com/c/123?mode=a",
                "https://chatgpt.com/c/123?mode=b"
            )
        )
    }

    @Test
    fun acceptTypesKeepOnlyBoundedMimeTokens() {
        assertEquals(
            "image/*, application/pdf",
            sanitizeProviderAcceptTypes(arrayOf(" image/*, application/pdf ", "image/*"))
        )
        assertEquals(
            "*/*",
            sanitizeProviderAcceptTypes(arrayOf("https://example.test/?token=secret", "not-a-mime"))
        )
        assertEquals("*/*", sanitizeProviderAcceptTypes(null))
    }

    @Test
    fun probeScriptIsRestrictedToProviderOwnedHttpsPages() {
        assertTrue(
            providerDiagnosticsProbeScript(WebAiService.CHATGPT, "https://chatgpt.com/") != null
        )
        assertNull(
            providerDiagnosticsProbeScript(WebAiService.CHATGPT, "https://chatgpt.com.evil.example/")
        )
        assertNull(
            providerDiagnosticsProbeScript(WebAiService.CHATGPT, "http://chatgpt.com/")
        )
    }

    @Test
    fun probeScriptAddsStructuralSignalsWithoutReadingUserContent() {
        val script = requireNotNull(
            providerDiagnosticsProbeScript(WebAiService.QWEN, "https://qwen.ai/")
        )

        assertTrue(script.contains("autocomplete~=\"username\""))
        assertTrue(script.contains("input[type=\"password\"]"))
        assertTrue(script.contains("node.multiple === true"))
        assertTrue(!script.contains(".value"))
        assertTrue(!script.contains("innerText"))
        assertTrue(!script.contains("textContent"))
        assertTrue(!script.contains("outerHTML"))
    }

    @Test
    fun parserAcceptsNonNegativeCapabilityCountsAndKnownEditorKinds() {
        assertEquals(
            ProviderDiagnosticsProbeResult.Ready(
                ProviderDomCapabilities(
                    textareas = 2,
                    contentEditables = 1,
                    identityInputs = 1,
                    passwordInputs = 1,
                    fileInputs = 3,
                    multipleFileInputs = 2,
                    activeEditorKind = "textarea"
                )
            ),
            parseProviderDiagnosticsProbeResult("\"2|1|1|1|3|2|textarea|-1|-1|-1\"")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Ready(
                ProviderDomCapabilities(
                    textareas = 0,
                    contentEditables = 0,
                    identityInputs = 0,
                    passwordInputs = 0,
                    fileInputs = 0,
                    multipleFileInputs = 0,
                    activeEditorKind = null
                )
            ),
            parseProviderDiagnosticsProbeResult("\"0|0|0|0|0|0|none|-1|-1|-1\"")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult("\"1|0|0|0|0|0|unexpected|-1|-1|-1\"")
        )
    }

    @Test
    fun safeReportContainsOnlyExplicitDiagnosticsFields() {
        val report = ProviderDiagnosticsSnapshot(
            providerName = "Example AI",
            host = "chat.example.test",
            providerOwned = true,
            webViewPackage = "com.android.webview 1.2.3",
            siteMode = "mobile",
            activityTracking = "not verified",
            activityState = "idle",
            fileChooserRequests = 1,
            fileChooserMode = "single",
            fileChooserHost = "chat.example.test",
            fileChooserAcceptTypes = "image/*",
            fileChooserOutcome = "selected (1)",
            domProbeSummary = "textarea=1, contenteditable=0, identity=1, password=1, " +
                "file=1, multi-file=0, active=textarea"
        ).safeReport()

        assertTrue(report.contains("Host: chat.example.test"))
        assertTrue(report.contains("File chooser requests: 1"))
        assertTrue(report.contains("Last file chooser host: chat.example.test"))
        assertTrue(!report.contains("https://"))
        assertTrue(!report.contains("cookie", ignoreCase = true))
        assertTrue(!report.contains("token", ignoreCase = true))
    }

    @Test
    fun parserRejectsOffProviderAndMalformedResultsConservatively() {
        assertEquals(
            ProviderDiagnosticsProbeResult.OffProvider,
            parseProviderDiagnosticsProbeResult("\"off-provider\"")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult("\"-1|0|0|0|0|0|none|-1|-1|-1\"")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult("\"0|0|0|0|1|2|none|-1|-1|-1\"")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult("null")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult(null)
        )
    }

    @Test
    fun turnAnchorCountsAreParsedAndBounded() {
        val ready = parseProviderDiagnosticsProbeResult("\"1|0|0|0|0|0|none|3|2|5\"")
        assertEquals(
            ProviderTurnAnchorCounts(userTurns = 3, assistantTurns = 2, turnsWithStableId = 5),
            (ready as ProviderDiagnosticsProbeResult.Ready).capabilities.turnAnchors
        )
        assertTrue(providerDiagnosticsProbeSummary(ready).endsWith("turns=user 3/assistant 2/stable-id 5"))
        val unconfigured = parseProviderDiagnosticsProbeResult("\"1|0|0|0|0|0|none|-1|-1|-1\"")
        assertTrue(providerDiagnosticsProbeSummary(unconfigured).endsWith("turns=not configured"))
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult("\"1|0|0|0|0|0|none|1|1|3\"")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult("\"1|0|0|0|0|0|none|-1|2|0\"")
        )
        assertEquals(
            ProviderDiagnosticsProbeResult.Failed,
            parseProviderDiagnosticsProbeResult("\"1|0|0|0|0|0|none\"")
        )
    }

    @Test
    fun turnAnchorProbeCountsWithoutReadingMessageContent() {
        val script = requireNotNull(
            providerDiagnosticsProbeScript(WebAiService.CHATGPT, "https://chatgpt.com/c/123")
        )

        assertTrue(script.contains("data-message-author-role"))
        assertTrue(script.contains("\"data-message-id\""))
        assertTrue(!script.contains("innerText"))
        assertTrue(!script.contains("textContent"))
        assertTrue(!script.contains("innerHTML"))
        assertTrue(!script.contains("outerHTML"))
        assertTrue(
            requireNotNull(providerDiagnosticsProbeScript(WebAiService.QWEN, "https://qwen.ai/"))
                .contains("var turnCounts = [-1, -1, -1];")
        )
    }
}
