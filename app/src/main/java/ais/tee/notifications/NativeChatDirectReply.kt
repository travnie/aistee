package ais.tee.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ais.tee.R
import ais.tee.data.engine.AiChatService
import ais.tee.data.engine.InstructionRenderer
import ais.tee.data.engine.NativeChatSendRequest
import ais.tee.data.engine.ProfileMerger
import ais.tee.data.engine.executeNativeChatSend
import ais.tee.data.model.AiProvider
import ais.tee.data.model.ApiKeyConfig
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ConversationSurfaceCapability
import ais.tee.data.model.conversationSurfaceCapabilities
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatConversation
import ais.tee.data.model.PresetProfiles
import ais.tee.data.model.StudioStateDecodeResult
import ais.tee.data.model.configuredDirectProviders
import ais.tee.data.model.hasKeyFor
import ais.tee.data.preferences.NativeChatStore
import ais.tee.data.preferences.NativeChatWriter
import ais.tee.data.preferences.StudioStateStore
import ais.tee.data.preferences.StudioStateWriter
import ais.tee.data.security.ApiKeyStore
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.data.skills.composeLocalSkillSystemInstruction
import ais.tee.ui.viewmodel.resolveStudioState
import ais.tee.widget.NativeChatWidgetUpdater
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal const val NATIVE_CHAT_DIRECT_REPLY_RESULT_KEY = "native_chat_direct_reply"
private const val ACTION_DIRECT_REPLY = "ais.tee.action.NATIVE_CHAT_DIRECT_REPLY"
private const val EXTRA_CONVERSATION_ID = "native_chat_direct_reply_conversation_id"
private const val INPUT_REPLY_ID = "reply_id"
private const val INPUT_CONVERSATION_ID = "conversation_id"
private const val INPUT_REPLY_TEXT = "reply_text"
private const val INPUT_REPLY_EPOCH = "reply_epoch"
private const val INPUT_QUEUED_SEND = "queued_send"
internal const val MAX_REPLY_TEXT_CHARS = 4_000
internal const val NATIVE_CHAT_QUEUED_SEND_TAG = "native-chat-queued-send"
private const val DIRECT_REPLY_USER_MESSAGE_PREFIX = "direct_reply_user_"

internal fun nativeChatQueuedSendTag(replyId: String): String = "$NATIVE_CHAT_QUEUED_SEND_TAG:$replyId"

internal fun nativeChatReplyUserMessageId(replyId: String): String = DIRECT_REPLY_USER_MESSAGE_PREFIX + replyId

internal fun nativeChatReplyIdFromUserMessageId(messageId: String): String? =
    messageId.takeIf { it.startsWith(DIRECT_REPLY_USER_MESSAGE_PREFIX) }
        ?.removePrefix(DIRECT_REPLY_USER_MESSAGE_PREFIX)
        ?.takeIf { it.isNotBlank() }

