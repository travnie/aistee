package ais.tee.data.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSkillArchiveTest {
    private val skill = "---\nname: unit-convert\ndescription: Converts units.\n---\nUse it."

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun readsRootAndSingleFolderLayouts() {
        val root = readSkillArchive(
            zip(
                "SKILL.md" to skill.toByteArray(),
                "scripts/index.html" to "<p>".toByteArray(),
                "references/notes.md" to "x".toByteArray(),
                "__MACOSX/._SKILL.md" to ByteArray(3),
            )
        )
        assertEquals(skill, root.source)
        assertEquals(setOf("scripts/index.html"), root.scripts.keys)
        assertEquals(listOf("references/notes.md"), root.ignoredEntries)
        assertEquals(64, root.scriptFiles.single().sha256.length)

        val folder = readSkillArchive(
            zip("unit-convert/SKILL.md" to skill.toByteArray(), "unit-convert/scripts/a.js" to "1".toByteArray())
        )
        assertEquals(setOf("scripts/a.js"), folder.scripts.keys)
    }

    @Test
    fun rejectsUnsafeOrAmbiguousArchives() {
        listOf(
            zip("../SKILL.md" to skill.toByteArray()),
            zip("/SKILL.md" to skill.toByteArray()),
            zip("SKILL.md" to skill.toByteArray(), "scripts\\evil.js" to ByteArray(1)),
            zip("SKILL.md" to skill.toByteArray(), "scripts/a.js" to ByteArray(1), "scripts/A.js" to ByteArray(1)),
            zip("a/SKILL.md" to skill.toByteArray(), "b/SKILL.md" to skill.toByteArray()),
            zip("scripts/index.html" to ByteArray(1)),
            zip("SKILL.md" to byteArrayOf(0xC3.toByte(), 0x28)),
        ).forEach { archive -> assertThrows(SkillArchiveException::class.java) { readSkillArchive(archive) } }
        assertThrows(SkillArchiveException::class.java) {
            readSkillArchive(ByteArrayInputStream("not a zip".toByteArray()))
        }
    }

    @Test
    fun capsEntriesAndUnpackedSize() {
        val many = (0..MAX_SKILL_ARCHIVE_ENTRIES).map { "scripts/f$it.js" to ByteArray(1) }
        assertThrows(SkillArchiveException::class.java) {
            readSkillArchive(zip("SKILL.md" to skill.toByteArray(), *many.toTypedArray()))
        }
        // Highly compressible, so the zip is small but the unpacked size is over the cap.
        val big = ByteArray(ACTIVE_SKILL_MAX_BUNDLE_BYTES + 1)
        val error = assertThrows(SkillArchiveException::class.java) {
            readSkillArchive(zip("SKILL.md" to skill.toByteArray(), "scripts/big.bin" to big))
        }
        assertTrue(error.message!!.contains("MiB"))
    }
}
