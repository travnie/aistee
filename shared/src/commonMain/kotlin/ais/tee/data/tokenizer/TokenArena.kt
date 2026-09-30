package ais.tee.data.tokenizer

import ais.tee.data.model.ProviderUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a token count was obtained. Never collapse these modes into one unlabeled number. */
@Serializable
enum class TokenMeasurementMode {
    PROVIDER_EXACT,
    PROVIDER_ESTIMATE,
    LOCAL_EXACT_ENCODING,
    REFERENCE_FALLBACK
}

/** Provenance of a response used by Token Arena efficiency views. */
@Serializable
enum class ArenaResponseProvenance {
    LIVE_PROVIDER,
    CACHED_REPLAY,
    SIMULATED_FALLBACK
}

/** One exact prompt representation participating in an Arena experiment. */
@Serializable
data class TokenArenaVariant(
    val id: String,
    val label: String,
    val prompt: String
) {
    init {
        require(id.isNotBlank()) { "Token Arena variant id must not be blank" }
        require(label.isNotBlank()) { "Token Arena variant label must not be blank" }
    }

    val promptFingerprint: String
        get() = stablePromptFingerprint(prompt)

    /** Keep exact prompt text out of incidental logs, crash breadcrumbs and debugger stringification. */
    override fun toString(): String = "TokenArenaVariant(id=$id, prompt=<redacted>)"
}

/**
 * Token count for one prompt variant, with enough provenance to interpret the number honestly.
 *
 * `LOCAL_EXACT_ENCODING` is exact only for the named encoding, not universally for other models.
 * Provider-reported measurements must preserve whether the provider calls the number exact or estimated.
 * Both provider modes must name the provider and model that supplied the count.
 */
@Serializable
data class TokenArenaTokenMeasurement(
    val variantId: String,
    val promptFingerprint: String,
    val tokens: Long,
    val mode: TokenMeasurementMode,
    val backendLabel: String,
    val encodingLabel: String? = null,
    val providerId: String? = null,
    val modelName: String? = null
) {
    init {
        require(variantId.isNotBlank()) { "Token measurement variant id must not be blank" }
        require(promptFingerprint.isNotBlank()) { "Token measurement prompt fingerprint must not be blank" }
        require(tokens >= 0) { "Token count must not be negative" }
        require(backendLabel.isNotBlank()) { "Token measurement backend label must not be blank" }
        if (mode == TokenMeasurementMode.LOCAL_EXACT_ENCODING) {
            require(!encodingLabel.isNullOrBlank()) {
                "Local exact token measurements must name their encoding"
            }
        }
        if (
            mode == TokenMeasurementMode.PROVIDER_EXACT ||
            mode == TokenMeasurementMode.PROVIDER_ESTIMATE
        ) {
            require(!providerId.isNullOrBlank() && !modelName.isNullOrBlank()) {
                "Provider token measurements must name provider and model"
            }
        }
    }
}

/** Response-side metrics for one prompt variant. Provider usage remains the canonical usage model. */
@Serializable
data class TokenArenaResponseObservation(
    val variantId: String,
    val promptFingerprint: String,
    val providerId: String,
    val modelName: String,
    val provenance: ArenaResponseProvenance,
    val usage: ProviderUsage? = null,
    val latencyMs: Long? = null,
    val responseLengthChars: Int? = null,
    val qualityScore: Double? = null,
    val pricingSnapshotLabel: String? = null,
    val isError: Boolean = false,
    val isPartial: Boolean = false
) {
    init {
        require(variantId.isNotBlank()) { "Arena response variant id must not be blank" }
        require(promptFingerprint.isNotBlank()) { "Arena response prompt fingerprint must not be blank" }
        require(providerId.isNotBlank()) { "Arena response provider id must not be blank" }
        require(modelName.isNotBlank()) { "Arena response model name must not be blank" }
        require(latencyMs == null || latencyMs >= 0) { "Arena response latency must not be negative" }
        require(responseLengthChars == null || responseLengthChars >= 0) {
            "Arena response length must not be negative"
        }
        require(qualityScore == null || qualityScore.isFinite()) {
            "Arena quality score must be finite"
        }
    }

    val canEnterLiveEfficiencyRanking: Boolean
        get() = provenance == ArenaResponseProvenance.LIVE_PROVIDER && !isError && !isPartial
}

/**
 * Portable, serializable record for one reproducible Token Arena experiment.
 *
 * Construction snapshots every collection. Public collection access returns defensive snapshots so
 * callers cannot mutate validated state through JVM/Java collection casts. Prompt fingerprints bind
 * recorded results to the exact UTF-16 prompt contents. Manual value equality keeps decoded
 * experiments usable as stable state and collection keys without reintroducing data-class `copy()`.
 */
