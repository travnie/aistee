package ais.tee.ui.screens

import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Resolves local-only references, without loading file content or adding it to an API prompt. */
internal data class ProjectLibrarySourcePreview(val asset: ProjectLibraryAsset?) {
    val label: String get() = asset?.title ?: "Removed source"
    override fun toString(): String = "ProjectLibrarySourcePreview(<redacted>)"
}

internal fun projectLibrarySourcePreviews(
    sourceIds: List<String>,
    archive: ProjectLibraryArchive,
): List<ProjectLibrarySourcePreview> {
    val available = archive.assets.associateBy { it.id }
    return sourceIds.asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .take(4)
        .map { id -> ProjectLibrarySourcePreview(available[id]) }
        .toList()
}

/** Local-file link, not a claim that the AI model cited this source. */
@Composable
internal fun ProjectLibrarySourceChips(
    sourceIds: List<String>,
    archive: ProjectLibraryArchive,
    onOpenAsset: (ProjectLibraryAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previews = projectLibrarySourcePreviews(sourceIds, archive)
    if (previews.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("library_source_chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(previews.take(3)) { index, preview ->
            AssistChip(
                onClick = { preview.asset?.let(onOpenAsset) },
                enabled = preview.asset != null,
                label = {
                    Text(
                        text = "Library source · ${preview.label}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag("library_source_chip_$index"),
            )
        }
        if (previews.size > 3) {
            item {
                Text(
                    text = "More sources",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
