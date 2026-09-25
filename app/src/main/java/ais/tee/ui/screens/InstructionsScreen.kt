package ais.tee.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ais.tee.share.copyPlainTextToClipboard
import ais.tee.data.tokenizer.LocalTokenCounter
import ais.tee.ui.theme.*
import ais.tee.ui.viewmodel.StudioUiState
import ais.tee.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TOKEN_COUNT_DEBOUNCE_MS = 360L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionsScreen(
    viewModel: StudioViewModel,
    uiState: StudioUiState,
    onNavigateToPlayground: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderedText = uiState.renderedInstructions
    val lines = remember(renderedText) {
        renderedText.lines().filter { it.isNotBlank() }
    }
    var tokenCount by remember(renderedText) { mutableStateOf<Int?>(null) }
    var tokenCountFailed by remember(renderedText) { mutableStateOf(false) }

    LaunchedEffect(renderedText) {
        delay(TOKEN_COUNT_DEBOUNCE_MS)
        runCatching {
            withContext(Dispatchers.Default) { LocalTokenCounter.count(renderedText) }
        }.onSuccess { count ->
            tokenCount = count
        }.onFailure {
            tokenCountFailed = true
        }
    }

    val tokenSummary = when {
        tokenCount != null -> "${tokenCount} tokens · ${LocalTokenCounter.ENCODING_LABEL}"
        tokenCountFailed -> "tokens unavailable"
        else -> "counting tokens…"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Rendered Instructions",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${lines.size} effective rules • $tokenSummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            copyPlainTextToClipboard(
                                context = context,
                                label = "AI System Instructions",
                                text = renderedText,
                                sensitive = true,
                            )
                            viewModel.showSnackbar("Copied instructions to clipboard!")
                        },
                        modifier = Modifier.testTag("btn_copy_instructions")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Instructions",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = {
                            val sendIntent: Intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, renderedText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Instructions"))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
            // Language Switcher Banner
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Renderer Language",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Target instruction locale (EN / PL)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("auto" to "Auto", "en" to "EN", "pl" to "PL").forEach { (code, label) ->
                                val isSelected = uiState.language == code
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.testTag("lang_$code")
                                ) {
                                    Button(
                                        onClick = { viewModel.setLanguage(code) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Quick Playground Action Banner
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Test Active Rules",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Simulate how an assistant behaves under this compiled profile",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = onNavigateToPlayground,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("btn_goto_playground")
                        ) {
                            Text("Test Chat", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Rendered Instruction Rules List
            item {
                Text(
                    text = "System Prompt Output (Markdown List)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(lines) { index, line ->
                val cleanLine = line.removePrefix("- ").trim()
                val isCore = index == 1 || index == 0
                val isBoundary = index == lines.lastIndex

                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isCore -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            isBoundary -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when {
                            isCore -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            isBoundary -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            if (isCore) {
                                Text(
                                    text = if (index == 0) "BASE VOICE" else "CORE FACTUAL INTEGRITY",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp
                                )
                            } else if (isBoundary) {
                                Text(
                                    text = "SAFETY & PERMISSIONS BOUNDARY",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentRose,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = cleanLine,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            // Raw Text Box (Monospace)
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Raw Rendered String",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    copyPlainTextToClipboard(
                                        context = context,
                                        label = "Raw System Prompt",
                                        text = renderedText,
                                        sensitive = true,
                                    )
                                    viewModel.showSnackbar("Raw instructions copied!")
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Raw", Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = renderedText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                                .padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}
