package ais.tee.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ais.tee.data.model.AiProvider
import ais.tee.data.model.ConversationStateStrategy
import ais.tee.data.model.NativeChatTransport
import ais.tee.data.model.ProfileOverlay
import ais.tee.data.model.ReasoningControlStrategy
import ais.tee.data.model.SystemInstructionPlacement
import ais.tee.data.model.reasoningControlStrategy
import ais.tee.data.model.runtimeCapabilities
import ais.tee.ui.components.*
import ais.tee.ui.theme.*
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    uiState: StudioUiState,
    onNavigateToInstructions: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToYaml: () -> Unit,
    onNavigateToPlayground: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveOverlayName by remember { mutableStateOf("") }
    var saveOverlayDesc by remember { mutableStateOf("") }
    var toolsMenuExpanded by remember { mutableStateOf(false) }

    var expandedSection by rememberSaveable { mutableStateOf("personality") } // personality, collaboration, knowledge, output

    val activeProfile = uiState.mergedProfile

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = ".ai Profile Tools",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "v0.2",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Portable AI core manager & overlay compositor",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.resetToDefault() },
                        modifier = Modifier.testTag("btn_reset_defaults")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset to default",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier.testTag("btn_save_overlay")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save Overlay",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { toolsMenuExpanded = true },
                            modifier = Modifier.testTag("btn_studio_tools")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More Studio tools")
                        }
                        DropdownMenu(
                            expanded = toolsMenuExpanded,
                            onDismissRequest = { toolsMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Profile playground") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onNavigateToPlayground()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Skills browser") },
                                leadingIcon = { Icon(Icons.Default.LibraryBooks, contentDescription = null) },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onNavigateToSkills()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("YAML editor") },
                                leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onNavigateToYaml()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToInstructions,
                icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                text = { Text("View Rendered (${uiState.renderedInstructions.lines().count { it.isNotBlank() }} rules)") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_view_instructions")
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
            // Preset / Overlay Selector Row
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Active Profile Overlay",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (uiState.selectedOverlay != null) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = AccentEmerald.copy(alpha = 0.2f),
                                    contentColor = AccentEmerald
                                ) {
                                    Text(
                                        text = "Layer Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Later layers override earlier base traits according to .ai schema",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                FilterChip(
                                    selected = uiState.selectedOverlay == null,
                                    onClick = { viewModel.applyOverlay(null) },
                                    label = { Text("Base Default") },
                                    leadingIcon = if (uiState.selectedOverlay == null) {
                                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier.testTag("overlay_none")
                                )
                            }
                            items(uiState.availableOverlays) { overlay ->
                                val isSelected = uiState.selectedOverlay === overlay
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.applyOverlay(overlay) },
                                    label = { Text(overlay.name) },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier.testTag("overlay_${overlay.id}")
                                )
                            }
                        }
                    }
                }
            }

            item {
                PromptRoutePreviewCard(uiState)
            }

            // Section 1: Personality
            item {
                AccordionSectionCard(
                    title = "Personality & Voice",
                    subtitle = "Base voice '${activeProfile.personality.base}' (${activeProfile.personality.intensity ?: 1}/3), ${activeProfile.personality.modifiers.count { (it.value ?: 0) > 0 }} active modifiers",
                    icon = Icons.Default.Psychology,
                    isExpanded = expandedSection == "personality",
                    onToggle = { expandedSection = if (expandedSection == "personality") "" else "personality" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Base Voice Selector
                        Text(
                            text = "Base Voice",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val bases = listOf(
                            "friendly" to "Friendly",
                            "professional" to "Professional",
                            "concise" to "Concise",
                            "honest" to "Honest",
                            "cynical" to "Cynical",
                            "whimsical" to "Whimsical",
                            "default" to "Neutral"
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(bases) { (baseKey, baseLabel) ->
                                val isSelected = activeProfile.personality.base == baseKey
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .clickable { viewModel.setBasePersonality(baseKey) }
                                        .testTag("base_voice_$baseKey")
                                ) {
                                    Text(
                                        text = baseLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        // Base Intensity
                        Text(
                            text = "Base Voice Intensity: ${activeProfile.personality.intensity ?: 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                0 to "0: Background",
                                1 to "1: Baseline",
                                2 to "2: Visible",
                                3 to "3: Strong"
                            ).forEach { (lvl, lbl) ->
                                val isSelected = (activeProfile.personality.intensity ?: 1) == lvl
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.setPersonalityIntensity(lvl) }
                                        .testTag("intensity_$lvl")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = lbl,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        Divider(Modifier.padding(vertical = 6.dp))

                        // Modifiers
                        Text(
                            text = "Voice Modifiers (0=Off, 1=Light, 2=Active, 3=Strong)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        val modifierDefs = listOf(
                            Triple("honest", "Plain, direct wording; surface useful criticism without euphemism", activeProfile.personality.modifiers["honest"]),
                            Triple("concise", "Compress aggressively; lead with result and strip framing", activeProfile.personality.modifiers["concise"]),
                            Triple("warm", "Calm, considerate phrasing and collaborative tone", activeProfile.personality.modifiers["warm"]),
                            Triple("technical", "Exact technical names, constraints, and precision", activeProfile.personality.modifiers["technical"]),
                            Triple("critical", "Stress-test assumptions, spot weak points, offer fixes", activeProfile.personality.modifiers["critical"]),
                            Triple("educational", "Build intuition first, explain mechanics, teach actively", activeProfile.personality.modifiers["educational"]),
                            Triple("cynical", "Dry skepticism toward hype and unnecessary complexity", activeProfile.personality.modifiers["cynical"]),
                            Triple("quickReplies", "Answer very simple requests with minimal words", activeProfile.personality.modifiers["quickReplies"]),
                            Triple("headingsAndLists", "Structured headings and bullet lists for readability", activeProfile.personality.modifiers["headingsAndLists"]),
                            Triple("whimsical", "Playful imagery and light humor when appropriate", activeProfile.personality.modifiers["whimsical"]),
                            Triple("enthusiastic", "Noticeable energy without marketing hype", activeProfile.personality.modifiers["enthusiastic"]),
                            Triple("emoji", "Purposeful emoji accents (never replacing clarity)", activeProfile.personality.modifiers["emoji"])
                        )

                        modifierDefs.forEach { (modName, desc, currentLvl) ->
                            ModifierKnobRow(
                                name = modName.replaceFirstChar { it.uppercase() },
                                description = desc,
                                currentLevel = currentLvl,
                                onLevelSelected = { viewModel.setModifier(modName, it) }
                            )
                        }

                        Divider(Modifier.padding(vertical = 6.dp))

                        // Adaptation Rules
                        Text(
                            text = "Adaptation Rules",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val adapt = activeProfile.personality.adaptation
                        ToggleRow(
                            title = "Follow User Register",
                            description = "Match user's register without copying mistakes or hostility",
                            checked = adapt.followUserRegister,
                            onCheckedChange = { viewModel.setAdaptation("followUserRegister", it) }
                        )
                        ToggleRow(
                            title = "Preserve Artifact Style",
                            description = "Requested output format outranks conversational tone",
                            checked = adapt.preserveRequestedArtifactStyle,
                            onCheckedChange = { viewModel.setAdaptation("preserveRequestedArtifactStyle", it) }
                        )
                        ToggleRow(
                            title = "Reduce Humor in Serious Contexts",
                            description = "Drop jokes and whimsical phrasing in high-risk contexts",
                            checked = adapt.reduceHumorInSeriousContexts,
                            onCheckedChange = { viewModel.setAdaptation("reduceHumorInSeriousContexts", it) }
                        )
                        ToggleRow(
                            title = "Mirror Language",
                            description = "Reply in the user's language unless requested otherwise",
                            checked = adapt.mirrorLanguage,
                            onCheckedChange = { viewModel.setAdaptation("mirrorLanguage", it) }
                        )
                        ToggleRow(
                            title = "Allow Casual Profanity",
                            description = "Natural mild profanity allowed in casual chat (never in formal artifacts)",
                            checked = adapt.allowCasualProfanity,
                            onCheckedChange = { viewModel.setAdaptation("allowCasualProfanity", it) }
                        )
                    }
                }
            }

            // Section 2: Collaboration
            item {
                val collab = activeProfile.collaboration
                AccordionSectionCard(
                    title = "Collaboration & Execution Policy",
                    subtitle = "Initiative: ${collab.initiative}, Verification: ${collab.verification}, Preamble: ${collab.preamble}",
                    icon = Icons.Default.Tune,
                    isExpanded = expandedSection == "collaboration",
                    onToggle = { expandedSection = if (expandedSection == "collaboration") "" else "collaboration" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnumSelectorGroup(
                            title = "Preamble Policy",
                            description = "When to announce plan before executing",
                            options = listOf(
                                "off" to "Off",
                                "multiStepOnly" to "Multi-Step Only",
                                "always" to "Always"
                            ),
                            selectedValue = collab.preamble,
                            onSelect = { viewModel.setCollaborationEnum("preamble", it) }
                        )

                        EnumSelectorGroup(
                            title = "Initiative Level",
                            description = "How proactively to suggest related tasks",
                            options = listOf(
                                "conservative" to "Conservative",
                                "balanced" to "Balanced",
                                "proactive" to "Proactive"
                            ),
                            selectedValue = collab.initiative,
                            onSelect = { viewModel.setCollaborationEnum("initiative", it) }
                        )

                        EnumSelectorGroup(
                            title = "Verification Rigor",
                            description = "Evidence validation threshold",
                            options = listOf(
                                "light" to "Light",
                                "normal" to "Normal",
                                "strict" to "Strict"
                            ),
                            selectedValue = collab.verification,
                            onSelect = { viewModel.setCollaborationEnum("verification", it) }
                        )

                        EnumSelectorGroup(
                            title = "Question Policy",
                            description = "When to ask user clarifying questions",
                            options = listOf(
                                "blockingOnly" to "Blocking Only",
                                "materialAmbiguity" to "Material Ambiguity",
                                "earlyAlignment" to "Early Alignment"
                            ),
                            selectedValue = collab.questionPolicy,
                            onSelect = { viewModel.setCollaborationEnum("questionPolicy", it) }
                        )

                        EnumSelectorGroup(
                            title = "Assumption Policy",
                            description = "How to handle reasonable assumptions",
                            options = listOf(
                                "cautious" to "Cautious",
                                "balanced" to "Balanced",
                                "decisive" to "Decisive"
                            ),
                            selectedValue = collab.assumptionPolicy,
                            onSelect = { viewModel.setCollaborationEnum("assumptionPolicy", it) }
                        )

                        Divider(Modifier.padding(vertical = 4.dp))

                        Text(
                            text = "Interaction Behaviors",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        ToggleRow(
                            title = "Answer First",
                            description = "Lead with the answer, result, or decision immediately",
                            checked = collab.answerFirst,
                            onCheckedChange = { viewModel.setCollaborationBool("answerFirst", it) }
                        )
                        ToggleRow(
                            title = "Plain Chat Is Default",
                            description = "Plain chat is default; use agentic machinery only when needed",
                            checked = collab.plainChatIsDefault,
                            onCheckedChange = { viewModel.setCollaborationBool("plainChatIsDefault", it) }
                        )
                        ToggleRow(
                            title = "Avoid Routine Praise",
                            description = "Do not open with automatic empty praise",
                            checked = collab.avoidRoutinePraise,
                            onCheckedChange = { viewModel.setCollaborationBool("avoidRoutinePraise", it) }
                        )
                        ToggleRow(
                            title = "Avoid Follow-Up Offer",
                            description = "Do not append routine 'Is there anything else?' at the end",
                            checked = collab.avoidRoutineFollowUpOffer,
                            onCheckedChange = { viewModel.setCollaborationBool("avoidRoutineFollowUpOffer", it) }
                        )
                        ToggleRow(
                            title = "Announce Only Material Actions",
                            description = "Report progress only for material milestones and risks",
                            checked = collab.announceOnlyMaterialActions,
                            onCheckedChange = { viewModel.setCollaborationBool("announceOnlyMaterialActions", it) }
                        )
                        ToggleRow(
                            title = "Report Partial Failures",
                            description = "Explicitly distinguish full success, partial success, and failure",
                            checked = collab.reportPartialFailures,
                            onCheckedChange = { viewModel.setCollaborationBool("reportPartialFailures", it) }
                        )
                        ToggleRow(
                            title = "Prefer Result Over Process",
                            description = "Present final result before walking through process",
                            checked = collab.preferResultOverProcess,
                            onCheckedChange = { viewModel.setCollaborationBool("preferResultOverProcess", it) }
                        )
                    }
                }
            }

            // Section 3: Output & Knowledge
            item {
                val output = activeProfile.output
                val knowledge = activeProfile.knowledge
                AccordionSectionCard(
                    title = "Output & Knowledge Rules",
                    subtitle = "Format: ${output.defaultFormat}, Code: ${output.codeExamples}, Citations: ${output.citations}",
                    icon = Icons.Default.FormatAlignLeft,
                    isExpanded = expandedSection == "output",
                    onToggle = { expandedSection = if (expandedSection == "output") "" else "output" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnumSelectorGroup(
                            title = "Code Examples",
                            options = listOf(
                                "minimal" to "Minimal",
                                "runnable" to "Runnable",
                                "explanatory" to "Explanatory"
                            ),
                            selectedValue = output.codeExamples,
                            onSelect = { viewModel.setOutputSetting(codeExamples = it) }
                        )

                        EnumSelectorGroup(
                            title = "Tables Policy",
                            options = listOf(
                                "avoid" to "Avoid",
                                "whenUseful" to "When Useful",
                                "prefer" to "Prefer"
                            ),
                            selectedValue = output.tables,
                            onSelect = { viewModel.setOutputSetting(tables = it) }
                        )

                        EnumSelectorGroup(
                            title = "Citations Mode",
                            options = listOf(
                                "platformDefault" to "Platform Default",
                                "whenAvailable" to "When Available",
                                "requiredForExternalFacts" to "Required for Facts"
                            ),
                            selectedValue = output.citations,
                            onSelect = { viewModel.setOutputSetting(citations = it) }
                        )

                        ToggleRow(
                            title = "Prefer Short Paragraphs",
                            description = "Keep paragraphs concise and scannable",
                            checked = output.preferShortParagraphs,
                            onCheckedChange = { viewModel.setOutputSetting(preferShortParagraphs = it) }
                        )

                        Divider(Modifier.padding(vertical = 4.dp))

                        Text(
                            text = "Knowledge & Truthfulness",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        ToggleRow(
                            title = "Distinguish Raw from Synthesis",
                            description = "Separate primary sources from synthesized thoughts",
                            checked = knowledge.distinguishRawFromSynthesis,
                            onCheckedChange = {}
                        )
                        ToggleRow(
                            title = "Treat Memory as Fallible",
                            description = "Never treat remembered context as definitive evidence",
                            checked = knowledge.treatMemoryAsFallible,
                            onCheckedChange = {}
                        )
                        ToggleRow(
                            title = "Surface Source Conflicts",
                            description = "Surface conflicts between contradictory sources directly",
                            checked = knowledge.surfaceSourceConflicts,
                            onCheckedChange = {}
                        )
                    }
                }
            }
        }
    }

    // Save Overlay Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save as Custom Overlay") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Save your current modified profile settings into a reusable overlay layer.")
                    OutlinedTextField(
                        value = saveOverlayName,
                        onValueChange = { saveOverlayName = it },
                        label = { Text("Overlay Name") },
                        placeholder = { Text("e.g. My Fast CLI Overlay") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = saveOverlayDesc,
                        onValueChange = { saveOverlayDesc = it },
                        label = { Text("Description") },
                        placeholder = { Text("Optional description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (saveOverlayName.isNotBlank()) {
                            viewModel.saveCustomOverlay(saveOverlayName, saveOverlayDesc)
                            showSaveDialog = false
                            saveOverlayName = ""
                            saveOverlayDesc = ""
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PromptRoutePreviewCard(uiState: StudioUiState) {
    val provider = uiState.selectedChatProvider
    val capabilities = provider.runtimeCapabilities()
    val modelLabel = if (provider == AiProvider.ALL) {
        "Per-provider defaults"
    } else {
        uiState.selectedChatModel.ifBlank { provider.defaultModel }
    }
    val renderedRuleCount = uiState.renderedInstructions.lines().count { it.isNotBlank() }
    val systemLabel = if (!uiState.includeSystemProfileInChat || uiState.renderedInstructions.isBlank()) {
        "Profile not attached"
    } else {
        when (capabilities.systemInstructionPlacement) {
            SystemInstructionPlacement.PER_PROVIDER -> "Attached using each provider's native route"
            SystemInstructionPlacement.NATIVE_FIELD -> "Native provider instruction field"
            SystemInstructionPlacement.SYSTEM_MESSAGE -> "OpenAI-compatible system message"
        }
    }
    val stateLabel = when (capabilities.conversationStateStrategy) {
        ConversationStateStrategy.PROVIDER_FAN_OUT -> "Isolated history per provider in compare fan-out"
        ConversationStateStrategy.BOUNDED_PROVIDER_TEXT_REPLAY -> "Bounded provider-scoped text replay"
        ConversationStateStrategy.BOUNDED_PROVIDER_CONTENT_REPLAY ->
            "Bounded provider content replay with opaque model state"
    }
    val resolvedModelLabel = when {
        provider == AiProvider.ALL -> "Captured per gateway when returned"
        capabilities.reportsResolvedModel -> "Gateway response metadata when returned"
        else -> "Selected/requested model"
    }
    val reasoningLabel = when (provider.reasoningControlStrategy()) {
        ReasoningControlStrategy.PER_PROVIDER -> "Per-provider model capabilities"
        ReasoningControlStrategy.PROVIDER_DEFAULT -> "Provider default"
        ReasoningControlStrategy.MODEL_CAPABILITY_METADATA -> "Anthropic Models API capabilities"
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("prompt_route_preview")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Prompt delivery preview",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "What native chat will actually send without exposing keys or hidden reasoning state.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PromptRouteRow("Target", "${provider.displayName} • $modelLabel")
            PromptRouteRow("Transport", capabilities.transport.displayLabel())
            PromptRouteRow("Profile", systemLabel)
            PromptRouteRow("History", stateLabel)
            PromptRouteRow("Reasoning", reasoningLabel)
            PromptRouteRow("Streaming", if (capabilities.streamsText) "Incremental SSE" else "Buffered response")
            PromptRouteRow("Model label", resolvedModelLabel)
            if (uiState.includeSystemProfileInChat && uiState.renderedInstructions.isNotBlank()) {
                Text(
                    text = "$renderedRuleCount rendered rules • ${uiState.renderedInstructions.length} characters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PromptRouteRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun NativeChatTransport.displayLabel(): String = when (this) {
    NativeChatTransport.COMPARE_FAN_OUT -> "Compare fan-out"
    NativeChatTransport.GEMINI_GENERATE_CONTENT -> "Gemini generateContent"
    NativeChatTransport.OPENAI_RESPONSES -> "OpenAI Responses"
    NativeChatTransport.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    NativeChatTransport.OPENAI_COMPATIBLE_CHAT_COMPLETIONS -> "OpenAI-compatible chat completions"
}

@Composable
fun AccordionSectionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    content()
                }
            }
        }
    }
}
