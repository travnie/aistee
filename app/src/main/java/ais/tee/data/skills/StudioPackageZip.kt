package ais.tee.data.skills

import ais.tee.data.skills.StudioPackageFiles
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a user-reviewed Studio draft; never installs, executes or enables its contents. */
internal object StudioPackageZip {
    fun write(output: OutputStream, draft: StudioPackageFiles) {
        require(draft.files.size in 2..32) { "Invalid Studio package file count." }
        val paths = draft.files.keys
        require(paths.all { path ->
            path.isNotBlank() && path.length <= 240 && !path.startsWith('/') &&
                !path.contains('\\') && !path.contains(':') &&
                path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
        }) { "Invalid Studio package path." }
        require(paths.size == paths.map { it.lowercase() }.distinct().size) {
            "Duplicate Studio package paths."
        }
        require(draft.files.values.sumOf { it.encodeToByteArray().size.toLong() } <= 1_048_576L) {
            "Studio package is too large."
        }
        ZipOutputStream(output).use { zip ->
            draft.files.toSortedMap().forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
    }
}
