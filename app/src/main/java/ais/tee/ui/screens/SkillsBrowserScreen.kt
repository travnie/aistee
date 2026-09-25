package ais.tee.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ais.tee.share.copyPlainTextToClipboard
import ais.tee.data.document.MarkdownDocumentFileAccess
import ais.tee.data.repository.SkillsAndDocsRepository
import ais.tee.data.skills.LocalSkillAlreadyExistsException
import ais.tee.data.skills.LocalSkillLibraryStore
import ais.tee.ui.theme.*
import ais.tee.ui.viewmodel.ExternalMarkdownOpenResult
import ais.tee.ui.viewmodel.MarkdownWorkspaceOrigin
import ais.tee.ui.viewmodel.MarkdownWorkspaceViewModel
import ais.tee.ui.viewmodel.NavigationTab
import ais.tee.ui.viewmodel.StudioViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SKILL_IMPORT_MIME_TYPES = arrayOf(
    "text/markdown",
    "text/plain",
    "application/octet-stream"
)

private fun skillPreviewReadErrorMessage(detail: String?): String =
    detail?.takeIf(String::isNotBlank)?.let { "Could not preview selected skill: $it" }
        ?: "Could not preview the selected skill file."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsBrowserScreen(
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val markdownWorkspaceViewModel: MarkdownWorkspaceViewModel = viewModel()
    val localSkillStore = remember(context) {
        LocalSkillLibraryStore(
            File(context.noBackupFilesDir, LocalSkillLibraryStore.LIBRARY_DIRECTORY_NAME)
        )
    }
    var selectedCategoryTab by remember { mutableStateOf(0) } // 0: Skills, 1: Instructions, 2: Templates
    var searchQuery by remember { mutableStateOf("") }
    var selectedItemContent by remember { mutableStateOf<Pair<String, String>?>(null) } // Title to Content dialog
    var skillImportPreview by remember { mutableStateOf<SkillImportPreview?>(null) }
    var pendingSkillReplacement by remember { mutableStateOf<SkillImportPreview?>(null) }
    var pendingSkillEditName by remember { mutableStateOf<String?>(null) }
    var skillImportLoading by remember { mutableStateOf(false) }
    var skillImportSaving by remember { mutableStateOf(false) }
    var localSkillCount by remember { mutableIntStateOf(0) }
    var localSkillsRefreshToken by remember { mutableIntStateOf(0) }

    fun openLocalSkillEditor(
        name: String,
        source: String,
        allowDiscardDirty: Boolean
    ) {
        when (
            markdownWorkspaceViewModel.openExternalText(
                text = source,
                displayName = "SKILL.md",
                allowDiscardDirty = allowDiscardDirty,
                origin = MarkdownWorkspaceOrigin.LocalSkill(name),
                markDirty = false
            )
        ) {
            ExternalMarkdownOpenResult.OPENED -> {
                pendingSkillEditName = null
                viewModel.selectTab(NavigationTab.YAML)
            }
            ExternalMarkdownOpenResult.NEEDS_DISCARD -> pendingSkillEditName = name
            ExternalMarkdownOpenResult.BUSY -> viewModel.showSnackbar(
                "Markdown workspace is still restoring or busy. Try again when it is ready."
            )
            ExternalMarkdownOpenResult.TOO_LARGE -> viewModel.showSnackbar(
                "This local skill is larger than the 8 MiB Markdown workspace limit."
            )
        }
    }

    val skillImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                skillImportLoading = true
                try {
                    val previewResult = runCatching {
                        val opened = MarkdownDocumentFileAccess.import(context, uri)
                        buildSkillImportPreview(
                            displayName = opened.displayName,
                            source = opened.document.text,
                            validateFilename = opened.hasProviderDisplayName
                        )
                    }
                    previewResult.fold(
                        onSuccess = { skillImportPreview = it },
                        onFailure = { error ->
                            when (error) {
                                is CancellationException -> throw error
                                is IOException, is SecurityException ->
                                    viewModel.showSnackbar(skillPreviewReadErrorMessage(error.message))
                                else -> throw error
                            }
                        }
                    )
                } finally {
                    skillImportLoading = false
                }
            }
        }
    }

    fun launchSkillPreview() {
        try {
            skillImportLauncher.launch(SKILL_IMPORT_MIME_TYPES)
        } catch (_: ActivityNotFoundException) {
            viewModel.showSnackbar("No document picker is available.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Skills & Reference Library",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Portable skills, reusable instructions, and starter templates",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search skills, instructions, templates...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_skills_field")
                )
            }

            item {
                TabRow(
                    selectedTabIndex = selectedCategoryTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(MaterialTheme.shapes.medium)
                ) {
                    Tab(
                        selected = selectedCategoryTab == 0,
                        onClick = { selectedCategoryTab = 0 },
                        text = {
                            Text(
                                "Skills (${SkillsAndDocsRepository.skills.size + localSkillCount})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                    Tab(
                        selected = selectedCategoryTab == 1,
                        onClick = { selectedCategoryTab = 1 },
                        text = { Text("Instructions (${SkillsAndDocsRepository.instructions.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedCategoryTab == 2,
                        onClick = { selectedCategoryTab = 2 },
                        text = { Text("Templates (${SkillsAndDocsRepository.templates.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            when (selectedCategoryTab) {
                0 -> {
                    item {
                        LocalSkillPreviewCard(
                            loading = skillImportLoading,
                            onPreview = ::launchSkillPreview
                        )
                    }
                    item {
                        LocalSkillLibrarySection(
                            store = localSkillStore,
                            searchQuery = searchQuery,
                            refreshToken = localSkillsRefreshToken,
                            onCountChanged = { localSkillCount = it },
                            onViewSource = { name, source -> selectedItemContent = name to source },
                            onEditSource = { name, source ->
                                openLocalSkillEditor(name, source, allowDiscardDirty = false)
                            },
                            onMessage = viewModel::showSnackbar
                        )
                    }

                    val filtered = SkillsAndDocsRepository.skills.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                                it.description.contains(searchQuery, ignoreCase = true) ||
                                it.category.contains(searchQuery, ignoreCase = true)
                    }
                    items(filtered) { skill ->
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(1.5.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedItemContent = skill.title to skill.content }
                                .testTag("skill_${skill.id}")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = skill.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = skill.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = skill.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Platform: ${skill.targetPlatform}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentCyan
                                    )
                                    TextButton(
                                        onClick = { selectedItemContent = skill.title to skill.content },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("View Skill Spec →", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val filtered = SkillsAndDocsRepository.instructions.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                                it.summary.contains(searchQuery, ignoreCase = true) ||
                                it.category.contains(searchQuery, ignoreCase = true)
                    }
                    items(filtered) { inst ->
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(1.5.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedItemContent = inst.title to inst.promptText }
                                .testTag("inst_${inst.id}")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = inst.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = inst.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = inst.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = inst.promptText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }
                2 -> {
                    val filtered = SkillsAndDocsRepository.templates.filter {
                        it.filename.contains(searchQuery, ignoreCase = true) ||
                                it.description.contains(searchQuery, ignoreCase = true) ||
                                it.type.contains(searchQuery, ignoreCase = true)
                    }
                    items(filtered) { tmpl ->
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(1.5.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedItemContent = tmpl.filename to tmpl.code }
                                .testTag("tmpl_${tmpl.id}")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tmpl.filename,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            text = tmpl.type,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = tmpl.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                TextButton(
                                    onClick = { selectedItemContent = tmpl.filename to tmpl.code },
                                    modifier = Modifier.align(Alignment.End),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("View Source Code →", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedItemContent?.let { (title, content) ->
        AlertDialog(
            onDismissRequest = { selectedItemContent = null },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(12.dp)
                ) {
                    LazyColumn {
                        item {
                            Text(
                                text = content,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyPlainTextToClipboard(
                            context = context,
                            label = title,
                            text = content,
                            sensitive = true,
                        )
                        viewModel.showSnackbar("Copied '$title' to clipboard!")
                        selectedItemContent = null
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Content")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedItemContent = null }) {
                    Text("Close")
                }
            }
        )
    }

    pendingSkillEditName?.let { skillName ->
        AlertDialog(
            onDismissRequest = { pendingSkillEditName = null },
            title = { Text("Replace unsaved Markdown draft?") },
            text = {
                Text(
                    "The Markdown workspace has unsaved edits. Discard that draft and edit '$skillName' as its local SKILL.md source?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val document = localSkillStore.read(skillName)
                            if (document == null) {
                                pendingSkillEditName = null
                                localSkillsRefreshToken += 1
                                viewModel.showSnackbar("Local skill is no longer available.")
                            } else {
                                openLocalSkillEditor(
                                    name = skillName,
                                    source = document.source,
                                    allowDiscardDirty = true
                                )
                            }
                        }
                    }
                ) {
                    Text("Discard and edit")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSkillEditName = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    skillImportPreview?.let { preview ->
        SkillImportPreviewDialog(
            preview = preview,
            saving = skillImportSaving,
            onAdd = {
                scope.launch {
                    skillImportSaving = true
                    try {
                        val saved = localSkillStore.add(preview.source)
                        localSkillsRefreshToken += 1
                        skillImportPreview = null
                        viewModel.showSnackbar("Saved '${saved.name}' to local skills.")
                    } catch (_: LocalSkillAlreadyExistsException) {
                        pendingSkillReplacement = preview
                        skillImportPreview = null
                    } catch (error: IOException) {
                        viewModel.showSnackbar(error.message ?: "Could not save local skill.")
                    } finally {
                        skillImportSaving = false
                    }
                }
            },
            onDismiss = { if (!skillImportSaving) skillImportPreview = null }
        )
    }

    pendingSkillReplacement?.let { preview ->
        SkillReplacementConfirmationDialog(
            preview = preview,
            saving = skillImportSaving,
            onReplace = {
                scope.launch {
                    skillImportSaving = true
                    try {
                        val saved = localSkillStore.add(preview.source, replaceExisting = true)
                        localSkillsRefreshToken += 1
                        pendingSkillReplacement = null
                        viewModel.showSnackbar("Replaced '${saved.name}' in local skills.")
                    } catch (error: IOException) {
                        viewModel.showSnackbar(error.message ?: "Could not replace local skill.")
                    } finally {
                        skillImportSaving = false
                    }
                }
            },
            onCancel = {
                if (!skillImportSaving) {
                    pendingSkillReplacement = null
                    skillImportPreview = preview
                }
            }
        )
    }
}

@Composable
private fun LocalSkillPreviewCard(
    loading: Boolean,
    onPreview: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Text(
                    text = "Preview local SKILL.md",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Validate a portable skill before adding it. Previewing only reads the file; imported instructions, scripts, and tools are never executed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onPreview,
                enabled = !loading,
                modifier = Modifier.testTag("preview_local_skill_button")
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Reading…" else "Choose SKILL.md")
            }
        }
    }
}

@Composable
private fun SkillImportPreviewDialog(
    preview: SkillImportPreview,
    saving: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("skill_import_preview_dialog"),
        title = {
            Text(
                text = if (preview.isValid) "Portable skill preview" else "Skill needs attention",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
            ) {
                item {
                    Text(
                        text = preview.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace
                    )
                }
                item {
                    Text(
                        text = "Previewing is read-only. Adding stores a private copy; Aistee does not execute imported instructions, scripts, or declared tools.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (preview.issues.isNotEmpty()) {
                    item {
                        Text(
                            text = "Validation",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(preview.issues) { issue ->
                        val prefix = issue.field?.let { "$it: " }.orEmpty()
                        Text(
                            text = "• $prefix${issue.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                preview.manifest?.let { manifest ->
                    item {
                        HorizontalDivider()
                    }
                    item {
                        Text(
                            text = manifest.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    manifest.compatibility?.let { compatibility ->
                        item {
                            Text(
                                text = "Compatibility: $compatibility",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    manifest.allowedTools?.let { allowedTools ->
                        item {
                            Text(
                                text = "Declared tools: $allowedTools",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (manifest.metadata.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Metadata",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                manifest.metadata.forEach { (key, value) ->
                                    Text(
                                        text = "$key: $value",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "A single-file picker cannot verify the parent skill-directory name. That constraint is checked when a directory is available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    HorizontalDivider()
                }
                item {
                    Text(
                        text = "Source preview",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preview.sourceForDisplay(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (preview.isValid) {
                Button(onClick = onAdd, enabled = !saving) {
                    if (saving) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (saving) "Adding…" else "Add to library")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (preview.isValid) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun SkillReplacementConfirmationDialog(
    preview: SkillImportPreview,
    saving: Boolean,
    onReplace: () -> Unit,
    onCancel: () -> Unit
) {
    val skillName = preview.manifest?.name ?: preview.displayName
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Replace '$skillName'?") },
        text = {
            Text(
                "A local skill with this name already exists. Replacing it overwrites the stored SKILL.md source. " +
                    "Export the current copy first if you may need it later."
            )
        },
        confirmButton = {
            Button(onClick = onReplace, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (saving) "Replacing…" else "Replace")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !saving) {
                Text("Cancel")
            }
        }
    )
}
