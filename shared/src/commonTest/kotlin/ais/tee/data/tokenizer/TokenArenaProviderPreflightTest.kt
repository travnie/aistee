package ais.tee.data.tokenizer

import ais.tee.data.model.AiProvider
import ais.tee.data.model.ProviderInputBudgetPreflight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TokenArenaProviderPreflightTest {
    private companion object {
        const val GEMINI_MODEL = "gemini-test"
        const val REQUEST_TAG_A = "request-a"
        const val REQUEST_TAG_B = "request-b"
    }

    private val variant = TokenArenaVariant(
        id = "compact",
        label = "Compact",
        prompt = "Summarize this precisely.",
    )

    private fun budget(
        request: TokenArenaProviderPreflightRequest,
        provider: AiProvider,
        model: String,
        inputTokens: Int,
        inputTokenLimit: Int,
        requestContextFingerprint: String? = request.requestContextFingerprint,
    ) = ProviderInputBudgetPreflight(
        provider = provider,
        model = model,
        inputTokens = inputTokens,
        inputTokenLimit = inputTokenLimit,
        requestTag = request.requestTag,
        requestContextFingerprint = requestContextFingerprint,
    )

    @Test
    fun geminiPreflightBecomesExactProviderMeasurementWithLimit() {
        val request = variant.providerPreflightRequest(REQUEST_TAG_A)
        val measurement = request.withBudget(
            budget(request, AiProvider.GEMINI, GEMINI_MODEL, 120, 1_000)
        ).measureForArena(variant)

        assertEquals(TokenMeasurementMode.PROVIDER_EXACT, measurement.mode)
        assertEquals("Gemini countTokens", measurement.backendLabel)
        assertEquals(AiProvider.GEMINI.id, measurement.providerId)
        assertEquals(GEMINI_MODEL, measurement.modelName)
        assertEquals(
            TOKEN_ARENA_PROMPT_ONLY_CONTEXT_FINGERPRINT,
            measurement.requestContextFingerprint,
        )
        assertEquals(120L, measurement.tokens)
        assertEquals(1_000L, measurement.inputTokenLimit)
        assertEquals(880L, measurement.remainingInputTokens)
        assertEquals(TokenArenaProviderInputLimitStatus.FITS, measurement.providerInputLimitStatus)
    }

    @Test
    fun claudePreflightStaysExplicitlyEstimated() {
        val request = variant.providerPreflightRequest(REQUEST_TAG_A)
        val measurement = request.withBudget(
            budget(request, AiProvider.CLAUDE, "claude-test", 1_200, 1_000)
        ).measureForArena(variant)

        assertEquals(TokenMeasurementMode.PROVIDER_ESTIMATE, measurement.mode)
        assertEquals("Claude messages/count_tokens", measurement.backendLabel)
        assertEquals(-200L, measurement.remainingInputTokens)
        assertEquals(TokenArenaProviderInputLimitStatus.EXCEEDED, measurement.providerInputLimitStatus)
    }

    @Test
    fun localMeasurementsDoNotInventAProviderLimit() {
        val counter = object : TokenCounter {
            override val encodingLabel = "test"
            override fun count(text: String) = text.length
        }

        val measurement = counter.measureForArena(variant, "local")

        assertNull(measurement.inputTokenLimit)
        assertNull(measurement.remainingInputTokens)
        assertEquals(
            TokenArenaProviderInputLimitStatus.NOT_APPLICABLE,
            measurement.providerInputLimitStatus,
        )
    }

    @Test
    fun nonProviderMeasurementsRejectProviderInputLimits() {
        assertFailsWith<IllegalArgumentException> {
            TokenArenaTokenMeasurement(
                variantId = variant.id,
                promptFingerprint = variant.promptFingerprint,
                tokens = 10,
                mode = TokenMeasurementMode.LOCAL_EXACT_ENCODING,
                backendLabel = "local",
                encodingLabel = "test",
                inputTokenLimit = 100,
            )
        }
    }

    @Test
    fun providerMeasurementWithoutRecordedLimitIsUnavailableNotNotApplicable() {
        val measurement = TokenArenaTokenMeasurement(
            variantId = variant.id,
            promptFingerprint = variant.promptFingerprint,
            tokens = 10,
            mode = TokenMeasurementMode.PROVIDER_EXACT,
            backendLabel = "provider",
            providerId = AiProvider.GEMINI.id,
            modelName = GEMINI_MODEL,
        )

        assertEquals(
            TokenArenaProviderInputLimitStatus.UNAVAILABLE,
            measurement.providerInputLimitStatus,
        )
    }

    @Test
    fun editedVariantRejectsInFlightProviderResult() {
        val request = variant.providerPreflightRequest(REQUEST_TAG_A)
        val result = request.withBudget(
            budget(request, AiProvider.GEMINI, GEMINI_MODEL, 120, 1_000)
        )
        val edited = variant.copy(prompt = "Edited after count started.")

        assertFailsWith<IllegalArgumentException> {
            result.measureForArena(edited)
        }
    }

    @Test
    fun providerBudgetMustCarryTheCountedRequestContext() {
        val request = variant.providerPreflightRequest(REQUEST_TAG_A)

        assertFailsWith<IllegalArgumentException> {
            request.withBudget(
                budget(
                    request = request,
                    provider = AiProvider.GEMINI,
                    model = GEMINI_MODEL,
                    inputTokens = 50,
                    inputTokenLimit = 1_000,
                    requestContextFingerprint = null,
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request.withBudget(
                budget(
                    request = request,
                    provider = AiProvider.GEMINI,
                    model = GEMINI_MODEL,
                    inputTokens = 50,
                    inputTokenLimit = 1_000,
                    requestContextFingerprint = "native-chat:v1",
                )
            )
        }
    }

    @Test
    fun samePromptConcurrentCountsRequireDifferentCorrelationTags() {
        val first = variant.providerPreflightRequest(REQUEST_TAG_A)
        val second = variant.providerPreflightRequest(REQUEST_TAG_B)

        assertFailsWith<IllegalArgumentException> {
            first.withBudget(
                budget(second, AiProvider.GEMINI, GEMINI_MODEL, 50, 1_000)
            )
        }
    }

    @Test
    fun requestKeepsExactWhitespaceAndRejectsBlankCorrelationTag() {
        val spaced = variant.copy(prompt = "  keep exact whitespace  ")
        val request = spaced.providerPreflightRequest(REQUEST_TAG_A)

        assertEquals(spaced.prompt, request.prompt)
        assertEquals(spaced.promptFingerprint, request.promptFingerprint)
        assertFailsWith<IllegalArgumentException> {
            spaced.providerPreflightRequest("   ")
        }
    }

    @Test
    fun requestSnapshotRedactsPromptAndCorrelationFromDebugString() {
        val request = variant.providerPreflightRequest(REQUEST_TAG_A)
        val debug = request.toString()

        assertFalse(debug.contains(variant.prompt))
        assertFalse(debug.contains(REQUEST_TAG_A))
    }

    @Test
    fun unsupportedProviderPreflightIsRejectedInsteadOfMislabelled() {
        val request = variant.providerPreflightRequest(REQUEST_TAG_A)

        assertFailsWith<IllegalArgumentException> {
            request.withBudget(
                budget(request, AiProvider.CHATGPT, "gpt-test", 10, 100)
            ).measureForArena(variant)
        }
    }
}
