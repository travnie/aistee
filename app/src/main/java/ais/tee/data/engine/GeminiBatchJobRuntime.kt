package ais.tee.data.engine

import android.content.Context
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.isGeminiBatch
import ais.tee.data.model.needsPolling
import ais.tee.data.preferences.AsyncProviderJobStore
import ais.tee.data.security.ApiKeyStore
import kotlinx.coroutines.CancellationException

internal const val GEMINI_BATCH_WORK_NAME_PREFIX = "gemini-batch-job:"
internal const val GEMINI_BATCH_MAX_POLL_ATTEMPTS = 18

internal suspend fun refreshGeminiBatchJob(
    context: Context,
    jobId: String,
    service: AiChatService = AiChatService(),
): AsyncJobRefreshResult? {
    val appContext = context.applicationContext
    val store = AsyncProviderJobStore(appContext.noBackupFilesDir)
    val job = store.load().jobs.firstOrNull { it.id == jobId } ?: return null
    if (!job.isGeminiBatch || !job.needsPolling) return AsyncJobRefreshResult(job, shouldRetry = false)

    val apiKey = ApiKeyStore(appContext).load().geminiKey.trim()
    if (apiKey.isEmpty()) {
        return AsyncJobRefreshResult(
            markBatchJobApiKeyUnavailable(
                store = store,
                job = job,
                message = "Gemini API key is unavailable. Add it again to refresh this job.",
            ),
            shouldRetry = false,
        )
    }

    val snapshot = service.retrieveGeminiBatch(job.remoteId, apiKey)
    return persistGeminiBatchState(appContext, job, snapshot)
}

internal suspend fun cancelGeminiBatchJob(
    context: Context,
    jobId: String,
    service: AiChatService = AiChatService(),
): AsyncProviderJob? {
    val appContext = context.applicationContext
    val store = AsyncProviderJobStore(appContext.noBackupFilesDir)
    val job = store.load().jobs.firstOrNull { it.id == jobId } ?: return null
    if (!job.isGeminiBatch || job.state.isTerminal) return job

    val apiKey = ApiKeyStore(appContext).load().geminiKey.trim()
    if (apiKey.isEmpty()) {
        return markBatchJobApiKeyUnavailable(
            store = store,
            job = job,
            message = "Gemini API key is unavailable. Add it again before cancelling.",
        )
    }

    val cancellingJob = store.update(job.id) {
        it.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = "Cancelling…",
        )
    } ?: job

    val cancelAccepted = try {
        service.cancelGeminiBatch(job.remoteId, apiKey)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    // Reconcile provider state even when :cancel races a completion or the cancel request itself fails.
    // A just-completed result is captured instead of leaving a stale RUNNING record behind.
    val snapshot = service.retrieveGeminiBatch(job.remoteId, apiKey)
    val persisted = persistGeminiBatchState(
        context = appContext,
        job = cancellingJob,
        snapshot = snapshot,
        pendingMessage = when {
            snapshot.state.isTerminal -> null
            cancelAccepted -> "Cancelling…"
            else -> "Cancel request failed; job is still running."
        },
    )
    if (persisted.shouldRetry) {
        BatchJobWork.enqueue(
            context = appContext,
            jobId = job.id,
            workNamePrefix = GEMINI_BATCH_WORK_NAME_PREFIX,
            maxAutomaticPollAttempts = GEMINI_BATCH_MAX_POLL_ATTEMPTS,
        )
    }
    return persisted.job
}

internal fun persistGeminiBatchState(
    context: Context,
    job: AsyncProviderJob,
    snapshot: GeminiBatchSnapshot,
    pendingMessage: String? = null,
): AsyncJobRefreshResult =
    persistBatchResult(
        context = context,
        job = job,
        snapshot = BatchResultSnapshot(
            state = snapshot.state,
            outputText = snapshot.outputText,
            errorMessage = snapshot.errorMessage,
            pendingMessage = pendingMessage,
        ),
        resultLabel = "Gemini batch",
        savedDescription = "Saved by Aistee from a completed Gemini Batch job.",
        emptyOutputMessage = "Gemini finished the batch without text output.",
    )
