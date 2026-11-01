package ais.tee.ui.screens

import ais.tee.data.engine.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class McpSavedServersStateTest {
    @Test fun committedSaveAndRemovalReconcileStateAfterTheTabScopeIsCancelled() = runBlocking {
        val backing = FakeMcpServerPersistence()
        var started = CountDownLatch(1)
        var release = CountDownLatch(1)
        val store = McpServerStore(object : McpServerPersistence by backing {
            override fun write(value: String): Boolean {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return backing.write(value)
            }
        })
        val state = McpPreviewState().apply {
            serverLabel = "Work"
            endpoint = "https://tools.example.org/mcp"
        }
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        state.savedServers.save(saveScope, store, state)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        saveScope.cancel() // Simulate removing the MCP composable while its disk commit is pending.
        release.countDown()
        withTimeout(5_000) { while (state.savedServers.busy) delay(10) }
        val saved = store.load().servers.single()
        assertEquals(saved.id, state.serverId)
        assertEquals(listOf(saved), state.savedServers.snapshot?.servers)

        started = CountDownLatch(1)
        release = CountDownLatch(1)
        val removeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        state.savedServers.remove(removeScope, store, state)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        removeScope.cancel()
        release.countDown()
        withTimeout(5_000) { while (state.savedServers.busy) delay(10) }
        assertNull(state.serverId)
        assertEquals("", state.endpoint)
        assertTrue(state.savedServers.snapshot?.servers.orEmpty().isEmpty())
    }
}