@Serializable
class TokenArenaExperiment private constructor(
    val id: String,
    val intentLabel: String,
    @SerialName("variants") private val variantSnapshot: List<TokenArenaVariant>,
    @SerialName("tokenMeasurements") private val tokenMeasurementSnapshot: List<TokenArenaTokenMeasurement>,
    @SerialName("responseObservations") private val responseObservationSnapshot: List<TokenArenaResponseObservation>
) {
    val variants: List<TokenArenaVariant>
        get() = variantSnapshot.toList()

    val tokenMeasurements: List<TokenArenaTokenMeasurement>
        get() = tokenMeasurementSnapshot.toList()

    val responseObservations: List<TokenArenaResponseObservation>
        get() = responseObservationSnapshot.toList()

    init {
        require(id.isNotBlank()) { "Token Arena experiment id must not be blank" }
        require(intentLabel.isNotBlank()) { "Token Arena intent label must not be blank" }
        require(variantSnapshot.isNotEmpty()) { "Token Arena experiment needs at least one variant" }

        val variantIds = variantSnapshot.map(TokenArenaVariant::id)
        require(variantIds.toSet().size == variantIds.size) {
            "Token Arena variant ids must be unique"
        }
        val promptFingerprints = variantSnapshot.associate { variant ->
            variant.id to variant.promptFingerprint
        }
        require(tokenMeasurementSnapshot.all { measurement ->
            promptFingerprints[measurement.variantId] == measurement.promptFingerprint
        }) {
            "Every token measurement must reference the current prompt version of a known variant"
        }
        require(responseObservationSnapshot.all { observation ->
            promptFingerprints[observation.variantId] == observation.promptFingerprint
        }) {
            "Every response observation must reference the current prompt version of a known variant"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenArenaExperiment) return false
        return id == other.id &&
            intentLabel == other.intentLabel &&
            variantSnapshot == other.variantSnapshot &&
            tokenMeasurementSnapshot == other.tokenMeasurementSnapshot &&
            responseObservationSnapshot == other.responseObservationSnapshot
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + intentLabel.hashCode()
        result = 31 * result + variantSnapshot.hashCode()
        result = 31 * result + tokenMeasurementSnapshot.hashCode()
        result = 31 * result + responseObservationSnapshot.hashCode()
        return result
    }

    /** Avoid rendering user-authored intent labels or prompt-bearing variants in debug strings. */
    override fun toString(): String =
        "TokenArenaExperiment(id=$id, variantCount=${variantSnapshot.size}, " +
            "tokenMeasurementCount=${tokenMeasurementSnapshot.size}, " +
            "responseObservationCount=${responseObservationSnapshot.size})"

    companion object {
        fun create(
            id: String,
            intentLabel: String,
            variants: List<TokenArenaVariant>,
            tokenMeasurements: List<TokenArenaTokenMeasurement> = emptyList(),
            responseObservations: List<TokenArenaResponseObservation> = emptyList()
        ): TokenArenaExperiment = TokenArenaExperiment(
            id = id,
            intentLabel = intentLabel,
            variantSnapshot = variants.toList(),
            tokenMeasurementSnapshot = tokenMeasurements.toList(),
            responseObservationSnapshot = responseObservations.toList()
        )
    }
}

/**
 * Convert an exact local [TokenCounter] into an explicitly encoding-scoped Arena measurement.
 * The recorded variant and counted prompt are one object so stale text cannot be attributed to it.
 */
fun TokenCounter.measureForArena(
    variant: TokenArenaVariant,
    backendLabel: String
): TokenArenaTokenMeasurement = TokenArenaTokenMeasurement(
    variantId = variant.id,
    promptFingerprint = variant.promptFingerprint,
    tokens = count(variant.prompt).toLong(),
    mode = TokenMeasurementMode.LOCAL_EXACT_ENCODING,
    backendLabel = backendLabel,
    encodingLabel = encodingLabel
)

/** Stable non-security fingerprint used only to detect accidental stale prompt/result associations. */
internal fun stablePromptFingerprint(prompt: String): String {
    var hash = -3750763034362895579L // FNV-1a 64-bit offset basis in signed Long form.
    prompt.forEach { codeUnit ->
        val code = codeUnit.code
        hash = hash xor (code.toLong() and 0xffL)
        hash *= 1099511628211L
        hash = hash xor ((code ushr 8).toLong() and 0xffL)
        hash *= 1099511628211L
    }
    return "${prompt.length}:${hash.toULong().toString(16).padStart(16, '0')}"
}
