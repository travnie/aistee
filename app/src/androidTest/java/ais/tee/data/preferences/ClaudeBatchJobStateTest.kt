package ais.tee.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ais.tee.data.engine.ClaudeBatchResult
import ais.tee.data.engine.ClaudeBatchSnapshot
import ais.tee.data.engine.persistClaudeBatchState
import ais.tee.data.model.AiProvider
import ais.tee.data.model.AsyncProviderJob
import ais.tee.data.model.AsyncProviderJobKind
import ais.tee.data.model.AsyncProviderJobState
import ais.tee.data.model.DEFAULT_PROJECT_ID
import ais.tee.data.model.needsPolling
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeBatchJobStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun runningBatchKeepsPollingAndEndedBatchSavesTheResultOnce() {
        val store = AsyncProviderJobStore(context.noBackupFilesDir)
        val library = ProjectLibraryStore(context.noBackupFilesDir)
        val job = requireNotNull(
            store.upsert(
                AsyncProviderJob(
                    id = "claude-batch-test-${UUID.randomUUID()}",
                    provider = AiProvider.CLAUDE,
                    kind = AsyncProviderJobKind.BATCH,
                    remoteId = "msgbatch_test",
                    model = "claude-opus-5",
                    projectId = DEFAULT_PROJECT_ID,
                    state = AsyncProviderJobState.RUNNING,
                    createdAtEpochMs = 1,
                )
            )
        )
        var assetId: String? = null
        try {
            val running = persistClaudeBatchState(context, job, ClaudeBatchSnapshot("msgbatch_test", ended = false, cancelling = false), null)
            assertTrue(running.shouldRetry)
            assertEquals(AsyncProviderJobState.RUNNING, running.job.state)

            val ended = ClaudeBatchSnapshot("msgbatch_test", ended = true, cancelling = false)
            val done = persistClaudeBatchState(
                context,
                running.job,
                ended,
                ClaudeBatchResult(AsyncProviderJobState.SUCCEEDED, "claude-opus-5", "batch answer", null),
            )
            assertFalse(done.shouldRetry)
            assertEquals(AsyncProviderJobState.SUCCEEDED, done.job.state)
            assetId = done.job.resultAssetId
            assertNotNull(assetId)
            assertTrue(library.loadTextAsset(assetId!!)!!.text.contains("batch answer"))

            // A repeated refresh does not save a second copy.
            val again = persistClaudeBatchState(
                context,
                done.job,
                ended,
                ClaudeBatchResult(AsyncProviderJobState.SUCCEEDED, "claude-opus-5", "batch answer", null),
            )
            assertEquals(assetId, again.job.resultAssetId)
        } finally {
            store.delete(job.id)
            assetId?.let(library::deleteAsset)
        }
    }

    @Test
    fun succeededBatchWithoutTextEndsIncompleteInsteadOfPollingForever() {
        val store = AsyncProviderJobStore(context.noBackupFilesDir)
        val job = requireNotNull(
            store.upsert(
                AsyncProviderJob(
                    id = "claude-batch-empty-${UUID.randomUUID()}",
                    provider = AiProvider.CLAUDE,
                    kind = AsyncProviderJobKind.BATCH,
                    remoteId = "msgbatch_empty",
                    model = "claude-opus-5",
                    projectId = DEFAULT_PROJECT_ID,
                    state = AsyncProviderJobState.RUNNING,
                    createdAtEpochMs = 1,
                )
            )
        )
        try {
            val done = persistClaudeBatchState(
                context,
                job,
                ClaudeBatchSnapshot("msgbatch_empty", ended = true, cancelling = false),
                ClaudeBatchResult(AsyncProviderJobState.SUCCEEDED, "claude-opus-5", "  ", null),
            )
            assertEquals(AsyncProviderJobState.INCOMPLETE, done.job.state)
            assertFalse(done.shouldRetry)
            assertFalse(done.job.needsPolling)
        } finally {
            store.delete(job.id)
        }
    }
}
