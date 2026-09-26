package ais.tee.data.model

import kotlinx.serialization.Serializable

const val ASYNC_PROVIDER_JOB_ARCHIVE_VERSION = 1

@Serializable
enum class AsyncProviderJobKind {
    BACKGROUND_RESPONSE,
    BATCH,
}

@Serializable
enum class AsyncProviderJobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    INCOMPLETE;

    val isTerminal: Boolean
        get() = this !in setOf(QUEUED, RUNNING)
}

@Serializable
data class AsyncProviderJob(
    val id: String,
    val provider: AiProvider,
    val kind: AsyncProviderJobKind,
    val remoteId: String,
    val model: String,
    val projectId: String = DEFAULT_PROJECT_ID,
    val state: AsyncProviderJobState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
    val resultAssetId: String? = null,
    val errorMessage: String? = null,
) {
    override fun toString(): String =
        "AsyncProviderJob(id=<redacted>, provider=${provider.id}, kind=$kind, remoteId=<redacted>, " +
            "model=$model, projectId=<redacted>, state=$state, resultAssetId=" +
            if (resultAssetId == null) "null)" else "<redacted>)"
}

@Serializable
data class AsyncProviderJobArchive(
    val version: Int = ASYNC_PROVIDER_JOB_ARCHIVE_VERSION,
    val jobs: List<AsyncProviderJob> = emptyList(),
) {
    override fun toString(): String =
        "AsyncProviderJobArchive(version=$version, jobs=${jobs.size})"
}

fun AsyncProviderJobArchive.normalizedAsyncProviderJobs(): AsyncProviderJobArchive? {
    if (version != ASYNC_PROVIDER_JOB_ARCHIVE_VERSION) return null
    val seen = mutableSetOf<String>()
    val normalized = jobs.mapNotNull { job ->
        val id = job.id.trim()
        val remoteId = job.remoteId.trim()
        val model = job.model.trim()
        val projectId = job.projectId.trim()
        if (
            id.isEmpty() ||
            remoteId.isEmpty() ||
            model.isEmpty() ||
            projectId.isEmpty() ||
            job.provider == AiProvider.ALL ||
            !seen.add(id)
        ) {
            return@mapNotNull null
        }
        job.copy(
            id = id,
            remoteId = remoteId,
            model = model,
            projectId = projectId,
            resultAssetId = job.resultAssetId?.trim()?.takeIf(String::isNotEmpty),
            errorMessage = job.errorMessage?.trim()?.takeIf(String::isNotEmpty)?.take(500),
        )
    }
    return copy(jobs = normalized)
}
