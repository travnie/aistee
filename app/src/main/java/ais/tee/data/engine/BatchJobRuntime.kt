package ais.tee.data.engine

import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobKind
import ais.tee.data.model.AsyncProviderJobState
import ais.tee.data.model.ProjectLibraryAssetKind
import ais.tee.data.model.ProjectLibraryAssetOrigin
import ais.tee.data.model.needsPolling
import ais.tee.data.preferences.AsyncProviderJobStore
import ais.tee.data.preferences.ProjectLibraryStore
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

private const val INPUT_JOB_ID = "job_id"
private const val INPUT_MAX_ATTEMPTS = "max_poll_attempts"

internal object BatchJobWork {
    fun enqueue(
        context: Context,
        jobId: String,
        workNamePrefix: String,
        maxAutomaticPollAttempts: Int,
    ) {
        if (jobId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<BatchJobWorker>()
            .setInputData(
                Data.Builder()
                    .putString(INPUT_JOB_ID, jobId)
                    .putInt(INPUT_MAX_ATTEMPTS, maxAutomaticPollAttempts.coerceAtLeast(1))
                    .build(),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(workNamePrefix, jobId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, jobId: String, workNamePrefix: String) {
        if (jobId.isBlank()) return
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(workName(workNamePrefix, jobId))
    }

    private fun workName(prefix: String, jobId: String): String = prefix + jobId
}

class BatchJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        runBatchJobPollingWork(
            context = applicationContext,
            jobId = inputData.getString(INPUT_JOB_ID)?.trim().orEmpty(),
            runAttemptCount = runAttemptCount,
            maxAutomaticPollAttempts = inputData.getInt(INPUT_MAX_ATTEMPTS, 1).coerceAtLeast(1),
        )
}

/**
 * Compatibility entry point for Claude work enqueued by older Aistee versions.
 * New Claude and Gemini polling both use [BatchJobWorker].
 */
class ClaudeBatchJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        runBatchJobPollingWork(
            context = applicationContext,
            jobId = inputData.getString(INPUT_JOB_ID)?.trim().orEmpty(),
            runAttemptCount = runAttemptCount,
            maxAutomaticPollAttempts = 16,
        )
}

private suspend fun runBatchJobPollingWork(
    context: Context,
    jobId: String,
    runAttemptCount: Int,
    maxAutomaticPollAttempts: Int,
): ListenableWorker.Result {
    if (jobId.isEmpty()) return ListenableWorker.Result.success()
    return try {
        val store = AsyncProviderJobStore(context.noBackupFilesDir)
        val job = store.load().jobs.firstOrNull { it.id == jobId }
            ?: return ListenableWorker.Result.success()
        if (job.kind != AsyncProviderJobKind.BATCH || !job.needsPolling) {
            return ListenableWorker.Result.success()
        }
        val backend = AsyncJobBackends.forJob(job) ?: return ListenableWorker.Result.success()
        val refreshed = backend.refresh(context, jobId, AiChatService())
            ?: return ListenableWorker.Result.success()
        when {
            !refreshed.shouldRetry -> ListenableWorker.Result.success()
            runAttemptCount < maxAutomaticPollAttempts -> ListenableWorker.Result.retry()
            else -> pauseBatchPolling(store, jobId)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < maxAutomaticPollAttempts) {
            ListenableWorker.Result.retry()
        } else {
            pauseBatchPolling(AsyncProviderJobStore(context.noBackupFilesDir), jobId)
        }
    }
}

private fun pauseBatchPolling(store: AsyncProviderJobStore, jobId: String): ListenableWorker.Result {
    store.update(jobId) { job ->
        if (!job.needsPolling) return@update job
        job.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = "Automatic polling paused. Open Jobs and refresh manually.",
        )
    }
    return ListenableWorker.Result.success()
}

internal fun markBatchJobApiKeyUnavailable(
    store: AsyncProviderJobStore,
    job: AsyncProviderJob,
    message: String,
): AsyncProviderJob =
    store.update(job.id) {
        it.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            errorMessage = message,
        )
    } ?: job

internal data class BatchResultSnapshot(
    val state: AsyncProviderJobState,
    val model: String? = null,
    val outputText: String? = null,
    val errorMessage: String? = null,
    val pendingMessage: String? = null,
    val resultReady: Boolean = true,
)

internal fun persistBatchResult(
    context: Context,
    job: AsyncProviderJob,
    snapshot: BatchResultSnapshot,
    resultLabel: String,
    savedDescription: String,
    emptyOutputMessage: String,
): AsyncJobRefreshResult {
    val store = AsyncProviderJobStore(context.noBackupFilesDir)
    val library = ProjectLibraryStore(context.noBackupFilesDir)
    var savedAssetId: String? = null
    val stored = store.update(job.id) { persisted ->
        if (!persisted.needsPolling) return@update persisted
        val now = System.currentTimeMillis()
        val model = snapshot.model?.trim()?.takeIf(String::isNotEmpty) ?: persisted.model

        if (!snapshot.resultReady) {
            return@update persisted.copy(
                model = model,
                state = AsyncProviderJobState.RUNNING,
                updatedAtEpochMs = now,
                errorMessage = snapshot.pendingMessage,
            )
        }

        var resultAssetId = persisted.resultAssetId
        var state = snapshot.state
        var error = snapshot.errorMessage ?: snapshot.pendingMessage
        if (state == AsyncProviderJobState.SUCCEEDED && resultAssetId == null) {
            val output = snapshot.outputText?.trim()
            if (output.isNullOrEmpty()) {
                state = AsyncProviderJobState.INCOMPLETE
                error = error ?: emptyOutputMessage
            } else {
                resultAssetId = library
                    .saveTextAsset(
                        projectId = persisted.projectId,
                        title = "$resultLabel result · $model",
                        mediaType = "text/markdown",
                        extension = "md",
                        text = batchResultMarkdown(resultLabel, savedDescription, model, output),
                        kind = ProjectLibraryAssetKind.ARTIFACT,
                        origin = ProjectLibraryAssetOrigin.JOB_OUTPUT,
                    )
                    ?.id
                savedAssetId = resultAssetId
                if (resultAssetId == null) {
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
    }

    if (stored == null) {
        savedAssetId?.let(library::deleteAsset)
    }
    val updated = stored ?: job
    return AsyncJobRefreshResult(updated, shouldRetry = updated.needsPolling)
}

private fun batchResultMarkdown(
    resultLabel: String,
    savedDescription: String,
    model: String,
    output: String,
): String = buildString {
    append("# ").append(resultLabel).append(" result\n\n")
    append("- Model: `").append(model).append("`\n")
    append("- ").append(savedDescription).append("\n\n")
    append("---\n\n")
    append(output)
    append('\n')
}
