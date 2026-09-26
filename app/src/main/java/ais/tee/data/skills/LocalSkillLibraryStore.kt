package ais.tee.data.skills

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class LocalSkillSummary(
    val name: String,
    val description: String,
    val enabled: Boolean
)

internal data class LocalSkillDocument(
    val manifest: AgentSkillManifest,
    val source: String,
    val sourceDigest: String
)

internal data class LocalSkillReplacementResult(
    val skill: LocalSkillSummary,
    val sourceDigest: String
)

internal class LocalSkillAlreadyExistsException(
    val skillName: String
) : IOException("Local skill '$skillName' already exists.")

internal class LocalSkillNotFoundException(
    val skillName: String
) : IOException("Local skill '$skillName' is no longer available.")

internal class LocalSkillRenameRequiredException(
    val existingName: String,
    val newName: String
) : IOException("Rename '$existingName' to '$newName' before saving this source.")

internal class LocalSkillSourceConflictException(
    val skillName: String
) : IOException("Local skill '$skillName' changed since this editor was opened. Reload it before saving.")

internal class LocalSkillActivationException(message: String) : IOException(message)

private data class ParsedLocalSkillSource(
    val manifest: AgentSkillManifest,
    val bytes: ByteArray,
    val sourceDigest: String
)

private data class RenameMarker(
    val sourceDirectoryNames: List<String>,
    val committed: Boolean
)

