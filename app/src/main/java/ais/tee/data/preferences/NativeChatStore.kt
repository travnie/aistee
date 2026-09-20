package ais.tee.data.preferences

import android.util.AtomicFile
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatArchiveCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

internal class NativeChatStore(directory: File) {
    private val atomicFile = AtomicFile(File(directory, FILE_NAME))

    fun load(): NativeChatArchive? {
        if (!atomicFile.baseFile.isFile) return null
        val bytes = try {
            atomicFile.readFully()
        } catch (_: IOException) {
            return null
        }
        return NativeChatArchiveCodec.decode(bytes)
    }

    fun save(archive: NativeChatArchive): Boolean = runCatching {
        atomicFile.baseFile.parentFile?.mkdirs()
        val bytes = NativeChatArchiveCodec.encode(archive)
        var output: FileOutputStream? = atomicFile.startWrite()
        try {
            val stream = requireNotNull(output)
            stream.write(bytes)
            stream.flush()
            atomicFile.finishWrite(stream)
            output = null
        } catch (error: IOException) {
            output?.let(atomicFile::failWrite)
            throw error
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val FILE_NAME = "native-chat-archive-v1.json"
    }
}

internal class NativeChatWriter private constructor(
    private val save: (NativeChatArchive) -> Boolean,
    private val afterSave: suspend (NativeChatArchive) -> Unit,
    scope: CoroutineScope
) {
    private val archives = Channel<NativeChatArchive>(Channel.CONFLATED)
    private val latestArchive = AtomicReference<NativeChatArchive?>(null)

    init {
        scope.launch {
            for (archive in archives) {
                var retryDelayMs = RETRY_DELAY_MS
                while (latestArchive.get() == archive) {
                    if (save(archive)) {
                        if (latestArchive.get() == archive) {
                            try {
                                afterSave(archive)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Widget or other post-save work must not stop chat persistence.
                            }
                        }
                        break
                    }
                    delay(retryDelayMs)
                    retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    fun enqueue(archive: NativeChatArchive) {
        latestArchive.set(archive)
        archives.trySend(archive)
    }

    fun currentArchive(): NativeChatArchive? = latestArchive.get()

    companion object {
        @Volatile
        private var instance: NativeChatWriter? = null

        fun getInstance(
            store: NativeChatStore,
            afterSave: suspend (NativeChatArchive) -> Unit = {},
        ): NativeChatWriter =
            instance ?: synchronized(this) {
                instance ?: NativeChatWriter(
                    save = store::save,
                    afterSave = afterSave,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                ).also { instance = it }
            }

        fun currentArchive(): NativeChatArchive? = instance?.currentArchive()

        internal fun createForTest(
            save: (NativeChatArchive) -> Boolean,
            scope: CoroutineScope,
            afterSave: suspend (NativeChatArchive) -> Unit = {},
        ): NativeChatWriter = NativeChatWriter(save, afterSave, scope)

        internal fun replaceInstanceForTest(writer: NativeChatWriter?) {
            instance = writer
        }

        private const val RETRY_DELAY_MS = 50L
        private const val MAX_RETRY_DELAY_MS = 5_000L
    }
}
