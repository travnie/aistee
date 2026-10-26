package ais.tee.data.skills

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Activity-result-safe temporary ZIP handoff.
 *
 * Only the opaque filename is saved in Android's tiny SavedState Bundle. The ZIP bytes stay
 * in the app-private, backup-excluded cache while the system document picker is open.
 * The cache is not a package library or an installation/trust source.
 */
internal object StudioPendingPluginExport {
    private const val FOLDER_NAME = "studio-plugin-exports"
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    private val fileName = Regex("^studio-[0-9a-f]{32}[.]zip$")

    fun prepare(cacheRoot: File, draft: StudioPackageFiles): String {
        val folder = folder(cacheRoot)
        require(folder.isDirectory || folder.mkdirs()) { "Could not prepare Studio export cache." }
        prune(folder)
        val id = "studio-" + UUID.randomUUID().toString().replace("-", "") + ".zip"
        val output = File(folder, id)
        try {
            output.outputStream().use { StudioPackageZip.write(it, draft) }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
        return id
    }

    /** Fails closed if Android removed the cache entry or the saved ID is invalid. */
    fun copyTo(cacheRoot: File, id: String, output: OutputStream) {
        val file = validatedFile(cacheRoot, id)
        if (!file.isFile) throw IOException("Studio draft expired; preview and export again.")
        file.inputStream().use { it.copyTo(output) }
    }

    fun discard(cacheRoot: File, id: String?) {
        if (id != null && fileName.matches(id)) File(folder(cacheRoot), id).delete()
    }

    private fun validatedFile(cacheRoot: File, id: String): File {
        if (!fileName.matches(id)) throw IOException("Invalid Studio export reference.")
        return File(folder(cacheRoot), id)
    }

    private fun folder(root: File): File = File(root, FOLDER_NAME)

    private fun prune(folder: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        folder.listFiles()?.forEach { file ->
            if (file.isFile && fileName.matches(file.name) && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
