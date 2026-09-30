package ais.tee.data.tokenizer

/** Identity of one token-measurement series that is safe to compare without blending provenance. */
data class TokenMeasurementSeriesKey(
    val mode: TokenMeasurementMode,
    val backendLabel: String,
    val encodingLabel: String?,
    val providerId: String?,
    val modelName: String?
)

/** Token delta for one variant against a reference variant within one comparable measurement series. */
data class TokenArenaTokenDelta(
    val referenceVariantId: String,
    val variantId: String,
    val series: TokenMeasurementSeriesKey,
    val referenceTokens: Long,
    val tokens: Long,
    val deltaTokens: Long,
    val deltaPercent: Double?
)

/**
 * Compare prompt token counts only when the complete measurement identity matches.
 *
 * A local `o200k` result therefore never silently becomes comparable with a provider-reported count,
 * and provider exact/estimate modes remain separate even for the same backend/model. Another encoding
 * or backend also creates a distinct series. Ambiguous duplicate measurements in the same series are
 * skipped rather than choosing one arbitrarily.
 */
fun TokenArenaExperiment.tokenDeltas(referenceVariantId: String): List<TokenArenaTokenDelta> {
    require(variants.any { variant -> variant.id == referenceVariantId }) {
        "Token Arena reference variant must exist in the experiment"
    }

    val measurementsBySeries = tokenMeasurements.groupBy(TokenArenaTokenMeasurement::seriesKey)
    return variants
        .asSequence()
        .filter { variant -> variant.id != referenceVariantId }
        .flatMap { variant ->
            measurementsBySeries.asSequence().mapNotNull { (series, measurements) ->
                val referenceMatches = measurements.filter { it.variantId == referenceVariantId }
                val variantMatches = measurements.filter { it.variantId == variant.id }
                if (referenceMatches.size != 1 || variantMatches.size != 1) return@mapNotNull null

                val reference = referenceMatches.single()
                val candidate = variantMatches.single()
                val delta = safeLongDelta(candidate.tokens, reference.tokens)
                TokenArenaTokenDelta(
                    referenceVariantId = referenceVariantId,
                    variantId = variant.id,
                    series = series,
                    referenceTokens = reference.tokens,
                    tokens = candidate.tokens,
                    deltaTokens = delta,
                    deltaPercent = if (reference.tokens > 0) {
                        delta.toDouble() / reference.tokens.toDouble() * 100.0
                    } else {
                        null
                    }
                )
            }
        }
        .toList()
}

/** Derived response-side efficiency values. Null means the provider did not supply enough data. */
data class TokenArenaResponseEfficiency(
    val observation: TokenArenaResponseObservation,
    val qualityPerThousandInputTokens: Double?,
    val qualityPerUsd: Double?
)

/**
 * Derive response efficiency from provider-reported usage only.
 *
 * Local/reference prompt counts remain useful comparison baselines, but they are not substituted for
 * provider input usage because doing so would make unrelated tokenization families look equivalent.
 */
fun TokenArenaResponseObservation.efficiencyMetrics(): TokenArenaResponseEfficiency {
    val quality = qualityScore
    val providerInputTokens = usage?.inputTokens
    val providerCostUsd = usage?.costUsd

    val qualityPerThousandInputTokens = if (
        quality != null && providerInputTokens != null && providerInputTokens > 0
    ) {
        (quality * (1000.0 / providerInputTokens.toDouble())).finiteOrNull()
    } else {
        null
    }
    val qualityPerUsd = if (quality != null && providerCostUsd != null && providerCostUsd > 0.0) {
        (quality / providerCostUsd).finiteOrNull()
    } else {
        null
    }

    return TokenArenaResponseEfficiency(
        observation = this,
        qualityPerThousandInputTokens = qualityPerThousandInputTokens,
        qualityPerUsd = qualityPerUsd
    )
}

enum class TokenArenaLiveRankingMetric {
    QUALITY_PER_THOUSAND_INPUT_TOKENS,
    QUALITY_PER_USD
}

data class TokenArenaRankedObservation(
    val rank: Int,
    val metric: TokenArenaLiveRankingMetric,
    val score: Double,
    val observation: TokenArenaResponseObservation
)

/**
 * Rank only complete, successful live-provider observations that have the requested derived metric.
 * Cached, simulated, failed and partial responses cannot enter this ranking by construction.
 */
fun TokenArenaExperiment.liveEfficiencyRanking(
    metric: TokenArenaLiveRankingMetric
): List<TokenArenaRankedObservation> {
    val scored = responseObservations
        .asSequence()
        .filter { observation -> observation.canEnterLiveEfficiencyRanking }
        .mapNotNull { observation ->
            val efficiency = observation.efficiencyMetrics()
            val score = when (metric) {
                TokenArenaLiveRankingMetric.QUALITY_PER_THOUSAND_INPUT_TOKENS ->
                    efficiency.qualityPerThousandInputTokens
                TokenArenaLiveRankingMetric.QUALITY_PER_USD -> efficiency.qualityPerUsd
            } ?: return@mapNotNull null
            observation to score
        }
        .sortedWith(
            compareByDescending<Pair<TokenArenaResponseObservation, Double>> { it.second }
                .thenBy { it.first.variantId }
                .thenBy { it.first.providerId }
                .thenBy { it.first.modelName }
        )
        .toList()

    return scored.mapIndexed { index, (observation, score) ->
        TokenArenaRankedObservation(
            rank = index + 1,
            metric = metric,
            score = score,
            observation = observation
        )
    }
}

private fun TokenArenaTokenMeasurement.seriesKey(): TokenMeasurementSeriesKey =
    TokenMeasurementSeriesKey(
        mode = mode,
        backendLabel = backendLabel,
        encodingLabel = encodingLabel,
        providerId = providerId,
        modelName = modelName
    )

private fun safeLongDelta(value: Long, reference: Long): Long =
    if (value >= reference) value - reference else -(reference - value)

private fun Double.finiteOrNull(): Double? = takeIf { value -> value.isFinite() }
