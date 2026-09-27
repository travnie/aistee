package ais.tee.data.engine

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobState
import ais.tee.data.model.isClaudeBatch
import ais.tee.data.model.needsPolling
import ais.tee.data.preferences.AsyncProviderJobStore
import ais.tee.data.preferences.ProjectLibraryStore
import ais.tee.data.security.ApiKeyStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

private const val INPUT_JOB_ID = "job_id"
private const val WORK_NAME_PREFIX = "claude-batch-job:"
// Exponential from 1 minute, capped by WorkManager at 5 hours: about a day of polling,
// which covers the 24-hour batch window.
private const val MAX_AUTOMATIC_POLL_ATTEMPTS = 16

internal object ClaudeBatchJobWork {
    fun enqueue(context: Context, jobId: String) {
        if (jobId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<ClaudeBatchJobWorker>()
            .setInputData(Data.Builder().putString(INPUT_JOB_ID, jobId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, jobId: String) {
        if (jobId.isBlank()) return
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(jobId))
    }

    private fun workName(jobId: String): String = WORK_NAME_PREFIX + jobId
}

class ClaudeBatchJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(INPUT_JOB_ID)?.trim().orEmpty()
        if (jobId.isEmpty()) return Result.success()
        return try {
            val refreshed = refreshClaudeBatchJob(applicationContext, jobId) ?: return Result.success()
            when {
                !refreshed.shouldRetry -> Result.success()
                runAttemptCount < MAX_AUTOMATIC_POLL_ATTEMPTS -> Result.retry()
                else -> pausePolling(jobId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < MAX_AUTOMATIC_POLL_ATTEMPTS) Result.retry() else pausePolling(jobId)
        }
    }

    /** Out of retries, whether still running or failing: say so on the job instead of going quiet. */
    private fun pausePolling(jobId: String): Result {
        AsyncProviderJobStore(applicationContext.noBackupFilesDir).update(jobId) { job ->
            job.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                errorMessage = "Automatic polling paused. Open Jobs and refresh manually.",
            )
        }
        return Result.success()
    }
}

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
        val updated = store.update(job.id) {
            it.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                errorMessage = "Claude API key is unavailable. Add it again to refresh this job.",
            )
        } ?: job
        return AsyncJobRefreshResult(updated, shouldRetry = false)
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
        return store.update(job.id) {
            it.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                errorMessage = "Claude API key is unavailable. Add it again before cancelling.",
            )
        }
    }
    // Cancelling is asynchronous on Anthropic's side; polling picks up the final state.
    val snapshot = service.cancelClaudeBatch(job.remoteId, apiKey)
    val result = if (snapshot.ended) service.fetchClaudeBatchResult(job.remoteId, apiKey) else null
    val persisted = persistClaudeBatchState(appContext, job, snapshot, result)
    if (persisted.shouldRetry) ClaudeBatchJobWork.enqueue(appContext, job.id)
    return persisted.job
}

/** Stores the latest batch state; an ended batch's text result is saved to Project Library once. */
internal fun persistClaudeBatchState(
    context: Context,
    job: AsyncProviderJob,
    snapshot: ClaudeBatchSnapshot,
    result: ClaudeBatchResult?,
): AsyncJobRefreshResult {
    val store = AsyncProviderJobStore(context.noBackupFilesDir)
    var outputMissing = false
    val updated = store.update(job.id) { persisted ->
        if (!persisted.needsPolling) return@update persisted
        val now = System.currentTimeMillis()
        if (result == null) {
            return@update persisted.copy(
                state = AsyncProviderJobState.RUNNING,
                updatedAtEpochMs = now,
                errorMessage = if (snapshot.cancelling) "Cancelling…" else null,
            )
        }
        val model = result.model.ifBlank { persisted.model }
        var resultAssetId = persisted.resultAssetId
        var error = result.errorMessage
        var state = result.state
        if (result.state == AsyncProviderJobState.SUCCEEDED && resultAssetId == null) {
            val output = result.outputText?.trim()
            if (output.isNullOrEmpty()) {
                // Nothing to save, so the job ends here instead of being fetched again on every start.
                state = AsyncProviderJobState.INCOMPLETE
                error = error ?: "Claude finished the batch without text output."
            } else {
                resultAssetId = ProjectLibraryStore(context.noBackupFilesDir)
                    .saveTextAsset(
                        projectId = persisted.projectId,
                        title = "Claude batch result · $model",
                        mediaType = "text/markdown",
                        extension = "md",
                        text = claudeBatchResultMarkdown(model, output),
                    )
                    ?.id
                if (resultAssetId == null) {
                    outputMissing = true
                    error = "Completed, but the result could not be saved to Project Library."
                }
            }
        }
        persisted.copy(
            model = model,
            state = state,
            updatedAtEpochMs = now,
            resultAssetId = resultAssetId,
            errorMessage = error,
        )
    } ?: job
    return AsyncJobRefreshResult(updated, shouldRetry = updated.needsPolling && (result == null || outputMissing))
}

internal fun claudeBatchResultMarkdown(model: String, output: String): String = buildString {
    append("# Claude batch result\n\n")
    append("- Model: `").append(model).append("`\n")
    append("- Saved by Aistee from a completed Message Batch.\n\n")
    append("---\n\n")
    append(output)
    append('\n')
}
