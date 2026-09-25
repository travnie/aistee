package ais.tee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ais.tee.share.copyPlainTextToClipboard
import ais.tee.data.document.MarkdownWorkspaceRecoveryStore
import ais.tee.data.engine.YamlParser
import ais.tee.ui.theme.AccentEmerald
import ais.tee.ui.theme.AccentRose
import ais.tee.ui.viewmodel.MarkdownWorkspaceViewModel
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel

private enum class YamlDocumentTab(
    val label: String,
    val fileLabel: String,
    val languageLabel: String
) {
    COMPOSED("Composed Profile", "profile.yaml (Effective Composed Output)", "YAML"),
    OVERLAY("Active Overlay", "profile.overlay.yaml (Private Downstream Layer)", "YAML"),
    SCHEMA("Schema", "style-profile.schema.json", "JSON")
}

@Composable
fun YamlEditorScreen(
    viewModel: StudioViewModel,
    uiState: StudioUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedDocumentTool by rememberSaveable { mutableIntStateOf(0) }
    var selectedYamlTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val markdownWorkspaceViewModel: MarkdownWorkspaceViewModel = viewModel()
    val markdownUiState by markdownWorkspaceViewModel.uiState.collectAsStateWithLifecycle()
    val recoveryStore = remember(context.applicationContext) {
        MarkdownWorkspaceRecoveryStore(context.noBackupFilesDir)
    }

    SideEffect {
        markdownWorkspaceViewModel.attachRecoveryStore(recoveryStore)
        markdownWorkspaceViewModel.attachLifecycle(lifecycleOwner)
    }

    LaunchedEffect(markdownUiState.openMarkdownRequestId) {
        val requestId = markdownUiState.openMarkdownRequestId
        if (requestId > 0L && markdownWorkspaceViewModel.consumeOpenMarkdownRequest(requestId)) {
            selectedDocumentTool = 1
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedDocumentTool, modifier = Modifier.fillMaxWidth()) {
            Tab(
                selected = selectedDocumentTool == 0,
                onClick = { selectedDocumentTool = 0 },
                text = { Text("YAML profile") },
                icon = { Icon(Icons.Default.Code, contentDescription = null) }
            )
            Tab(
                selected = selectedDocumentTool == 1,
                onClick = { selectedDocumentTool = 1 },
                text = { Text("Markdown") },
                icon = { Icon(Icons.Default.Description, contentDescription = null) },
                modifier = Modifier.testTag("documents_markdown_tab")
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedDocumentTool == 0) {
                YamlProfileLayersScreen(
                    viewModel = viewModel,
                    uiState = uiState,
                    selectedTabIndex = selectedYamlTabIndex,
                    onSelectedTab = { selectedYamlTabIndex = it }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    LocalSkillWorkspaceSourceBar(
                        workspaceViewModel = markdownWorkspaceViewModel,
                        onMessage = viewModel::showSnackbar
                    )
                    MarkdownWorkspaceScreen(
                        workspaceViewModel = markdownWorkspaceViewModel,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YamlProfileLayersScreen(
    viewModel: StudioViewModel,
    uiState: StudioUiState,
    selectedTabIndex: Int,
    onSelectedTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedTab = YamlDocumentTab.entries[selectedTabIndex]
    val yamlText = yamlDocumentText(selectedTab, uiState)

    Scaffold(
        topBar = {
            YamlProfileTopBar(
                onCopy = {
                    copyPlainTextToClipboard(
                        context = context,
                        label = "Profile YAML",
                        text = yamlText,
                        sensitive = true,
                    )
                    viewModel.showSnackbar("Copied YAML to clipboard!")
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 80.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item { YamlValidationCard(uiState) }
            item {
                YamlLayerTabs(
                    selectedIndex = selectedTabIndex,
                    onSelected = onSelectedTab
                )
            }
            item { YamlDocumentCard(selectedTab, yamlText) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YamlProfileTopBar(onCopy: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("YAML & Profile Layers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Recursive composition engine with schema v0.2",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconButton(onClick = onCopy, modifier = Modifier.testTag("btn_copy_yaml")) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy YAML",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@Composable
private fun YamlValidationCard(uiState: StudioUiState) {
    val isValid = uiState.validationResult.isValid
    val accent = if (isValid) AccentEmerald else AccentRose
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.15f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isValid) "Schema Validation Passed" else "Validation Error Found",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = if (isValid) {
                        "Profile strictly conforms to schema/style-profile.schema.json"
                    } else {
                        uiState.validationResult.errors.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun YamlLayerTabs(selectedIndex: Int, onSelected: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(MaterialTheme.shapes.medium)
    ) {
        YamlDocumentTab.entries.forEachIndexed { index, tab ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelected(index) },
                text = { Text(tab.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            )
        }
    }
}

@Composable
private fun YamlDocumentCard(tab: YamlDocumentTab, yamlText: String) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tab.fileLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        text = tab.languageLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.background)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = yamlText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

private fun yamlDocumentText(tab: YamlDocumentTab, uiState: StudioUiState): String = when (tab) {
    YamlDocumentTab.COMPOSED -> uiState.yamlRepresentation
    YamlDocumentTab.OVERLAY -> uiState.selectedOverlay?.let(YamlParser::dumpOverlay)
        ?: "# No active overlay selected.\n# Current view is using Base Default Profile."
    YamlDocumentTab.SCHEMA -> STYLE_PROFILE_SCHEMA
}

private val STYLE_PROFILE_SCHEMA = """
{
  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
  "${'$'}comment": "style-profile.schema.json (Schema Version 0.2)",
  "title": "StyleProfile",
  "type": "object",
  "required": ["schemaVersion", "id", "locale", "personality", "collaboration"],
  "properties": {
    "schemaVersion": { "type": "string", "const": "0.2" },
    "id": { "type": "string" },
    "locale": { "type": "string" },
    "personality": {
      "type": "object",
      "properties": {
        "base": { "enum": ["default", "professional", "friendly", "honest", "whimsical", "concise", "cynical"] },
        "intensity": { "type": ["integer", "null"], "minimum": 0, "maximum": 3 }
      }
    }
  }
}
""".trimIndent()
