package ais.tee.data.engine

import android.content.Context
import ais.tee.data.model.*
import ais.tee.data.security.EncryptedJsonStore
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small injectable persistence seam; production stores only Keystore-encrypted JSON. */
internal interface McpServerPersistence {
    fun read(): String?
    fun hasStoredData(): Boolean
    fun write(value: String): Boolean
    fun clear(): Boolean
}

internal data class McpServerSnapshot(
    val servers: List<McpServerConfiguration>,
    val readable: Boolean = true,
)

/** Call on an IO dispatcher. No catalog, session, authorization or tool grant is persisted. */
internal class McpServerStore(private val persistence: McpServerPersistence) {
    constructor(context: Context) : this(encryptedPersistence(context))

    fun load(): McpServerSnapshot = synchronized(lock) { readSnapshot() }

    fun save(id: String?, label: String, endpoint: String): McpServerSaveResult = synchronized(lock) {
        val snapshot = readSnapshot()
        if (!snapshot.readable) return@synchronized failure(McpServerSaveFailure.STORAGE)
        val result = McpServerConfigurations.upsert(snapshot.servers, id, label,
            validatedMcpEndpoint(endpoint.trim())?.toString()) {
            UUID.randomUUID().toString().replace("-", "").take(20)
        }
        if (result !is McpServerSaveResult.Saved) return@synchronized result
        if (write(McpServerConfigurations.replace(snapshot.servers, result.server))) result
        else failure(McpServerSaveFailure.STORAGE)
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val snapshot = readSnapshot()
        snapshot.readable && snapshot.servers.any { it.id == id } &&
            write(snapshot.servers.filterNot { it.id == id })
    }

    /** Explicit recovery only: unreadable records are never silently overwritten. */
    fun clear(): Boolean = synchronized(lock) { runCatching { persistence.clear() }.getOrDefault(false) }

    private fun readSnapshot(): McpServerSnapshot = runCatching {
        val raw = persistence.read()
        if (raw == null) return@runCatching McpServerSnapshot(emptyList(), !persistence.hasStoredData())
        require(raw.length <= MAX_STORED_CHARS)
        val record = Json.decodeFromString<StoredServers>(raw)
        require(record.version == 1 && McpServerConfigurations.validArchive(record.servers))
        require(record.servers.all {
            validatedMcpEndpoint(it.endpoint)?.toString() == it.endpoint
        })
        McpServerSnapshot(record.servers)
    }.getOrElse { McpServerSnapshot(emptyList(), readable = false) }

    private fun write(servers: List<McpServerConfiguration>): Boolean = runCatching {
        val raw = Json.encodeToString(StoredServers(1, servers))
        raw.length <= MAX_STORED_CHARS && persistence.write(raw)
    }.getOrDefault(false)

    @Serializable
    private data class StoredServers(val version: Int, val servers: List<McpServerConfiguration>)

    private companion object {
        val lock = Any()
        const val MAX_STORED_CHARS = 32_000
        fun failure(reason: McpServerSaveFailure) = McpServerSaveResult.Failure(reason)
        fun encryptedPersistence(context: Context): McpServerPersistence {
            val store = EncryptedJsonStore(context, "encrypted_mcp_servers", "aistee-mcp-servers")
            return object : McpServerPersistence {
                override fun read() = store.read()
                override fun hasStoredData() = store.hasStoredData()
                override fun write(value: String) = store.write(value)
                override fun clear() = store.clear()
            }
        }
    }
}
