package ais.tee.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsyncProviderJobsTest {
    @Test
    fun normalizationRejectsInvalidOrDuplicateJobs() {
        val valid = AsyncProviderJob(
            id = "job-1",
            provider = AiProvider.CHATGPT,
            kind = AsyncProviderJobKind.BACKGROUND_RESPONSE,
            remoteId = "resp_1",
            model = "gpt-5.6",
            state = AsyncProviderJobState.RUNNING,
            createdAtEpochMs = 1,
        )
        val normalized = AsyncProviderJobArchive(
            jobs = listOf(
                valid,
                valid.copy(remoteId = "resp_duplicate"),
                valid.copy(id = "bad-provider", provider = AiProvider.ALL),
                valid.copy(id = "blank-remote", remoteId = " "),
            )
        ).normalizedAsyncProviderJobs()

        assertEquals(listOf(valid), normalized?.jobs)
    }

    @Test
    fun normalizationTrimsMetadataAndBoundsErrors() {
        val normalized = requireNotNull(
            AsyncProviderJobArchive(
                jobs = listOf(
                    AsyncProviderJob(
                        id = " job ",
                        provider = AiProvider.CHATGPT,
                        kind = AsyncProviderJobKind.BACKGROUND_RESPONSE,
                        remoteId = " resp ",
                        model = " gpt-5.6 ",
                        projectId = " inbox ",
                        state = AsyncProviderJobState.FAILED,
                        createdAtEpochMs = 1,
                        resultAssetId = " asset ",
                        errorMessage = "x".repeat(700),
                    )
                )
            ).normalizedAsyncProviderJobs()
        ).jobs.single()

        assertEquals("job", normalized.id)
        assertEquals("resp", normalized.remoteId)
        assertEquals("gpt-5.6", normalized.model)
        assertEquals("inbox", normalized.projectId)
        assertEquals("asset", normalized.resultAssetId)
        assertEquals(500, normalized.errorMessage?.length)
    }

    @Test
    fun terminalStateMatchesPollingSemantics() {
        assertFalse(AsyncProviderJobState.QUEUED.isTerminal)
        assertFalse(AsyncProviderJobState.RUNNING.isTerminal)
        assertTrue(AsyncProviderJobState.SUCCEEDED.isTerminal)
        assertTrue(AsyncProviderJobState.CANCELLED.isTerminal)
        assertTrue(AsyncProviderJobState.INCOMPLETE.isTerminal)
    }

    @Test
    fun unsupportedArchiveVersionIsRejected() {
        assertNull(
            AsyncProviderJobArchive(
                version = ASYNC_PROVIDER_JOB_ARCHIVE_VERSION + 1
            ).normalizedAsyncProviderJobs()
        )
    }
}
