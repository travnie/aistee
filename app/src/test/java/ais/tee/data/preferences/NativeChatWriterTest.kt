package ais.tee.data.preferences

import ais.tee.data.model.NativeChatArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeChatWriterTest {
    @Test
    fun latestArchiveIsVisibleWhileDiskWriteIsBlocked() {
        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = NativeChatWriter.createForTest(
            save = {
                saveStarted.countDown()
                releaseSave.await(2, TimeUnit.SECONDS)
                true
            },
            scope = scope
        )
        NativeChatWriter.replaceInstanceForTest(writer)
        try {
            val first = NativeChatArchive(activeConversationId = "first")
            val latest = NativeChatArchive(activeConversationId = "latest")
            writer.enqueue(first)
            assertTrue(saveStarted.await(2, TimeUnit.SECONDS))
            writer.enqueue(latest)
            assertSame(latest, NativeChatWriter.currentArchive())
        } finally {
            NativeChatWriter.replaceInstanceForTest(null)
            releaseSave.countDown()
            scope.cancel()
        }
    }

    @Test
    fun postSaveCallbackSkipsSupersededArchive() {
        val firstSaveStarted = CountDownLatch(1)
        val releaseFirstSave = CountDownLatch(1)
        val latestCallback = CountDownLatch(1)
        val callbackArchive = java.util.concurrent.atomic.AtomicReference<NativeChatArchive?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = NativeChatWriter.createForTest(
            save = { archive ->
                if (archive.activeConversationId == "first") {
                    firstSaveStarted.countDown()
                    releaseFirstSave.await(2, TimeUnit.SECONDS)
                }
                true
            },
            scope = scope,
            afterSave = { archive ->
                callbackArchive.set(archive)
                latestCallback.countDown()
            },
        )
        try {
            val first = NativeChatArchive(activeConversationId = "first")
            val latest = NativeChatArchive(activeConversationId = "latest")
            writer.enqueue(first)
            assertTrue(firstSaveStarted.await(2, TimeUnit.SECONDS))
            writer.enqueue(latest)
            releaseFirstSave.countDown()
            assertTrue(latestCallback.await(2, TimeUnit.SECONDS))
            assertSame(latest, callbackArchive.get())
        } finally {
            releaseFirstSave.countDown()
            scope.cancel()
        }
    }

    @Test
    fun latestArchiveKeepsRetryingAfterInitialFailures() {
        val attempts = AtomicInteger(0)
        val saved = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = NativeChatWriter.createForTest(
            save = {
                val attempt = attempts.incrementAndGet()
                if (attempt >= 5) saved.countDown()
                attempt >= 5
            },
            scope = scope
        )
        try {
            writer.enqueue(NativeChatArchive(activeConversationId = "c1"))
            assertTrue(saved.await(2, TimeUnit.SECONDS))
            assertEquals(5, attempts.get())
        } finally {
            scope.cancel()
        }
    }
}
