package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncProviderJobPollingTest {
    private val job = AsyncProviderJob(
        id = "j",
        provider = AiProvider.CLAUDE,
        kind = AsyncProviderJobKind.BATCH,
        remoteId = "msgbatch_1",
        model = "m",
        state = AsyncProviderJobState.RUNNING,
        createdAtEpochMs = 1,
    )

    @Test
    fun pollsUntilFinishedAndItsResultIsSaved() {
        assertTrue(job.needsPolling)
        assertTrue(job.copy(state = AsyncProviderJobState.SUCCEEDED).needsPolling)
        assertFalse(job.copy(state = AsyncProviderJobState.SUCCEEDED, resultAssetId = "a").needsPolling)
        assertFalse(job.copy(state = AsyncProviderJobState.FAILED).needsPolling)
        assertTrue(job.isClaudeBatch)
        assertFalse(job.copy(provider = AiProvider.CHATGPT, kind = AsyncProviderJobKind.BACKGROUND_RESPONSE).isClaudeBatch)
    }
}
