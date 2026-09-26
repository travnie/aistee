package ais.tee

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ais.tee.data.document.MarkdownDocumentFileAccess
import ais.tee.data.document.MarkdownWorkspaceRecoveryStore
import ais.tee.data.model.WebAiService
import ais.tee.data.model.webChatSections
import ais.tee.data.security.TextInspectionResult
import ais.tee.data.security.TextInspector
import ais.tee.navigation.AisteeQuickActionNavigation
import ais.tee.navigation.QuickActionDestination
import ais.tee.notifications.NativeChatNotificationPublisher
import ais.tee.notifications.NativeChatNotificationVisibility
import ais.tee.notifications.WebChatDraftReply
import ais.tee.share.CreateMessageAppAction
import ais.tee.share.IncomingSharePayload
import ais.tee.share.PendingWebShare
import ais.tee.share.canOpenInMarkdownWorkspace
import ais.tee.share.extractIncomingSharePayload
import ais.tee.share.extractIncomingViewPayload
import ais.tee.share.normalizeIncomingSharePayload
import ais.tee.ui.screens.*
import ais.tee.ui.theme.AisteeTheme
import ais.tee.ui.viewmodel.ExternalMarkdownOpenResult
import ais.tee.ui.viewmodel.MarkdownWorkspaceViewModel
import ais.tee.ui.viewmodel.NavigationTab
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun profilePlaygroundDestination(): NavigationTab = NavigationTab.PLAYGROUND

internal fun showPrimaryNavigation(tab: NavigationTab): Boolean = tab != NavigationTab.WEB_CHATS

internal fun navigationSuiteUsesBottomBar(type: NavigationSuiteType): Boolean =
    type == NavigationSuiteType.NavigationBar ||
        type == NavigationSuiteType.ShortNavigationBarCompact ||
        type == NavigationSuiteType.ShortNavigationBarMedium

