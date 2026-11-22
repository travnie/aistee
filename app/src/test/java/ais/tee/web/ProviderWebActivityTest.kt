package ais.tee.web

import ais.tee.data.model.WebAiService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context

class ProviderWebActivityTest {
    @Test
    fun everyProviderWithVerifiedStableMarkupHasAnAuditableGenerationProbe() {
        val verifiedProviders = setOf(
            WebAiService.CLAUDE,
            WebAiService.CHATGPT,
            WebAiService.GEMINI,
            WebAiService.DEEPSEEK,
            WebAiService.KIMI,
            WebAiService.VIBE
        )

        WebAiService.entries.forEach { service ->
            assertTrue(
                "Unexpected generation tracking state for ${service.id}",
                providerGenerationTrackingSupported(service) == (service in verifiedProviders)
            )
        }
    }

    @Test
    fun probesUsePortableExactGenerationControls() {
        WebAiService.entries
            .flatMap { service ->
                ProviderWebRegistry.generationSelectors(service) +
                    ProviderWebRegistry.generationIdleSelectors(service)
            }
            .forEach { selector ->
                assertFalse("Broad stop selector: $selector", selector.contains("*="))
                assertFalse("Unsupported :has selector: $selector", selector.contains(":has("))
            }
    }

    @Test
    fun chatGptUsesItsStableStopButtonTestId() {
        assertTrue(
            ProviderWebRegistry.generationSelectors(WebAiService.CHATGPT)
                .contains("[data-testid=\"stop-button\"]")
        )
    }

    @Test
    fun localeIndependentGenerationControlsArePreferredWhereVerified() {
        assertTrue(
            ProviderWebRegistry.generationSelectors(WebAiService.CLAUDE)
                .first().contains("data-testid")
        )
        assertTrue(
            ProviderWebRegistry.generationSelectors(WebAiService.GEMINI)
                .first().contains("data-test-id")
        )
        assertFalse(
            ProviderWebRegistry.generationSelectors(WebAiService.DEEPSEEK)
                .first().contains("aria-label")
        )
        assertFalse(
            ProviderWebRegistry.generationSelectors(WebAiService.KIMI)
                .first().contains("aria-label")
        )
    }

    @Test
    fun vibeTrackerRejectsIdleFingerprintOnTheSameSubmitControl() {
        val activeSubmitId = "active"
        val otherSubmitId = "other"

        assertTrue(evaluateGenerationControlPredicate(activeSubmitIds = listOf(activeSubmitId)))
        assertFalse(
            evaluateGenerationControlPredicate(
                activeSubmitIds = listOf(activeSubmitId),
                idleSubmitIds = listOf(activeSubmitId)
            )
        )
        assertTrue(
            evaluateGenerationControlPredicate(
                activeSubmitIds = listOf(activeSubmitId),
                idleSubmitIds = listOf(otherSubmitId)
            )
        )

        val script = requireNotNull(
            providerGenerationActivityScript(
                service = WebAiService.VIBE,
                consumeCompletion = false
            )
        )
        assertTrue(script.contains("const hasActiveGenerationControl ="))
        assertTrue(script.contains("hasActiveGenerationControl("))
    }

    @Test
    fun vibeUsesLocaleIndependentSubmitStopIcon() {
        val activeSelectors = ProviderWebRegistry.generationSelectors(WebAiService.VIBE)
        val idleSelectors = ProviderWebRegistry.generationIdleSelectors(WebAiService.VIBE)
        val activeSelector = activeSelectors.first()
        val idleSelector = idleSelectors.first()
        assertTrue(activeSelectors.contains("[data-testid=\"stop-generating\"]"))
        assertTrue(idleSelectors.contains("[data-testid=\"send-button\"]"))
        assertTrue(activeSelector.contains("button[type=\"submit\"]"))
        assertTrue(activeSelector.endsWith("svg rect"))
        assertTrue(idleSelector.contains("path[d^=\"M12 18v4h4v-4h-4ZM16 14v4h4v-4h-4\"]"))
        assertFalse(activeSelector.contains("aria-label"))
        assertFalse(idleSelector.contains("aria-label"))
    }

    private fun evaluateGenerationControlPredicate(
        activeSubmitIds: List<String?>,
        idleSubmitIds: List<String?> = emptyList()
    ): Boolean {
        val context = Context.enter()
        return try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            val scope = context.initStandardObjects()
            val activeIds = activeSubmitIds.joinToString(prefix = "[", postfix = "]") { id ->
                id?.let { "\"$it\"" } ?: "null"
            }
            val idleIds = idleSubmitIds.joinToString(prefix = "[", postfix = "]") { id ->
                id?.let { "\"$it\"" } ?: "null"
            }
            val source = """
                var predicate = ${generationControlPredicateScript()};
                function control(submitId) {
                    return {
                        closest: function(selector) {
                            return selector === 'button[type="submit"]' ? submitId : null;
                        }
                    };
                }
                var activeControls = $activeIds.map(control);
                var idleControls = $idleIds.map(control);
                predicate(activeControls, idleControls);
            """.trimIndent()
            Context.toBoolean(context.evaluateString(scope, source, "generation-control-test", 1, null))
        } finally {
            Context.exit()
        }
    }

}
