package ais.tee.data.engine

import android.content.Context
import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobKind
import ais.tee.data.model.AsyncProviderJobState

/** What a provider returned when a job was started. */
internal data class StartedAsyncJob(
    val remoteId: String,
    val model: String,
    val state: AsyncProviderJobState,
    val errorMessage: String? = null,
    /** Runs once the local record exists, e.g. to save a result that was already available. */
    val afterStored: (Context, AsyncProviderJob) -> Unit = { _, _ -> },
)

/**
 * One provider's asynchronous job API behind a common surface, so the Jobs UI and view model never
 * branch on provider. Add a backend to [AsyncJobBackends.all] to support a new provider.
 */
internal interface AsyncJobBackend {
    val provider: AiProvider
    val kind: AsyncProviderJobKind
    /** Short label for the provider choice in Jobs. */
    val label: String
    /** What the job does and what the provider keeps, shown before starting. */
    val description: String
    val promptLabel: String
    val startLabel: String
    val missingKeyMessage: String

    fun apiKey(config: ApiKeyConfig): String

    suspend fun start(service: AiChatService, prompt: String, model: String, apiKey: String): StartedAsyncJob
    /** Best-effort remote cancellation when the local record could not be saved. */
    suspend fun cancelRemote(service: AiChatService, remoteId: String, apiKey: String)

    suspend fun refresh(context: Context, jobId: String, service: AiChatService): AsyncJobRefreshResult?
    suspend fun cancel(context: Context, jobId: String, service: AiChatService): AsyncProviderJob?

    fun schedulePolling(context: Context, jobId: String)
    fun cancelPolling(context: Context, jobId: String)

    fun handles(job: AsyncProviderJob): Boolean = job.provider == provider && job.kind == kind
}

internal object AsyncJobBackends {
    val all: List<AsyncJobBackend> = listOf(OpenAiBackgroundJobBackend, ClaudeBatchJobBackend)

    fun forJob(job: AsyncProviderJob): AsyncJobBackend? = all.firstOrNull { it.handles(job) }

    fun forProvider(provider: AiProvider): AsyncJobBackend? = all.firstOrNull { it.provider == provider }
}

internal object OpenAiBackgroundJobBackend : AsyncJobBackend {
    override val provider = AiProvider.CHATGPT
    override val kind = AsyncProviderJobKind.BACKGROUND_RESPONSE
    override val label = "OpenAI background"
    override val description =
        "Long OpenAI Responses can run asynchronously while Aistee polls durable status in the background. " +
            "Aistee keeps store=false; OpenAI still temporarily stores background response data so polling can work."
    override val promptLabel = "Standalone background prompt"
    override val startLabel = "Start background job"
    override val missingKeyMessage = "Add an OpenAI API key from native chat settings before starting a job."

    override fun apiKey(config: ApiKeyConfig): String = config.openAiKey

    override suspend fun start(service: AiChatService, prompt: String, model: String, apiKey: String): StartedAsyncJob {
        val snapshot = service.createOpenAiBackgroundResponse(prompt = prompt, model = model, apiKey = apiKey)
        return StartedAsyncJob(
            remoteId = snapshot.remoteId,
            model = snapshot.model.ifBlank { model },
            state = snapshot.state,
            errorMessage = snapshot.errorMessage,
            afterStored = { context, job -> persistOpenAiBackgroundSnapshot(context, job, snapshot) },
        )
    }

    override suspend fun cancelRemote(service: AiChatService, remoteId: String, apiKey: String) {
        service.cancelOpenAiBackgroundResponse(responseId = remoteId, apiKey = apiKey)
    }

    override suspend fun refresh(context: Context, jobId: String, service: AiChatService) =
        refreshOpenAiBackgroundJob(context, jobId, service)

    override suspend fun cancel(context: Context, jobId: String, service: AiChatService) =
        cancelOpenAiBackgroundJob(context, jobId, service)

    override fun schedulePolling(context: Context, jobId: String) = OpenAiBackgroundJobWork.enqueue(context, jobId)
    override fun cancelPolling(context: Context, jobId: String) = OpenAiBackgroundJobWork.cancel(context, jobId)
}

internal object ClaudeBatchJobBackend : AsyncJobBackend {
    override val provider = AiProvider.CLAUDE
    override val kind = AsyncProviderJobKind.BATCH
    override val label = "Claude batch"
    override val description =
        "Claude Message Batches cost half the normal price and usually finish within an hour (at most 24 hours). " +
            "Aistee polls in the background. Anthropic keeps the batch request and result for 29 days so they can be downloaded."
    override val promptLabel = "Standalone batch prompt"
    override val startLabel = "Start batch job"
    override val missingKeyMessage = "Add a Claude API key from native chat settings before starting a job."

    override fun apiKey(config: ApiKeyConfig): String = config.claudeKey

    override suspend fun start(service: AiChatService, prompt: String, model: String, apiKey: String): StartedAsyncJob {
        val snapshot = service.createClaudeBatch(prompt, model, apiKey)
        return StartedAsyncJob(remoteId = snapshot.remoteId, model = model, state = AsyncProviderJobState.RUNNING)
    }

    override suspend fun cancelRemote(service: AiChatService, remoteId: String, apiKey: String) {
        service.cancelClaudeBatch(remoteId, apiKey)
    }

    override suspend fun refresh(context: Context, jobId: String, service: AiChatService) =
        refreshClaudeBatchJob(context, jobId, service)

    override suspend fun cancel(context: Context, jobId: String, service: AiChatService) =
        cancelClaudeBatchJob(context, jobId, service)

    override fun schedulePolling(context: Context, jobId: String) = ClaudeBatchJobWork.enqueue(context, jobId)
    override fun cancelPolling(context: Context, jobId: String) = ClaudeBatchJobWork.cancel(context, jobId)
}
