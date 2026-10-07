package ais.tee.data.model

import kotlinx.serialization.Serializable

const val PROJECT_LIBRARY_VERSION = 2
const val PROJECT_LIBRARY_LEGACY_VERSION = 1
const val DEFAULT_PROJECT_ID = "inbox"

private val PROJECT_LIBRARY_FILE_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,160}$")
private val PROJECT_LIBRARY_SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

@Serializable
enum class ProjectLibraryAssetKind {
    DOCUMENT,
    PROMPT,
    INSTRUCTION,
    SKILL,
    ARTIFACT,
}

@Serializable
enum class ProjectLibraryAssetOrigin {
    LOCAL,
    SHARED,
    IMPORTED,
    CHAT_EXPORT,
    JOB_OUTPUT,
    TOOL_OUTPUT,
}

@Serializable
data class LocalProject(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    override fun toString(): String =
        "LocalProject(id=<redacted>, name=<redacted>, createdAtEpochMs=$createdAtEpochMs, updatedAtEpochMs=$updatedAtEpochMs)"
}

@Serializable
data class ProjectLibraryAsset(
    val id: String,
    val projectId: String,
    val title: String,
    val mediaType: String,
    val fileName: String,
    val sizeBytes: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
    val revision: Long = 1L,
    val contentSha256: String? = null,
    val kind: ProjectLibraryAssetKind = ProjectLibraryAssetKind.DOCUMENT,
    val origin: ProjectLibraryAssetOrigin = ProjectLibraryAssetOrigin.LOCAL,
) {
    override fun toString(): String =
        "ProjectLibraryAsset(id=<redacted>, projectId=<redacted>, title=<redacted>, mediaType=$mediaType, " +
            "fileName=<redacted>, sizeBytes=$sizeBytes, revision=$revision, kind=$kind, origin=$origin)"
}

@Serializable
data class ProjectLibraryArchive(
    val version: Int = PROJECT_LIBRARY_VERSION,
    val projects: List<LocalProject> = emptyList(),
    val assets: List<ProjectLibraryAsset> = emptyList(),
) {
    override fun toString(): String =
        "ProjectLibraryArchive(version=$version, projects=${projects.size}, assets=${assets.size})"
}

fun ProjectLibraryArchive.normalizedProjectLibrary(now: Long): ProjectLibraryArchive? =
    takeIf { it.version == PROJECT_LIBRARY_VERSION }
        ?.normalizedContents(now)

private fun ProjectLibraryArchive.normalizedContents(now: Long): ProjectLibraryArchive {
    val normalizedProjects = normalizedProjectsWithInbox(now)
    val validProjectIds = normalizedProjects.mapTo(mutableSetOf()) { it.id }
    return copy(
        projects = normalizedProjects,
        assets = normalizedAssets(validProjectIds),
    )
}

private fun ProjectLibraryArchive.normalizedProjectsWithInbox(now: Long): List<LocalProject> {
    val seenProjectIds = mutableSetOf<String>()
    val normalized = projects
        .mapNotNull { it.normalizedProject(seenProjectIds) }
        .toMutableList()
    if (DEFAULT_PROJECT_ID !in seenProjectIds) {
        normalized.add(
            0,
            LocalProject(
                id = DEFAULT_PROJECT_ID,
                name = "Inbox",
                createdAtEpochMs = now,
            ),
        )
    }
    return normalized
}

private fun ProjectLibraryArchive.normalizedAssets(validProjectIds: Set<String>): List<ProjectLibraryAsset> {
    val seenAssetIds = mutableSetOf<String>()
    return assets.mapNotNull { asset ->
        asset.normalizedAsset(
            validProjectIds = validProjectIds,
            seenAssetIds = seenAssetIds,
        )
    }
}

private fun LocalProject.normalizedProject(seenProjectIds: MutableSet<String>): LocalProject? {
    val normalizedId = id.trim()
    val normalizedName = name.trim()
    val metadataIsValid = listOf(
        normalizedId.isNotEmpty(),
        normalizedName.isNotEmpty(),
    ).all { it }
    if (!metadataIsValid) return null
    if (!seenProjectIds.add(normalizedId)) return null
    return copy(id = normalizedId, name = normalizedName)
}

private fun ProjectLibraryAsset.normalizedAsset(
    validProjectIds: Set<String>,
    seenAssetIds: MutableSet<String>,
): ProjectLibraryAsset? {
    val normalizedId = id.trim()
    val normalizedProjectId = projectId.trim()
    val normalizedTitle = title.trim()
    val normalizedFileName = fileName.trim()
    val normalizedMediaType = mediaType.trim().lowercase()
    val normalizedHash = contentSha256?.trim()?.lowercase()
    val metadataIsValid = listOf(
        normalizedId.isNotEmpty(),
        normalizedProjectId in validProjectIds,
        normalizedTitle.isNotEmpty(),
        normalizedFileName.matches(PROJECT_LIBRARY_FILE_NAME_PATTERN),
        normalizedFileName != ".",
        normalizedFileName != "..",
        normalizedMediaType.isNotEmpty(),
        sizeBytes >= 0,
        revision >= 1L,
        updatedAtEpochMs >= createdAtEpochMs,
        normalizedHash?.matches(PROJECT_LIBRARY_SHA256_PATTERN) != false,
    ).all { it }
    if (!metadataIsValid) return null
    if (!seenAssetIds.add(normalizedId)) return null

    return copy(
        id = normalizedId,
        projectId = normalizedProjectId,
        title = normalizedTitle,
        fileName = normalizedFileName,
        mediaType = normalizedMediaType,
        contentSha256 = normalizedHash,
    )
}
