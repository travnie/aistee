package ais.tee.data.preferences

import ais.tee.data.model.NativeChatConversation
import ais.tee.data.model.NativeChatArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeChatWriterTest {
    @Test
    fun simultaneousTransactionsAndStaleForegroundSnapshotKeepEveryChange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val initial = NativeChatArchive(conversations = listOf(
            NativeChatConversation(id = "one", createdAtEpochMs = 1),
            NativeChatConversation(id = "two", createdAtEpochMs = 1),
        ))
        val disk = java.util.concurrent.atomic.AtomicReference(initial)
        val writer = NativeChatWriter.createForTest(save = { disk.set(it); true }, scope = scope, load = { initial })
        try {
            val changes = listOf("one", "two").map { id ->
                async(Dispatchers.Default) {
                    writer.updateAndPersist { archive -> archive.copy(conversations = archive.conversations.map {
                        if (it.id == id) it.copy(title = "background-$id") else it
                    }) }
                }
            }
            changes.forEach { it.await() }
            val foreground = initial.copy(conversations = initial.conversations.map {
                if (it.id == "one") it.copy(draft = "unsaved draft") else it
            })
            writer.enqueue(foreground, initial)
            writer.updateAndPersist { it }
            assertEquals(listOf("background-one", "background-two"), disk.get().conversations.map { it.title })
            assertEquals("unsaved draft", disk.get().conversations.first().draft)
        } finally { scope.cancel() }
    }

    @Test
    fun failedTransactionReportsStorageFailureAndRetainsTurnForRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val initial = NativeChatArchive(activeConversationId = "before")
        val disk = java.util.concurrent.atomic.AtomicReference(initial)
        val writer = NativeChatWriter.createForTest(
            save = { if (fail.get()) false else { disk.set(it); true } },
            scope = scope,
            load = { initial },
        )
        try {
            var threw = false
            try { writer.updateAndPersist { it.copy(activeConversationId = "generated") } }
            catch (_: IOException) { threw = true }
            assertTrue(threw)
            assertEquals("generated", writer.currentArchive()!!.activeConversationId)
            fail.set(false)
            writer.updateAndPersist { it }
            assertEquals("generated", disk.get().activeConversationId)
        } finally { scope.cancel() }
    }

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
