package ais.tee.data.preferences

import androidx.test.core.app.ApplicationProvider
import ais.tee.data.model.AiProvider
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobKind
import ais.tee.data.model.AsyncProviderJobState
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncProviderJobStoreTest {
    @Test
    fun jobRoundTripsUpdatesAndDeletesWithoutLeakingRemoteIdInToString() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "async-job-test-${UUID.randomUUID()}")
        try {
            val store = AsyncProviderJobStore(root)
            val job = AsyncProviderJob(
                id = "local-1",
                provider = AiProvider.CHATGPT,
                kind = AsyncProviderJobKind.BACKGROUND_RESPONSE,
                remoteId = "resp_secret",
                model = "gpt-5.6",
                state = AsyncProviderJobState.RUNNING,
                createdAtEpochMs = 1,
            )

            assertEquals(job, store.upsert(job))
            assertEquals(job, store.load().jobs.single())
            assertFalse(store.load().jobs.single().toString().contains("resp_secret"))

            val updated = requireNotNull(
                store.update(job.id) {
                    it.copy(
                        state = AsyncProviderJobState.SUCCEEDED,
                        updatedAtEpochMs = 2,
                        resultAssetId = "asset-1",
                    )
                }
            )
            assertEquals(AsyncProviderJobState.SUCCEEDED, updated.state)
            assertEquals("asset-1", store.load().jobs.single().resultAssetId)

            assertTrue(store.delete(job.id))
            assertTrue(store.load().jobs.isEmpty())
            assertFalse(store.delete(job.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoresJobsFromInterruptedAtomicFileBackup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "async-job-test-${UUID.randomUUID()}")
        try {
            val store = AsyncProviderJobStore(root)
            val job = AsyncProviderJob(
                id = "recover-me",
                provider = AiProvider.CHATGPT,
                kind = AsyncProviderJobKind.BACKGROUND_RESPONSE,
                remoteId = "resp_recover",
                model = "gpt-5.6",
                state = AsyncProviderJobState.RUNNING,
                createdAtEpochMs = 1,
            )
            assertEquals(job, store.upsert(job))
            val base = File(root, AsyncProviderJobStore.FILE_NAME)
            val backup = File(root, "${AsyncProviderJobStore.FILE_NAME}.bak")
            assertTrue(base.renameTo(backup))
            assertEquals(job, store.load().jobs.single())
            assertTrue(base.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidJob() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "async-job-test-${UUID.randomUUID()}")
        try {
            val store = AsyncProviderJobStore(root)
            assertNull(
                store.upsert(
                    AsyncProviderJob(
                        id = "bad",
                        provider = AiProvider.ALL,
                        kind = AsyncProviderJobKind.BACKGROUND_RESPONSE,
                        remoteId = "resp",
                        model = "all",
                        state = AsyncProviderJobState.RUNNING,
                        createdAtEpochMs = 1,
                    )
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