@Composable
private fun PrimaryNavigationShell(
    currentTab: NavigationTab,
    onSelectTab: (NavigationTab) -> Unit,
    content: @Composable (applyNavigationBarInset: Boolean) -> Unit
) {
    val showNavigation = showPrimaryNavigation(currentTab)
    val navigationSuiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(
        currentWindowAdaptiveInfoV2()
    )
    val navigationState = rememberNavigationSuiteScaffoldState(
        initialValue = if (showNavigation) {
            NavigationSuiteScaffoldValue.Visible
        } else {
            NavigationSuiteScaffoldValue.Hidden
        }
    )

    LaunchedEffect(showNavigation) {
        navigationState.snapTo(
            if (showNavigation) NavigationSuiteScaffoldValue.Visible
            else NavigationSuiteScaffoldValue.Hidden
        )
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = currentTab == NavigationTab.WEB_CHATS,
                onClick = { onSelectTab(NavigationTab.WEB_CHATS) },
                icon = { Icon(Icons.Default.Language, contentDescription = "Web AI Accounts") },
                label = { Text("Web AI") },
                modifier = Modifier.testTag("nav_tab_web_chats")
            )
            item(
                selected = currentTab == NavigationTab.COMPARE_HUB,
                onClick = { onSelectTab(NavigationTab.COMPARE_HUB) },
                icon = { Icon(Icons.Default.Forum, contentDescription = "AI Compare Hub") },
                label = { Text("Compare") },
                modifier = Modifier.testTag("nav_tab_compare_hub")
            )
            item(
                selected = currentTab.belongsToStudioSection(),
                onClick = { onSelectTab(NavigationTab.STUDIO) },
                icon = { Icon(Icons.Default.Tune, contentDescription = "Studio") },
                label = { Text("Studio") },
                modifier = Modifier.testTag("nav_tab_studio")
            )
        },
        layoutType = navigationSuiteType,
        state = navigationState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_adaptive_nav")
    ) {
        content(!showNavigation || !navigationSuiteUsesBottomBar(navigationSuiteType))
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()
    private val markdownWorkspaceViewModel: MarkdownWorkspaceViewModel by viewModels()
    private var retainedShareIntentHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        markdownWorkspaceViewModel.attachRecoveryStore(MarkdownWorkspaceRecoveryStore(noBackupFilesDir))
        markdownWorkspaceViewModel.attachLifecycle(this)
        retainedShareIntentHandled = savedInstanceState?.getBoolean(KEY_SHARE_INTENT_HANDLED) == true
        restoreShareState(savedInstanceState)
        handleNavigationIntent(intent)
        if (!retainedShareIntentHandled) handleIncomingShareIntent(intent)

        setContent {
            AisteeTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                IncomingShareRoutingDialogs(
                    uiState = uiState,
                    viewModel = viewModel,
                    markdownWorkspaceViewModel = markdownWorkspaceViewModel
                )

                LaunchedEffect(uiState.snackbarMessage) {
                    uiState.snackbarMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.dismissSnackbar()
                    }
                }

                PrimaryNavigationShell(
                    currentTab = uiState.currentTab,
                    onSelectTab = viewModel::selectTab
                ) { applyNavigationBarInset ->
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            StreambenchMiniPlayer(
                                applyNavigationBarInset = applyNavigationBarInset
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = innerPadding.calculateBottomPadding())
                        ) {
                            val webChatsActive = uiState.currentTab == NavigationTab.WEB_CHATS
                            var webChatsVisited by remember { mutableStateOf(webChatsActive) }
                            LaunchedEffect(webChatsActive) {
                                if (webChatsActive) webChatsVisited = true
                            }
                            if (webChatsActive || webChatsVisited) {
                                WebChatScreen(
                                    viewModel = viewModel,
                                    uiState = uiState,
                                    onOpenNativeCompare = { viewModel.selectTab(NavigationTab.COMPARE_HUB) },
                                    onOpenStudio = { viewModel.selectTab(NavigationTab.STUDIO) },
                                    isActive = webChatsActive,
                                    modifier = if (webChatsActive) Modifier.fillMaxSize() else Modifier.size(0.dp)
                                )
                            }
                            if (!webChatsActive) {
                                when (uiState.currentTab) {
                                    NavigationTab.WEB_CHATS -> Unit
                                    NavigationTab.COMPARE_HUB -> ChatScreen(
                                        viewModel = viewModel,
                                        uiState = uiState
                                    )
                                    NavigationTab.STUDIO -> StudioScreen(
                                        viewModel = viewModel,
                                        uiState = uiState,
                                        onNavigateToInstructions = { viewModel.selectTab(NavigationTab.INSTRUCTIONS) },
                                        onNavigateToSkills = { viewModel.selectTab(NavigationTab.SKILLS) },
                                        onNavigateToYaml = { viewModel.selectTab(NavigationTab.YAML) },
                                        onNavigateToPlayground = { viewModel.selectTab(profilePlaygroundDestination()) }
                                    )
                                    NavigationTab.INSTRUCTIONS -> InstructionsScreen(
                                        viewModel = viewModel,
                                        uiState = uiState,
                                        onNavigateToPlayground = { viewModel.selectTab(profilePlaygroundDestination()) }
                                    )
                                    NavigationTab.YAML -> YamlEditorScreen(
                                        viewModel = viewModel,
                                        uiState = uiState
                                    )
                                    NavigationTab.PLAYGROUND -> PlaygroundScreen(
                                        viewModel = viewModel,
                                        uiState = uiState
                                    )
                                    NavigationTab.SKILLS -> SkillsBrowserScreen(
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshNativeChatFromPersistence()
        NativeChatNotificationVisibility.setAppVisible(true)
    }

    override fun onStop() {
        NativeChatNotificationVisibility.setAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        retainedShareIntentHandled = false
        setIntent(intent)
        handleNavigationIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARE_INTENT_HANDLED, retainedShareIntentHandled)
        saveShareState(outState)
    }

    private fun handleNavigationIntent(intent: Intent) {
        when {
            AisteeQuickActionNavigation.isOpenWebDraftReplyAction(intent.action) -> {
                val serviceId = AisteeQuickActionNavigation.webServiceId(
                    currentExtra = intent.getStringExtra(AisteeQuickActionNavigation.EXTRA_WEB_SERVICE_ID),
                    dataScheme = intent.data?.scheme,
                    dataHost = intent.data?.host,
                    dataLastPathSegment = intent.data?.lastPathSegment,
                )
                val service = WebAiService.entries.firstOrNull { candidate ->
                    serviceId?.equals(candidate.id, ignoreCase = true) == true
                }
                if (service != null) {
                    val replyText = WebChatDraftReply.replyText(intent)
                    val opened = if (replyText != null) {
                        viewModel.stageWebDraftReply(service, replyText)
                    } else {
                        viewModel.openStagedWebDraft(service)
                    }
                    if (!opened) viewModel.showSnackbar("No staged draft reply is available.")
                }
                consumeNavigationIntent(intent)
            }
            AisteeQuickActionNavigation.isOpenNativeConversationAction(intent.action) -> {
                val conversationId = AisteeQuickActionNavigation.nativeConversationId(
                    currentExtra = intent.getStringExtra(AisteeQuickActionNavigation.EXTRA_NATIVE_CONVERSATION_ID),
                    dataScheme = intent.data?.scheme,
                    dataHost = intent.data?.host,
                    dataLastPathSegment = intent.data?.lastPathSegment,
                )
                conversationId?.let { resolvedConversationId ->
                    NativeChatNotificationPublisher.cancelConversation(this, resolvedConversationId)
                    viewModel.openNativeConversation(resolvedConversationId)
                }
                consumeNavigationIntent(intent)
            }
            AisteeQuickActionNavigation.isOpenDestinationAction(intent.action) -> {
                val destinationId = AisteeQuickActionNavigation.destinationId(
                    currentExtra = intent.getStringExtra(AisteeQuickActionNavigation.EXTRA_DESTINATION),
                    legacyExtra = intent.getStringExtra(AisteeQuickActionNavigation.LEGACY_WIDGET_EXTRA_DESTINATION),
                    dataLastPathSegment = intent.data?.lastPathSegment,
                )
                when (val destination = AisteeQuickActionNavigation.quickActionDestination(destinationId)) {
                    is QuickActionDestination.Tab -> viewModel.selectTab(destination.tab)
                    QuickActionDestination.NewNativeChat -> viewModel.openNewNativeConversation()
                    QuickActionDestination.ProjectLibrary -> viewModel.openProjectLibrary()
                    null -> Unit
                }
                consumeNavigationIntent(intent)
            }
        }
    }

    private fun consumeNavigationIntent(intent: Intent) {
        intent.action = Intent.ACTION_MAIN
        intent.data = null
        intent.clipData = null
        intent.removeExtra(AisteeQuickActionNavigation.EXTRA_DESTINATION)
        intent.removeExtra(AisteeQuickActionNavigation.EXTRA_NATIVE_CONVERSATION_ID)
        intent.removeExtra(AisteeQuickActionNavigation.EXTRA_WEB_SERVICE_ID)
        intent.removeExtra(AisteeQuickActionNavigation.LEGACY_WIDGET_EXTRA_DESTINATION)
    }

    private fun handleIncomingShareIntent(intent: Intent) {
        val payload = when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> extractIncomingSharePayload(intent)
            Intent.ACTION_VIEW -> extractIncomingViewPayload(intent)
            Intent.ACTION_PROCESS_TEXT -> normalizeIncomingSharePayload(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
                uriStrings = emptyList()
            )
            CreateMessageAppAction.ACTION -> CreateMessageAppAction.payload(
                action = intent.action,
                text = intent.getCharSequenceExtra(CreateMessageAppAction.EXTRA_TEXT),
            )
            else -> null
        } ?: return
        retainedShareIntentHandled = true
        viewModel.receiveIncomingShare(payload)
    }

    private fun saveShareState(outState: Bundle) {
        val state = viewModel.uiState.value
        val pending = state.pendingWebShare
        val incoming = state.incomingShare
        when {
            pending != null -> {
                outState.putString(KEY_SHARE_STAGE, SHARE_STAGE_PENDING)
                outState.putLong(KEY_SHARE_ID, pending.id)
                outState.putString(KEY_SHARE_SERVICE_ID, pending.service.id)
                writeSharePayload(outState, pending.payload)
            }
            incoming != null -> {
                outState.putString(KEY_SHARE_STAGE, SHARE_STAGE_INCOMING)
                outState.putLong(KEY_SHARE_INCOMING_ID, state.incomingShareId)
                writeSharePayload(outState, incoming)
            }
        }
    }

    private fun restoreShareState(savedState: Bundle?) {
        val stage = savedState?.getString(KEY_SHARE_STAGE) ?: return
        val payload = normalizeIncomingSharePayload(
            text = savedState.getString(KEY_SHARE_TEXT),
            uriStrings = savedState.getStringArrayList(KEY_SHARE_URIS).orEmpty(),
            mimeTypeHint = savedState.getString(KEY_SHARE_MIME_TYPE),
            isOpenDocument = savedState.getBoolean(KEY_SHARE_OPEN_DOCUMENT, false)
        ) ?: return
        val pending = if (stage == SHARE_STAGE_PENDING) {
            val serviceId = savedState.getString(KEY_SHARE_SERVICE_ID)
            val service = WebAiService.entries.firstOrNull { it.id == serviceId }
            val shareId = savedState.getLong(KEY_SHARE_ID, 0L)
            if (service != null && shareId > 0L) PendingWebShare(shareId, service, payload) else null
        } else null
        viewModel.restoreShareState(
            incomingShare = payload.takeIf { stage == SHARE_STAGE_INCOMING },
            incomingShareRequestId = savedState.getLong(KEY_SHARE_INCOMING_ID, 0L),
            pendingShare = pending
        )
    }

    private fun writeSharePayload(outState: Bundle, payload: IncomingSharePayload) {
        outState.putString(KEY_SHARE_TEXT, payload.text)
        outState.putStringArrayList(KEY_SHARE_URIS, ArrayList(payload.uriStrings))
        outState.putString(KEY_SHARE_MIME_TYPE, payload.mimeTypeHint)
        outState.putBoolean(KEY_SHARE_OPEN_DOCUMENT, payload.isOpenDocument)
    }

    private companion object {
        const val KEY_SHARE_INTENT_HANDLED = "llmbench.share.intent_handled"
        const val KEY_SHARE_STAGE = "llmbench.share.stage"
        const val KEY_SHARE_ID = "llmbench.share.id"
        const val KEY_SHARE_INCOMING_ID = "llmbench.share.incoming_id"
        const val KEY_SHARE_SERVICE_ID = "llmbench.share.service_id"
        const val KEY_SHARE_TEXT = "llmbench.share.text"
        const val KEY_SHARE_URIS = "llmbench.share.uris"
        const val KEY_SHARE_MIME_TYPE = "llmbench.share.mime_type"
        const val KEY_SHARE_OPEN_DOCUMENT = "llmbench.share.open_document"
        const val SHARE_STAGE_INCOMING = "incoming"
        const val SHARE_STAGE_PENDING = "pending"
    }
}

@Composable
private fun IncomingShareRoutingDialogs(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    markdownWorkspaceViewModel: MarkdownWorkspaceViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val markdownUiState by markdownWorkspaceViewModel.uiState.collectAsStateWithLifecycle()
    var confirmMarkdownReplace by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.incomingShareId) {
        confirmMarkdownReplace = false
    }

    fun finishMarkdownOpen(requestId: Long) {
        confirmMarkdownReplace = false
        if (viewModel.dismissIncomingShareIfCurrent(requestId)) {
            viewModel.selectTab(NavigationTab.YAML)
        }
    }

    val markdownBusyMessage = "Markdown workspace is still restoring or busy. Try again when it is ready."
    val markdownOpenErrorMessage = "Could not open the document in Markdown workspace."

    fun openIncomingTextInMarkdown(
        payload: IncomingSharePayload,
        requestId: Long,
        allowDiscardDirty: Boolean
    ) {
        val text = payload.text ?: return
        when (
            markdownWorkspaceViewModel.openExternalText(
                text = text,
                allowDiscardDirty = allowDiscardDirty
            )
        ) {
            ExternalMarkdownOpenResult.OPENED -> finishMarkdownOpen(requestId)
            ExternalMarkdownOpenResult.NEEDS_DISCARD -> confirmMarkdownReplace = true
            ExternalMarkdownOpenResult.BUSY -> viewModel.showSnackbar(markdownBusyMessage)
            ExternalMarkdownOpenResult.TOO_LARGE -> viewModel.showSnackbar(
                "Shared text is larger than the 8 MiB Markdown workspace limit."
            )
        }
    }

    fun openIncomingDocumentInMarkdown(
        payload: IncomingSharePayload,
        requestId: Long,
        allowDiscardDirty: Boolean
    ) {
        if (!payload.canOpenInMarkdownWorkspace()) return
        if (markdownUiState.isBusy) {
            viewModel.showSnackbar(markdownBusyMessage)
            return
        }
        if (markdownUiState.isDirty && !allowDiscardDirty) {
            confirmMarkdownReplace = true
            return
        }
        val uri = payload.uriStrings.singleOrNull()?.let(Uri::parse) ?: return
        if (!markdownWorkspaceViewModel.beginImport()) {
            viewModel.showSnackbar(markdownBusyMessage)
            return
        }
        scope.launch {
            val result = runCatching { MarkdownDocumentFileAccess.import(context, uri) }
            result.fold(
                onSuccess = { opened ->
                    if (!viewModel.isIncomingShareCurrent(requestId)) {
                        markdownWorkspaceViewModel.cancelImport()
                        return@fold
                    }
                    if (markdownWorkspaceViewModel.completeImport(opened.displayName, opened.document)) {
                        finishMarkdownOpen(requestId)
                    } else {
                        markdownWorkspaceViewModel.cancelImport()
                        viewModel.showSnackbar(markdownOpenErrorMessage)
                    }
                },
                onFailure = { error ->
                    markdownWorkspaceViewModel.cancelImport()
                    if (error is CancellationException) throw error
                    viewModel.showSnackbar(error.message ?: markdownOpenErrorMessage)
                }
            )
        }
    }

    fun openIncomingInMarkdown(
        payload: IncomingSharePayload,
        requestId: Long,
        allowDiscardDirty: Boolean
    ) {
        if (payload.text != null) {
            openIncomingTextInMarkdown(payload, requestId, allowDiscardDirty)
        } else {
            openIncomingDocumentInMarkdown(payload, requestId, allowDiscardDirty)
        }
    }

    uiState.incomingShare?.let { payload ->
        if (confirmMarkdownReplace && payload.canOpenInMarkdownWorkspace()) {
            ReplaceMarkdownDraftDialog(
                currentName = markdownUiState.displayName,
                onDiscard = {
                    openIncomingInMarkdown(
                        payload = payload,
                        requestId = uiState.incomingShareId,
                        allowDiscardDirty = true
                    )
                },
                onDismiss = { confirmMarkdownReplace = false }
            )
        } else {
            IncomingShareProviderDialog(
                payload = payload,
                favoriteServices = uiState.favoriteWebServices,
                onOpenMarkdown = if (payload.canOpenInMarkdownWorkspace()) {
                    {
                        openIncomingInMarkdown(
                            payload = payload,
                            requestId = uiState.incomingShareId,
                            allowDiscardDirty = false
                        )
                    }
                } else {
                    null
                },
                markdownEnabled = !markdownUiState.isBusy,
                onSelect = viewModel::routeIncomingShareToWeb,
                onDismiss = viewModel::dismissIncomingShare
            )
        }
    }
}

