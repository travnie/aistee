package ais.tee.data.preferences

import android.util.AtomicFile
import ais.tee.data.model.ASYNC_PROVIDER_JOB_ARCHIVE_VERSION
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobArchive
import ais.tee.data.model.normalizedAsyncProviderJobs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class AsyncProviderJobStore(private val noBackupRoot: File) {
    private val atomicFile = AtomicFile(File(noBackupRoot, FILE_NAME))
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(): AsyncProviderJobArchive = synchronized(FILE_LOCK) {
        loadUnlocked()
    }

    fun upsert(job: AsyncProviderJob): AsyncProviderJob? = synchronized(FILE_LOCK) {
        val normalizedJob = AsyncProviderJobArchive(jobs = listOf(job))
            .normalizedAsyncProviderJobs()
            ?.jobs
            ?.singleOrNull()
            ?: return@synchronized null
        val archive = loadUnlocked()
        val jobs = archive.jobs.filterNot { it.id == normalizedJob.id } + normalizedJob
        if (saveUnlocked(archive.copy(jobs = jobs))) normalizedJob else null
    }

    fun update(
        jobId: String,
        transform: (AsyncProviderJob) -> AsyncProviderJob,
    ): AsyncProviderJob? = synchronized(FILE_LOCK) {
        val archive = loadUnlocked()
        val current = archive.jobs.firstOrNull { it.id == jobId } ?: return@synchronized null
        val normalized = AsyncProviderJobArchive(jobs = listOf(transform(current)))
            .normalizedAsyncProviderJobs()
            ?.jobs
            ?.singleOrNull()
            ?: return@synchronized null
        val updated = archive.copy(
            jobs = archive.jobs.map { if (it.id == jobId) normalized else it }
        )
        if (saveUnlocked(updated)) normalized else null
    }

    fun delete(jobId: String): Boolean = synchronized(FILE_LOCK) {
        val archive = loadUnlocked()
        if (archive.jobs.none { it.id == jobId }) return@synchronized false
        saveUnlocked(archive.copy(jobs = archive.jobs.filterNot { it.id == jobId }))
    }

    private fun loadUnlocked(): AsyncProviderJobArchive {
        val decoded = if (atomicFile.baseFile.isFile) {
            runCatching {
                json.decodeFromString<AsyncProviderJobArchive>(
                    atomicFile.readFully().decodeToString(throwOnInvalidSequence = true)
                )
            }.getOrNull()
        } else {
            null
        }
        return decoded
            ?.normalizedAsyncProviderJobs()
            ?: AsyncProviderJobArchive(version = ASYNC_PROVIDER_JOB_ARCHIVE_VERSION)
    }

    private fun saveUnlocked(archive: AsyncProviderJobArchive): Boolean = runCatching {
        atomicFile.baseFile.parentFile?.mkdirs()
        val normalized = archive.normalizedAsyncProviderJobs() ?: return@runCatching false
        val bytes = json.encodeToString(normalized).encodeToByteArray()
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

    companion object {
        private val FILE_LOCK = Any()
        const val FILE_NAME = "async-provider-jobs-v1.json"
    }
}