// WorkManager caps serialized Data at 10 KiB, not at a character count.
internal fun buildNativeChatReplyInput(
    replyId: String, conversationId: String, text: String, replyEpoch: Long = 0L, queuedSend: Boolean = false,
): Data? =
    try {
        Data.Builder()
            .putString(INPUT_REPLY_ID, replyId)
            .putString(INPUT_CONVERSATION_ID, conversationId)
            .putString(INPUT_REPLY_TEXT, text)
            .putLong(INPUT_REPLY_EPOCH, replyEpoch)
            .putBoolean(INPUT_QUEUED_SEND, queuedSend)
            .build()
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun canNativeChatDirectReply(
    conversation: NativeChatConversation,
    apiKeys: ApiKeyConfig,
): Boolean {
    if (
        !conversation.selectedProvider.conversationSurfaceCapabilities()
            .supports(ConversationSurfaceCapability.DIRECT_REPLY)
    ) {
        return false
    }
    return when (conversation.selectedProvider) {
        AiProvider.ALL -> apiKeys.configuredDirectProviders().isNotEmpty()
        else -> apiKeys.hasKeyFor(conversation.selectedProvider)
    }
}

internal data class PreparedNativeChatDirectReply(
    val archive: NativeChatArchive,
    val conversation: NativeChatConversation,
    val userMessage: ModelChatMessage,
)

internal fun prepareNativeChatDirectReply(
    archive: NativeChatArchive,
    conversationId: String,
    replyId: String,
    rawText: String,
    now: Long,
    expectedReplyEpoch: Long = 0L,
): PreparedNativeChatDirectReply? {
    val text = rawText.trim()
    if (text.isEmpty() || text.length > MAX_REPLY_TEXT_CHARS) return null
    val conversation = archive.conversations.firstOrNull { it.id == conversationId } ?: return null
    if (conversation.replyEpoch != expectedReplyEpoch) return null
    val messageId = nativeChatReplyUserMessageId(replyId)
    val existing = conversation.messages.firstOrNull { it.id == messageId }
    val userMessage = existing ?: ModelChatMessage(
        id = messageId,
        sender = CHAT_ROLE_USER,
        text = text,
        timestamp = now,
    )
    val updatedConversation = if (existing != null) {
        conversation
    } else {
        conversation.copy(
            updatedAtEpochMs = now,
            messages = conversation.messages + userMessage,
        )
    }
    return PreparedNativeChatDirectReply(
        archive = archive.copy(
            conversations = archive.conversations.map {
                if (it.id == conversationId) updatedConversation else it
            }
        ),
        conversation = updatedConversation,
        userMessage = userMessage,
    )
}

internal fun mergeNativeChatDirectReplyResponses(
    archive: NativeChatArchive,
    conversationId: String,
    userMessageId: String,
    responses: List<ModelChatMessage>,
    now: Long,
): NativeChatArchive? {
    val conversation = archive.conversations.firstOrNull { it.id == conversationId } ?: return null
    val userIndex = conversation.messages.indexOfFirst { it.id == userMessageId }
    if (userIndex < 0) return null
    val responseIds = responses.mapTo(mutableSetOf()) { it.id }
    val withoutPriorCopies = conversation.messages.filterNot { it.id in responseIds }.toMutableList()
    val refreshedUserIndex = withoutPriorCopies.indexOfFirst { it.id == userMessageId }
    if (refreshedUserIndex < 0) return null
    withoutPriorCopies[refreshedUserIndex] = withoutPriorCopies[refreshedUserIndex].copy(isQueued = false)
    withoutPriorCopies.addAll(refreshedUserIndex + 1, responses)
    val updated = conversation.copy(
        updatedAtEpochMs = now,
        messages = withoutPriorCopies,
    )
    return archive.copy(
        conversations = archive.conversations.map {
            if (it.id == conversationId) updated else it
        }
    )
}

internal object NativeChatDirectReply {
    fun action(context: Context, conversation: NativeChatConversation): NotificationCompat.Action? {
        val appContext = context.applicationContext
        val apiKeys = ApiKeyStore(appContext).load()
        if (!canNativeChatDirectReply(conversation, apiKeys)) return null

        val replyIntent = Intent(appContext, NativeChatDirectReplyReceiver::class.java)
            .setAction(ACTION_DIRECT_REPLY)
            .setData(
                Uri.Builder()
                    .scheme("aistee")
                    .authority("notification-reply")
                    .appendPath(conversation.id)
                    .build()
            )
            .putExtra(EXTRA_CONVERSATION_ID, conversation.id)
            .putExtra(INPUT_REPLY_EPOCH, conversation.replyEpoch)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(NATIVE_CHAT_DIRECT_REPLY_RESULT_KEY)
            .setLabel(appContext.getString(R.string.notification_reply_action))
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_quick_settings,
            appContext.getString(R.string.notification_reply_action),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    fun enqueue(context: Context, conversationId: String, rawText: String, replyEpoch: Long): Boolean =
        enqueue(context, conversationId, UUID.randomUUID().toString(), rawText, replyEpoch, queuedSend = false)

    /**
     * Sends an in-app turn that was written while offline. The user message already exists in the
     * archive; the job only runs with a network and drops the turn if the message was cancelled.
     */
    fun enqueueQueuedSend(
        context: Context,
        conversationId: String,
        replyId: String,
        rawText: String,
        replyEpoch: Long,
    ): Boolean = enqueue(context, conversationId, replyId, rawText, replyEpoch, queuedSend = true)

    fun cancelQueuedSend(context: Context, replyId: String) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(nativeChatQueuedSendTag(replyId))
    }

    private fun enqueue(
        context: Context,
        conversationId: String,
        replyId: String,
        rawText: String,
        replyEpoch: Long,
        queuedSend: Boolean,
    ): Boolean {
        val text = rawText.trim()
        if (conversationId.isBlank() || text.isEmpty() || text.length > MAX_REPLY_TEXT_CHARS) return false
        val input = buildNativeChatReplyInput(replyId, conversationId, text, replyEpoch, queuedSend) ?: return false
        val request = OneTimeWorkRequestBuilder<NativeChatDirectReplyWorker>()
            .setInputData(input)
            .apply {
                if (queuedSend) {
                    addTag(NATIVE_CHAT_QUEUED_SEND_TAG)
                    addTag(nativeChatQueuedSendTag(replyId))
                }
            }
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .apply {
                // Before Android 12 expedited work requires a foreground-service
                // notification. Use ordinary constrained work on those devices.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "native-chat-direct-reply:$conversationId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return true
    }
}

class NativeChatDirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DIRECT_REPLY) return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.trim().orEmpty()
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NATIVE_CHAT_DIRECT_REPLY_RESULT_KEY)
            ?.toString()
            .orEmpty()
        if (!NativeChatDirectReply.enqueue(context, conversationId, reply, intent.getLongExtra(INPUT_REPLY_EPOCH, 0L))) {
            publishFailureForStoredConversation(context, conversationId)
        }
    }
}

class NativeChatDirectReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(INPUT_CONVERSATION_ID)?.trim().orEmpty()
        val replyId = inputData.getString(INPUT_REPLY_ID)?.trim().orEmpty()
        val replyText = inputData.getString(INPUT_REPLY_TEXT).orEmpty()
        if (conversationId.isEmpty() || replyId.isEmpty()) return Result.success()

        val queuedSend = inputData.getBoolean(INPUT_QUEUED_SEND, false)
        return try {
            processDirectReply(
                applicationContext, conversationId, replyId, replyText, inputData.getLong(INPUT_REPLY_EPOCH, 0L), queuedSend,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                if (queuedSend) releaseQueuedMessage(applicationContext, conversationId, replyId)
                publishFailureForStoredConversation(applicationContext, conversationId)
                Result.success()
            }
        }
    }
}

private data class DirectReplyPromptContext(
    val systemInstruction: String?,
    val activeProfile: ais.tee.data.model.Profile?,
)

private suspend fun processDirectReply(
    context: Context,
    conversationId: String,
    replyId: String,
    replyText: String,
    replyEpoch: Long,
    queuedSend: Boolean,
): Boolean {
    val store = NativeChatStore(context.noBackupFilesDir)
    val writer = NativeChatWriter.getInstance(store) { archive ->
        NativeChatWidgetUpdater.onArchiveSaved(context.applicationContext, archive)
    }
    val apiKeys = ApiKeyStore(context).load()
    var prepared: PreparedNativeChatDirectReply? = null
    val preparedArchive = writer.updateAndPersist { archive ->
        val conversation = archive.conversations.firstOrNull { it.id == conversationId }
            ?: return@updateAndPersist null
        if (!canNativeChatDirectReply(conversation, apiKeys)) return@updateAndPersist null
        // A queued in-app send that the user cancelled or edited must not come back.
        if (queuedSend && conversation.messages.none { it.id == nativeChatReplyUserMessageId(replyId) }) {
            return@updateAndPersist null
        }
        prepareNativeChatDirectReply(
            archive, conversationId, replyId, replyText, System.currentTimeMillis(), replyEpoch,
        )?.also { prepared = it }?.archive
    }
    if (preparedArchive == null) {
        if (queuedSend) {
            releaseQueuedMessage(context, conversationId, replyId)
        } else {
            publishFailureForStoredConversation(context, conversationId)
        }
        return false // Permanent rejection: deleted conversation, invalid input, or missing key.
    }
    val reply = requireNotNull(prepared)
    val originalConversation = reply.conversation
    if (!queuedSend) NativeChatNotificationPublisher.publishReplyInProgress(context, originalConversation)

    val promptContext = loadDirectReplyPromptContext(context, reply.conversation)
    val providers = when (reply.conversation.selectedProvider) {
        AiProvider.ALL -> apiKeys.configuredDirectProviders()
        else -> listOf(reply.conversation.selectedProvider)
    }
    if (providers.isEmpty()) {
        NativeChatNotificationPublisher.publishReplyFailed(context, originalConversation)
        return false
    }

    // A failed archive write leaves the generated turn in the shared writer.
    // Reuse it on retry instead of making the same provider request again.
    val existingResponses = reply.conversation.messages.filter { message ->
        providers.any { message.id == "direct_reply_${replyId}_${it.id}" }
    }
    val missingProviders = providers.filter { provider ->
        existingResponses.none { it.id == "direct_reply_${replyId}_${provider.id}" }
    }
    val generated = existingResponses + if (missingProviders.isEmpty()) emptyList() else executeNativeChatSend(
        request = NativeChatSendRequest(
            prompt = reply.userMessage.text,
            targetProvider = reply.conversation.selectedProvider,
            providersToRun = missingProviders,
            selectedModel = reply.conversation.selectedModel,
            apiProcessingMode = reply.conversation.apiProcessingMode,
            apiKeys = apiKeys,
            systemInstruction = promptContext.systemInstruction,
            profile = promptContext.activeProfile,
            conversationHistory = reply.conversation.messages,
            allowSingleProviderSimulationFallback = false,
        ),
        aiChatService = AiChatService(),
    ).map { result ->
        result.message.copy(id = "direct_reply_${replyId}_${result.provider.id}")
    }

    val completedArchive = writer.updateAndPersist { latest ->
        mergeNativeChatDirectReplyResponses(
            archive = latest,
            conversationId = conversationId,
            userMessageId = reply.userMessage.id,
            responses = generated,
            now = System.currentTimeMillis(),
        )
    } ?: return false // Conversation or turn was explicitly removed while generating.

    val completedConversation = completedArchive.conversations.firstOrNull { it.id == conversationId }
        ?: return false
    val hasCompletedResponse = generated.any { !it.isError && !it.isPartial && !it.isSimulated }
    if (hasCompletedResponse) {
        NativeChatNotificationPublisher.publishConversation(context, completedConversation)
    } else {
        NativeChatNotificationPublisher.publishReplyFailed(context, originalConversation)
    }
    return true
}

