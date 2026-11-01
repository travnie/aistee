package ais.tee.data.engine

import ais.tee.data.model.*
import org.junit.Assert.*
import org.junit.Test

internal class FakeMcpServerPersistence : McpServerPersistence {
    var value: String? = null
    var present = false
    var failWrites = false
    override fun read() = value
    override fun hasStoredData() = present
    override fun write(value: String): Boolean {
        if (failWrites) return false
        this.value = value
        present = true
        return true
    }
    override fun clear(): Boolean {
        if (failWrites) return false
        value = null
        present = false
        return true
    }
}

class McpServerStoreTest {
    private val persistence = FakeMcpServerPersistence()
    private val store = McpServerStore(persistence)
    private val endpoint = "https://tools.example.org/mcp"

    private fun saved(label: String = "Tools", endpoint: String = this.endpoint, id: String? = null) =
        (store.save(id, label, endpoint) as McpServerSaveResult.Saved).server

    private fun fails(reason: McpServerSaveFailure, id: String? = null,
                      label: String = "Tools", endpoint: String = this.endpoint) {
        assertEquals(McpServerSaveResult.Failure(reason), store.save(id, label, endpoint))
    }

    @Test fun createReloadUpdateAndRemoveKeepStableIdentityWithoutCatalogs() {
        assertEquals(McpServerSnapshot(emptyList()), store.load())
        val first = saved("  Work  ", "HTTPS://TOOLS.EXAMPLE.ORG:443/mcp")
        assertEquals("Work", first.label)
        assertEquals(endpoint, first.endpoint)
        assertTrue(MCP_SERVER_ID.matches(first.id))
        assertEquals(listOf(first), McpServerStore(persistence).load().servers)
        val changed = saved("Renamed", "$endpoint/other", first.id)
        assertEquals(first.id, changed.id)
        assertEquals(listOf(changed), store.load().servers)
        val record = requireNotNull(persistence.value)
        assertFalse(record.contains("discoveryId"))
        assertFalse(record.contains("inputSchema"))
        assertTrue(store.remove(first.id))
        assertTrue(store.load().servers.isEmpty())
        assertFalse(store.remove(first.id))
    }

    @Test fun duplicateCanonicalEndpointsAndStaleIdsCannotReplaceRecords() {
        val first = saved()
        val original = persistence.value
        fails(McpServerSaveFailure.DUPLICATE_ENDPOINT, endpoint = "HTTPS://TOOLS.EXAMPLE.ORG:443/mcp")
        fails(McpServerSaveFailure.STORAGE, id = "missing")
        assertEquals(original, persistence.value)
        assertEquals(first, saved(id = first.id))
    }

    @Test fun boundsAllowUpdatingAFullListButRejectAdditionalServers() {
        val servers = (0 until MAX_MCP_SERVERS).map { saved("Server $it", "$endpoint/$it") }
        fails(McpServerSaveFailure.FULL)
        assertEquals(servers.first().id, saved("Changed", servers.first().endpoint, servers.first().id).id)
        assertEquals(MAX_MCP_SERVERS, store.load().servers.size)
    }

    @Test fun invalidLabelsAndUnsafeOrOversizedEndpointsDoNotWrite() {
        for (label in listOf(" ", "x".repeat(MAX_MCP_SERVER_LABEL + 1), "a\nb")) {
            fails(McpServerSaveFailure.INVALID_LABEL, label = label)
        }
        for (url in listOf("http://tools.example.org", "https://user:secret@tools.example.org",
            "$endpoint?token=secret", "$endpoint#secret", "https://127.0.0.1", "https://tools.local",
            "$endpoint/a\nb", "$endpoint/" + "a".repeat(MAX_MCP_ENDPOINT_LENGTH))) {
            fails(McpServerSaveFailure.INVALID_ENDPOINT, endpoint = url)
        }
        assertNull(persistence.value)
    }

    @Test fun unreadableCiphertextRequiresExplicitResetAndNeverGetsOverwritten() {
        persistence.present = true // Decryption failed, not an empty record.
        assertFalse(store.load().readable)
        fails(McpServerSaveFailure.STORAGE)
        assertFalse(store.remove("anything"))
        assertNull(persistence.value)
        assertTrue(store.clear())
        assertTrue(store.load().readable)
        saved()
    }

    @Test fun corruptUnknownAndInvalidArchivesFailClosed() {
        val valid = saved()
        val record = requireNotNull(persistence.value)
        val invalid = listOf("{", record.replace("\"version\":1", "\"version\":2"),
            record.replace(valid.id, "../bad"), record.replace("https://", "http://"),
            record.replace("\"servers\":[", "\"servers\":[" +
                "{\"id\":\"${valid.id}\",\"label\":\"Tools\",\"endpoint\":\"$endpoint\"},"),
            " ".repeat(32_001))
        for (raw in invalid) {
            persistence.value = raw
            assertFalse(store.load().readable)
            fails(McpServerSaveFailure.STORAGE)
            assertEquals(raw, persistence.value)
        }
    }

    @Test fun failedWritesPreserveTheLastSavedRecord() {
        val first = saved()
        persistence.failWrites = true
        fails(McpServerSaveFailure.STORAGE, id = first.id, label = "Changed")
        assertFalse(store.remove(first.id))
        assertFalse(store.clear())
        assertEquals(listOf(first), store.load().servers)
    }

    @Test fun diagnosticsRedactUserNamesAndEndpointPaths() {
        val config = McpServerConfiguration("one", "private-name", "$endpoint/private-secret")
        assertEquals("McpServerConfiguration([redacted])", config.toString())
        assertFalse(McpServerSaveResult.Saved(config).toString().contains("secret"))
    }
}
