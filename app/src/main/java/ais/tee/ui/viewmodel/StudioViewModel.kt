package ais.tee.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ais.tee.data.engine.AiChatService
import ais.tee.data.engine.InstructionRenderer
import ais.tee.data.engine.NativeBenchChatTools
import ais.tee.data.engine.NativeChatSendRequest
import ais.tee.data.engine.executeNativeChatSend
import ais.tee.data.engine.ProfileMerger
import ais.tee.data.engine.ValidationResult
import ais.tee.data.engine.YamlParser
import ais.tee.data.model.*
import ais.tee.data.preferences.NativeChatStore
import ais.tee.data.preferences.NativeChatWriter
import ais.tee.data.preferences.ProjectLibraryStore
import ais.tee.data.preferences.StudioStateStore
import ais.tee.data.preferences.StudioStateWriter
import ais.tee.data.preferences.WebChatDraftStore
import ais.tee.data.preferences.WebChatPreferencesStore
import ais.tee.data.security.ApiKeyStore
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.data.skills.composeLocalSkillSystemInstruction
import ais.tee.share.IncomingSharePayload
import ais.tee.share.PendingWebShare
import ais.tee.share.claimText
import ais.tee.share.completeTextClaim
import ais.tee.share.releaseTextClaim
import ais.tee.notifications.NativeChatConversationShortcuts
import ais.tee.notifications.nativeChatConversationIdForShortcut
import ais.tee.notifications.NativeChatNotificationPublisher
import ais.tee.widget.NativeChatWidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class NativeChatNavigationRequest(
    val id: Long,
    val conversationId: String
)

data class PendingWebDraft(
    val id: String,
    val service: WebAiService,
    val text: String,
    val isClaimed: Boolean = false,
) {
    override fun toString(): String =
        "PendingWebDraft(id=$id, service=${service.id}, text=<redacted>, isClaimed=$isClaimed)"
}

internal fun resolveNativeConversationTarget(
    archive: NativeChatArchive,
    requestedId: String
): String? = requestedId.trim().takeIf { candidate ->
    candidate.isNotEmpty() && archive.conversations.any { it.id == candidate }
}

data class ChatMessage(
    val id: String,
    val sender: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: List<String> = emptyList()
)

private data class NativeChatSendPlan(
    val targetProvider: AiProvider,
    val providersToRun: List<AiProvider>,
    val apiKeys: ApiKeyConfig
)

private data class NativeChatPromptContext(
    val systemPrompt: String?,
    val activeProfile: Profile?
)

private const val STREAMING_UI_FLUSH_INTERVAL_MS = 50L
private const val NATIVE_CHAT_DRAFT_PERSIST_DELAY_MS = 300L

data class StudioUiState(
    val baseProfile: Profile = PresetProfiles.DefaultBaseProfile,
    val selectedOverlay: ProfileOverlay? = null,
    val availableOverlays: List<ProfileOverlay> = PresetProfiles.BuiltInOverlays,
    val mergedProfile: Profile = PresetProfiles.DefaultBaseProfile,
    val renderedInstructions: String = "",
    val language: String = "auto", // auto, en, pl
    val validationResult: ValidationResult = ValidationResult(true, emptyList(), emptyList()),
    val yamlRepresentation: String = "",
    val playgroundMessages: List<ChatMessage> = emptyList(),
    val isSimulating: Boolean = false,
    val currentTab: NavigationTab = NavigationTab.WEB_CHATS,
    val selectedWebService: WebAiService = WebAiService.CLAUDE,
    val favoriteWebServices: Set<WebAiService> = emptySet(),
    val snackbarMessage: String? = null,
    val incomingShare: IncomingSharePayload? = null,
    val incomingShareId: Long = 0L,
    val pendingWebShare: PendingWebShare? = null,
    val pendingWebDraft: PendingWebDraft? = null,

    // Integrated Multi-Provider AI Chat
    val nativeChat: NativeChatArchive = NativeChatArchive(),
    val isNativeConversationStoreReady: Boolean = false,
    val nativeChatNavigationRequest: NativeChatNavigationRequest? = null,
    val apiKeyConfig: ApiKeyConfig = ApiKeyConfig(),
    val isChatGenerating: Boolean = false,
    val activeGeneratingProviders: Set<AiProvider> = emptySet(),
    val showApiKeyDialog: Boolean = false,
    val gatewayModelOptions: Map<AiProvider, List<String>> = emptyMap(),
    val refreshingGatewayCatalogs: Set<AiProvider> = emptySet(),
    val projectLibrary: ProjectLibraryArchive = ProjectLibraryArchive(),
    val isProjectLibraryReady: Boolean = false,
) {
    val activeNativeConversation: NativeChatConversation?
        get() = nativeChat.activeConversation
    val chatMessages: List<ModelChatMessage>
        get() = activeNativeConversation?.messages.orEmpty()
    val nativeChatDraft: String
        get() = activeNativeConversation?.draft.orEmpty()
    val selectedChatProvider: AiProvider
        get() = activeNativeConversation?.selectedProvider ?: AiProvider.ALL
    val selectedChatModel: String
        get() = activeNativeConversation?.selectedModel ?: "all"
    val apiProcessingMode: ApiProcessingMode
        get() = activeNativeConversation?.apiProcessingMode ?: ApiProcessingMode.AUTO
    val includeSystemProfileInChat: Boolean
        get() = activeNativeConversation?.includeSystemProfile ?: true
}

enum class NavigationTab {
    WEB_CHATS,
    COMPARE_HUB,
    STUDIO,
    INSTRUCTIONS,
    YAML,
    PLAYGROUND,
    SKILLS;

    fun belongsToStudioSection(): Boolean = when (this) {
        STUDIO, INSTRUCTIONS, YAML, PLAYGROUND, SKILLS -> true
        WEB_CHATS, COMPARE_HUB -> false
    }
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val aiChatService = AiChatService()
    private val apiKeyStore = ApiKeyStore(application.applicationContext)
    private val studioStateStore = StudioStateStore(application.applicationContext)
    private val webChatPreferencesStore = WebChatPreferencesStore(application.applicationContext)
    private val webChatDraftStore = WebChatDraftStore(application.noBackupFilesDir)
    private val nativePersistenceLock = Any()
    private var nativePersistenceBase: NativeChatArchive? = null
    private val nativeChatStore = NativeChatStore(application.noBackupFilesDir)
    private val projectLibraryStore = ProjectLibraryStore(application.noBackupFilesDir)
    private val nativeChatWriter = NativeChatWriter.getInstance(nativeChatStore) { archive ->
        NativeChatWidgetUpdater.onArchiveSaved(application.applicationContext, archive)
    }
    private val localSkillStore = LocalSkillLibraryStore(
        File(application.noBackupFilesDir, LocalSkillLibraryStore.LIBRARY_DIRECTORY_NAME)
    )
    private var studioStateWriter: StudioStateWriter? = null

