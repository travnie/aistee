package ais.tee.data.tokenizer

import ais.tee.data.model.ProviderUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenArenaMetricsTest {
    @Test
    fun tokenDeltasCompareOnlyIdenticalMeasurementSeries() {
        val reference = variant(REFERENCE_ID, "reference")
        val candidate = variant(CANDIDATE_ID, "candidate")
        val experiment = TokenArenaExperiment.create(
            id = ARENA_ID,
            intentLabel = "Compare prompt representations",
            variants = listOf(reference, candidate),
            tokenMeasurements = listOf(
                localMeasurement(reference, 100, O200K_ENCODING),
                localMeasurement(candidate, 80, O200K_ENCODING),
                localMeasurement(candidate, 55, "other-encoding"),
                providerMeasurement(reference, 120, TokenMeasurementMode.PROVIDER_EXACT),
                providerMeasurement(candidate, 90, TokenMeasurementMode.PROVIDER_EXACT),
                providerMeasurement(
                    candidate,
                    70,
                    TokenMeasurementMode.PROVIDER_EXACT,
                    requestContextFingerprint = OTHER_CONTEXT,
                ),
                providerMeasurement(reference, 118, TokenMeasurementMode.PROVIDER_ESTIMATE),
                providerMeasurement(candidate, 92, TokenMeasurementMode.PROVIDER_ESTIMATE)
            )
        )

        val deltas = experiment.tokenDeltas(REFERENCE_ID)

        assertEquals(3, deltas.size)
        val local = deltas.single { it.series.mode == TokenMeasurementMode.LOCAL_EXACT_ENCODING }
        assertEquals(-20L, local.deltaTokens)
        assertEquals(-20.0, local.deltaPercent)
        val providerExact = deltas.single {
            it.series.mode == TokenMeasurementMode.PROVIDER_EXACT
        }
        assertEquals(-30L, providerExact.deltaTokens)
        assertEquals(-25.0, providerExact.deltaPercent)
        val providerEstimate = deltas.single {
            it.series.mode == TokenMeasurementMode.PROVIDER_ESTIMATE
        }
        assertEquals(-26L, providerEstimate.deltaTokens)
        assertEquals((-26.0 / 118.0) * 100.0, providerEstimate.deltaPercent)
        assertEquals(providerExact.series.providerId, providerEstimate.series.providerId)
        assertEquals(providerExact.series.modelName, providerEstimate.series.modelName)
        assertTrue(deltas.none { it.series.encodingLabel == "other-encoding" })
        assertTrue(deltas.none { it.series.requestContextFingerprint == OTHER_CONTEXT })
    }

    @Test
    fun providerCountsWithDifferentRequestContextDoNotShareADeltaSeries() {
        val reference = variant(REFERENCE_ID, "reference")
        val candidate = variant(CANDIDATE_ID, "candidate")
        val experiment = TokenArenaExperiment.create(
            id = ARENA_ID,
            intentLabel = "Different provider context",
            variants = listOf(reference, candidate),
            tokenMeasurements = listOf(
                providerMeasurement(
                    reference,
                    120,
                    TokenMeasurementMode.PROVIDER_EXACT,
                    requestContextFingerprint = "prompt-only:v1",
                ),
                providerMeasurement(
                    candidate,
                    90,
                    TokenMeasurementMode.PROVIDER_EXACT,
                    requestContextFingerprint = "system-profile:v1",
                ),
            ),
        )

        assertTrue(experiment.tokenDeltas(REFERENCE_ID).isEmpty())
    }

    @Test
    fun legacyProviderCountsWithoutKnownContextAreExcludedFromDeltas() {
        val reference = variant(REFERENCE_ID, "reference")
        val candidate = variant(CANDIDATE_ID, "candidate")
        val experiment = TokenArenaExperiment.create(
            id = ARENA_ID,
            intentLabel = "Unknown provider context",
            variants = listOf(reference, candidate),
            tokenMeasurements = listOf(
                providerMeasurement(
                    reference,
                    120,
                    TokenMeasurementMode.PROVIDER_EXACT,
                    requestContextFingerprint = null,
                ),
                providerMeasurement(
                    candidate,
                    90,
                    TokenMeasurementMode.PROVIDER_EXACT,
                    requestContextFingerprint = null,
                ),
            ),
        )

        assertTrue(experiment.tokenDeltas(REFERENCE_ID).isEmpty())
    }

    @Test
    fun zeroReferenceTokensDoNotProduceInfinitePercentageDelta() {
        val reference = variant(REFERENCE_ID, "")
        val candidate = variant(CANDIDATE_ID, "x")
        val experiment = TokenArenaExperiment.create(
            id = ARENA_ID,
            intentLabel = "Zero baseline",
            variants = listOf(reference, candidate),
            tokenMeasurements = listOf(
                localMeasurement(reference, 0, O200K_ENCODING),
                localMeasurement(candidate, 1, O200K_ENCODING)
            )
        )

        val delta = experiment.tokenDeltas(REFERENCE_ID).single()
        assertEquals(1L, delta.deltaTokens)
        assertNull(delta.deltaPercent)
    }

    @Test
    fun responseEfficiencyUsesProviderReportedUsageOnly() {
        val observation = observation(
            usage = ProviderUsage(inputTokens = 500, costUsd = 0.01),
            qualityScore = 0.8
        )

        val efficiency = observation.efficiencyMetrics()

        assertEquals(1.6, efficiency.qualityPerThousandInputTokens)
        assertEquals(80.0, efficiency.qualityPerUsd)
    }

    @Test
    fun tokenEfficiencyAvoidsIntermediateOverflow() {
        val efficiency = observation(
            usage = ProviderUsage(inputTokens = 1_000),
            qualityScore = Double.MAX_VALUE
        ).efficiencyMetrics()

        assertEquals(Double.MAX_VALUE, efficiency.qualityPerThousandInputTokens)
    }

    @Test
    fun missingProviderUsageDoesNotBorrowLocalTokenMeasurement() {
        val prompt = variant(REFERENCE_ID, PROMPT_TEXT)
        val observation = observation(qualityScore = 1.0)
        val experiment = TokenArenaExperiment.create(
            id = ARENA_ID,
            intentLabel = "No provider usage",
            variants = listOf(prompt),
            tokenMeasurements = listOf(localMeasurement(prompt, 20, O200K_ENCODING)),
            responseObservations = listOf(observation)
        )

        assertNull(observation.efficiencyMetrics().qualityPerThousandInputTokens)
        assertTrue(
            experiment.liveEfficiencyRanking(
                TokenArenaLiveRankingMetric.QUALITY_PER_THOUSAND_INPUT_TOKENS
            ).isEmpty()
        )
    }

    @Test
    fun liveRankingExcludesCachedSimulatedPartialAndFailedResponses() {
        val prompt = variant(REFERENCE_ID, PROMPT_TEXT)
        val liveBetter = observation(
            variant = prompt,
            qualityScore = 0.9,
            usage = ProviderUsage(inputTokens = 300, costUsd = 0.03)
        )
        val liveLower = observation(
            variant = prompt,
            providerId = "provider-b",
            qualityScore = 0.7,
            usage = ProviderUsage(inputTokens = 500, costUsd = 0.02)
        )
        val excluded = listOf(
            observation(
                variant = prompt,
                providerId = "cached",
                provenance = ArenaResponseProvenance.CACHED_REPLAY,
                qualityScore = 10.0,
                usage = ProviderUsage(inputTokens = 1, costUsd = 0.0001)
            ),
            observation(
                variant = prompt,
                providerId = "simulated",
                provenance = ArenaResponseProvenance.SIMULATED_FALLBACK,
                qualityScore = 10.0,
                usage = ProviderUsage(inputTokens = 1, costUsd = 0.0001)
            ),
            observation(
                variant = prompt,
                providerId = "partial",
                isPartial = true,
                qualityScore = 10.0,
                usage = ProviderUsage(inputTokens = 1, costUsd = 0.0001)
            ),
            observation(
                variant = prompt,
                providerId = "failed",
                isError = true,
                qualityScore = 10.0,
                usage = ProviderUsage(inputTokens = 1, costUsd = 0.0001)
            )
        )
        val experiment = TokenArenaExperiment.create(
            id = ARENA_ID,
            intentLabel = "Live ranking",
            variants = listOf(prompt),
            responseObservations = listOf(liveBetter, liveLower) + excluded
        )

        val ranking = experiment.liveEfficiencyRanking(
            TokenArenaLiveRankingMetric.QUALITY_PER_THOUSAND_INPUT_TOKENS
        )

        assertEquals(listOf(liveBetter, liveLower), ranking.map { it.observation })
        assertEquals(listOf(1, 2), ranking.map { it.rank })
    }

    @Test
    fun zeroCostDoesNotCreateInfiniteQualityPerUsd() {
        val efficiency = observation(
            qualityScore = 1.0,
            usage = ProviderUsage(inputTokens = 10, costUsd = 0.0)
        ).efficiencyMetrics()

        assertNull(efficiency.qualityPerUsd)
    }

    private fun variant(id: String, prompt: String) = TokenArenaVariant(
        id = id,
        label = id,
        prompt = prompt
    )

    private fun localMeasurement(
        variant: TokenArenaVariant,
        tokens: Long,
        encoding: String
    ) = TokenArenaTokenMeasurement(
        variantId = variant.id,
        promptFingerprint = variant.promptFingerprint,
        tokens = tokens,
        mode = TokenMeasurementMode.LOCAL_EXACT_ENCODING,
        backendLabel = "local-$encoding",
        encodingLabel = encoding
    )

    private fun providerMeasurement(
        variant: TokenArenaVariant,
        tokens: Long,
        mode: TokenMeasurementMode,
        requestContextFingerprint: String? = PROMPT_ONLY_CONTEXT,
    ) = TokenArenaTokenMeasurement(
        variantId = variant.id,
        promptFingerprint = variant.promptFingerprint,
        tokens = tokens,
        mode = mode,
        backendLabel = PROVIDER_BACKEND,
        providerId = PROVIDER_ID,
        modelName = MODEL_NAME,
        requestContextFingerprint = requestContextFingerprint,
    )

    private fun observation(
        variant: TokenArenaVariant = variant(REFERENCE_ID, PROMPT_TEXT),
        providerId: String = PROVIDER_ID,
        provenance: ArenaResponseProvenance = ArenaResponseProvenance.LIVE_PROVIDER,
        usage: ProviderUsage? = null,
        qualityScore: Double? = null,
        isError: Boolean = false,
        isPartial: Boolean = false
    ) = TokenArenaResponseObservation(
        variantId = variant.id,
        promptFingerprint = variant.promptFingerprint,
        providerId = providerId,
        modelName = MODEL_NAME,
        provenance = provenance,
        usage = usage,
        qualityScore = qualityScore,
        isError = isError,
        isPartial = isPartial
    )

    companion object {
        private const val ARENA_ID = "arena"
        private const val REFERENCE_ID = "reference"
        private const val CANDIDATE_ID = "candidate"
        private const val PROVIDER_ID = "provider"
        private const val MODEL_NAME = "model"
        private const val PROVIDER_BACKEND = "provider-count-api"
        private const val PROMPT_ONLY_CONTEXT = "prompt-only:v1"
        private const val OTHER_CONTEXT = "system:other"
        private const val O200K_ENCODING = "o200k"
        private const val PROMPT_TEXT = "prompt"
    }
}
