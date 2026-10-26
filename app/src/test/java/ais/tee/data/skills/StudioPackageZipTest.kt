package ais.tee.data.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StudioPackageZipTest {
    @Test
    fun createsZipWithPortablePackageAndOriginalSkill() {
        val source = ais.tee.data.skills.AgentSkillDraftFactory.create(
            "helper", "When asked", "# Instructions\nExplain.")
        val draft = StudioPackageBuilder.createPlugin("sample-plugin", "Description", listOf(source))
        val output = ByteArrayOutputStream()
        StudioPackageZip.write(output, draft)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals(setOf("plugin.json", "skills/helper/SKILL.md"), entries.keys)
        assertEquals(source, entries.getValue("skills/helper/SKILL.md"))
    }

    @Test
    fun refusesTraversalInExportSource() {
        assertThrows(IllegalArgumentException::class.java) {
            StudioPackageZip.write(
                ByteArrayOutputStream(),
                StudioPackageFiles("bad", mapOf("plugin.json" to "{}", "../outside" to "bad"))
            )
        }
    }
}
