package ais.tee.data.preferences

import ais.tee.data.model.DEFAULT_PROJECT_ID
import ais.tee.data.model.LocalProject
import ais.tee.data.model.PROJECT_LIBRARY_LEGACY_VERSION
import ais.tee.data.model.PROJECT_LIBRARY_VERSION
import ais.tee.data.model.ProjectLibraryArchive
import ais.tee.data.model.ProjectLibraryAsset
import ais.tee.data.model.ProjectLibraryAssetKind
import ais.tee.data.model.ProjectLibraryAssetOrigin
import ais.tee.data.model.normalizedProjectLibrary
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.UUID

internal data class LoadedProjectLibraryAsset(
    val metadata: ProjectLibraryAsset,
    val text: String,
) {
    override fun toString(): String =
        "LoadedProjectLibraryAsset(metadata=$metadata, text=<redacted>)"
}

private data class PreparedTextAssetUpdate(
    val metadata: ProjectLibraryAsset,
    val previous: File,
    val target: File,
    val temp: File,
    val bytes: ByteArray,
)

internal class ProjectLibraryStore(private val noBackupRoot: File) {
    private val root = File(noBackupRoot, DIRECTORY_NAME)
    private val assetsDirectory = File(root, ASSETS_DIRECTORY_NAME)
    private val indexFile = AtomicFile(File(root, INDEX_FILE_NAME))
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(now: Long = System.currentTimeMillis()): ProjectLibraryArchive =
        synchronized(FILE_LOCK) { loadUnlocked(now) }

    private fun loadUnlocked(now: Long): ProjectLibraryArchive {
        val decoded = if (indexFile.baseFile.isFile) {
            runCatching {
                json.decodeFromString<ProjectLibraryArchive>(
                    indexFile.readFully().decodeToString(throwOnInvalidSequence = true),
                )
            }.getOrNull()
        } else {
            null
        }
        val candidate = when (decoded?.version) {
            null -> ProjectLibraryArchive(version = PROJECT_LIBRARY_VERSION)
            PROJECT_LIBRARY_VERSION -> decoded
            PROJECT_LIBRARY_LEGACY_VERSION -> migrateLegacyArchive(decoded)
            else -> null
        }
        val normalized = candidate
            ?.normalizedProjectLibrary(now)
            ?: ProjectLibraryArchive().normalizedProjectLibrary(now)!!
        val recovered = recoverUnavailableAssetRevisions(normalized)
        if (
            decoded?.version == PROJECT_LIBRARY_LEGACY_VERSION ||
            recovered != normalized
        ) {
            saveArchive(recovered)
        }
        return recovered
    }

    private fun migrateLegacyArchive(archive: ProjectLibraryArchive): ProjectLibraryArchive =
        archive.copy(
            version = PROJECT_LIBRARY_VERSION,
            assets = archive.assets.map { asset ->
                asset.copy(
                    updatedAtEpochMs = asset.createdAtEpochMs,
                    revision = 1L,
                    contentSha256 = readVerifiedBytes(asset)?.sha256(),
                    kind = ProjectLibraryAssetKind.DOCUMENT,
                    origin = ProjectLibraryAssetOrigin.LOCAL,
                )
            },
        )

    fun createProject(name: String, now: Long = System.currentTimeMillis()): LocalProject? =
        synchronized(FILE_LOCK) { createProjectUnlocked(name, now) }

    private fun createProjectUnlocked(name: String, now: Long): LocalProject? {
        val normalizedName = name.trim().takeIf { it.isNotEmpty() }?.take(MAX_PROJECT_NAME_CHARS) ?: return null
        val archive = loadUnlocked(now)
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
        kind: ProjectLibraryAssetKind = ProjectLibraryAssetKind.DOCUMENT,
        origin: ProjectLibraryAssetOrigin = ProjectLibraryAssetOrigin.LOCAL,
        now: Long = System.currentTimeMillis(),
    ): ProjectLibraryAsset? = synchronized(FILE_LOCK) {
        saveTextAssetUnlocked(projectId, title, mediaType, extension, text, kind, origin, now)
    }