@Composable
private fun IncomingShareProviderDialog(
    payload: IncomingSharePayload,
    favoriteServices: Set<WebAiService>,
    onOpenMarkdown: (() -> Unit)?,
    markdownEnabled: Boolean,
    onSelect: (WebAiService) -> Unit,
    onDismiss: () -> Unit
) {
    var safetyReviewed by rememberSaveable(payload.text, payload.uriStrings) { mutableStateOf(false) }
    var inspection by remember(payload.text, payload.uriStrings) {
        mutableStateOf<TextInspectionResult?>(
            if (payload.text == null) TextInspectionResult(emptyList()) else null
        )
    }

    LaunchedEffect(payload.text, payload.uriStrings) {
        val text = payload.text
        inspection = if (text == null) {
            TextInspectionResult(emptyList())
        } else {
            withContext(Dispatchers.Default) { TextInspector.inspect(text) }
        }
    }

    val currentInspection = inspection
    if (currentInspection == null) {
        SharedTextInspectionProgressDialog(onDismiss = onDismiss)
        return
    }
    if (!safetyReviewed && currentInspection.hasFindings) {
        SharedTextSafetyReviewDialog(
            inspection = currentInspection,
            onContinue = { safetyReviewed = true },
            onDismiss = onDismiss
        )
        return
    }

    val summary = buildList {
        if (payload.text != null) add("text")
        if (payload.attachmentCount > 0) {
            val suffix = if (payload.attachmentCount == 1) "" else "s"
            add("${payload.attachmentCount} attachment$suffix")
        }
    }.joinToString(" + ")
    val sections = webChatSections(favoriteServices)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (payload.isOpenDocument) "Open with Aistee" else "Share to Aistee") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Choose a destination for $summary.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                onOpenMarkdown?.let {
                    Text(
                        "Local",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    TextButton(
                        onClick = it,
                        enabled = markdownEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Markdown workspace", modifier = Modifier.fillMaxWidth())
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                if (sections.favorites.isNotEmpty()) {
                    Text(
                        "Favorites",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    sections.favorites.forEach { service ->
                        TextButton(onClick = { onSelect(service) }, modifier = Modifier.fillMaxWidth()) {
                            Text("★ ${service.displayName}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                sections.primary.forEach { service ->
                    TextButton(
                        onClick = { onSelect(service) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(service.displayName, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (sections.additional.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "More chats",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    sections.additional.forEach { service ->
                        TextButton(
                            onClick = { onSelect(service) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(service.displayName, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ReplaceMarkdownDraftDialog(
    currentName: String,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace unsaved Markdown draft?") },
        text = {
            Text(
                "$currentName has edits that have not been exported. Discard them and open the shared text as a new local Markdown draft?"
            )
        },
        confirmButton = {
            Button(onClick = onDiscard) { Text("Discard and open") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SharedTextInspectionProgressDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inspecting shared text") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    "Checking hidden Unicode, encoded carriers and prompt-like instructions before routing this text.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SharedTextSafetyReviewDialog(
    inspection: TextInspectionResult,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.WarningAmber, contentDescription = null)
                Text("Review shared text")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Aistee found ${inspection.detectedCount} suspicious text detection${if (inspection.detectedCount == 1) "" else "s"}. Review the retained details before this text is used locally or routed to a provider.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${if (inspection.truncated) "Retained" else "Severity"}: High ${inspection.highCount} • Medium ${inspection.mediumCount} • Low ${inspection.lowCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                inspection.findings.take(12).forEach { finding ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${finding.severity.name.lowercase()} • ${finding.label} • line ${finding.line}:${finding.column}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            finding.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (inspection.findings.size > 12 || inspection.truncated) {
                    Text(
                        "More findings exist; the preview is intentionally bounded.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                Text(
                    "Inspection is read-only. Aistee does not rewrite, remove or execute the shared text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onContinue) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
