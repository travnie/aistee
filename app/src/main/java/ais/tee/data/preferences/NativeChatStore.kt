package ais.tee.data.preferences

import android.util.AtomicFile
import ais.tee.data.model.NativeChatArchive
import ais.tee.data.model.NativeChatArchiveCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ais.tee.data.model.mergeNativeChatChanges
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
    scope: CoroutineScope,
    private val load: () -> NativeChatArchive? = { null },
) {
    private val archives = Channel<Unit>(Channel.CONFLATED)
    private val latestArchive = AtomicReference<NativeChatArchive?>(null)
    private val stateLock = Any()
    private val diskLock = Mutex()
    private var revision = 0L
    private val pending = mutableListOf<Pair<Long, CompletableDeferred<Boolean>>>()

    init {
        scope.launch {
            for (ignored in archives) {
                var retryDelayMs = RETRY_DELAY_MS
                while (true) {
                    val (archive, version) = synchronized(stateLock) {
                        latestArchive.get() to revision
                    }
                    if (archive == null) break
                    val saved = diskLock.withLock { save(archive) }
                    val isCurrent = synchronized(stateLock) {
                        pending.filter { it.first <= version }.forEach { it.second.complete(saved) }
                        pending.removeAll { it.first <= version }
                        revision == version
                    }
                    if (saved) {
                        if (isCurrent) {
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
                    if (!isCurrent) break
                    delay(retryDelayMs)
                    retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_DELAY_MS)
                }
            }
        }
    }

    // Apply only the caller's changes, so an older foreground snapshot cannot erase
    // turns inserted by a notification worker. Explicit deletions remain deletions.
    fun enqueue(archive: NativeChatArchive, base: NativeChatArchive? = null): NativeChatArchive =
        synchronized(stateLock) {
            val latest = latestArchive.get()
            val merged = if (base != null && latest != null) {
                mergeNativeChatChanges(base, archive, latest)
            } else archive
            latestArchive.set(merged)
            revision++
            archives.trySend(Unit)
            merged
        }

    suspend fun read(): NativeChatArchive? {
        latestArchive.get()?.let { return it }
        return diskLock.withLock {
            val loaded = load()
            synchronized(stateLock) {
                latestArchive.get() ?: loaded?.also(latestArchive::set)
            }
        }
    }

    // Every worker mutates the same in-memory archive and waits for the shared
    // disk writer. No second AtomicFile writer can interleave with foreground saves.
    suspend fun updateAndPersist(
        transform: (NativeChatArchive) -> NativeChatArchive?,
    ): NativeChatArchive? {
        read() ?: throw IOException("Native chat archive unavailable")
        val persisted = CompletableDeferred<Boolean>()
        val updated = synchronized(stateLock) {
            val current = requireNotNull(latestArchive.get())
            val next = transform(current) ?: return null
            latestArchive.set(next)
            revision++
            pending.add(revision to persisted)
            archives.trySend(Unit)
            next
        }
        if (!persisted.await()) throw IOException("Native chat archive write failed")
        return updated
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
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    load = store::load,
                ).also { instance = it }
            }

        fun currentArchive(): NativeChatArchive? = instance?.currentArchive()

        internal fun createForTest(
            save: (NativeChatArchive) -> Boolean,
            scope: CoroutineScope,
            afterSave: suspend (NativeChatArchive) -> Unit = {},
            load: () -> NativeChatArchive? = { null },
        ): NativeChatWriter = NativeChatWriter(save, afterSave, scope, load)

        internal fun replaceInstanceForTest(writer: NativeChatWriter?) {
            instance = writer
        }

        private const val RETRY_DELAY_MS = 50L
        private const val MAX_RETRY_DELAY_MS = 5_000L
    }
}