internal class LocalSkillLibraryStore(
    private val rootDirectory: File,
    private val deleteFile: (File) -> Boolean = { file -> file.delete() },
    private val deleteDirectory: (File) -> Boolean = { directory -> directory.deleteRecursively() }
) {
    private val mutex = PROCESS_MUTEX

    suspend fun load(): List<LocalSkillSummary> = mutex.withLock {
        recoverPendingRenames()
        inspectStoredSkills(pruneInvalid = true).sortedBy(LocalSkillSummary::name)
    }

    suspend fun loadEnabledManifests(): List<AgentSkillManifest> = mutex.withLock {
        recoverPendingRenames()
        val candidates = readEnabledManifests()
        val accepted = mutableListOf<AgentSkillManifest>()
        candidates.sortedBy(AgentSkillManifest::name).forEach { manifest ->
            if (localSkillRuntimeBudgetError(accepted + manifest) == null) {
                accepted += manifest
            } else {
                removeEnabledMarker(storageDirectory(manifest.name))
            }
        }
        accepted
    }

    suspend fun read(name: String): LocalSkillDocument? = mutex.withLock {
        recoverPendingRenames()
        val directory = storageDirectory(name)
        readStoredDocument(directory)
            ?.takeIf { it.manifest.name == name }
    }

    /**
     * Saves [source]. [scripts] (paths under `scripts/`) replaces the skill's stored script files
     * when given, as from an archive import; null keeps whatever is stored.
     */
    suspend fun add(
        source: String,
        replaceExisting: Boolean = false,
        scripts: Map<String, ByteArray>? = null
    ): LocalSkillSummary {
        val parsed = parseLocalSkillSource(source)

        return mutex.withLock {
            recoverPendingRenames()
            val skillDirectory = storageDirectory(parsed.manifest.name)
            retireTombstoneReservationForReuse(skillDirectory.name)
            val existing = if (skillDirectory.isDirectory) readStoredDocument(skillDirectory) else null
            if (existing != null && !replaceExisting) {
                throw LocalSkillAlreadyExistsException(parsed.manifest.name)
            }
            if (skillDirectory.exists() && existing == null) {
                reclaimUnavailableStorage(skillDirectory, parsed.manifest.name)
            }
            ensureCapacityFor(parsed.manifest.name)
            val enabled = existing != null && isEnabled(skillDirectory)
            if (enabled) {
                validateRuntimeBudgetFor(parsed.manifest)
            }
            withContext(Dispatchers.IO) {
                skillDirectory.mkdirs()
                scripts?.let { replaceScripts(skillDirectory, it) }
                writeAtomically(File(skillDirectory, SKILL_FILE_NAME), parsed.bytes)
            }
            parsed.manifest.toSummary(enabled)
        }
    }

    suspend fun replace(
        name: String,
        expectedSourceDigest: String,
        source: String
    ): LocalSkillReplacementResult {
        val parsed = parseLocalSkillSource(source)
        if (parsed.manifest.name != name) {
            throw LocalSkillRenameRequiredException(name, parsed.manifest.name)
        }

        return mutex.withLock {
            recoverPendingRenames()
            val skillDirectory = storageDirectory(name)
            val existing = readStoredDocument(skillDirectory)
                ?.takeIf { it.manifest.name == name }
                ?: throw LocalSkillNotFoundException(name)
            if (existing.sourceDigest != expectedSourceDigest) {
                throw LocalSkillSourceConflictException(name)
            }
            val enabled = isEnabled(skillDirectory)
            if (enabled) {
                validateRuntimeBudgetFor(parsed.manifest)
            }
            withContext(Dispatchers.IO) {
                writeAtomically(File(skillDirectory, SKILL_FILE_NAME), parsed.bytes)
            }
            LocalSkillReplacementResult(
                skill = parsed.manifest.toSummary(enabled),
                sourceDigest = parsed.sourceDigest
            )
        }
    }

    suspend fun rename(
        existingName: String,
        expectedSourceDigest: String,
        source: String
    ): LocalSkillReplacementResult {
        val parsed = parseLocalSkillSource(source)
        val newName = parsed.manifest.name
        if (newName == existingName) {
            return replace(existingName, expectedSourceDigest, source)
        }

        return mutex.withLock {
            recoverPendingRenames()
            val existingDirectory = storageDirectory(existingName)
            val existing = readStoredDocument(existingDirectory)
                ?.takeIf { it.manifest.name == existingName }
                ?: throw LocalSkillNotFoundException(existingName)
            if (existing.sourceDigest != expectedSourceDigest) {
                throw LocalSkillSourceConflictException(existingName)
            }

            // Capture ancestry before retiring the destination key from older tombstones.
            // This keeps rollback valid before the new commit point without allowing the old
            // target to tombstone a newly committed rename-back destination.
            val inheritedSources = validCommittedRenameMarker(existingDirectory)
                ?.sourceDirectoryNames
                .orEmpty()
            val targetDirectory = storageDirectory(newName)
            retireTombstoneReservationForReuse(targetDirectory.name)
            if (targetDirectory.exists()) {
                val target = readStoredDocument(targetDirectory)
                if (target != null) throw LocalSkillAlreadyExistsException(newName)
                reclaimUnavailableStorage(targetDirectory, newName)
            }

            val enabled = isEnabled(existingDirectory)
            if (enabled) {
                validateRuntimeBudgetFor(parsed.manifest, excludeName = existingName)
            }

            val renamedSources = (inheritedSources + existingDirectory.name)
                .distinct()
                .filterNot { it == targetDirectory.name }

            withContext(Dispatchers.IO) {
                targetDirectory.mkdirs()
                val renameMarker = File(targetDirectory, RENAME_FROM_FILE_NAME)
                writeAtomically(
                    renameMarker,
                    renameMarkerValue(renamedSources, committed = false).encodeToByteArray()
                )
                try {
                    copyScripts(existingDirectory, targetDirectory)
                    writeAtomically(File(targetDirectory, SKILL_FILE_NAME), parsed.bytes)
                    if (enabled) {
                        writeAtomically(File(targetDirectory, ENABLED_FILE_NAME), ByteArray(0))
                    }
                    // This atomic marker rewrite is the commit point. Pending targets are hidden;
                    // committed targets are canonical and tombstone every listed source identity.
                    writeAtomically(
                        renameMarker,
                        renameMarkerValue(renamedSources, committed = true).encodeToByteArray()
                    )
                } catch (error: IOException) {
                    deleteDirectory(targetDirectory)
                    throw error
                }
            }

            cleanupCommittedRenameSources(targetDirectory)
            LocalSkillReplacementResult(
                skill = parsed.manifest.toSummary(enabled),
                sourceDigest = parsed.sourceDigest
            )
        }
    }

    /**
     * Every file of the stored skill for the active runtime: `SKILL.md` plus `scripts/…`. The
     * bundle digest covers both, so editing instructions or declared tools also clears trust.
     */
    suspend fun readBundleFiles(name: String): Map<String, ByteArray>? = mutex.withLock {
        recoverPendingRenames()
        val directory = storageDirectory(name)
        readStoredDocument(directory)?.takeIf { it.manifest.name == name } ?: return@withLock null
        withContext(Dispatchers.IO) {
            val files = linkedMapOf(SKILL_FILE_NAME to File(directory, SKILL_FILE_NAME).readBytes())
            val scriptsRoot = File(directory, SCRIPTS_DIRECTORY_NAME)
            var total = files.values.sumOf { it.size.toLong() }
            scriptsRoot.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                val relative = SCRIPTS_DIRECTORY_NAME + "/" + file.relativeTo(scriptsRoot).invariantSeparatorsPath
                if (activeSkillBundlePath(relative.removePrefix("$SCRIPTS_DIRECTORY_NAME/")) != relative) return@forEach
                total += file.length()
                if (total > ACTIVE_SKILL_MAX_BUNDLE_BYTES) throw IOException("Skill files exceed the size limit.")
                files[relative] = file.readBytes()
            }
            files
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean): LocalSkillSummary? = mutex.withLock {
        recoverPendingRenames()
        val directory = storageDirectory(name)
        val document = readStoredDocument(directory)
            ?.takeIf { it.manifest.name == name }
            ?: return@withLock null
        if (enabled) {
            validateRuntimeBudgetFor(document.manifest)
        }
        withContext(Dispatchers.IO) {
            val marker = File(directory, ENABLED_FILE_NAME)
            if (enabled) {
                writeAtomically(marker, ByteArray(0))
            } else if (marker.exists() && !marker.delete()) {
                throw IOException("Could not disable local skill '$name'.")
            }
        }
        document.manifest.toSummary(enabled)
    }

    suspend fun remove(name: String) = mutex.withLock {
        recoverPendingRenames()
        val directory = storageDirectory(name)
        cleanupCommittedRenameSources(directory)
        val marker = validCommittedRenameMarker(directory)
        val hasRemainingSources = withContext(Dispatchers.IO) {
            marker?.sourceDirectoryNames?.any { sourceName ->
                File(rootDirectory, sourceName).exists()
            } == true
        }
        if (hasRemainingSources) {
            throw IOException("Could not remove local skill '$name' while rename cleanup is pending.")
        }
        withContext(Dispatchers.IO) {
            if (directory.exists() && !deleteDirectory(directory)) {
                throw IOException("Could not remove local skill '$name'.")
            }
        }
    }

    private suspend fun parseLocalSkillSource(source: String): ParsedLocalSkillSource {
        val parsed = withContext(Dispatchers.Default) {
            AgentSkillManifestParser.parse(source)
        }
        val manifest = parsed.manifest?.takeIf { parsed.issues.isEmpty() }
            ?: throw IllegalArgumentException("Only valid portable SKILL.md content can be saved.")
        val bytes = source.encodeToByteArray()
        if (bytes.size > MAX_SKILL_BYTES) throw IOException("Skill source exceeds the library size limit.")
        return ParsedLocalSkillSource(
            manifest = manifest,
            bytes = bytes,
            sourceDigest = localSkillSourceDigest(source)
        )
    }

    private suspend fun validateRuntimeBudgetFor(
        candidate: AgentSkillManifest,
        excludeName: String = candidate.name
    ) {
        val enabledOthers = readEnabledManifests(excludeName = excludeName)
        localSkillRuntimeBudgetError(enabledOthers + candidate)?.let { message ->
            throw LocalSkillActivationException(message)
        }
    }

    private suspend fun readEnabledManifests(excludeName: String? = null): List<AgentSkillManifest> {
        val directories = withContext(Dispatchers.IO) { storageDirectories() }
        val enabled = ArrayList<AgentSkillManifest>(directories.size)
        directories.forEach { directory ->
            val document = readStoredDocument(directory)
            if (document == null) {
                pruneUnreadableDirectory(directory)
            } else if (document.manifest.name != excludeName && isEnabled(directory)) {
                enabled += document.manifest
            }
        }
        return enabled
    }

    private suspend fun removeEnabledMarker(directory: File) = withContext(Dispatchers.IO) {
        val marker = File(directory, ENABLED_FILE_NAME)
        if (marker.exists() && !marker.delete()) {
            throw IOException("Could not disable an over-budget local skill.")
        }
    }

    private suspend fun ensureCapacityFor(name: String) {
        val targetDirectory = storageDirectory(name)
        val directories = withContext(Dispatchers.IO) {
            rootDirectory.mkdirs()
            storageDirectories()
        }
        if (targetDirectory.isDirectory || directories.size < MAX_LOCAL_SKILLS) return

        val valid = inspectStoredSkills(pruneInvalid = true)
        if (valid.size >= MAX_LOCAL_SKILLS) throw IOException("Local skill library is full.")
    }

    private suspend fun inspectStoredSkills(pruneInvalid: Boolean): List<LocalSkillSummary> {
        val directories = withContext(Dispatchers.IO) { storageDirectories() }
        val valid = ArrayList<LocalSkillSummary>(directories.size)
        directories.forEach { directory ->
            val document = readStoredDocument(directory)
            if (document == null) {
                if (pruneInvalid) pruneUnreadableDirectory(directory)
            } else {
                valid += document.manifest.toSummary(isEnabled(directory))
            }
        }
        return valid
    }

    private suspend fun recoverPendingRenames() {
        val directories = withContext(Dispatchers.IO) { storageDirectories() }
        directories.forEach { targetDirectory ->
            val markerFile = File(targetDirectory, RENAME_FROM_FILE_NAME)
            val markerPresent = withContext(Dispatchers.IO) { markerFile.isFile }
            if (!markerPresent) return@forEach
            val marker = readRenameMarker(markerFile)
            when {
                marker == null -> withContext(Dispatchers.IO) { deleteDirectory(targetDirectory) }
                !marker.committed -> withContext(Dispatchers.IO) { deleteDirectory(targetDirectory) }
                readStoredDocument(
                    targetDirectory,
                    includeRenameTargets = true,
                    includeRenamedSources = true
                ) == null -> withContext(Dispatchers.IO) { deleteDirectory(targetDirectory) }
                else -> cleanupCommittedRenameSources(targetDirectory)
            }
        }
    }

    private suspend fun cleanupCommittedRenameSources(targetDirectory: File) {
        val markerFile = File(targetDirectory, RENAME_FROM_FILE_NAME)
        val marker = validCommittedRenameMarker(targetDirectory) ?: return
        val remaining = withContext(Dispatchers.IO) {
            marker.sourceDirectoryNames.filter { sourceName ->
                val sourceDirectory = File(rootDirectory, sourceName)
                sourceDirectory.exists() && !deleteDirectory(sourceDirectory)
            }
        }
        withContext(Dispatchers.IO) {
            when {
                remaining.isEmpty() -> deleteFile(markerFile)
                remaining != marker.sourceDirectoryNames -> runCatching {
                    writeAtomically(
                        markerFile,
                        renameMarkerValue(remaining, committed = true).encodeToByteArray()
                    )
                }
            }
        }
    }

    private suspend fun retireTombstoneReservationForReuse(storageDirectoryName: String) {
        val directories = withContext(Dispatchers.IO) { storageDirectories() }
        directories.forEach { candidate ->
            val marker = validCommittedRenameMarker(candidate) ?: return@forEach
            if (storageDirectoryName !in marker.sourceDirectoryNames) return@forEach
            if (candidate.name == storageDirectoryName) {
                throw IOException("Local skill storage is awaiting rename cleanup.")
            }

            val sourceDirectory = File(rootDirectory, storageDirectoryName)
            val sourceRemoved = withContext(Dispatchers.IO) {
                !sourceDirectory.exists() || deleteDirectory(sourceDirectory)
            }
            if (!sourceRemoved) {
                throw IOException("Local skill storage is awaiting rename cleanup.")
            }

            val remaining = marker.sourceDirectoryNames.filterNot { it == storageDirectoryName }
            val markerFile = File(candidate, RENAME_FROM_FILE_NAME)
            withContext(Dispatchers.IO) {
                if (remaining.isEmpty()) {
                    if (markerFile.exists() && !deleteFile(markerFile)) {
                        throw IOException("Could not retire local skill rename history.")
                    }
                } else {
                    writeAtomically(
                        markerFile,
                        renameMarkerValue(remaining, committed = true).encodeToByteArray()
                    )
                }
            }
        }
    }

    private suspend fun reclaimUnavailableStorage(directory: File, skillName: String) {
        val committedTarget = validCommittedRenameMarker(directory) != null
        val hiddenSource = isCommittedRenameSourceDirectory(directory)
        if (committedTarget && !hiddenSource) {
            throw IOException("Local skill storage for '$skillName' is awaiting rename cleanup.")
        }
        withContext(Dispatchers.IO) {
            if (!deleteDirectory(directory)) {
                throw IOException("Could not reclaim invalid local skill storage for '$skillName'.")
            }
        }
    }

    private suspend fun pruneUnreadableDirectory(directory: File) {
        val committedTarget = validCommittedRenameMarker(directory) != null
        if (committedTarget && !isCommittedRenameSourceDirectory(directory)) return
        withContext(Dispatchers.IO) { deleteDirectory(directory) }
    }

    private suspend fun isEnabled(directory: File): Boolean = withContext(Dispatchers.IO) {
        File(directory, ENABLED_FILE_NAME).isFile
    }

    private suspend fun readStoredDocument(
        directory: File,
        includeRenameTargets: Boolean = false,
        includeRenamedSources: Boolean = false
    ): LocalSkillDocument? {
        if (!includeRenameTargets) {
            val markerFile = File(directory, RENAME_FROM_FILE_NAME)
            val markerPresent = withContext(Dispatchers.IO) { markerFile.isFile }
            if (markerPresent) {
                val marker = readRenameMarker(markerFile)
                if (marker == null || !marker.committed) return null
            }
        }
        if (!includeRenamedSources && isCommittedRenameSourceDirectory(directory)) return null
        val source = withContext(Dispatchers.IO) {
            readStoredSource(directory)
        } ?: return null
        val parsed = withContext(Dispatchers.Default) {
            AgentSkillManifestParser.parse(source)
        }
        val manifest = parsed.manifest?.takeIf { parsed.issues.isEmpty() } ?: return null
        if (storageKey(manifest.name) != directory.name) return null
        return LocalSkillDocument(
            manifest = manifest,
            source = source,
            sourceDigest = localSkillSourceDigest(source)
        )
    }

    private suspend fun validCommittedRenameMarker(directory: File): RenameMarker? {
        val marker = committedRenameMarker(directory) ?: return null
        val targetDocument = readStoredDocument(
            directory = directory,
            includeRenameTargets = true,
            includeRenamedSources = true
        ) ?: return null
        return marker.takeIf { targetDocument.manifest.name.isNotEmpty() }
    }

    private suspend fun committedRenameMarker(directory: File): RenameMarker? = withContext(Dispatchers.IO) {
        val markerFile = File(directory, RENAME_FROM_FILE_NAME)
        if (!markerFile.isFile) return@withContext null
        readRenameMarkerSync(markerFile)?.takeIf(RenameMarker::committed)
    }

    private suspend fun isCommittedRenameSourceDirectory(directory: File): Boolean {
        val directories = withContext(Dispatchers.IO) { storageDirectories() }
        for (candidate in directories) {
            val marker = validCommittedRenameMarker(candidate) ?: continue
            if (directory.name in marker.sourceDirectoryNames) return true
        }
        return false
    }

    private suspend fun readRenameMarker(markerFile: File): RenameMarker? = withContext(Dispatchers.IO) {
        readRenameMarkerSync(markerFile)
    }

    private fun readRenameMarkerSync(markerFile: File): RenameMarker? =
        runCatching { parseRenameMarker(markerFile.readText()) }.getOrNull()

    private fun renameMarkerValue(sourceDirectoryNames: List<String>, committed: Boolean): String =
        buildString {
            append(if (committed) RENAME_COMMITTED_HEADER else RENAME_PENDING_HEADER)
            sourceDirectoryNames.distinct().forEach { sourceName ->
                append('\n')
                append(sourceName)
            }
        }

    private fun parseRenameMarker(value: String): RenameMarker? {
        val lines = value.lineSequence().filter(String::isNotBlank).toList()
        if (lines.size < 2) return null
        val committed = when (lines.first()) {
            RENAME_COMMITTED_HEADER -> true
            RENAME_PENDING_HEADER -> false
            else -> return null
        }
        val sourceDirectoryNames = lines.drop(1).distinct()
        if (sourceDirectoryNames.isEmpty() || sourceDirectoryNames.any { !isStorageKey(it) }) return null
        return RenameMarker(sourceDirectoryNames = sourceDirectoryNames, committed = committed)
    }

    private fun readStoredSource(directory: File): String? {
        val sourceFile = File(directory, SKILL_FILE_NAME)
        if (!sourceFile.isFile || sourceFile.length() !in 0..MAX_SKILL_BYTES.toLong()) return null
        return try {
            sourceFile.readBytes().decodeToString(throwOnInvalidSequence = true)
        } catch (_: IOException) {
            null
        }
    }

    /** Writes the new script set beside the old one, then swaps it in. */
    private fun replaceScripts(skillDirectory: File, scripts: Map<String, ByteArray>) {
        val incoming = File(skillDirectory, "$SCRIPTS_DIRECTORY_NAME.incoming")
        incoming.deleteRecursively()
        scripts.forEach { (path, bytes) ->
            val relative = path.removePrefix("$SCRIPTS_DIRECTORY_NAME/")
            require(activeSkillBundlePath(relative) == path) { "Invalid script path" }
            writeAtomically(File(incoming, relative), bytes)
        }
        val current = File(skillDirectory, SCRIPTS_DIRECTORY_NAME)
        if (current.exists() && !deleteDirectory(current)) throw IOException("Could not replace skill scripts.")
        if (scripts.isNotEmpty() && !incoming.renameTo(current)) throw IOException("Could not store skill scripts.")
        incoming.deleteRecursively()
    }

    private fun copyScripts(from: File, to: File) {
        val source = File(from, SCRIPTS_DIRECTORY_NAME)
        if (source.isDirectory) source.copyRecursively(File(to, SCRIPTS_DIRECTORY_NAME), overwrite = true)
    }

    private fun storageDirectories(): List<File> =
        rootDirectory.listFiles()
            ?.filter { it.isDirectory && isStorageKey(it.name) }
            .orEmpty()

    private fun storageDirectory(name: String): File =
        File(rootDirectory, storageKey(name))

    private fun storageKey(name: String): String =
        STORAGE_PREFIX + sha256Hex(name.encodeToByteArray())

    private fun isStorageKey(value: String): Boolean =
        value.startsWith(STORAGE_PREFIX) && isSha256Hex(value.drop(STORAGE_PREFIX.length))

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temporary.delete()
        }
    }

    private fun AgentSkillManifest.toSummary(enabled: Boolean): LocalSkillSummary =
        LocalSkillSummary(name = name, description = description, enabled = enabled)

    companion object {
        private val PROCESS_MUTEX = Mutex()
        const val MAX_LOCAL_SKILLS = 32
        const val LIBRARY_DIRECTORY_NAME = "local-skills-v1"
        private const val SKILL_FILE_NAME = "SKILL.md"
        private const val SCRIPTS_DIRECTORY_NAME = "scripts"
        private const val ENABLED_FILE_NAME = ".enabled"
        private const val RENAME_FROM_FILE_NAME = ".rename-from"
        private const val RENAME_PENDING_HEADER = "pending"
        private const val RENAME_COMMITTED_HEADER = "committed"
        private const val MAX_SKILL_BYTES = 8 * 1024 * 1024
        private const val STORAGE_PREFIX = "skill-"
    }
}
