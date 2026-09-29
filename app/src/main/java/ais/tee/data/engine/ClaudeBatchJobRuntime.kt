package ais.tee.data.engine

import android.content.Context
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobState
import ais.tee.data.model.isClaudeBatch
import ais.tee.data.model.needsPolling
import ais.tee.data.preferences.AsyncProviderJobStore
import ais.tee.data.security.ApiKeyStore

internal const val CLAUDE_BATCH_WORK_NAME_PREFIX = "claude-batch-job:"
internal const val CLAUDE_BATCH_MAX_POLL_ATTEMPTS = 16

internal suspend fun refreshClaudeBatchJob(
    context: Context,
    jobId: String,
    service: AiChatService = AiChatService(),
): AsyncJobRefreshResult? {
    val appContext = context.applicationContext
    val store = AsyncProviderJobStore(appContext.noBackupFilesDir)
    val job = store.load().jobs.firstOrNull { it.id == jobId } ?: return null
    if (!job.isClaudeBatch || !job.needsPolling) return AsyncJobRefreshResult(job, shouldRetry = false)

    val apiKey = ApiKeyStore(appContext).load().claudeKey.trim()
    if (apiKey.isEmpty()) {
        return AsyncJobRefreshResult(
            markBatchJobApiKeyUnavailable(
                store = store,
                job = job,
                message = "Claude API key is unavailable. Add it again to refresh this job.",
            ),
            shouldRetry = false,
        )
    }

    val snapshot = service.retrieveClaudeBatch(job.remoteId, apiKey)
    val result = if (snapshot.ended) service.fetchClaudeBatchResult(job.remoteId, apiKey) else null
    return persistClaudeBatchState(appContext, job, snapshot, result)
}

internal suspend fun cancelClaudeBatchJob(
    context: Context,
    jobId: String,
    service: AiChatService = AiChatService(),
): AsyncProviderJob? {
    val appContext = context.applicationContext
    val store = AsyncProviderJobStore(appContext.noBackupFilesDir)
    val job = store.load().jobs.firstOrNull { it.id == jobId } ?: return null
    if (!job.isClaudeBatch || job.state.isTerminal) return job

    val apiKey = ApiKeyStore(appContext).load().claudeKey.trim()
    if (apiKey.isEmpty()) {
        return markBatchJobApiKeyUnavailable(
            store = store,
            job = job,
            message = "Claude API key is unavailable. Add it again before cancelling.",
        )
    }

    val snapshot = service.cancelClaudeBatch(job.remoteId, apiKey)
    val result = if (snapshot.ended) service.fetchClaudeBatchResult(job.remoteId, apiKey) else null
    val persisted = persistClaudeBatchState(appContext, job, snapshot, result)
    if (persisted.shouldRetry) {
        BatchJobWork.enqueue(
            context = appContext,
            jobId = job.id,
            workNamePrefix = CLAUDE_BATCH_WORK_NAME_PREFIX,
            maxAutomaticPollAttempts = CLAUDE_BATCH_MAX_POLL_ATTEMPTS,
        )
    }
    return persisted.job
}

internal fun persistClaudeBatchState(
    context: Context,
    job: AsyncProviderJob,
    snapshot: ClaudeBatchSnapshot,
    result: ClaudeBatchResult?,
): AsyncJobRefreshResult =
    persistBatchResult(
        context = context,
        job = job,
        snapshot = if (result == null) {
            BatchResultSnapshot(
                state = AsyncProviderJobState.RUNNING,
                pendingMessage = if (snapshot.cancelling) "Cancelling…" else null,
                resultReady = false,
            )
        } else {
            BatchResultSnapshot(
                state = result.state,
                model = result.model,
                outputText = result.outputText,
                errorMessage = result.errorMessage,
            )
        },
        resultLabel = "Claude batch",
        savedDescription = "Saved by Aistee from a completed Message Batch.",
        emptyOutputMessage = "Claude finished the batch without text output.",
    )
