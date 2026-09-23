package ais.tee.data.model

import kotlinx.serialization.Serializable

const val PROJECT_LIBRARY_VERSION = 1
const val DEFAULT_PROJECT_ID = "inbox"

@Serializable
data class LocalProject(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    override fun toString(): String =
        "LocalProject(id=<redacted>, name=<redacted>, createdAtEpochMs=${createdAtEpochMs}, updatedAtEpochMs=${updatedAtEpochMs})"
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
) {
    override fun toString(): String =
        "ProjectLibraryAsset(id=<redacted>, projectId=<redacted>, title=<redacted>, mediaType=${mediaType}, fileName=<redacted>, sizeBytes=${sizeBytes})"
}

@Serializable
data class ProjectLibraryArchive(
    val version: Int = PROJECT_LIBRARY_VERSION,
    val projects: List<LocalProject> = emptyList(),
    val assets: List<ProjectLibraryAsset> = emptyList(),
) {
    override fun toString(): String =
        "ProjectLibraryArchive(version=${version}, projects=${projects.size}, assets=${assets.size})"
}

fun ProjectLibraryArchive.normalizedProjectLibrary(now: Long): ProjectLibraryArchive? {
    if (version != PROJECT_LIBRARY_VERSION) return null
    val seenProjects = mutableSetOf<String>()
    val normalizedProjects = projects.mapNotNull { project ->
        val id = project.id.trim()
        val name = project.name.trim()
        if (id.isEmpty() || name.isEmpty() || !seenProjects.add(id)) return@mapNotNull null
        project.copy(id = id, name = name)
    }.toMutableList()
    if (normalizedProjects.none { it.id == DEFAULT_PROJECT_ID }) {
        normalizedProjects.add(
            0,
            LocalProject(
                id = DEFAULT_PROJECT_ID,
                name = "Inbox",
                createdAtEpochMs = now,
            )
        )
        seenProjects += DEFAULT_PROJECT_ID
    }

    val seenAssets = mutableSetOf<String>()
    val normalizedAssets = assets.mapNotNull { asset ->
        val id = asset.id.trim()
        val projectId = asset.projectId.trim()
        val title = asset.title.trim()
        val fileName = asset.fileName.trim()
        val mediaType = asset.mediaType.trim().lowercase()
        if (
            id.isEmpty() ||
            projectId !in seenProjects ||
            title.isEmpty() ||
            fileName.isEmpty() ||
            mediaType.isEmpty() ||
            asset.sizeBytes < 0 ||
            !seenAssets.add(id)
        ) {
            return@mapNotNull null
        }
        asset.copy(
            id = id,
            projectId = projectId,
            title = title,
            fileName = fileName,
            mediaType = mediaType,
        )
    }
    return copy(projects = normalizedProjects, assets = normalizedAssets)
}