private suspend fun loadDirectReplyPromptContext(
    context: Context,
    conversation: NativeChatConversation,
): DirectReplyPromptContext {
    val studioStore = StudioStateStore(context)
    val snapshot = StudioStateWriter.currentSnapshot() ?: when (val loaded = studioStore.load()) {
        is StudioStateDecodeResult.Success -> loaded.snapshot
        is StudioStateDecodeResult.UnsupportedVersion ->
            (studioStore.loadFallback() as? StudioStateDecodeResult.Success)?.snapshot
        StudioStateDecodeResult.MissingOrInvalid -> null
    }
    val restored = snapshot?.resolveStudioState()
    val mergedProfile = if (conversation.includeSystemProfile) {
        restored?.let {
            it.selectedOverlay?.let { overlay -> ProfileMerger.merge(it.baseProfile, overlay) } ?: it.baseProfile
        } ?: PresetProfiles.DefaultBaseProfile
    } else {
        null
    }
    val baseInstruction = mergedProfile?.let { profile ->
        InstructionRenderer.render(profile, restored?.language ?: "auto").ifBlank { null }
    }
    val skills = LocalSkillLibraryStore(
        File(context.noBackupFilesDir, LocalSkillLibraryStore.LIBRARY_DIRECTORY_NAME)
    ).loadEnabledManifests()
    return DirectReplyPromptContext(
        systemInstruction = composeLocalSkillSystemInstruction(baseInstruction, skills),
        activeProfile = mergedProfile,
    )
}

private fun publishFailureForStoredConversation(context: Context, conversationId: String) {
    val store = NativeChatStore(context.noBackupFilesDir)
    val archive = NativeChatWriter.currentArchive() ?: store.load() ?: return
    archive.conversations.firstOrNull { it.id == conversationId }?.let {
        NativeChatNotificationPublisher.publishReplyFailed(context, it)
    }
}

/** Clears the queued marker so a turn that could not be sent shows as an ordinary unanswered message. */
private suspend fun releaseQueuedMessage(context: Context, conversationId: String, replyId: String) {
    val writer = NativeChatWriter.getInstance(NativeChatStore(context.noBackupFilesDir)) { archive ->
        NativeChatWidgetUpdater.onArchiveSaved(context.applicationContext, archive)
    }
    val messageId = nativeChatReplyUserMessageId(replyId)
    runCatching {
        writer.updateAndPersist { archive ->
            val conversation = archive.conversations.firstOrNull { it.id == conversationId }
                ?.takeIf { conversation -> conversation.messages.any { it.id == messageId && it.isQueued } }
                ?: return@updateAndPersist null
            archive.copy(
                conversations = archive.conversations.map { candidate ->
                    if (candidate.id != conversation.id) {
                        candidate
                    } else {
                        candidate.copy(
                            messages = candidate.messages.map { if (it.id == messageId) it.copy(isQueued = false) else it }
                        )
                    }
                }
            )
        }
    }
}
