package ais.tee.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import ais.tee.share.copyPlainTextToClipboard
import ais.tee.R
import ais.tee.data.document.MarkdownDocumentFileAccess
import ais.tee.data.document.MarkdownTable
import ais.tee.data.document.MarkdownWorkspaceRecoveryStore
import ais.tee.data.model.AiProvider
import ais.tee.data.model.CHAT_ROLE_USER
import ais.tee.data.model.ModelChatMessage
import ais.tee.data.model.NativeChatConversation
import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.renderChatMarkdown
import ais.tee.data.model.isCompletedAssistantResponse
import ais.tee.data.model.supportedApiProcessingModes
import ais.tee.notifications.NativeChatNotificationPreferences
import ais.tee.notifications.NativeChatNotificationPreferencesStore
import ais.tee.notifications.NativeChatNotificationPublisher
import ais.tee.notifications.NativeChatNotificationSettingsDialog
import ais.tee.ui.theme.*
import ais.tee.ui.viewmodel.ExternalMarkdownOpenResult
import ais.tee.ui.viewmodel.MarkdownWorkspaceViewModel
import ais.tee.ui.viewmodel.NavigationTab
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CHAT_MARKDOWN_EXPORT_NAME = "aistee-chat.md"
private const val MAX_CHAT_PROMPT_IMPORT_CHARS = 128 * 1024

private data class PendingMarkdownAsset(
    val text: String,
    val displayName: String,
    val sourceDescription: String
)

internal fun canOpenResponseAsMarkdown(
    message: ModelChatMessage,
    isPreparingChatMarkdown: Boolean,
    isWorkspaceBusy: Boolean
): Boolean =
    !isPreparingChatMarkdown &&
        !isWorkspaceBusy &&
        message.isCompletedAssistantResponse()

internal fun shouldAutoScrollChat(
    previousMessageCount: Int,
    lastVisibleItemIndex: Int
): Boolean = previousMessageCount <= 0 || lastVisibleItemIndex >= previousMessageCount - 2

internal fun shouldShowJumpToLatest(
    totalItemCount: Int,
    lastVisibleItemIndex: Int
): Boolean = totalItemCount > 0 && lastVisibleItemIndex < totalItemCount - 2

internal fun chatMessageContentType(message: ModelChatMessage): String = when {
    message.sender == CHAT_ROLE_USER -> "user"
    message.isError -> "error"
    else -> "assistant"
}