    private val _uiState = MutableStateFlow(
        StudioUiState(
            selectedWebService = webChatPreferencesStore.loadSelectedService(),
            favoriteWebServices = webChatPreferencesStore.loadFavorites()
        )
    )
    private val pendingGatewayCatalogRefreshes = mutableSetOf<AiProvider>()
    private var chatGenerationJob: Job? = null
    private val streamingTextBatcher = StreamingTextBatcher()
    private var streamingUiFlushJob: Job? = null
    private var studioPersistenceJob: Job? = null
    private var nativeChatDraftPersistenceJob: Job? = null
    private var studioPersistenceOwnerId: Long? = null
    private val activeChatGenerationId = AtomicLong(0)
    private val incomingShareId = AtomicLong(0)
    private val pendingWebShareId = AtomicLong(0)
    private val nativeChatNavigationRequestId = AtomicLong(0)
    private val pendingNativeConversationId = AtomicReference<String?>(null)
    private val pendingNativeChatShare = AtomicReference<PendingNativeChatShare?>(null)
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(apiKeyConfig = apiKeyStore.load()) }
        val cachedStudioState = StudioStateWriter.currentSnapshot()
        val primaryStudioState = cachedStudioState
            ?.let(StudioStateDecodeResult::Success)
            ?: studioStateStore.load()
        val useFallbackPersistence = primaryStudioState is StudioStateDecodeResult.UnsupportedVersion
        val persistedStudioState = if (useFallbackPersistence && cachedStudioState == null) {
            studioStateStore.loadFallback()
        } else {
            primaryStudioState
        }
        when (persistedStudioState) {
            is StudioStateDecodeResult.Success -> restoreStudioSnapshot(persistedStudioState.snapshot)
            else -> recompute()
        }
        initPlaygroundWelcome()
        initProjectLibrary()
        initNativeChatPersistence()
        startStudioPersistence(useFallbackPersistence)
    }

    private fun initProjectLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val archive = projectLibraryStore.load()
            _uiState.update {
                it.copy(projectLibrary = archive, isProjectLibraryReady = true)
            }
        }
    }

    private suspend fun reloadProjectLibraryFromDisk() {
        val archive = withContext(Dispatchers.IO) { projectLibraryStore.load() }
        _uiState.update { it.copy(projectLibrary = archive, isProjectLibraryReady = true) }
    }

    private fun initPlaygroundWelcome() {
        val state = _uiState.value
        val activeProfile = state.mergedProfile
        val profileName = state.selectedOverlay?.name ?: if (activeProfile == PresetProfiles.DefaultBaseProfile) {
            "Default Friendly"
        } else {
            activeProfile.personality.base.replaceFirstChar { it.uppercase() }
        }
        val welcomeMsg = ChatMessage(
            id = "welcome",
            sender = "assistant",
            text = "Hello! I am ready to assist. Adjust personality knobs, overlays, or collaboration policies in the Studio tab, and ask me any question to test how my responses adapt to your style profile.",
            notes = listOf("Active Profile: $profileName (intensity: ${activeProfile.personality.intensity ?: 1})")
        )
        _uiState.update { it.copy(playgroundMessages = listOf(welcomeMsg)) }
    }

    private fun welcomeChatMessages(): List<ModelChatMessage> {
        val providerLines = AiProvider.concreteProviders.joinToString("\n") { provider ->
            "• **${provider.displayName}** (`${provider.defaultModel}`)"
        }
        return listOf(
            ModelChatMessage(
                id = NATIVE_CHAT_WELCOME_MESSAGE_ID,
                sender = CHAT_ROLE_ASSISTANT,
                provider = AiProvider.ALL,
                modelName = "Multi-Model Hub",
                text = "Welcome to the **AI Chat Hub**! 🚀\n\nHere you can interact with:\n$providerLines\n\n✨ **Compare Mode**: Select *'All Models'* to send your prompt to every configured direct provider concurrently and compare their outputs side-by-side.\n\n⚙️ Tap the **Key icon** in the top bar to connect your live API keys.",
                activeProfileNotes = listOf("Active System Profile linked from Studio")
            )
        )
    }

    private fun createNativeConversation(
        template: NativeChatConversation? = null,
        now: Long = System.currentTimeMillis()
    ): NativeChatConversation = NativeChatConversation(
        id = UUID.randomUUID().toString(),
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        messages = welcomeChatMessages(),
        selectedProvider = template?.selectedProvider ?: AiProvider.ALL,
        selectedModel = template?.selectedModel ?: "all",
        includeSystemProfile = template?.includeSystemProfile ?: true,
        projectId = template?.projectId ?: DEFAULT_PROJECT_ID,
    )

    private fun initialNativeChatArchive(): NativeChatArchive {
        val conversation = createNativeConversation()
        return NativeChatArchive(
            activeConversationId = conversation.id,
            conversations = listOf(conversation)
        )
    }

    private fun initNativeChatPersistence() {
        val initial = initialNativeChatArchive()
        _uiState.update { it.copy(nativeChat = initial) }
        val cached = NativeChatWriter.currentArchive()?.normalized()
        if (cached != null && cached.conversations.isNotEmpty()) {
            synchronized(nativePersistenceLock) {
                nativePersistenceBase = cached
                _uiState.update {
                    it.copy(nativeChat = cached, isNativeConversationStoreReady = true)
                }
            }
            applyPendingNativeConversationTarget()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val restored = nativeChatWriter.read()
                ?.normalized()
                ?.takeIf { it.conversations.isNotEmpty() }
                ?: initial
            synchronized(nativePersistenceLock) {
                // Initialize only an empty writer; a worker may already have restored it.
                val latest = nativeChatWriter.currentArchive() ?: nativeChatWriter.enqueue(restored)
                nativePersistenceBase = latest
                _uiState.update {
                    it.copy(nativeChat = latest, isNativeConversationStoreReady = true)
                }
            }
            applyPendingNativeConversationTarget()
            applyPendingNativeChatShare()
        }
    }

    fun refreshNativeChatFromPersistence() {
        synchronized(nativePersistenceLock) {
            val state = _uiState.value
            if (!state.isNativeConversationStoreReady || state.isChatGenerating) return
            // Persist unsaved drafts against their baseline before showing worker changes.
            val refreshed = nativeChatWriter.enqueue(state.nativeChat, nativePersistenceBase)
            if (_uiState.compareAndSet(state, state.copy(nativeChat = refreshed))) {
                nativePersistenceBase = refreshed
            } else {
                nativePersistenceBase = state.nativeChat
            }
        }
        applyPendingNativeConversationTarget()
        applyPendingNativeChatShare()
    }

    fun selectTab(tab: NavigationTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun openNativeConversation(conversationId: String) {
        val target = conversationId.trim()
        if (target.isEmpty()) return
        selectTab(NavigationTab.COMPARE_HUB)
        pendingNativeConversationId.set(target)
        applyPendingNativeConversationTarget()
    }

    /**
     * Stages Direct Share text in the chosen native conversation's composer (never sends it). A
     * shortcut that no longer matches a conversation, or a share without text, falls back to the
     * normal share flow.
     */
    fun receiveNativeChatShare(shortcutId: String, payload: IncomingSharePayload) {
        pendingNativeChatShare.set(PendingNativeChatShare(shortcutId, payload))
        applyPendingNativeChatShare()
    }

    private fun applyPendingNativeChatShare() {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady) return
        val pending = pendingNativeChatShare.getAndSet(null) ?: return
        val text = pending.payload.text
        val conversationId = nativeChatConversationIdForShortcut(
            shortcutId = pending.shortcutId,
            conversationIds = state.nativeChat.conversations.map { it.id },
        )
        if (conversationId == null || text == null) {
            receiveIncomingShare(pending.payload)
            return
        }
        openNativeConversation(conversationId)
        _uiState.update { current ->
            current.copy(
                nativeChat = current.nativeChat.copy(
                    conversations = current.nativeChat.conversations.map { conversation ->
                        if (conversation.id == conversationId) {
                            conversation.copy(draft = stageSharedTextInDraft(conversation.draft, text))
                        } else {
                            conversation
                        }
                    }
                )
            )
        }
        persistNativeChat()
        if (pending.payload.uriStrings.isNotEmpty()) {
            showSnackbar("Added the shared text. Native chats do not take attachments yet.")
        }
    }

    private fun publishNativeConversationShortcut(conversationId: String) {
        val conversation = _uiState.value.nativeChat.conversations.firstOrNull { it.id == conversationId } ?: return
        if (conversation.messages.none { it.sender == CHAT_ROLE_USER }) return
        val title = conversation.title
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            NativeChatConversationShortcuts.publish(application, conversationId, title)
        }
    }

    private fun applyPendingNativeConversationTarget() {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady) return
        val requestedId = pendingNativeConversationId.getAndSet(null) ?: return
        val conversationId = resolveNativeConversationTarget(state.nativeChat, requestedId)
        if (conversationId == null) {
            showSnackbar("Conversation is no longer available.")
            return
        }
        switchNativeConversation(conversationId)
        val request = NativeChatNavigationRequest(
            id = nativeChatNavigationRequestId.incrementAndGet(),
            conversationId = conversationId
        )
        _uiState.update { it.copy(nativeChatNavigationRequest = request) }
    }

    fun consumeNativeConversationNavigationRequest(requestId: Long) {
        _uiState.update { state ->
            if (state.nativeChatNavigationRequest?.id == requestId) {
                state.copy(nativeChatNavigationRequest = null)
            } else {
                state
            }
        }
    }

    fun selectWebService(service: WebAiService) {
        _uiState.update { it.copy(selectedWebService = service) }
        webChatPreferencesStore.saveSelectedService(service)
    }

    fun stageWebDraftReply(service: WebAiService, text: String): Boolean {
        val staged = webChatDraftStore.stage(service, text) ?: return false
        _uiState.update { state ->
            state.copy(
                currentTab = NavigationTab.WEB_CHATS,
                selectedWebService = service,
                pendingWebDraft = PendingWebDraft(
                    id = staged.id,
                    service = service,
                    text = staged.text,
                ),
            )
        }
        webChatPreferencesStore.saveSelectedService(service)
        return true
    }

    fun openStagedWebDraft(service: WebAiService): Boolean {
        val staged = webChatDraftStore.peek(service) ?: return false
        _uiState.update { state ->
            state.copy(
                currentTab = NavigationTab.WEB_CHATS,
                selectedWebService = service,
                pendingWebDraft = PendingWebDraft(
                    id = staged.id,
                    service = service,
                    text = staged.text,
                ),
            )
        }
        webChatPreferencesStore.saveSelectedService(service)
        return true
    }

    fun claimPendingWebDraft(service: WebAiService, draftId: String): String? {
        while (true) {
            val state = _uiState.value
            val pending = state.pendingWebDraft
                ?.takeIf { it.service == service && it.id == draftId && !it.isClaimed }
                ?: return null
            val next = state.copy(pendingWebDraft = pending.copy(isClaimed = true))
            if (_uiState.compareAndSet(state, next)) return pending.text
        }
    }

    fun completePendingWebDraft(service: WebAiService, draftId: String): Boolean {
        val consumed = webChatDraftStore.consume(service, draftId)
        val latest = if (consumed) null else webChatDraftStore.peek(service)
        val staleWasReplaced = latest?.id != draftId
        _uiState.update { state ->
            if (state.pendingWebDraft?.let { it.service == service && it.id == draftId } == true) {
                state.copy(
                    pendingWebDraft = latest?.let { draft ->
                        PendingWebDraft(draft.id, draft.service, draft.text)
                    }
                )
            } else {
                state
            }
        }
        return consumed || staleWasReplaced
    }

    fun releasePendingWebDraftClaim(service: WebAiService, draftId: String): Boolean {
        while (true) {
            val state = _uiState.value
            val pending = state.pendingWebDraft
                ?.takeIf { it.service == service && it.id == draftId && it.isClaimed }
                ?: return false
            if (_uiState.compareAndSet(state, state.copy(pendingWebDraft = pending.copy(isClaimed = false)))) {
                return true
            }
        }
    }

    fun dismissPendingWebDraft(service: WebAiService, draftId: String): Boolean {
        val current = webChatDraftStore.peek(service)
        val cleared = when {
            current == null -> true
            current.id == draftId -> webChatDraftStore.clear(service)
            else -> true
        }
        if (!cleared) return false
        val latest = webChatDraftStore.peek(service)
        _uiState.update { state ->
            if (state.pendingWebDraft?.let { it.service == service && it.id == draftId } == true) {
                state.copy(
                    pendingWebDraft = latest?.let { draft ->
                        PendingWebDraft(draft.id, draft.service, draft.text)
                    }
                )
            } else {
                state
            }
        }
        return true
    }

    fun toggleFavoriteWebService(service: WebAiService) {
        val current = _uiState.value.favoriteWebServices
        val updated = if (service in current) current - service else current + service
        _uiState.update { it.copy(favoriteWebServices = updated) }
        webChatPreferencesStore.saveFavorites(updated)
    }

    fun receiveIncomingShare(payload: IncomingSharePayload) {
        val requestId = incomingShareId.incrementAndGet()
        _uiState.update {
            it.copy(
                incomingShare = payload,
                incomingShareId = requestId,
                pendingWebShare = null
            )
        }
    }

    fun restoreShareState(
        incomingShare: IncomingSharePayload?,
        incomingShareRequestId: Long,
        pendingShare: PendingWebShare?
    ) {
        val restoredIncomingId = when {
            incomingShare == null -> 0L
            incomingShareRequestId > 0L -> {
                incomingShareId.updateAndGet { current -> maxOf(current, incomingShareRequestId) }
                incomingShareRequestId
            }
            else -> incomingShareId.incrementAndGet()
        }
        pendingShare?.let { restored ->
            pendingWebShareId.updateAndGet { current -> maxOf(current, restored.id) }
        }
        _uiState.update { state ->
            if (state.incomingShare != null || state.pendingWebShare != null) return@update state
            when {
                pendingShare != null -> state.copy(
                    currentTab = NavigationTab.WEB_CHATS,
                    selectedWebService = pendingShare.service,
                    incomingShare = null,
                    incomingShareId = 0L,
                    pendingWebShare = pendingShare
                )
                incomingShare != null -> state.copy(
                    incomingShare = incomingShare,
                    incomingShareId = restoredIncomingId
                )
                else -> state
            }
        }
    }

    fun dismissIncomingShare() {
        _uiState.update { it.copy(incomingShare = null, incomingShareId = 0L) }
    }

    fun isIncomingShareCurrent(requestId: Long): Boolean {
        val state = _uiState.value
        return requestId > 0L && state.incomingShare != null && state.incomingShareId == requestId
    }

    fun dismissIncomingShareIfCurrent(requestId: Long): Boolean {
        while (true) {
            val state = _uiState.value
            if (requestId <= 0L || state.incomingShare == null || state.incomingShareId != requestId) return false
            if (_uiState.compareAndSet(
                    state,
                    state.copy(incomingShare = null, incomingShareId = 0L)
                )
            ) return true
        }
    }

    fun routeIncomingShareToWeb(service: WebAiService) {
        val shareId = pendingWebShareId.incrementAndGet()
        var routed = false
        _uiState.update { state ->
            val payload = state.incomingShare ?: return@update state
            routed = true
            state.copy(
                currentTab = NavigationTab.WEB_CHATS,
                selectedWebService = service,
                incomingShare = null,
                incomingShareId = 0L,
                pendingWebShare = PendingWebShare(shareId, service, payload)
            )
        }
        if (routed) webChatPreferencesStore.saveSelectedService(service)
    }

    fun claimPendingWebShareText(service: WebAiService, shareId: Long): String? {
        while (true) {
            val state = _uiState.value
            val pending = state.pendingWebShare
                ?.takeIf { it.service == service && it.id == shareId }
                ?: return null
            val text = pending.payload.text ?: return null
            val claimed = pending.claimText() ?: return null
            val next = state.copy(pendingWebShare = claimed)
            if (_uiState.compareAndSet(state, next)) return text
        }
    }

    fun completePendingWebShareText(service: WebAiService, shareId: Long): Boolean =
        updatePendingWebShare(
            service = service,
            shareId = shareId,
            predicate = { it.isTextClaimed && it.payload.text != null }
        ) { it.completeTextClaim() }

    fun releasePendingWebShareTextClaim(service: WebAiService, shareId: Long): Boolean =
        updatePendingWebShare(
            service = service,
            shareId = shareId,
            predicate = { it.isTextClaimed }
        ) { it.releaseTextClaim() }

    fun consumePendingWebShareUris(
        service: WebAiService,
        shareId: Long,
        uriStrings: Collection<String>
    ): Boolean {
        val consumed = uriStrings.toSet()
        if (consumed.isEmpty()) return false
        return updatePendingWebShare(
            service = service,
            shareId = shareId,
            predicate = { pending -> pending.payload.uriStrings.containsAll(consumed) }
        ) { pending ->
            val remainingUris = pending.payload.uriStrings.filterNot(consumed::contains)
            val payload = pending.payload.copy(
                uriStrings = remainingUris,
                mimeTypeHint = pending.payload.mimeTypeHint.takeIf { remainingUris.isNotEmpty() },
                isOpenDocument = pending.payload.isOpenDocument && remainingUris.isNotEmpty()
            )
            if (payload.isEmpty) null else pending.copy(payload = payload)
        }
    }

    fun dismissPendingWebShare(service: WebAiService, shareId: Long): Boolean =
        updatePendingWebShare(service, shareId) { null }

    private fun updatePendingWebShare(
        service: WebAiService,
        shareId: Long,
        predicate: (PendingWebShare) -> Boolean = { true },
        transform: (PendingWebShare) -> PendingWebShare?
    ): Boolean {
        while (true) {
            val state = _uiState.value
            val pending = state.pendingWebShare
                ?.takeIf { it.service == service && it.id == shareId }
                ?: return false
            if (!predicate(pending)) return false
            val next = state.copy(pendingWebShare = transform(pending))
            if (_uiState.compareAndSet(state, next)) return true
        }
    }

    // --- Chat Screen Actions ---

    private fun persistNativeChat() {
        synchronized(nativePersistenceLock) {
            val state = _uiState.value
            if (state.isNativeConversationStoreReady) {
                nativeChatWriter.enqueue(state.nativeChat, nativePersistenceBase)
                nativePersistenceBase = state.nativeChat
            }
        }
    }

    private fun updateActiveNativeConversation(
        persist: Boolean = true,
        transform: (NativeChatConversation) -> NativeChatConversation
    ) {
        _uiState.update { state ->
            state.copy(nativeChat = state.nativeChat.updateActiveConversation(transform))
        }
        if (persist) persistNativeChat()
    }

    private fun scheduleNativeChatDraftPersistence() {
        nativeChatDraftPersistenceJob?.cancel()
        nativeChatDraftPersistenceJob = viewModelScope.launch {
            delay(NATIVE_CHAT_DRAFT_PERSIST_DELAY_MS)
            nativeChatDraftPersistenceJob = null
            persistNativeChat()
        }
    }

    fun updateNativeConversationDraft(draft: String) {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady || state.activeNativeConversation?.draft == draft) return
        updateActiveNativeConversation(persist = false) { it.copy(draft = draft) }
        scheduleNativeChatDraftPersistence()
    }

    suspend fun createProject(name: String): Boolean {
        val project = withContext(Dispatchers.IO) {
            projectLibraryStore.createProject(name)
        } ?: return false
        reloadProjectLibraryFromDisk()
        moveActiveConversationToProject(project.id)
        return true
    }

    fun moveActiveConversationToProject(projectId: String): Boolean {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady) return false
        if (state.projectLibrary.projects.none { it.id == projectId }) return false
        updateActiveNativeConversation { conversation ->
            conversation.copy(projectId = projectId, updatedAtEpochMs = System.currentTimeMillis())
        }
        return true
    }

    suspend fun saveActiveChatToProjectLibrary(): ProjectLibraryAsset? {
        val conversation = _uiState.value.activeNativeConversation ?: return null
        val markdown = withContext(Dispatchers.Default) {
            renderChatMarkdown(
                messages = conversation.messages,
                maxUtf8Bytes = ProjectLibraryStore.MAX_ASSET_BYTES,
            )
        } ?: return null
        val asset = withContext(Dispatchers.IO) {
            projectLibraryStore.saveTextAsset(
                projectId = conversation.projectId,
                title = conversation.title,
                mediaType = "text/markdown",
                extension = "md",
                text = markdown,
            )
        } ?: return null
        reloadProjectLibraryFromDisk()
        return asset
    }

    suspend fun saveTableCsvToProjectLibrary(csv: String): ProjectLibraryAsset? {
        val conversation = _uiState.value.activeNativeConversation ?: return null
        val asset = withContext(Dispatchers.IO) {
            projectLibraryStore.saveTextAsset(
                projectId = conversation.projectId,
                title = "${conversation.title} table",
                mediaType = "text/csv",
                extension = "csv",
                text = csv,
            )
        } ?: return null
        reloadProjectLibraryFromDisk()
        return asset
    }

    suspend fun loadProjectLibraryAsset(assetId: String): String? =
        withContext(Dispatchers.IO) { projectLibraryStore.loadTextAsset(assetId)?.text }

    suspend fun deleteProjectLibraryAsset(assetId: String): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            projectLibraryStore.deleteAsset(assetId)
        }
        if (deleted) reloadProjectLibraryFromDisk()
        return deleted
    }

    suspend fun importNativeChatMarkdown(source: String): Boolean {
        if (!_uiState.value.isNativeConversationStoreReady) return false
        val imported = withContext(Dispatchers.Default) {
            parseChatMarkdown(source).chat
        } ?: return false
        val now = System.currentTimeMillis()
        val messages = imported.turns.mapIndexed { index, turn ->
            ModelChatMessage(
                id = "import_${now}_${index}",
                sender = turn.role,
                text = turn.text,
                timestamp = now + index,
                isImported = true,
            )
        }
        val firstUser = messages.firstOrNull { it.sender == CHAT_ROLE_USER } ?: return false
        val template = _uiState.value.activeNativeConversation
        val conversation = NativeChatConversation(
            id = UUID.randomUUID().toString(),
            title = nativeConversationTitle(firstUser.text),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            messages = messages,
            selectedProvider = template?.selectedProvider ?: AiProvider.ALL,
            selectedModel = template?.selectedModel ?: "all",
            includeSystemProfile = template?.includeSystemProfile ?: true,
            projectId = template?.projectId ?: DEFAULT_PROJECT_ID,
        )
        cancelChatGeneration()
        _uiState.update { state ->
            state.copy(
                nativeChat = state.nativeChat.copy(
                    activeConversationId = conversation.id,
                    conversations = listOf(conversation) + state.nativeChat.conversations,
                )
            )
        }
        persistNativeChat()
        withContext(Dispatchers.IO) {
            projectLibraryStore.saveTextAsset(
                projectId = conversation.projectId,
                title = conversation.title,
                mediaType = "text/markdown",
                extension = "md",
                text = source,
            )
        }
        reloadProjectLibraryFromDisk()
        return true
    }

    fun newNativeConversation() {
        if (!_uiState.value.isNativeConversationStoreReady) return
        cancelChatGeneration()
        val template = _uiState.value.activeNativeConversation
        val conversation = createNativeConversation(template)
        _uiState.update { state ->
            state.copy(
                nativeChat = state.nativeChat.copy(
                    activeConversationId = conversation.id,
                    conversations = listOf(conversation) + state.nativeChat.conversations
                )
            )
        }
        persistNativeChat()
    }

    fun switchNativeConversation(conversationId: String) {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady || state.nativeChat.activeConversationId == conversationId) return
        if (state.nativeChat.conversations.none { it.id == conversationId }) return
        cancelChatGeneration()
        _uiState.update { current ->
            current.copy(nativeChat = current.nativeChat.copy(activeConversationId = conversationId))
        }
        persistNativeChat()
        publishNativeConversationShortcut(conversationId)
    }

    fun deleteNativeConversation(conversationId: String) {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady) return
        if (state.nativeChat.activeConversationId == conversationId) cancelChatGeneration()
        _uiState.update { current ->
            val retained = current.nativeChat.conversations.filterNot { it.id == conversationId }
            val conversations = retained.ifEmpty { listOf(createNativeConversation()) }
            val activeId = current.nativeChat.activeConversationId
                .takeIf { id -> conversations.any { it.id == id } }
                ?: conversations.maxByOrNull { it.updatedAtEpochMs }?.id.orEmpty()
            current.copy(
                nativeChat = current.nativeChat.copy(
                    activeConversationId = activeId,
                    conversations = conversations
                )
            )
        }
        persistNativeChat()
        NativeChatNotificationPublisher.cancelConversation(getApplication(), conversationId)
        NativeChatConversationShortcuts.remove(getApplication(), conversationId)
    }

    fun setChatProvider(provider: AiProvider) {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady) return
        val knownModels = state.gatewayModelOptions[provider] ?: provider.availableModels
        val newModel = when {
            provider == AiProvider.ALL -> "all"
            state.selectedChatProvider == provider && state.selectedChatModel in knownModels -> state.selectedChatModel
            knownModels.isNotEmpty() -> knownModels.first()
            else -> ""
        }
        updateActiveNativeConversation { conversation ->
            conversation.copy(
                selectedProvider = provider,
                selectedModel = newModel,
                apiProcessingMode = provider.normalizeApiProcessingMode(conversation.apiProcessingMode),
            )
        }
        if (provider.usesLiveGatewayModelCatalog()) refreshGatewayModelCatalog(provider)
    }

    fun setChatModel(modelName: String) {
        if (!_uiState.value.isNativeConversationStoreReady) return
        updateActiveNativeConversation { it.copy(selectedModel = modelName) }
    }

    fun setApiProcessingMode(mode: ApiProcessingMode) {
        val state = _uiState.value
        if (!state.isNativeConversationStoreReady) return
        val normalized = state.selectedChatProvider.normalizeApiProcessingMode(mode)
        updateActiveNativeConversation { it.copy(apiProcessingMode = normalized) }
    }

    fun refreshGatewayModelCatalog(provider: AiProvider) {
        if (!provider.usesLiveGatewayModelCatalog()) return
        val state = _uiState.value
        if (provider in state.refreshingGatewayCatalogs) {
            pendingGatewayCatalogRefreshes += provider
            return
        }
        val apiKeys = state.apiKeyConfig
        _uiState.update {
            it.copy(refreshingGatewayCatalogs = it.refreshingGatewayCatalogs + provider)
        }

        viewModelScope.launch {
            try {
                val models = aiChatService.fetchGatewayModels(provider, apiKeys)
                _uiState.update { current ->
                    val selectedModel = if (current.selectedChatProvider == provider) {
                        when {
                            current.selectedChatModel in models -> current.selectedChatModel
                            models.isNotEmpty() -> models.first()
                            else -> ""
                        }
                    } else {
                        current.selectedChatModel
                    }
                    val nativeChat = if (current.selectedChatProvider == provider) {
                        current.nativeChat.updateActiveConversation { conversation ->
                            conversation.copy(selectedModel = selectedModel)
                        }
                    } else {
                        current.nativeChat
                    }
                    current.copy(
                        gatewayModelOptions = current.gatewayModelOptions + (provider to models),
                        nativeChat = nativeChat
                    )
                }
                persistNativeChat()
                if (models.isEmpty()) {
                    showSnackbar("No compatible text models are currently available for ${provider.shortName}.")
                }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                showSnackbar("Could not refresh ${provider.shortName} models; using cached or bundled options.")
            } finally {
                _uiState.update {
                    it.copy(refreshingGatewayCatalogs = it.refreshingGatewayCatalogs - provider)
                }
                if (pendingGatewayCatalogRefreshes.remove(provider)) {
                    refreshGatewayModelCatalog(provider)
                }
            }
        }
    }

    fun toggleIncludeSystemProfile(include: Boolean) {
        if (!_uiState.value.isNativeConversationStoreReady) return
        updateActiveNativeConversation { it.copy(includeSystemProfile = include) }
        showSnackbar(if (include) "System profile prompt attached to chat" else "Standard base model prompt mode")
    }

    fun setShowApiKeyDialog(show: Boolean) {
        _uiState.update { it.copy(showApiKeyDialog = show) }
    }

    fun saveApiKeys(
        geminiKey: String,
        openAiKey: String,
        claudeKey: String,
        deepseekKey: String = "",
        kimiKey: String = "",
        openRouterKey: String = "",
        aiHubMixKey: String = "",
        vercelAiGatewayKey: String = ""
    ) {
        val config = ApiKeyConfig(
            geminiKey = geminiKey.trim(),
            openAiKey = openAiKey.trim(),
            claudeKey = claudeKey.trim(),
            deepseekKey = deepseekKey.trim(),
            kimiKey = kimiKey.trim(),
            openRouterKey = openRouterKey.trim(),
            aiHubMixKey = aiHubMixKey.trim(),
            vercelAiGatewayKey = vercelAiGatewayKey.trim()
        )
        val stored = apiKeyStore.save(config)
        _uiState.update {
            it.copy(
                apiKeyConfig = config,
                showApiKeyDialog = false
            )
        }
        showSnackbar(if (stored) "API keys stored securely on device." else "API keys updated for this session only.")
        val selectedProvider = _uiState.value.selectedChatProvider
        if (selectedProvider.usesLiveGatewayModelCatalog()) {
            refreshGatewayModelCatalog(selectedProvider)
        }
    }

    fun clearChatHistory() {
        if (!_uiState.value.isNativeConversationStoreReady) return
        cancelChatGeneration()
        val conversationId = _uiState.value.nativeChat.activeConversationId
        val now = System.currentTimeMillis()
        updateActiveNativeConversation { conversation ->
            conversation.copy(
                title = DEFAULT_NATIVE_CONVERSATION_TITLE,
                updatedAtEpochMs = now,
                messages = welcomeChatMessages(),
                replyEpoch = maxOf(now, conversation.replyEpoch + 1),
            )
        }
        NativeChatNotificationPublisher.cancelConversation(getApplication(), conversationId)
        showSnackbar("Current conversation cleared.")
    }

    fun cancelChatGeneration() {
        if (!_uiState.value.isChatGenerating) return
        val now = System.currentTimeMillis()
        var cancelled = false
        streamingTextBatcher.withExclusiveAccess {
            if (!_uiState.value.isChatGenerating) return@withExclusiveAccess
            val cancelledGenerationId = activeChatGenerationId.get()
            flushStreamingGenerationLocked(cancelledGenerationId)
            activeChatGenerationId.incrementAndGet()
            streamingUiFlushJob?.cancel()
            streamingUiFlushJob = null
            streamingTextBatcher.drainGeneration(cancelledGenerationId)
            chatGenerationJob?.cancel()
            chatGenerationJob = null
            _uiState.update { state ->
                val messages = state.chatMessages.map { message ->
                    if (message.isPartial) {
                        message.copy(
                            activeProfileNotes = (message.activeProfileNotes + "Generation stopped").distinct()
                        )
                    } else {
                        message
                    }
                }
                state.copy(
                    nativeChat = state.nativeChat.updateActiveConversation { conversation ->
                        conversation.copy(messages = messages, updatedAtEpochMs = now)
                    },
                    isChatGenerating = false,
                    activeGeneratingProviders = emptySet()
                )
            }
            cancelled = true
        }
        if (cancelled) persistNativeChat()
    }

    private fun flushStreamingGeneration(generationId: Long) {
        streamingTextBatcher.withExclusiveAccess {
            flushStreamingGenerationLocked(generationId)
        }
    }

    private fun flushStreamingGenerationLocked(generationId: Long) {
        if (generationId != activeChatGenerationId.get()) {
            streamingTextBatcher.drainGeneration(generationId)
            return
        }
        val batches = streamingTextBatcher.drainGeneration(generationId)
        if (batches.isEmpty()) return
        _uiState.update { state ->
            var messages = state.chatMessages
            batches.forEach { batch ->
                val target = batch.target
                val index = messages.indexOfFirst { it.id == target.messageId }
                messages = if (index >= 0) {
                    messages.toMutableList().apply {
                        this[index] = this[index].copy(text = this[index].text + batch.text)
                    }
                } else {
                    messages + ModelChatMessage(
                        id = target.messageId,
                        sender = CHAT_ROLE_ASSISTANT,
                        provider = target.provider,
                        modelName = target.model,
                        text = batch.text,
                        isPartial = true
                    )
                }
            }
            state.copy(
                nativeChat = state.nativeChat.updateActiveConversation { conversation ->
                    conversation.copy(messages = messages)
                }
            )
        }
    }

    private fun appendStreamingDelta(
        generationId: Long,
        messageId: String,
        provider: AiProvider,
        model: String,
        delta: String
    ) {
        if (delta.isEmpty()) return
        streamingTextBatcher.withExclusiveAccess {
            if (generationId != activeChatGenerationId.get()) return@withExclusiveAccess
            streamingTextBatcher.append(
                StreamingTextTarget(generationId, messageId, provider, model),
                delta
            )
            if (_uiState.value.chatMessages.none { it.id == messageId }) {
                flushStreamingGenerationLocked(generationId)
            }
        }
    }

    private fun finishStreamingMessage(
        generationId: Long,
        messageId: String,
        provider: AiProvider,
        response: ModelChatMessage
    ) {
        var finished = false
        streamingTextBatcher.withExclusiveAccess {
            if (generationId != activeChatGenerationId.get()) return@withExclusiveAccess
            flushStreamingGenerationLocked(generationId)
            streamingTextBatcher.discard(generationId, messageId)
            val now = System.currentTimeMillis()
            _uiState.update { state ->
                val finalMessage = response.copy(id = messageId)
                val index = state.chatMessages.indexOfFirst { it.id == messageId }
                val messages = if (index >= 0) {
                    state.chatMessages.toMutableList().apply { this[index] = finalMessage }
                } else {
                    state.chatMessages + finalMessage
                }
                state.copy(
                    nativeChat = state.nativeChat.updateActiveConversation { conversation ->
                        conversation.copy(messages = messages, updatedAtEpochMs = now)
                    },
                    activeGeneratingProviders = state.activeGeneratingProviders - provider
                )
            }
            finished = true
        }
        if (finished) persistNativeChat()
    }

    private fun resolveNativeChatSendPlan(state: StudioUiState): NativeChatSendPlan? {
        val targetProvider = state.selectedChatProvider
        if (
            targetProvider.usesLiveGatewayModelCatalog() &&
            state.gatewayModelOptions[targetProvider]?.isEmpty() == true
        ) {
            showSnackbar("No compatible models are currently available for ${targetProvider.shortName}.")
            return null
        }
        val providersToRun = if (targetProvider == AiProvider.ALL) {
            state.apiKeyConfig.configuredDirectProviders()
        } else {
            listOf(targetProvider)
        }
        if (targetProvider == AiProvider.ALL && providersToRun.isEmpty()) {
            _uiState.update { it.copy(showApiKeyDialog = true) }
            showSnackbar("Add at least one direct provider API key to use All Models.")
            return null
        }
        return NativeChatSendPlan(targetProvider, providersToRun, state.apiKeyConfig)
    }

    private suspend fun prepareNativeChatPromptContext(state: StudioUiState): NativeChatPromptContext? {
        val profileSystemPrompt = if (state.includeSystemProfileInChat) {
            state.renderedInstructions.ifBlank { null }
        } else {
            null
        }
        val enabledLocalSkills = try {
            localSkillStore.loadEnabledManifests()
        } catch (error: IOException) {
            showSnackbar(
                (error.message ?: "Could not prepare enabled local skills.") +
                    " No provider request was sent."
            )
            return null
        }
        return NativeChatPromptContext(
            systemPrompt = composeLocalSkillSystemInstruction(profileSystemPrompt, enabledLocalSkills),
            activeProfile = state.mergedProfile.takeIf { state.includeSystemProfileInChat }
        )
    }

    fun sendChatMessage(prompt: String): Boolean {
        val trimmed = prompt.trim()
        val initialState = _uiState.value
        if (!initialState.isNativeConversationStoreReady) {
            showSnackbar("Conversation history is still loading.")
            return false
        }
        if (trimmed.isBlank() || initialState.isChatGenerating) return false

        val plan = resolveNativeChatSendPlan(initialState) ?: return false
        val targetProvider = plan.targetProvider
        val providersToRun = plan.providersToRun
        val apiKeys = plan.apiKeys

        val generationId = streamingTextBatcher.withExclusiveAccess {
            streamingUiFlushJob?.cancel()
            val id = activeChatGenerationId.incrementAndGet()
            streamingUiFlushJob = viewModelScope.launch {
                while (id == activeChatGenerationId.get()) {
                    delay(STREAMING_UI_FLUSH_INTERVAL_MS)
                    flushStreamingGeneration(id)
                }
            }
            id
        }
        _uiState.update {
            it.copy(
                isChatGenerating = true,
                activeGeneratingProviders = emptySet()
            )
        }

        chatGenerationJob = viewModelScope.launch {
            try {
                val promptContext = prepareNativeChatPromptContext(_uiState.value) ?: return@launch

                currentCoroutineContext().ensureActive()
                if (generationId != activeChatGenerationId.get()) return@launch
                val now = System.currentTimeMillis()
                val userMessage = ModelChatMessage(
                    id = "user_$now",
                    sender = CHAT_ROLE_USER,
                    text = trimmed,
                    timestamp = now
                )
                _uiState.update { current ->
                    val conversation = current.activeNativeConversation
                    val firstUserTurn = conversation?.messages?.none { it.sender == CHAT_ROLE_USER } != false
                    current.copy(
                        nativeChat = current.nativeChat.updateActiveConversation { active ->
                            active.copy(
                                title = if (firstUserTurn) nativeConversationTitle(trimmed) else active.title,
                                updatedAtEpochMs = now,
                                messages = active.messages + userMessage,
                                draft = if (active.draft.trim() == trimmed) "" else active.draft
                            )
                        },
                        activeGeneratingProviders = providersToRun.toSet()
                    )
                }
                persistNativeChat()
                _uiState.value.nativeChat.activeConversationId.let(::publishNativeConversationShortcut)
                val currentMessages = _uiState.value.chatMessages
                val activeProjectId = _uiState.value.activeNativeConversation?.projectId
                    ?: DEFAULT_PROJECT_ID
                val benchTools = NativeBenchChatTools(
                    context = getApplication(),
                    projectId = activeProjectId,
                )
                val toolDefinitions = benchTools.definitions()

                executeNativeChatSend(
                    request = NativeChatSendRequest(
                        prompt = trimmed,
                        targetProvider = targetProvider,
                        providersToRun = providersToRun,
                        selectedModel = _uiState.value.selectedChatModel,
                        apiProcessingMode = _uiState.value.apiProcessingMode,
                        apiKeys = apiKeys,
                        systemInstruction = promptContext.systemPrompt,
                        profile = promptContext.activeProfile,
                        conversationHistory = currentMessages,
                        allowSingleProviderSimulationFallback = targetProvider != AiProvider.ALL,
                        tools = toolDefinitions,
                        executeTool = benchTools::execute,
                    ),
                    aiChatService = aiChatService,
                    onTextDelta = { provider, model, delta ->
                        appendStreamingDelta(
                            generationId,
                            "stream_${userMessage.id}_${provider.id}",
                            provider,
                            model,
                            delta,
                        )
                    },
                    onResponse = { generated ->
                        finishStreamingMessage(
                            generationId,
                            "stream_${userMessage.id}_${generated.provider.id}",
                            generated.provider,
                            generated.message,
                        )
                    },
                )
                reloadProjectLibraryFromDisk()
                _uiState.value.activeNativeConversation?.let { conversation ->
                    NativeChatNotificationPublisher.publishConversation(
                        getApplication(),
                        conversation,
                    )
                }
            } finally {
                streamingTextBatcher.withExclusiveAccess {
                    if (generationId == activeChatGenerationId.get()) {
                        flushStreamingGenerationLocked(generationId)
                        streamingUiFlushJob?.cancel()
                        streamingUiFlushJob = null
                        streamingTextBatcher.drainGeneration(generationId)
                        chatGenerationJob = null
                        _uiState.update {
                            it.copy(
                                isChatGenerating = false,
                                activeGeneratingProviders = emptySet()
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    // --- Profile & Studio Customization Actions ---

    fun setLanguage(lang: String) {
        _uiState.update { it.copy(language = lang) }
        recompute()
    }

    fun setBasePersonality(base: String) {
        val updated = _uiState.value.baseProfile.copy(
            personality = _uiState.value.baseProfile.personality.copy(base = base)
        )
        _uiState.update { it.copy(baseProfile = updated) }
        recompute()
    }

    fun setPersonalityIntensity(intensity: Int?) {
        val updated = _uiState.value.baseProfile.copy(
            personality = _uiState.value.baseProfile.personality.copy(intensity = intensity)
        )
        _uiState.update { it.copy(baseProfile = updated) }
        recompute()
    }

    fun setModifier(name: String, value: Int?) {
        val currentMods = _uiState.value.baseProfile.personality.modifiers.toMutableMap()
        if (value != null) {
            currentMods[name] = value
        } else {
            currentMods.remove(name)
        }
        val updated = _uiState.value.baseProfile.copy(
            personality = _uiState.value.baseProfile.personality.copy(modifiers = currentMods)
        )
        _uiState.update { it.copy(baseProfile = updated) }
        recompute()
    }

    fun setAdaptation(key: String, value: Boolean) {
        val currentAdapt = _uiState.value.baseProfile.personality.adaptation
        val updatedAdapt = when (key) {
            "followUserRegister" -> currentAdapt.copy(followUserRegister = value)
            "preserveRequestedArtifactStyle" -> currentAdapt.copy(preserveRequestedArtifactStyle = value)
            "reduceHumorInSeriousContexts" -> currentAdapt.copy(reduceHumorInSeriousContexts = value)
            "mirrorLanguage" -> currentAdapt.copy(mirrorLanguage = value)
            "allowCasualProfanity" -> currentAdapt.copy(allowCasualProfanity = value)
            else -> currentAdapt
        }
        val updated = _uiState.value.baseProfile.copy(
            personality = _uiState.value.baseProfile.personality.copy(adaptation = updatedAdapt)
        )
        _uiState.update { it.copy(baseProfile = updated) }
        recompute()
    }

    fun setCollaborationEnum(field: String, value: String) {
        val current = _uiState.value.baseProfile.collaboration
        val updated = when (field) {
            "preamble" -> current.copy(preamble = value)
            "initiative" -> current.copy(initiative = value)
            "verification" -> current.copy(verification = value)
            "questionPolicy" -> current.copy(questionPolicy = value)
            "assumptionPolicy" -> current.copy(assumptionPolicy = value)
            else -> current
        }
        _uiState.update { it.copy(baseProfile = it.baseProfile.copy(collaboration = updated)) }
        recompute()
    }

    fun setCollaborationBool(field: String, value: Boolean) {
        val current = _uiState.value.baseProfile.collaboration
        val updated = when (field) {
            "answerFirst" -> current.copy(answerFirst = value)
            "plainChatIsDefault" -> current.copy(plainChatIsDefault = value)
            "respectExplicitTurnInstructions" -> current.copy(respectExplicitTurnInstructions = value)
            "avoidRoutinePraise" -> current.copy(avoidRoutinePraise = value)
            "avoidRoutineFollowUpOffer" -> current.copy(avoidRoutineFollowUpOffer = value)
            "announceOnlyMaterialActions" -> current.copy(announceOnlyMaterialActions = value)
            "reportPartialFailures" -> current.copy(reportPartialFailures = value)
            "preferResultOverProcess" -> current.copy(preferResultOverProcess = value)
            else -> current
        }
        _uiState.update { it.copy(baseProfile = it.baseProfile.copy(collaboration = updated)) }
        recompute()
    }

    fun setOutputSetting(
        defaultFormat: String? = null,
        maxHeadingDepth: Int? = null,
        preferShortParagraphs: Boolean? = null,
        tables: String? = null,
        codeExamples: String? = null,
        citations: String? = null
    ) {
        val current = _uiState.value.baseProfile.output
        val updated = current.copy(
            defaultFormat = defaultFormat ?: current.defaultFormat,
            maxHeadingDepth = maxHeadingDepth ?: current.maxHeadingDepth,
            preferShortParagraphs = preferShortParagraphs ?: current.preferShortParagraphs,
            tables = tables ?: current.tables,
            codeExamples = codeExamples ?: current.codeExamples,
            citations = citations ?: current.citations
        )
        _uiState.update { it.copy(baseProfile = it.baseProfile.copy(output = updated)) }
        recompute()
    }

    fun applyOverlay(overlay: ProfileOverlay?) {
        _uiState.update { it.copy(selectedOverlay = overlay) }
        recompute()
    }

    internal fun replacePersistedStudioState(
        baseProfile: Profile,
        customOverlays: List<ProfileOverlay>,
        selectedOverlay: ProfileOverlay?,
        language: String
    ) {
        _uiState.update { current ->
            current.copy(
                baseProfile = baseProfile,
                availableOverlays = PresetProfiles.BuiltInOverlays + customOverlays,
                selectedOverlay = selectedOverlay,
                language = language
            )
        }
        recompute()
    }

    fun resetToDefault() {
        _uiState.update {
            it.copy(
                baseProfile = PresetProfiles.DefaultBaseProfile,
                selectedOverlay = null
            )
        }
        recompute()
        showSnackbar("Reset profile to standard default.")
    }

    fun saveCustomOverlay(name: String, description: String) {
        val base = _uiState.value.baseProfile
        val newOverlay = ProfileOverlay(
            id = name.lowercase().replace(" ", "-"),
            name = name,
            description = description,
            locale = base.locale,
            personalityBase = base.personality.base,
            personalityIntensity = base.personality.intensity,
            modifierOverrides = base.personality.modifiers.filter { (it.value ?: 0) > 0 },
            verification = base.collaboration.verification,
            initiative = base.collaboration.initiative,
            customNote = "Exported from Aistee."
        )
        val updatedList = _uiState.value.availableOverlays + newOverlay
        _uiState.update {
            it.copy(
                availableOverlays = updatedList,
                selectedOverlay = newOverlay
            )
        }
        recompute()
        showSnackbar("Saved overlay: $name")
    }

    fun clearPlaygroundChat() {
        initPlaygroundWelcome()
    }

    fun sendTestPrompt(userPrompt: String) {
        if (userPrompt.isBlank()) return

        val userMsg = ChatMessage(
            id = "user_${System.currentTimeMillis()}",
            sender = "user",
            text = userPrompt
        )

        val currentList = _uiState.value.playgroundMessages + userMsg
        _uiState.update { it.copy(playgroundMessages = currentList, isSimulating = true) }

        viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            val profile = _uiState.value.mergedProfile
            val simulatedText = generateSimulatedResponse(userPrompt, profile)
            val notes = mutableListOf<String>()
            notes.add("Base Voice: ${profile.personality.base} (lvl ${profile.personality.intensity ?: 1})")
            profile.personality.modifiers.filter { (it.value ?: 0) > 0 }.forEach { (k, v) ->
                notes.add("$k: $v")
            }
            notes.add("Initiative: ${profile.collaboration.initiative}, Verification: ${profile.collaboration.verification}")

            val botMsg = ChatMessage(
                id = "bot_${System.currentTimeMillis()}",
                sender = "assistant",
                text = simulatedText,
                notes = notes
            )
            _uiState.update {
                it.copy(
                    playgroundMessages = it.playgroundMessages + botMsg,
                    isSimulating = false
                )
            }
        }
    }

    private fun generateSimulatedResponse(prompt: String, profile: Profile): String {
        val base = profile.personality.base
        val intensity = profile.personality.intensity ?: 1
        val conciseLevel = profile.personality.modifiers["concise"] ?: 0
        val technicalLevel = profile.personality.modifiers["technical"] ?: 0
        val cynicalLevel = profile.personality.modifiers["cynical"] ?: 0
        val educationalLevel = profile.personality.modifiers["educational"] ?: 0
        val honestLevel = profile.personality.modifiers["honest"] ?: 0

        val lowerPrompt = prompt.lowercase()

        return when {
            lowerPrompt.contains("git submodule") || lowerPrompt.contains("submodule") -> {
                when (base) {
                    "concise" -> "Git submodules embed a separate repo as a subdirectory pinned to a specific commit SHA.\n\n```bash\ngit submodule add <url> <path>\ngit submodule update --init --recursive\n```\nAvoid merge commits across submodule boundaries."
                    "cynical" -> "Git submodules are Git's clunkiest primitive—they pin external repos to exact commit SHAs. Most teams shoot themselves in the foot by forgetting recursive checkouts.\n\nIf you must use them for portable cores like `.ai`:\n```bash\ngit submodule update --init --recursive\n```"
                    "professional" -> "A Git submodule integrates an external repository into a designated path while retaining independent version history.\n\n### Specifications:\n- Stores pointer in `.gitmodules`.\n- Pins dependency to an explicit commit hash, avoiding unvetted upstream breakage.\n\n### Primary Commands:\n```bash\ngit submodule add https://github.com/trvny/.ai.git .ai/core\ngit submodule update --init --recursive\n```"
                    else -> "Git submodules allow you to keep an external Git repository inside a subfolder of your main project, pinned to a specific commit.\n\nIn `.ai`, this lets you pin the public reusable core at `.ai/core` while keeping downstream overlays separate:\n\n```bash\ngit submodule add https://github.com/trvny/.ai.git .ai/core\ncp .ai/core/examples/profile.overlay.yaml .ai/profile.yaml\n```\nLet me know if you need help with CI workflows or merge hooks!"
                }
            }
            lowerPrompt.contains("code review") || lowerPrompt.contains("review") -> {
                when {
                    cynicalLevel >= 2 || base == "cynical" -> "### Code Review: Reality Check\n1. **Over-engineering**: This PR adds 4 new abstract factories where a simple pure function would suffice.\n2. **Security**: Token is passed via plain query parameters instead of authorization headers.\n3. **Recommendation**: Delete the caching layer until telemetry proves a bottleneck."
                    technicalLevel >= 2 || base == "professional" -> "### Static Review & Verification\n1. **Invariant Check**: Thread safety is compromised in the mutable map singleton. Use `ConcurrentHashMap` or state flow synchronization.\n2. **Complexity**: O(N^2) search in loop block at line 42 can be reduced to O(N) via set hashing.\n3. **Patch**:\n```kotlin\nval lookupSet = items.map { it.id }.toSet()\nreturn list.filter { it.ref in lookupSet }\n```"
                    else -> "Here is a constructive review of the proposed changes:\n\n- The architectural separation between core profiles and downstream overlays is clean.\n- Consider adding input validation for boundary edge cases.\n- All unit tests pass cleanly!"
                }
            }
            lowerPrompt.contains("idea") || lowerPrompt.contains("startup") -> {
                when {
                    cynicalLevel >= 2 -> "90% of AI wrapper startups die from lack of distribution. You don't have a moat if an API update renders your product obsolete. Talk to 10 paying customers before writing another line of code."
                    honestLevel >= 2 -> "The core concept has value, but you need to solve unit economics first. Building features before validating willingness to pay is a common pitfall. Focus on customer acquisition costs."
                    else -> "That is an intriguing concept! Validating customer demand early will give you strong signal. Let's look at user pain points and minimum viable features."
                }
            }
            else -> {
                if (conciseLevel >= 2 || base == "concise") {
                    "Understood. For '$prompt', here is the direct solution with zero fluff:\n- Verify inputs against schema\n- Merge overlays recursively (later layers win)\n- Render instructions to target runtime."
                } else if (educationalLevel >= 2) {
                    "Let's break down '$prompt' step-by-step to build clear intuition first.\n\n### The Concept\nAt its core, the principle is modular composition: later layers win, while base defaults provide safety.\n\n### Practical Example\nWhen you compose a profile, scalars replace older values while mappings merge recursively."
                } else {
                    "Regarding '$prompt': The `.ai` core architecture maintains reusable AI profiles, overlays, and instructions in one public place while keeping project-specific private differences downstream."
                }
            }
        }
    }

    private fun recompute() {
        val base = _uiState.value.baseProfile
        val overlay = _uiState.value.selectedOverlay

        val merged = if (overlay != null) {
            ProfileMerger.merge(base, overlay)
        } else {
            base
        }

        val rendered = InstructionRenderer.render(merged, _uiState.value.language)
        val validation = ProfileMerger.validate(merged)
        val yaml = YamlParser.dumpProfile(merged)

        _uiState.update {
            it.copy(
                mergedProfile = merged,
                renderedInstructions = rendered,
                validationResult = validation,
                yamlRepresentation = yaml
            )
        }
    }

    private fun startStudioPersistence(useFallback: Boolean) {
        val writer = StudioStateWriter.getInstance(studioStateStore, fallback = useFallback)
            .also { studioStateWriter = it }
        val ownerId = writer.registerOwner().also { studioPersistenceOwnerId = it }
        writer.enqueue(uiState.value.toStudioStateSnapshot(), ownerId)
        studioPersistenceJob = viewModelScope.launch {
            uiState
                .map { it.toStudioStateSnapshot() }
                .distinctUntilChanged()
                .collect { snapshot -> writer.enqueue(snapshot, ownerId) }
        }
    }

    override fun onCleared() {
        val latestState = streamingTextBatcher.withExclusiveAccess {
            val generationId = activeChatGenerationId.get()
            flushStreamingGenerationLocked(generationId)
            activeChatGenerationId.incrementAndGet()
            streamingUiFlushJob?.cancel()
            streamingUiFlushJob = null
            streamingTextBatcher.drainGeneration(generationId)
            uiState.value
        }
        val latestSnapshot = latestState.toStudioStateSnapshot()
        nativeChatDraftPersistenceJob?.cancel()
        studioPersistenceJob?.cancel()
        studioPersistenceOwnerId?.let { ownerId ->
            studioStateWriter?.enqueue(latestSnapshot, ownerId)
        }
        persistNativeChat()
        super.onCleared()
    }

    fun showSnackbar(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}

private data class PendingNativeChatShare(
    val shortcutId: String,
    val payload: IncomingSharePayload,
)

internal fun stageSharedTextInDraft(draft: String, sharedText: String): String =
    if (draft.isBlank()) sharedText else draft.trimEnd() + "\n\n" + sharedText
