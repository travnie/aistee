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
import ais.tee.data.model.AiProvider
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobKind
import ais.tee.data.model.AsyncProviderJobState
import ais.tee.data.preferences.AsyncProviderJobStore
import ais.tee.data.preferences.ProjectLibraryStore
import ais.tee.data.security.ApiKeyStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

private const val INPUT_JOB_ID = "job_id"
private const val WORK_NAME_PREFIX = "openai-background-job:"
private const val MAX_AUTOMATIC_POLL_ATTEMPTS = 12

internal data class AsyncJobRefreshResult(
    val job: AsyncProviderJob,
    val shouldRetry: Boolean,
)

internal object OpenAiBackgroundJobWork {
    fun enqueue(context: Context, jobId: String) {
        if (jobId.isBlank()) return
        val input = Data.Builder()
            .putString(INPUT_JOB_ID, jobId)
            .build()
        val request = OneTimeWorkRequestBuilder<OpenAiBackgroundJobWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                10,
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(jobId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context, jobId: String) {
        if (jobId.isBlank()) return
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(jobId))
    }

    private fun workName(jobId: String): String = WORK_NAME_PREFIX + jobId
}

class OpenAiBackgroundJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(INPUT_JOB_ID)?.trim().orEmpty()
        if (jobId.isEmpty()) return Result.success()

        return try {
            val refreshed = refreshOpenAiBackgroundJob(applicationContext, jobId)
                ?: return Result.success()
            when {
                !refreshed.shouldRetry -> Result.success()
                runAttemptCount < MAX_AUTOMATIC_POLL_ATTEMPTS -> Result.retry()
                else -> {
                    AsyncProviderJobStore(applicationContext.noBackupFilesDir).update(jobId) { job ->
                        job.copy(
                            updatedAtEpochMs = System.currentTimeMillis(),
                            errorMessage = "Automatic polling paused. Open Jobs and refresh manually.",
                        )
                    }
                    Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            if (runAttemptCount < MAX_AUTOMATIC_POLL_ATTEMPTS) Result.retry() else Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_AUTOMATIC_POLL_ATTEMPTS) Result.retry() else Result.success()
        }
    }
}

internal suspend fun refreshOpenAiBackgroundJob(
    context: Context,
    jobId: String,
    service: AiChatService = AiChatService(),
): AsyncJobRefreshResult? {
    val appContext = context.applicationContext
    val store = AsyncProviderJobStore(appContext.noBackupFilesDir)
    val job = store.load().jobs.firstOrNull { it.id == jobId } ?: return null
    if (job.provider != AiProvider.CHATGPT || job.kind != AsyncProviderJobKind.BACKGROUND_RESPONSE) {
        return AsyncJobRefreshResult(job, shouldRetry = false)
    }
    if (job.state.isTerminal && (job.state != AsyncProviderJobState.SUCCEEDED || job.resultAssetId != null)) {
        return AsyncJobRefreshResult(job, shouldRetry = false)
    }

    val apiKey = ApiKeyStore(appContext).load().openAiKey.trim()
    if (apiKey.isEmpty()) {
        val updated = store.update(job.id) {
            it.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                errorMessage = "OpenAI API key is unavailable. Add it again to refresh this job.",
            )
        } ?: job
        return AsyncJobRefreshResult(updated, shouldRetry = false)
    }

    val snapshot = service.retrieveOpenAiBackgroundResponse(job.remoteId, apiKey)
    return persistOpenAiBackgroundSnapshot(appContext, job, snapshot)
}

internal suspend fun cancelOpenAiBackgroundJob(
    context: Context,
    jobId: String,
    service: AiChatService = AiChatService(),
): AsyncProviderJob? {
    val appContext = context.applicationContext
    val store = AsyncProviderJobStore(appContext.noBackupFilesDir)
    val job = store.load().jobs.firstOrNull { it.id == jobId } ?: return null
    if (job.provider != AiProvider.CHATGPT || job.kind != AsyncProviderJobKind.BACKGROUND_RESPONSE) {
        return job
    }
    if (job.state.isTerminal) return job

    val apiKey = ApiKeyStore(appContext).load().openAiKey.trim()
    if (apiKey.isEmpty()) {
        return store.update(job.id) {
            it.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                errorMessage = "OpenAI API key is unavailable. Add it again before cancelling.",
            )
        }
    }

    val snapshot = service.cancelOpenAiBackgroundResponse(job.remoteId, apiKey)
    val persisted = persistOpenAiBackgroundSnapshot(appContext, job, snapshot)
    if (!persisted.shouldRetry) {
        OpenAiBackgroundJobWork.cancel(appContext, job.id)
    }
    return persisted.job
}

internal fun persistOpenAiBackgroundSnapshot(
    context: Context,
    job: AsyncProviderJob,
    snapshot: OpenAiBackgroundResponseSnapshot,
): AsyncJobRefreshResult {
    val store = AsyncProviderJobStore(context.noBackupFilesDir)
    val updated = store.update(job.id) { persistedJob ->
        if (
            persistedJob.state.isTerminal &&
            (persistedJob.state != AsyncProviderJobState.SUCCEEDED ||
                persistedJob.resultAssetId != null ||
                snapshot.state != AsyncProviderJobState.SUCCEEDED)
        ) {
            return@update persistedJob
        }

        var resultAssetId = persistedJob.resultAssetId
        var localError = snapshot.errorMessage
        val resolvedModel = snapshot.model.ifBlank { persistedJob.model }

        if (snapshot.state == AsyncProviderJobState.SUCCEEDED && resultAssetId == null) {
            val output = snapshot.outputText?.trim()
            if (output.isNullOrEmpty()) {
                localError = localError ?: "OpenAI completed the background job without text output."
            } else {
                val markdown = buildString {
                    append("# OpenAI background result\n\n")
                    append("- Model: `")
                    append(resolvedModel)
                    append("`\n")
                    append("- Saved by Aistee from a completed background Response.\n\n")
                    append("---\n\n")
                    append(output)
                    append('\n')
                }
                resultAssetId = ProjectLibraryStore(context.noBackupFilesDir)
                    .saveTextAsset(
                        projectId = persistedJob.projectId,
                        title = "OpenAI background result · $resolvedModel",
                        mediaType = "text/markdown",
                        extension = "md",
                        text = markdown,
                    )
                    ?.id
                if (resultAssetId == null) {
                    localError = "Completed, but the result could not be saved to Project Library."
                }
            }
        }

        persistedJob.copy(
            model = resolvedModel,
            state = snapshot.state,
            updatedAtEpochMs = System.currentTimeMillis(),
            resultAssetId = resultAssetId,
            errorMessage = localError,
        )
    } ?: job

    val shouldRetry =
        !updated.state.isTerminal ||
            (updated.state == AsyncProviderJobState.SUCCEEDED &&
                updated.resultAssetId == null &&
                snapshot.outputText?.isNotBlank() == true)
    return AsyncJobRefreshResult(updated, shouldRetry)
}