    private fun saveTextAssetUnlocked(
        projectId: String,
        title: String,
        mediaType: String,
        extension: String,
        text: String,
        kind: ProjectLibraryAssetKind,
        origin: ProjectLibraryAssetOrigin,
        now: Long,
    ): ProjectLibraryAsset? {
        val bytes = encodeText(text) ?: return null
        if (bytes.size > MAX_ASSET_BYTES) return null
        val archive = loadUnlocked(now)
        if (archive.projects.none { it.id == projectId }) return null
        val normalizedTitle = title.trim().takeIf { it.isNotEmpty() }?.take(MAX_ASSET_TITLE_CHARS) ?: return null
        val normalizedType = mediaType.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        val normalizedExtension = normalizeExtension(extension) ?: return null

        if (!assetsDirectory.exists() && !assetsDirectory.mkdirs()) return null
        val id = UUID.randomUUID().toString()
        val fileName = "$id.$normalizedExtension"
        val target = File(assetsDirectory, fileName)
        val temp = File(assetsDirectory, "$fileName.tmp")
        return try {
            if (!writeRevisionBytes(temp, target, bytes)) return null
            val asset = ProjectLibraryAsset(
                id = id,
                projectId = projectId,
                title = normalizedTitle,
                mediaType = normalizedType,
                fileName = fileName,
                sizeBytes = bytes.size,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                revision = 1L,
                contentSha256 = bytes.sha256(),
                kind = kind,
                origin = origin,
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

    fun updateTextAsset(
        assetId: String,
        expectedRevision: Long,
        text: String,
        title: String? = null,
        now: Long = System.currentTimeMillis(),
    ): ProjectLibraryAsset? = synchronized(FILE_LOCK) {
        val archive = loadUnlocked(now)
        val asset = archive.assets.firstOrNull { it.id == assetId } ?: return@synchronized null
        val update = prepareTextAssetUpdate(asset, expectedRevision, text, title, now)
            ?: return@synchronized null
        persistPreparedUpdate(archive, asset, update)
    }

    private fun persistPreparedUpdate(
        archive: ProjectLibraryArchive,
        asset: ProjectLibraryAsset,
        update: PreparedTextAssetUpdate,
    ): ProjectLibraryAsset? {
        if (!canWritePreparedUpdate(update)) return null
        val updatedArchive = archive.withUpdatedAsset(asset.id, update.metadata)
        if (!saveArchive(updatedArchive)) {
            update.target.delete()
            return null
        }
        update.previous.delete()
        return update.metadata
    }

    private fun canWritePreparedUpdate(update: PreparedTextAssetUpdate): Boolean =
        ensureAssetsDirectory() &&
            prepareRevisionTarget(update.target) &&
            writeRevisionBytes(update.temp, update.target, update.bytes)

    private fun ProjectLibraryArchive.withUpdatedAsset(
        assetId: String,
        updated: ProjectLibraryAsset,
    ): ProjectLibraryArchive =
        copy(
            assets = assets.map { current ->
                if (current.id == assetId) updated else current
            },
        )

    private fun prepareTextAssetUpdate(
        asset: ProjectLibraryAsset,
        expectedRevision: Long,
        text: String,
        title: String?,
        now: Long,
    ): PreparedTextAssetUpdate? {
        val bytes = encodeText(text) ?: return null
        if (bytes.size > MAX_ASSET_BYTES) return null
        if (asset.revision != expectedRevision) return null
        if (asset.revision == Long.MAX_VALUE) return null

        val normalizedTitle = normalizedUpdateTitle(asset, title) ?: return null
        val extension = normalizeExtension(
            asset.fileName.substringAfterLast('.', missingDelimiterValue = ""),
        ) ?: return null
        val nextRevision = asset.revision + 1L
        val nextFileName = "${asset.id}.r$nextRevision.$extension"
        return PreparedTextAssetUpdate(
            metadata = asset.copy(
                title = normalizedTitle,
                fileName = nextFileName,
                sizeBytes = bytes.size,
                updatedAtEpochMs = now.coerceAtLeast(asset.updatedAtEpochMs),
                revision = nextRevision,
                contentSha256 = bytes.sha256(),
            ),
            previous = File(assetsDirectory, asset.fileName),
            target = File(assetsDirectory, nextFileName),
            temp = File(assetsDirectory, "$nextFileName.tmp"),
            bytes = bytes,
        )
    }

    private fun normalizedUpdateTitle(asset: ProjectLibraryAsset, title: String?): String? {
        if (title == null) return asset.title
        return title.trim()
            .takeIf { it.isNotEmpty() }
            ?.take(MAX_ASSET_TITLE_CHARS)
    }

    private fun ensureAssetsDirectory(): Boolean {
        if (assetsDirectory.exists()) return true
        return assetsDirectory.mkdirs()
    }

    private fun prepareRevisionTarget(target: File): Boolean {
        if (!target.exists()) return true
        // A process death after renaming new bytes but before committing index.json can
        // leave this next revision unreferenced. The current archive still points at the
        // previous file, so the orphan is safe to discard before retrying this revision.
        return target.delete()
    }

    private fun writeRevisionBytes(temp: File, target: File, bytes: ByteArray): Boolean = try {
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            false
        } else if (!syncDirectory(assetsDirectory)) {
            target.delete()
            syncDirectory(assetsDirectory)
            false
        } else {
            true
        }
    } catch (_: IOException) {
        temp.delete()
        target.delete()
        false
    }

    private fun syncDirectory(directory: File): Boolean = runCatching {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
        true
    }.getOrDefault(false)

    fun loadTextAsset(assetId: String, now: Long = System.currentTimeMillis()): LoadedProjectLibraryAsset? =
        synchronized(FILE_LOCK) { loadTextAssetUnlocked(assetId, now) }

    private fun loadTextAssetUnlocked(assetId: String, now: Long): LoadedProjectLibraryAsset? {
        val metadata = loadUnlocked(now).assets.firstOrNull { it.id == assetId } ?: return null
        val bytes = readVerifiedAssetBytes(metadata) ?: return null
        val text = runCatching {
            bytes.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull() ?: return null
        return LoadedProjectLibraryAsset(metadata, text)
    }

    fun deleteAsset(assetId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(FILE_LOCK) { deleteAssetUnlocked(assetId, now) }

    private fun deleteAssetUnlocked(assetId: String, now: Long): Boolean {
        val archive = loadUnlocked(now)
        val asset = archive.assets.firstOrNull { it.id == assetId } ?: return false
        val remaining = archive.assets.filterNot { it.id == assetId }
        if (!saveArchive(archive.copy(assets = remaining))) return false
        File(assetsDirectory, asset.fileName).delete()
        return true
    }

    private fun readVerifiedBytes(asset: ProjectLibraryAsset): ByteArray? {
        val file = File(assetsDirectory, asset.fileName)
        if (
            !file.isFile ||
            file.length() > MAX_ASSET_BYTES ||
            file.length() != asset.sizeBytes.toLong()
        ) {
            return null
        }
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun readVerifiedAssetBytes(asset: ProjectLibraryAsset): ByteArray? {
        val bytes = readVerifiedBytes(asset) ?: return null
        if (asset.contentSha256 != null && asset.contentSha256 != bytes.sha256()) return null
        return bytes
    }

    private fun recoverUnavailableAssetRevisions(
        archive: ProjectLibraryArchive,
    ): ProjectLibraryArchive {
        var changed = false
        val assets = archive.assets.map { asset ->
            if (readVerifiedAssetBytes(asset) != null) {
                asset
            } else {
                findRecoverableAssetRevision(asset)?.also { changed = true } ?: asset
            }
        }
        return if (changed) archive.copy(assets = assets) else archive
    }

    private fun findRecoverableAssetRevision(asset: ProjectLibraryAsset): ProjectLibraryAsset? {
        val extension = normalizeExtension(
            asset.fileName.substringAfterLast('.', missingDelimiterValue = ""),
        ) ?: return null
        val candidates = assetsDirectory.listFiles()
            ?.mapNotNull { file ->
                val revision = revisionForAssetFile(asset.id, extension, file.name)
                    ?: return@mapNotNull null
                if (revision >= asset.revision) null else revision to file
            }
            ?.sortedByDescending { it.first }
            .orEmpty()
        return candidates.firstNotNullOfOrNull { (revision, file) ->
            val bytes = readRecoverableBytes(file) ?: return@firstNotNullOfOrNull null
            asset.copy(
                fileName = file.name,
                sizeBytes = bytes.size,
                revision = revision,
                contentSha256 = bytes.sha256(),
            )
        }
    }

    private fun revisionForAssetFile(assetId: String, extension: String, fileName: String): Long? {
        if (fileName == "$assetId.$extension") return 1L
        val prefix = "$assetId.r"
        val suffix = ".$extension"
        if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) return null
        return fileName
            .removePrefix(prefix)
            .removeSuffix(suffix)
            .toLongOrNull()
            ?.takeIf { it >= 2L }
    }

    private fun readRecoverableBytes(file: File): ByteArray? {
        if (!file.isFile || file.length() > MAX_ASSET_BYTES) return null
        return runCatching {
            file.readBytes().also { bytes ->
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
        }.getOrNull()
    }

    private fun encodeText(text: String): ByteArray? =
        try {
            text.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }

    private fun normalizeExtension(extension: String): String? =
        extension.trim().removePrefix(".")
            .lowercase()
            .takeIf { it.matches(Regex("^[a-z0-9]{1,8}$")) }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
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
            // AtomicFile syncs the file itself; sync the containing directory too.
            // If this best-effort directory sync fails, the index may already be
            // committed, so do not report failure and delete the referenced asset.
            syncDirectory(root)
        } catch (error: IOException) {
            output?.let(indexFile::failWrite)
            throw error
        }
        true
    }.getOrDefault(false)

    companion object {
        private val FILE_LOCK = Any()

        // Keep the directory stable so schema upgrades do not strand existing local assets.
        const val DIRECTORY_NAME = "project-library-v1"
        const val MAX_ASSET_BYTES = 8 * 1024 * 1024
        private const val ASSETS_DIRECTORY_NAME = "assets"
        private const val INDEX_FILE_NAME = "index.json"
        private const val MAX_PROJECT_NAME_CHARS = 80
        private const val MAX_ASSET_TITLE_CHARS = 120
    }
}