internal fun chatBubbleMaxWidth(containerWidth: Dp): Dp =
    (containerWidth - 32.dp).coerceIn(340.dp, 640.dp)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun nativeChatInitialDestinationHistory(
    activeConversationId: String
): List<ThreePaneScaffoldDestinationItem<String>> = buildList {
    add(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List))
    activeConversationId.takeIf(String::isNotBlank)?.let { conversationId ->
        add(
            ThreePaneScaffoldDestinationItem(
                pane = ListDetailPaneScaffoldRole.Detail,
                contentKey = conversationId
            )
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ChatScreen(
    viewModel: StudioViewModel,
    uiState: StudioUiState,
    modifier: Modifier = Modifier
) {
    val navigationScope = rememberCoroutineScope()
    val activeConversationId = uiState.nativeChat.activeConversationId
    val initialDestinationHistory = remember(activeConversationId) {
        nativeChatInitialDestinationHistory(activeConversationId)
    }
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(
        initialDestinationHistory = initialDestinationHistory
    )
    val conversations = uiState.nativeChat.conversations.sortedByDescending { it.updatedAtEpochMs }
    val navigationRequest = uiState.nativeChatNavigationRequest

    LaunchedEffect(navigationRequest?.id) {
        val request = navigationRequest ?: return@LaunchedEffect
        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, request.conversationId)
        viewModel.consumeNativeConversationNavigationRequest(request.id)
    }

    fun showConversation(conversationId: String) {
        viewModel.switchNativeConversation(conversationId)
        navigationScope.launch {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, conversationId)
        }
    }

    fun createConversation() {
        viewModel.newNativeConversation()
        val conversationId = viewModel.uiState.value.nativeChat.activeConversationId
        if (conversationId.isNotBlank()) {
            navigationScope.launch {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, conversationId)
            }
        }
    }

    fun createIncognitoConversation() {
        viewModel.newIncognitoConversation()
        val conversationId = viewModel.uiState.value.nativeChat.activeConversationId
        if (conversationId.isNotBlank()) {
            navigationScope.launch {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, conversationId)
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        val keepDetailVisible =
            navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail
        viewModel.deleteNativeConversation(conversationId)
        if (keepDetailVisible) {
            val nextConversationId = viewModel.uiState.value.nativeChat.activeConversationId
            if (nextConversationId.isNotBlank()) {
                navigationScope.launch {
                    navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, nextConversationId)
                }
            }
        }
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = modifier.fillMaxSize(),
        listPane = {
            AnimatedPane {
                NativeConversationsPane(
                    conversations = conversations,
                    activeConversationId = activeConversationId,
                    incognitoConversationId = uiState.incognitoConversationId,
                    onNew = ::createConversation,
                    onNewIncognito = ::createIncognitoConversation,
                    onSelect = ::showConversation,
                    onDelete = ::deleteConversation
                )
            }
        },
        detailPane = {
            AnimatedPane {
                NativeChatDetailPane(
                    viewModel = viewModel,
                    uiState = uiState,
                    onOpenConversations = {
                        navigationScope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.List)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// skipcq: KT-R1006 - Existing screen composition complexity is outside this targeted export change.
private fun NativeChatDetailPane(
    viewModel: StudioViewModel,
    uiState: StudioUiState,
    onOpenConversations: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val markdownWorkspaceViewModel: MarkdownWorkspaceViewModel = viewModel()
    val markdownUiState by markdownWorkspaceViewModel.uiState.collectAsStateWithLifecycle()
    val recoveryStore = remember(context.applicationContext) {
        MarkdownWorkspaceRecoveryStore(context.noBackupFilesDir)
    }
    val promptInput = uiState.nativeChatDraft
    var showModelMenu by remember { mutableStateOf(false) }
    var showApiModeMenu by remember { mutableStateOf(false) }
    var showChatActionsMenu by remember { mutableStateOf(false) }
    val notificationPreferencesStore = remember(context.applicationContext) {
        NativeChatNotificationPreferencesStore(context.applicationContext)
    }
    var notificationPreferences by remember {
        mutableStateOf(notificationPreferencesStore.load())
    }
    var showNotificationSettings by remember { mutableStateOf(false) }
    var pendingNotificationPreferences by remember {
        mutableStateOf<NativeChatNotificationPreferences?>(null)
    }
    val notificationsDisabledMessage = stringResource(R.string.notification_system_disabled)
    val notificationPermissionDeniedMessage = stringResource(R.string.notification_permission_denied)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingNotificationPreferences ?: return@rememberLauncherForActivityResult
        val saved = if (granted) pending else pending.copy(enabled = false)
        notificationPreferencesStore.save(saved)
        notificationPreferences = saved
        pendingNotificationPreferences = null
        if (granted) {
            NativeChatNotificationPublisher.ensureChannel(context)
            if (!NativeChatNotificationPublisher.systemNotificationsAllowed(context)) {
                viewModel.showSnackbar(notificationsDisabledMessage)
            }
        } else {
            viewModel.showSnackbar(notificationPermissionDeniedMessage)
        }
    }
    var pendingMarkdownAsset by remember { mutableStateOf<PendingMarkdownAsset?>(null) }
    var pendingMarkdownPromptReplacement by remember { mutableStateOf<String?>(null) }
    var isPreparingChatMarkdown by remember { mutableStateOf(false) }
    var showProjectLibrary by remember { mutableStateOf(false) }
    var viewingTable by remember { mutableStateOf<MarkdownTable?>(null) }
    var pendingCsvExport by remember { mutableStateOf<String?>(null) }

    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val csv = pendingCsvExport
        pendingCsvExport = null
        if (uri != null && csv != null) {
            scope.launch {
                val exported = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            output.write(csv.encodeToByteArray())
                        } != null
                    }
                }.getOrDefault(false)
                viewModel.showSnackbar(if (exported) "Exported table as CSV." else "Could not export CSV.")
            }
        }
    }

    val chatMarkdownImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = runCatching {
                    withContext(Dispatchers.IO) {
                        MarkdownDocumentFileAccess.import(context, uri)
                    }
                }.getOrNull()
                when {
                    imported == null -> viewModel.showSnackbar("Could not read the selected Markdown file.")
                    viewModel.importNativeChatMarkdown(imported.document.text) -> {
                        viewModel.showSnackbar("Imported Aistee chat Markdown into a new conversation.")
                    }
                    else -> viewModel.showSnackbar("This file is not a canonical Aistee chat Markdown export.")
                }
            }
        }
    }

    SideEffect {
        markdownWorkspaceViewModel.attachRecoveryStore(recoveryStore)
        markdownWorkspaceViewModel.attachLifecycle(lifecycleOwner)
    }

    fun openMarkdownAsset(asset: PendingMarkdownAsset, allowDiscardDirty: Boolean) {
        when (
            markdownWorkspaceViewModel.openExternalText(
                text = asset.text,
                displayName = asset.displayName,
                allowDiscardDirty = allowDiscardDirty
            )
        ) {
            ExternalMarkdownOpenResult.OPENED -> {
                pendingMarkdownAsset = null
                viewModel.selectTab(NavigationTab.YAML)
            }
            ExternalMarkdownOpenResult.NEEDS_DISCARD -> pendingMarkdownAsset = asset
            ExternalMarkdownOpenResult.BUSY -> viewModel.showSnackbar(
                "Markdown workspace is still restoring or busy. Try again when it is ready."
            )
            ExternalMarkdownOpenResult.TOO_LARGE -> viewModel.showSnackbar(
                "Markdown asset is larger than the 8 MiB workspace limit."
            )
        }
    }

    fun applyNotificationPreferences(next: NativeChatNotificationPreferences) {
        showNotificationSettings = false
        if (!next.enabled) {
            notificationPreferencesStore.save(next)
            notificationPreferences = next
            NativeChatNotificationPublisher.cancelConversations(
                context,
                uiState.nativeChat.conversations.map { it.id },
            )
            return
        }

        NativeChatNotificationPublisher.ensureChannel(context)
        val needsRuntimePermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) {
            pendingNotificationPreferences = next
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPreferencesStore.save(next)
            notificationPreferences = next
            if (!NativeChatNotificationPublisher.systemNotificationsAllowed(context)) {
                viewModel.showSnackbar(notificationsDisabledMessage)
            }
        }
    }

    fun useMarkdownDraftAsPrompt() {
        val markdown = markdownUiState.text
        if (markdown.isBlank()) return
        if (markdown.length > MAX_CHAT_PROMPT_IMPORT_CHARS) {
            viewModel.showSnackbar(
                "Markdown draft is too large to place directly in the chat composer. Keep it as a local asset or use a smaller prompt."
            )
            return
        }
        if (promptInput.isNotBlank() && promptInput != markdown) {
            pendingMarkdownPromptReplacement = markdown
        } else {
            viewModel.updateNativeConversationDraft(markdown)
        }
    }

    val isIncognito = uiState.isActiveConversationIncognito
    val canOpenChatAsMarkdown =
        !isIncognito &&
            !uiState.isChatGenerating &&
            !isPreparingChatMarkdown &&
            uiState.chatMessages.any { it.sender == CHAT_ROLE_USER }

    val samplePrompts = listOf(
        "Compare how you analyze edge cases in code",
        "Explain async coroutines in Kotlin vs threads",
        "Critique my tech architecture proposal",
        "Summarize the key design principles of .ai profiles"
    )

    var previousMessageCount by remember { mutableIntStateOf(0) }
    var previousConversationId by remember { mutableStateOf<String?>(null) }
    val activeConversationId = uiState.nativeChat.activeConversationId
    val showJumpToLatest by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            shouldShowJumpToLatest(
                totalItemCount = layoutInfo.totalItemsCount,
                lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            )
        }
    }

    // Follow new messages only while the user is already at (or very near) the latest turn.
    LaunchedEffect(activeConversationId, uiState.chatMessages.size, uiState.isChatGenerating) {
        val currentMessageCount = uiState.chatMessages.size
        val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val conversationChanged = previousConversationId != activeConversationId
        val shouldFollow = conversationChanged || shouldAutoScrollChat(
            previousMessageCount = previousMessageCount,
            lastVisibleItemIndex = lastVisibleItemIndex
        )
        previousConversationId = activeConversationId
        previousMessageCount = currentMessageCount
        if (shouldFollow && currentMessageCount > 0) {
            listState.animateScrollToItem(currentMessageCount - 1)
        }
    }

    if (showProjectLibrary) {
        ProjectLibraryDialog(
            archive = uiState.projectLibrary,
            activeProjectId = uiState.activeNativeConversation?.projectId,
            onSelectProject = { projectId ->
                if (!viewModel.moveActiveConversationToProject(projectId)) {
                    viewModel.showSnackbar("Could not move this conversation to that project.")
                }
            },
            onCreateProject = { name ->
                scope.launch {
                    if (!viewModel.createProject(name)) {
                        viewModel.showSnackbar("Could not create project.")
                    }
                }
            },
            onOpenAsset = { asset ->
                scope.launch {
                    val text = viewModel.loadProjectLibraryAsset(asset.id)
                    if (text == null) {
                        viewModel.showSnackbar("Could not open Library asset.")
                    } else {
                        openMarkdownAsset(
                            PendingMarkdownAsset(
                                text = text,
                                displayName = asset.title,
                                sourceDescription = "Project Library",
                            ),
                            allowDiscardDirty = false,
                        )
                        showProjectLibrary = false
                    }
                }
            },
            onDeleteAsset = { asset ->
                scope.launch {
                    if (!viewModel.deleteProjectLibraryAsset(asset.id)) {
                        viewModel.showSnackbar("Could not delete Library asset.")
                    }
                }
            },
            onDismiss = { showProjectLibrary = false },
        )
    }

    viewingTable?.let { table ->
        MarkdownTableScreen(
            table = table,
            title = "Table",
            onDismiss = { viewingTable = null },
            onCopyCsv = { csv ->
                copyPlainTextToClipboard(
                    context = context,
                    label = "AI table CSV",
                    text = csv,
                    sensitive = true,
                )
                viewModel.showSnackbar("Copied table as CSV")
            },
            onExportCsv = { csv ->
                pendingCsvExport = csv
                try {
                    csvExportLauncher.launch(MARKDOWN_TABLE_CSV_EXPORT_NAME)
                } catch (_: ActivityNotFoundException) {
                    pendingCsvExport = null
                    viewModel.showSnackbar("No document picker is available.")
                }
            },
            onSaveToLibrary = { csv ->
                scope.launch {
                    val saved = viewModel.saveTableCsvToProjectLibrary(csv)
                    viewModel.showSnackbar(if (saved != null) "Saved table to Library." else "Could not save table to Library.")
                }
            },
        )
    }

    if (showNotificationSettings) {
        NativeChatNotificationSettingsDialog(
            initialPreferences = notificationPreferences,
            onDismiss = { showNotificationSettings = false },
            onSave = ::applyNotificationPreferences,
        )
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isIncognito) {
                        IncognitoChatBanner(
                            onSaveAsNormal = viewModel::saveIncognitoAsNormalConversation,
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                    }
                    // Top header row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                PrimaryDark,
                                                AccentCyan,
                                                AccentEmerald
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "AI Hub",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI Multi-Chat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isIncognito) IncognitoBadge()
                                Text(
                                    text = uiState.activeNativeConversation?.title ?: "Native chat",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = onOpenConversations,
                                enabled = uiState.isNativeConversationStoreReady,
                                modifier = Modifier.testTag("btn_native_conversations")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "Native conversations",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = {
                                    viewModel.toggleIncludeSystemProfile(!uiState.includeSystemProfileInChat)
                                },
                                modifier = Modifier.testTag("btn_toggle_profile_attachment")
                            ) {
                                Icon(
                                    imageVector = if (uiState.includeSystemProfileInChat) Icons.Default.Psychology else Icons.Outlined.Psychology,
                                    contentDescription = "Toggle System Profile Attachment",
                                    tint = if (uiState.includeSystemProfileInChat) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = { viewModel.setShowApiKeyDialog(true) },
                                modifier = Modifier.testTag("btn_api_keys_settings")
                            ) {
                                BadgedBox(
                                    badge = {
                                        val hasAnyKey = uiState.apiKeyConfig.geminiKey.isNotBlank() ||
                                                uiState.apiKeyConfig.openAiKey.isNotBlank() ||
                                                uiState.apiKeyConfig.claudeKey.isNotBlank() ||
                                                uiState.apiKeyConfig.deepseekKey.isNotBlank() ||
                                                uiState.apiKeyConfig.kimiKey.isNotBlank() ||
                                                uiState.apiKeyConfig.openRouterKey.isNotBlank() ||
                                                uiState.apiKeyConfig.aiHubMixKey.isNotBlank() ||
                                                uiState.apiKeyConfig.vercelAiGatewayKey.isNotBlank()
                                        if (hasAnyKey) {
                                            Badge(
                                                containerColor = AccentEmerald,
                                                modifier = Modifier.size(6.dp)
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = "Configure API Keys",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Box {
                                IconButton(
                                    onClick = { showChatActionsMenu = true },
                                    modifier = Modifier.testTag("btn_chat_more_actions")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More chat actions",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = showChatActionsMenu,
                                    onDismissRequest = { showChatActionsMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Use Markdown draft as prompt") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Description, contentDescription = null)
                                        },
                                        enabled = !uiState.isChatGenerating &&
                                            !markdownUiState.isBusy &&
                                            markdownUiState.text.isNotBlank(),
                                        onClick = {
                                            showChatActionsMenu = false
                                            useMarkdownDraftAsPrompt()
                                        },
                                        modifier = Modifier.testTag("btn_use_markdown_prompt")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Open chat as Markdown") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Description, contentDescription = null)
                                        },
                                        enabled = canOpenChatAsMarkdown && !markdownUiState.isBusy,
                                        onClick = {
                                            showChatActionsMenu = false
                                            val snapshot = uiState.chatMessages.toList()
                                            isPreparingChatMarkdown = true
                                            scope.launch {
                                                try {
                                                    val markdown = withContext(Dispatchers.Default) {
                                                        renderChatMarkdown(
                                                            messages = snapshot,
                                                            maxUtf8Bytes = MarkdownDocumentFileAccess.MAX_DOCUMENT_BYTES
                                                        )
                                                    }
                                                    if (markdown == null) {
                                                        viewModel.showSnackbar(
                                                            "Chat export is larger than the 8 MiB Markdown workspace limit."
                                                        )
                                                    } else {
                                                        openMarkdownAsset(
                                                            asset = PendingMarkdownAsset(
                                                                text = markdown,
                                                                displayName = CHAT_MARKDOWN_EXPORT_NAME,
                                                                sourceDescription = "this chat snapshot"
                                                            ),
                                                            allowDiscardDirty = false
                                                        )
                                                    }
                                                } finally {
                                                    isPreparingChatMarkdown = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("btn_open_chat_markdown")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Import Aistee chat Markdown") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                                        },
                                        enabled = !uiState.isChatGenerating,
                                        onClick = {
                                            showChatActionsMenu = false
                                            chatMarkdownImportLauncher.launch(
                                                arrayOf("text/markdown", "text/plain", "application/octet-stream")
                                            )
                                        },
                                        modifier = Modifier.testTag("btn_import_chat_markdown")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save chat to Library") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null)
                                        },
                                        enabled = canOpenChatAsMarkdown && uiState.isProjectLibraryReady,
                                        onClick = {
                                            showChatActionsMenu = false
                                            scope.launch {
                                                val asset = viewModel.saveActiveChatToProjectLibrary()
                                                viewModel.showSnackbar(
                                                    if (asset != null) {
                                                        "Saved chat Markdown to Project Library."
                                                    } else {
                                                        "Could not save chat to Project Library."
                                                    }
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("btn_save_chat_library")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Project Library") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                                        },
                                        enabled = uiState.isProjectLibraryReady,
                                        onClick = {
                                            showChatActionsMenu = false
                                            showProjectLibrary = true
                                        },
                                        modifier = Modifier.testTag("btn_project_library")
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notification_settings_menu)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (notificationPreferences.enabled) {
                                                    Icons.Default.Notifications
                                                } else {
                                                    Icons.Outlined.Notifications
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            showChatActionsMenu = false
                                            notificationPreferences = notificationPreferencesStore.load()
                                            showNotificationSettings = true
                                        },
                                        modifier = Modifier.testTag("btn_chat_notifications")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear conversation") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                                        },
                                        onClick = {
                                            showChatActionsMenu = false
                                            viewModel.clearChatHistory()
                                        },
                                        modifier = Modifier.testTag("btn_clear_chat_history")
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_selector_row")
                    ) {
                        items(AiProvider.entries) { provider ->
                            val isSelected = uiState.selectedChatProvider == provider
                            val providerColor = getProviderColor(provider)

                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setChatProvider(provider) },
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = getProviderIcon(provider),
                                            contentDescription = null,
                                            tint = if (isSelected) providerColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = provider.shortName,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 12.sp
                                        )
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = providerColor.copy(alpha = 0.15f),
                                    selectedLabelColor = providerColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    selectedBorderColor = providerColor,
                                    borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.testTag("chip_provider_${provider.id}")
                            )
                        }
                    }

                    if (uiState.selectedChatProvider != AiProvider.ALL) {
                        val selectedProvider = uiState.selectedChatProvider
                        val modelOptions = uiState.gatewayModelOptions[selectedProvider]
                            ?: selectedProvider.availableModels
                        val processingModes = selectedProvider.supportedApiProcessingModes()
                        val isRefreshingCatalog = selectedProvider in uiState.refreshingGatewayCatalogs
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable { showModelMenu = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Model: ${uiState.selectedChatModel.ifBlank { "No models" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (isRefreshingCatalog) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Change Model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false }
                            ) {
                                modelOptions.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = model,
                                                fontWeight = if (uiState.selectedChatModel == model) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            viewModel.setChatModel(model)
                                            showModelMenu = false
                                        },
                                        leadingIcon = {
                                            if (uiState.selectedChatModel == model) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = getProviderColor(uiState.selectedChatProvider),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (processingModes.size > 1) {
                            Spacer(Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { showApiModeMenu = true }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .testTag("api_processing_mode_menu")
                            ) {
                                Text(
                                    text = "API mode: ${uiState.apiProcessingMode.displayName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Change API processing mode",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                DropdownMenu(
                                    expanded = showApiModeMenu,
                                    onDismissRequest = { showApiModeMenu = false },
                                ) {
                                    processingModes.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = mode.displayName,
                                                        fontWeight = if (uiState.apiProcessingMode == mode) {
                                                            FontWeight.Bold
                                                        } else {
                                                            FontWeight.Normal
                                                        },
                                                    )
                                                    Text(
                                                        text = mode.description,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.setApiProcessingMode(mode)
                                                showApiModeMenu = false
                                            },
                                            leadingIcon = {
                                                if (uiState.apiProcessingMode == mode) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = getProviderColor(selectedProvider),
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            },
                                            modifier = Modifier.testTag("api_processing_mode_${mode.name.lowercase()}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        items(samplePrompts) { prompt ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clickable {
                                        viewModel.sendChatMessage(prompt)
                                    }
                                    .testTag("sample_chat_prompt_${prompt.take(12)}")
                            ) {
                                Text(
                                    text = prompt,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = viewModel::updateNativeConversationDraft,
                            placeholder = {
                                val destination = when (uiState.selectedChatProvider) {
                                    AiProvider.ALL -> "Ask Gemini, ChatGPT, Claude, DeepSeek & Kimi..."
                                    AiProvider.GEMINI -> "Ask Google Gemini..."
                                    AiProvider.CHATGPT -> "Ask OpenAI ChatGPT..."
                                    AiProvider.CLAUDE -> "Ask Anthropic Claude..."
                                    AiProvider.DEEPSEEK -> "Ask DeepSeek..."
                                    AiProvider.KIMI -> "Ask Moonshot Kimi..."
                                    AiProvider.OPENROUTER -> "Ask a free OpenRouter model..."
                                    AiProvider.AIHUBMIX -> "Ask a free AIHubMix model..."
                                    AiProvider.VERCEL -> "Ask via Vercel AI Gateway..."
                                }
                                Text(
                                    text = destination,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            maxLines = 4,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input_field")
                        )

                        FloatingActionButton(
                            onClick = {
                                if (uiState.isChatGenerating) {
                                    viewModel.cancelChatGeneration()
                                } else if (promptInput.isNotBlank()) {
                                    viewModel.sendChatMessage(promptInput)
                                }
                            },
                            shape = CircleShape,
                            containerColor = getProviderColor(uiState.selectedChatProvider),
                            contentColor = Color.White,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag(if (uiState.isChatGenerating) "stop_generation_button" else "send_prompt_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.isChatGenerating) Icons.Default.Stop else Icons.Default.Send,
                                contentDescription = if (uiState.isChatGenerating) "Stop generation" else "Send Prompt",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxBubbleWidth = chatBubbleMaxWidth(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("chat_message_list")
            ) {
                items(
                    items = uiState.chatMessages,
                    key = { it.id },
                    contentType = ::chatMessageContentType
                ) { message ->
                    ChatMessageItem(
                        message = message,
                        maxBubbleWidth = maxBubbleWidth,
                        canOpenMarkdown = !isIncognito && canOpenResponseAsMarkdown(
                            message = message,
                            isPreparingChatMarkdown = isPreparingChatMarkdown,
                            isWorkspaceBusy = markdownUiState.isBusy
                        ),
                        onCopyText = { text ->
                            copyPlainTextToClipboard(
                                context = context,
                                label = "AI Message",
                                text = text,
                                sensitive = true,
                            )
                            viewModel.showSnackbar("Copied to clipboard")
                        },
                        onOpenMarkdown = { response ->
                            openMarkdownAsset(
                                asset = PendingMarkdownAsset(
                                    text = response.text,
                                    displayName = "aistee-${(response.provider ?: AiProvider.GEMINI).id}-response.md",
                                    sourceDescription = "this AI response"
                                ),
                                allowDiscardDirty = false
                            )
                        },
                        onRetryPrompt = { prompt -> viewModel.sendChatMessage(prompt) },
                        onViewTable = { table -> viewingTable = table }
                    )
                }

                if (uiState.isChatGenerating) {
                    item(key = "generating_indicator", contentType = "status") {
                        GeneratingIndicator(activeProviders = uiState.activeGeneratingProviders)
                    }
                }
            }

            AnimatedVisibility(
                visible = showJumpToLatest,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = innerPadding.calculateBottomPadding() + 20.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val lastIndex = listState.layoutInfo.totalItemsCount - 1
                            if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
                        }
                    },
                    modifier = Modifier.testTag("btn_jump_to_latest")
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to latest message")
                }
            }
        }
    }

    pendingMarkdownAsset?.let { asset ->
        AlertDialog(
            onDismissRequest = { pendingMarkdownAsset = null },
            title = { Text("Replace unsaved Markdown draft?") },
            text = {
                Text(
                    "${markdownUiState.displayName} has edits that have not been exported. Discard them and open ${asset.sourceDescription} as a new local Markdown draft?"
                )
            },
            confirmButton = {
                Button(
                    onClick = { openMarkdownAsset(asset, allowDiscardDirty = true) },
                    modifier = Modifier.testTag("btn_confirm_markdown_asset_replace")
                ) {
                    Text("Discard and open")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMarkdownAsset = null }) { Text("Cancel") }
            }
        )
    }

    pendingMarkdownPromptReplacement?.let { markdown ->
        AlertDialog(
            onDismissRequest = { pendingMarkdownPromptReplacement = null },
            title = { Text("Replace chat prompt?") },
            text = {
                Text(
                    "The composer already contains text. Replace it with the current Markdown draft? Nothing will be sent until you tap Send."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateNativeConversationDraft(markdown)
                        pendingMarkdownPromptReplacement = null
                    },
                    modifier = Modifier.testTag("btn_confirm_markdown_prompt_replace")
                ) {
                    Text("Replace")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMarkdownPromptReplacement = null }) { Text("Cancel") }
            }
        )
    }

    if (uiState.showApiKeyDialog) {
        ApiKeySettingsDialog(
            currentKeys = uiState.apiKeyConfig,
            onDismiss = { viewModel.setShowApiKeyDialog(false) },
            onSave = { gemini, openAi, claude, deepseek, kimi, openRouter, aiHubMix, vercel ->
                viewModel.saveApiKeys(gemini, openAi, claude, deepseek, kimi, openRouter, aiHubMix, vercel)
            }
        )
    }
}

@Composable
private fun NativeConversationsPane(
    conversations: List<NativeChatConversation>,
    activeConversationId: String,
    incognitoConversationId: String?,
    onNew: () -> Unit,
    onNewIncognito: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val pendingDelete = conversations.firstOrNull { it.id == pendingDeleteId }

    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxSize()
            .testTag("native_conversations_pane")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Text(
                text = "Native conversations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            FilledTonalButton(
                onClick = onNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_new_native_conversation")
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New conversation")
            }
            OutlinedButton(
                onClick = onNewIncognito,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_new_incognito_conversation")
            ) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New incognito chat")
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    val isActive = conversation.id == activeConversationId
                    val userTurns = conversation.messages.count { it.sender == CHAT_ROLE_USER }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelect(conversation.id) }
                                .testTag("native_conversation_${conversation.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                if (isActive) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    if (conversation.id == incognitoConversationId) {
                                        IncognitoBadge()
                                    }
                                    Text(
                                        conversation.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        "${conversation.selectedProvider.shortName} • $userTurns ${if (userTurns == 1) "turn" else "turns"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { pendingDeleteId = conversation.id },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete conversation")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete conversation?") },
            text = { Text("This removes '${conversation.title}' from local native chat history.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(conversation.id)
                        pendingDeleteId = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ChatMessageItem(
    message: ModelChatMessage,
    maxBubbleWidth: Dp,
    canOpenMarkdown: Boolean,
    onCopyText: (String) -> Unit,
    onOpenMarkdown: (ModelChatMessage) -> Unit,
    onRetryPrompt: (String) -> Unit,
    onViewTable: (MarkdownTable) -> Unit = {}
) {
    val isUser = message.sender == "user"
    val tables = remember(message.id, message.text, message.isPartial, message.isError) {
        markdownTablesForMessageActions(message)
    }
    var showTableMenu by remember { mutableStateOf(false) }
    val provider = message.provider ?: AiProvider.GEMINI
    val providerColor = getProviderColor(provider)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isUser) "user_message_bubble" else "assistant_message_bubble_${provider.id}"),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(providerColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getProviderIcon(provider),
                        contentDescription = null,
                        tint = providerColor,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = providerColor
                )
                message.modelName?.let { model ->
                    Text(
                        text = "• $model",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.isSimulated) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.testTag("badge_simulated_${message.id}")
                    ) {
                        Text(
                            text = "SIMULATED",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            } else {
                Text(
                    text = "You",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else if (message.isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                }
            ),
            border = if (!isUser) {
                BorderStroke(1.dp, providerColor.copy(alpha = 0.25f))
            } else null,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else if (message.isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    lineHeight = 21.sp
                )

                if (!isUser) {
                    formatChatResponseDiagnostics(message)?.let { diagnostics ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = diagnostics,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            lineHeight = 14.sp
                        )
                    }
                }

                if (!isUser && message.activeProfileNotes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        message.activeProfileNotes.forEach { note ->
                            Text(
                                text = "• $note",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                if (!isUser) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (tables.isNotEmpty()) {
                            Box {
                                IconButton(
                                    onClick = {
                                        if (tables.size == 1) onViewTable(tables.single()) else showTableMenu = true
                                    },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("btn_view_table_${message.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.TableChart,
                                        contentDescription = markdownTableActionLabel(tables.first(), 0, tables.size)
                                            .takeIf { tables.size == 1 } ?: "View tables",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                DropdownMenu(expanded = showTableMenu, onDismissRequest = { showTableMenu = false }) {
                                    tables.forEachIndexed { index, table ->
                                        DropdownMenuItem(
                                            text = { Text(markdownTableActionLabel(table, index, tables.size)) },
                                            onClick = {
                                                showTableMenu = false
                                                onViewTable(table)
                                            },
                                            modifier = Modifier.testTag("btn_view_table_${message.id}_$index")
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(
                            onClick = { onOpenMarkdown(message) },
                            enabled = canOpenMarkdown,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("btn_open_response_markdown_${message.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = "Open response as Markdown",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        IconButton(
                            onClick = { onCopyText(message.text) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy message",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GeneratingIndicator(activeProviders: Set<AiProvider>) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = AccentCyan
                )
                val providerNames = if (activeProviders.isEmpty()) {
                    "AI Assistant"
                } else {
                    activeProviders.joinToString(", ") { it.shortName }
                }
                Text(
                    text = "Generating response from $providerNames...",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                )
            }
        }
    }
}

@Composable
fun ApiKeySettingsDialog(
    currentKeys: ais.tee.data.model.ApiKeyConfig,
    onDismiss: () -> Unit,
    onSave: (
        gemini: String,
        openAi: String,
        claude: String,
        deepseek: String,
        kimi: String,
        openRouter: String,
        aiHubMix: String,
        vercel: String
    ) -> Unit
) {
    var geminiKey by remember { mutableStateOf(currentKeys.geminiKey) }
    var openAiKey by remember { mutableStateOf(currentKeys.openAiKey) }
    var claudeKey by remember { mutableStateOf(currentKeys.claudeKey) }
    var deepseekKey by remember { mutableStateOf(currentKeys.deepseekKey) }
    var kimiKey by remember { mutableStateOf(currentKeys.kimiKey) }
    var openRouterKey by remember { mutableStateOf(currentKeys.openRouterKey) }
    var aiHubMixKey by remember { mutableStateOf(currentKeys.aiHubMixKey) }
    var vercelAiGatewayKey by remember { mutableStateOf(currentKeys.vercelAiGatewayKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Key, contentDescription = null, tint = AccentEmerald)
                Text("AI Provider API Keys", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Enter API keys for direct providers and optional gateways. Keys are stored locally on device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Google Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentCyan)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_gemini_api_key")
                )

                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = { openAiKey = it },
                    label = { Text("OpenAI API Key (ChatGPT)") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.SmartToy, contentDescription = null, tint = AccentEmerald)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_openai_api_key")
                )

                OutlinedTextField(
                    value = claudeKey,
                    onValueChange = { claudeKey = it },
                    label = { Text("Anthropic Claude API Key") },
                    placeholder = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.Flare, contentDescription = null, tint = AccentAmber)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_claude_api_key")
                )

                OutlinedTextField(
                    value = deepseekKey,
                    onValueChange = { deepseekKey = it },
                    label = { Text("DeepSeek API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFF2563EB))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_deepseek_api_key")
                )

                OutlinedTextField(
                    value = kimiKey,
                    onValueChange = { kimiKey = it },
                    label = { Text("Moonshot Kimi API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.ElectricBolt, contentDescription = null, tint = Color(0xFF8B5CF6))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_kimi_api_key")
                )

                OutlinedTextField(
                    value = openRouterKey,
                    onValueChange = { openRouterKey = it },
                    label = { Text("OpenRouter API Key") },
                    placeholder = { Text("sk-or-v1-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.Route, contentDescription = null, tint = Color(0xFF6366F1))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_openrouter_api_key")
                )

                OutlinedTextField(
                    value = aiHubMixKey,
                    onValueChange = { aiHubMixKey = it },
                    label = { Text("AIHubMix API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF14B8A6))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_aihubmix_api_key")
                )

                OutlinedTextField(
                    value = vercelAiGatewayKey,
                    onValueChange = { vercelAiGatewayKey = it },
                    label = { Text("Vercel AI Gateway Key") },
                    placeholder = { Text("AI Gateway key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF334155))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_vercel_ai_gateway_key")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(geminiKey, openAiKey, claudeKey, deepseekKey, kimiKey, openRouterKey, aiHubMixKey, vercelAiGatewayKey) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                modifier = Modifier.testTag("btn_save_api_keys")
            ) {
                Text("Save Keys")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_cancel_api_keys")
            ) {
                Text("Cancel")
            }
        },
        properties = DialogProperties(
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    )
}

fun getProviderColor(provider: AiProvider): Color {
    return when (provider) {
        AiProvider.GEMINI -> Color(0xFF0EA5E9)
        AiProvider.CHATGPT -> Color(0xFF10B981)
        AiProvider.CLAUDE -> Color(0xFFF59E0B)
        AiProvider.DEEPSEEK -> Color(0xFF2563EB)
        AiProvider.KIMI -> Color(0xFF8B5CF6)
        AiProvider.OPENROUTER -> Color(0xFF6366F1)
        AiProvider.AIHUBMIX -> Color(0xFF14B8A6)
        AiProvider.VERCEL -> Color(0xFF334155)
        AiProvider.ALL -> Color(0xFF8B5CF6)
    }
}

fun getProviderIcon(provider: AiProvider): ImageVector {
    return when (provider) {
        AiProvider.GEMINI -> Icons.Default.AutoAwesome
        AiProvider.CHATGPT -> Icons.Default.SmartToy
        AiProvider.CLAUDE -> Icons.Default.Flare
        AiProvider.DEEPSEEK -> Icons.Default.Psychology
        AiProvider.KIMI -> Icons.Default.ElectricBolt
        AiProvider.OPENROUTER -> Icons.Default.Route
        AiProvider.AIHUBMIX -> Icons.Default.Cloud
        AiProvider.VERCEL -> Icons.Default.Cloud
        AiProvider.ALL -> Icons.Default.Hub
    }
}


@Composable
private fun ProjectLibraryDialog(
    archive: ProjectLibraryArchive,
    activeProjectId: String?,
    onSelectProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
    onOpenAsset: (ProjectLibraryAsset) -> Unit,
    onDeleteAsset: (ProjectLibraryAsset) -> Unit,
    onDismiss: () -> Unit,
) {
    var newProjectName by remember { mutableStateOf("") }
    val selectedProjectId = activeProjectId
        ?.takeIf { id -> archive.projects.any { it.id == id } }
        ?: archive.projects.firstOrNull()?.id
    val assets = archive.assets
        .filter { it.projectId == selectedProjectId }
        .sortedByDescending { it.createdAtEpochMs }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project Library") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Conversation project",
                    style = MaterialTheme.typography.labelLarge,
                )
                archive.projects.forEach { project ->
                    FilterChip(
                        selected = project.id == selectedProjectId,
                        onClick = { onSelectProject(project.id) },
                        label = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it.take(80) },
                        label = { Text("New project") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            val name = newProjectName.trim()
                            if (name.isNotEmpty()) {
                                onCreateProject(name)
                                newProjectName = ""
                            }
                        },
                        enabled = newProjectName.isNotBlank(),
                    ) {
                        Text("Add")
                    }
                }
                HorizontalDivider()
                Text(
                    "Assets",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (assets.isEmpty()) {
                    Text(
                        "No assets in this project yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    assets.forEach { asset ->
                        ListItem(
                            headlineContent = {
                                Text(asset.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text("${asset.mediaType} · ${asset.sizeBytes} B")
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onOpenAsset(asset) }) {
                                        Icon(Icons.Outlined.OpenInNew, contentDescription = "Open asset")
                                    }
                                    IconButton(onClick = { onDeleteAsset(asset) }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete asset")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun IncognitoBadge() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.testTag("badge_incognito")
    ) {
        Text(
            text = "INCOGNITO",
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.inverseOnSurface
        )
    }
}

@Composable
internal fun IncognitoChatBanner(onSaveAsNormal: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = modifier
            .fillMaxWidth()
            .testTag("banner_incognito_chat")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Icon(
                Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Incognito: not saved, no notifications or widgets. Leaving discards it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
            TextButton(
                onClick = onSaveAsNormal,
                modifier = Modifier.testTag("btn_save_incognito_as_normal")
            ) {
                Text("Save as normal chat", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}
