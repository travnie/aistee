package ais.tee.data.preferences

import android.util.AtomicFile
import ais.tee.data.model.DEFAULT_PROJECT_ID
import ais.tee.data.model.LocalProject
import ais.tee.data.model.PROJECT_LIBRARY_VERSION
import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.normalizedProjectLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class LoadedProjectLibraryAsset(
    val metadata: ProjectLibraryAsset,
    val text: String,
) {
    override fun toString(): String =
        "LoadedProjectLibraryAsset(metadata=${metadata}, text=<redacted>)"
}

internal class ProjectLibraryStore(private val noBackupRoot: File) {
    private val root = File(noBackupRoot, DIRECTORY_NAME)
    private val assetsDirectory = File(root, ASSETS_DIRECTORY_NAME)
    private val indexFile = AtomicFile(File(root, INDEX_FILE_NAME))
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(now: Long = System.currentTimeMillis()): ProjectLibraryArchive {
        val decoded = if (indexFile.baseFile.isFile) {
            runCatching {
                json.decodeFromString<ProjectLibraryArchive>(
                    indexFile.readFully().decodeToString(throwOnInvalidSequence = true)
                )
            }.getOrNull()
        } else null
        return (decoded ?: ProjectLibraryArchive(version = PROJECT_LIBRARY_VERSION))
            .normalizedProjectLibrary(now)
            ?: ProjectLibraryArchive().normalizedProjectLibrary(now)!!
    }

    fun createProject(name: String, now: Long = System.currentTimeMillis()): LocalProject? {
        val normalizedName = name.trim().takeIf { it.isNotEmpty() }?.take(MAX_PROJECT_NAME_CHARS) ?: return null
        val archive = load(now)
        val project = LocalProject(
            id = UUID.randomUUID().toString(),
            name = normalizedName,
            createdAtEpochMs = now,
        )
        return if (saveArchive(archive.copy(projects = archive.projects + project))) project else null
    }

    fun saveTextAsset(
        projectId: String = DEFAULT_PROJECT_ID,
        title: String,
        mediaType: String,
        extension: String,
        text: String,
        now: Long = System.currentTimeMillis(),
    ): ProjectLibraryAsset? {
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size > MAX_ASSET_BYTES) return null
        val archive = load(now)
        if (archive.projects.none { it.id == projectId }) return null
        val normalizedTitle = title.trim().takeIf { it.isNotEmpty() }?.take(MAX_ASSET_TITLE_CHARS) ?: return null
        val normalizedType = mediaType.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        val normalizedExtension = extension.trim().removePrefix(".")
            .lowercase()
            .takeIf { it.matches(Regex("^[a-z0-9]{1,8}$")) }
            ?: return null

        if (!assetsDirectory.exists() && !assetsDirectory.mkdirs()) return null
        val id = UUID.randomUUID().toString()
        val fileName = "${id}.${normalizedExtension}"
        val target = File(assetsDirectory, fileName)
        val temp = File(assetsDirectory, "${fileName}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            val asset = ProjectLibraryAsset(
                id = id,
                projectId = projectId,
                title = normalizedTitle,
                mediaType = normalizedType,
                fileName = fileName,
                sizeBytes = bytes.size,
                createdAtEpochMs = now,
            )
            if (!saveArchive(archive.copy(assets = archive.assets + asset))) {
                target.delete()
                null
            } else {
                asset
            }
        } catch (_: IOException) {
            temp.delete()
            target.delete()
            null
        }
    }

    fun loadTextAsset(assetId: String, now: Long = System.currentTimeMillis()): LoadedProjectLibraryAsset? {
        val metadata = load(now).assets.firstOrNull { it.id == assetId } ?: return null
        val file = File(assetsDirectory, metadata.fileName)
        if (!file.isFile || file.length() > MAX_ASSET_BYTES) return null
        val text = runCatching {
            file.readBytes().decodeToString(throwOnInvalidSequence = true)
        }.getOrNull() ?: return null
        return LoadedProjectLibraryAsset(metadata, text)
    }

    fun deleteAsset(assetId: String, now: Long = System.currentTimeMillis()): Boolean {
        val archive = load(now)
        val asset = archive.assets.firstOrNull { it.id == assetId } ?: return false
        val remaining = archive.assets.filterNot { it.id == assetId }
        if (!saveArchive(archive.copy(assets = remaining))) return false
        File(assetsDirectory, asset.fileName).delete()
        return true
    }

    private fun saveArchive(archive: ProjectLibraryArchive): Boolean = runCatching {
        root.mkdirs()
        val bytes = json.encodeToString(archive).encodeToByteArray()
        var output: FileOutputStream? = indexFile.startWrite()
        try {
            val stream = requireNotNull(output)
            stream.write(bytes)
            stream.flush()
            indexFile.finishWrite(stream)
            output = null
        } catch (error: IOException) {
            output?.let(indexFile::failWrite)
            throw error
        }
        true
    }.getOrDefault(false)

    companion object {
        const val DIRECTORY_NAME = "project-library-v1"
        const val MAX_ASSET_BYTES = 8 * 1024 * 1024
        private const val ASSETS_DIRECTORY_NAME = "assets"
        private const val INDEX_FILE_NAME = "index.json"
        private const val MAX_PROJECT_NAME_CHARS = 80
        private const val MAX_ASSET_TITLE_CHARS = 120
    }
}
