package ais.tee.data.tokenizer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class TokenArenaTest {
    private object FakeCounter : TokenCounter {
        override val encodingLabel: String = "fake-encoding"
        override fun count(text: String): Int = text.length
    }

    @Test
    fun localMeasurementStaysExplicitlyEncodingScopedAndBoundToVariantPrompt() {
        val variant = TokenArenaVariant(
            id = SHORT_VARIANT_ID,
            label = "Short",
            prompt = PROMPT_HELLO
        )
        val measurement = FakeCounter.measureForArena(
            variant = variant,
            backendLabel = BACKEND_LOCAL
        )

        assertEquals(SHORT_VARIANT_ID, measurement.variantId)
        assertEquals(variant.promptFingerprint, measurement.promptFingerprint)
        assertEquals(5L, measurement.tokens)
        assertEquals(TokenMeasurementMode.LOCAL_EXACT_ENCODING, measurement.mode)
        assertEquals(BACKEND_LOCAL, measurement.backendLabel)
        assertEquals("fake-encoding", measurement.encodingLabel)
        assertEquals(null, measurement.providerId)
        assertEquals(null, measurement.modelName)
    }

    @Test
    fun providerMeasurementsRequireProviderAndModel() {
        listOf(
            TokenMeasurementMode.PROVIDER_EXACT,
            TokenMeasurementMode.PROVIDER_ESTIMATE,
        ).forEach { mode ->
            assertFailsWith<IllegalArgumentException> {
                TokenArenaTokenMeasurement(
                    variantId = SHORT_VARIANT_ID,
                    promptFingerprint = stablePromptFingerprint(PROMPT_HELLO),
                    tokens = 10,
                    mode = mode,
                    backendLabel = "provider-api"
                )
            }
        }
    }

    @Test
    fun experimentRejectsDuplicateUnknownAndStaleVariantReferences() {
        assertFailsWith<IllegalArgumentException> {
            TokenArenaExperiment.create(
                id = "duplicate",
                intentLabel = INTENT_LABEL,
                variants = listOf(
                    TokenArenaVariant(VARIANT_ID, LABEL_A, PROMPT_FIRST),
                    TokenArenaVariant(VARIANT_ID, "Again", "second")
                )
            )
        }

        val original = TokenArenaVariant(VARIANT_ID, LABEL_A, PROMPT_FIRST)
        val measurement = FakeCounter.measureForArena(original, BACKEND_LOCAL)
        assertFailsWith<IllegalArgumentException> {
            TokenArenaExperiment.create(
                id = "unknown-ref",
                intentLabel = INTENT_LABEL,
                variants = listOf(original),
                tokenMeasurements = listOf(
                    TokenArenaTokenMeasurement(
                        variantId = "missing",
                        promptFingerprint = original.promptFingerprint,
                        tokens = 3,
                        mode = TokenMeasurementMode.REFERENCE_FALLBACK,
                        backendLabel = "estimate"
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TokenArenaExperiment.create(
                id = "stale-prompt",
                intentLabel = INTENT_LABEL,
                variants = listOf(original.copy(prompt = "edited")),
                tokenMeasurements = listOf(measurement)
            )
        }
    }

    @Test
    fun experimentSnapshotsCallerAndReturnsDefensiveCollections() {
        val variant = TokenArenaVariant(VARIANT_ID, LABEL_A, PROMPT_FIRST)
        val variants = mutableListOf(variant)
        val measurements = mutableListOf(FakeCounter.measureForArena(variant, BACKEND_LOCAL))
        val experiment = TokenArenaExperiment.create(
            id = "snapshot",
            intentLabel = INTENT_LABEL,
            variants = variants,
            tokenMeasurements = measurements
        )

        variants.clear()
        measurements.clear()

        val exposedVariants = experiment.variants
        val exposedMeasurements = experiment.tokenMeasurements
        assertNotSame(exposedVariants, experiment.variants)
        assertNotSame(exposedMeasurements, experiment.tokenMeasurements)
        assertEquals(listOf(variant), experiment.variants)
        assertEquals(1, experiment.tokenMeasurements.size)
    }

    @Test
    fun experimentSerializationRoundTripPreservesValueSemantics() {
        val variant = TokenArenaVariant(VARIANT_ID, LABEL_A, PROMPT_HELLO)
        val experiment = TokenArenaExperiment.create(
            id = "round-trip",
            intentLabel = INTENT_LABEL,
            variants = listOf(variant),
            tokenMeasurements = listOf(FakeCounter.measureForArena(variant, BACKEND_LOCAL))
        )

        val json = Json.encodeToString(experiment)
        val decoded = Json.decodeFromString<TokenArenaExperiment>(json)

        assertEquals(experiment, decoded)
        assertEquals(experiment.hashCode(), decoded.hashCode())
        assertTrue(setOf(experiment).contains(decoded))
    }

    @Test
    fun promptFingerprintPreservesDistinctUnpairedUtf16Surrogates() {
        val first = charArrayOf(0xD800.toChar()).concatToString()
        val second = charArrayOf(0xD801.toChar()).concatToString()

        assertNotEquals(first, second)
        assertNotEquals(stablePromptFingerprint(first), stablePromptFingerprint(second))
    }

    @Test
    fun onlyCompleteSuccessfulLiveResponsesEnterLiveEfficiencyRanking() {
        val variant = TokenArenaVariant(VARIANT_ID, LABEL_A, "prompt")
        fun observation(
            provenance: ArenaResponseProvenance,
            isError: Boolean = false,
            isPartial: Boolean = false
        ) = TokenArenaResponseObservation(
            variantId = variant.id,
            promptFingerprint = variant.promptFingerprint,
            providerId = PROVIDER_ID,
            modelName = MODEL_NAME,
            provenance = provenance,
            isError = isError,
            isPartial = isPartial
        )

        assertTrue(observation(ArenaResponseProvenance.LIVE_PROVIDER).canEnterLiveEfficiencyRanking)
        assertFalse(
            observation(
                ArenaResponseProvenance.LIVE_PROVIDER,
                isError = true
            ).canEnterLiveEfficiencyRanking
        )
        assertFalse(
            observation(
                ArenaResponseProvenance.LIVE_PROVIDER,
                isPartial = true
            ).canEnterLiveEfficiencyRanking
        )
        assertFalse(observation(ArenaResponseProvenance.CACHED_REPLAY).canEnterLiveEfficiencyRanking)
        assertFalse(observation(ArenaResponseProvenance.SIMULATED_FALLBACK).canEnterLiveEfficiencyRanking)
    }

    private companion object {
        const val VARIANT_ID = "a"
        const val SHORT_VARIANT_ID = "short"
        const val LABEL_A = "A"
        const val INTENT_LABEL = "same intent"
        const val PROMPT_FIRST = "first"
        const val PROMPT_HELLO = "hello"
        const val BACKEND_LOCAL = "fake-local"
        const val PROVIDER_ID = "provider"
        const val MODEL_NAME = "model"
    }
}
