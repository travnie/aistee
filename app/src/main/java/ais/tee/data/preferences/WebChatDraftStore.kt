package ais.tee.data.preferences

import ais.tee.data.model.WebAiService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

internal data class StagedWebChatDraft(
    val id: String,
    val service: WebAiService,
    val text: String,
) {
    override fun toString(): String =
        "StagedWebChatDraft(id=$id, service=${service.id}, text=<redacted>)"
}

internal class WebChatDraftStore(private val noBackupRoot: File) {
    fun stage(service: WebAiService, text: String): StagedWebChatDraft? {
        if (text.isBlank() || text.length > MAX_DRAFT_CHARS) return null
        val draft = StagedWebChatDraft(
            id = UUID.randomUUID().toString(),
            service = service,
            text = text,
        )
        val directory = draftDirectory()
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = draftFile(service)
        val temp = File(directory, target.name + ".tmp")
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_DRAFT_UTF8_BYTES) return null
            DataOutputStream(FileOutputStream(temp)).use { output ->
                output.writeUTF(draft.id)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            draft
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }

    fun peek(service: WebAiService): StagedWebChatDraft? {
        val file = draftFile(service)
        if (!file.isFile) return null
        return try {
            DataInputStream(FileInputStream(file)).use { input ->
                val id = input.readUTF().takeIf(String::isNotBlank) ?: return null
                val size = input.readInt()
                if (size !in 1..MAX_DRAFT_UTF8_BYTES) return null
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                if (text.isBlank() || text.length > MAX_DRAFT_CHARS) return null
                StagedWebChatDraft(id = id, service = service, text = text)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun consume(service: WebAiService, draftId: String): Boolean {
        val current = peek(service) ?: return false
        if (current.id != draftId) return false
        return !draftFile(service).exists() || draftFile(service).delete()
    }

    fun clear(service: WebAiService): Boolean {
        val file = draftFile(service)
        return !file.exists() || file.delete()
    }

    private fun draftDirectory(): File = File(noBackupRoot, DIRECTORY_NAME)

    private fun draftFile(service: WebAiService): File =
        File(draftDirectory(), "${service.id}.draft")

    internal companion object {
        const val DIRECTORY_NAME = "web-chat-drafts"
        const val MAX_DRAFT_CHARS = 4_000
        const val MAX_DRAFT_UTF8_BYTES = 16_000
    }
}
