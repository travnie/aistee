package ais.tee.data.skills

import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Hard cap on archive entries, so a crafted zip cannot exhaust memory with tiny files. */
internal const val MAX_SKILL_ARCHIVE_ENTRIES = 256

internal class SkillArchiveException(message: String) : IOException(message)

/** One file an active skill would serve, shown in the import preview. */
internal data class SkillScriptFile(val path: String, val size: Int, val sha256: String)

/**
 * A skill folder read from a zip: its `SKILL.md`, the files under `scripts/` and the paths that
 * were ignored because nothing uses them. Only `scripts/` can ever be served to a skill.
 */
internal class SkillArchive(
    val source: String,
    val scripts: Map<String, ByteArray>,
    val ignoredEntries: List<String>,
) {
    val scriptFiles: List<SkillScriptFile>
        get() = scripts.toSortedMap().map { (path, bytes) -> SkillScriptFile(path, bytes.size, sha256Hex(bytes)) }

    override fun toString(): String = "SkillArchive(scripts=${scripts.size}, ignored=${ignoredEntries.size})"
}

/**
 * Reads a skill zip without executing anything. Accepts `SKILL.md` at the root or inside exactly
 * one top-level folder; rejects traversal, absolute or backslash paths, case-insensitive
 * duplicates, more than [MAX_SKILL_ARCHIVE_ENTRIES] files and more than
 * [ACTIVE_SKILL_MAX_BUNDLE_BYTES] of decompressed data, counted as it is read.
 */
internal fun readSkillArchive(input: InputStream): SkillArchive {
    val entries = LinkedHashMap<String, ByteArray>()
    val seen = HashSet<String>()
    var totalBytes = 0L
    try {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (isMetadataEntry(name)) continue
                if (!isSafeArchivePath(name)) throw SkillArchiveException("The archive contains an unsafe path: ${name.take(80)}")
                if (!seen.add(name.lowercase(Locale.ROOT))) {
                    throw SkillArchiveException("The archive contains the same file twice: ${name.take(80)}")
                }
                if (entries.size >= MAX_SKILL_ARCHIVE_ENTRIES) {
                    throw SkillArchiveException("The archive has more than $MAX_SKILL_ARCHIVE_ENTRIES files.")
                }
                val bytes = readCapped(zip, ACTIVE_SKILL_MAX_BUNDLE_BYTES - totalBytes)
                totalBytes += bytes.size
                entries[name] = bytes
            }
        }
    } catch (error: ZipException) {
        throw SkillArchiveException("The file is not a valid zip archive.")
    }

    val prefix = archiveRoot(entries.keys)
    val skillFile = entries.keys.firstOrNull { it == "${prefix}SKILL.md" } ?: entries.keys.first { it == "${prefix}skill.md" }
    val source = try {
        entries.getValue(skillFile).decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw SkillArchiveException("SKILL.md is not valid UTF-8.")
    }
    val scripts = LinkedHashMap<String, ByteArray>()
    val ignored = ArrayList<String>()
    entries.forEach { (name, bytes) ->
        if (name == skillFile) return@forEach
        val relative = name.removePrefix(prefix)
        if (relative.startsWith("scripts/") && activeSkillBundlePath(relative.removePrefix("scripts/")) == relative) {
            scripts[relative] = bytes
        } else {
            ignored += relative
        }
    }
    return SkillArchive(source, scripts, ignored)
}

/** `""` for a root-level SKILL.md, `"folder/"` for one top-level folder holding it. */
private fun archiveRoot(names: Collection<String>): String {
    if (names.any { it == "SKILL.md" || it == "skill.md" }) return ""
    val roots = names.map { it.substringBefore('/', missingDelimiterValue = "") }.toSet()
    val root = roots.singleOrNull()?.takeIf(String::isNotEmpty)
        ?: throw SkillArchiveException("The archive must contain SKILL.md at its root or in one top-level folder.")
    if (names.none { it == "$root/SKILL.md" || it == "$root/skill.md" }) {
        throw SkillArchiveException("The archive must contain SKILL.md at its root or in one top-level folder.")
    }
    return "$root/"
}

private fun isMetadataEntry(name: String): Boolean =
    name.startsWith("__MACOSX/") || name == ".DS_Store" || name.endsWith("/.DS_Store")

internal fun isSafeArchivePath(name: String): Boolean {
    if (name.isEmpty() || name.startsWith('/') || '\\' in name || ':' in name) return false
    return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

private fun readCapped(input: InputStream, remaining: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > remaining) {
            throw SkillArchiveException("The archive is larger than ${ACTIVE_SKILL_MAX_BUNDLE_BYTES / (1024 * 1024)} MiB when unpacked.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
