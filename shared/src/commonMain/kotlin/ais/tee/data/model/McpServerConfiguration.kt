package ais.tee.data.model

import kotlinx.serialization.Serializable

/** A user-owned endpoint configuration, never a live session or a tool permission grant. */
@Serializable
data class McpServerConfiguration(val id: String, val label: String, val endpoint: String) {
    // Even an endpoint path or a user-provided name can contain a secret.
    override fun toString(): String = "McpServerConfiguration([redacted])"
}

const val MAX_MCP_SERVERS = 8
const val MAX_MCP_SERVER_LABEL = 60
const val MAX_MCP_ENDPOINT_LENGTH = 2_048

/** Portable configuration rules. The transport supplies its parsed, canonical public HTTPS URL. */
object McpServerConfigurations {
    private val serverId = Regex("^[A-Za-z0-9_-]{1,20}$")
    fun validId(id: String) = serverId.matches(id)
    fun validLabel(label: String) = label.isNotBlank() && label.length <= MAX_MCP_SERVER_LABEL &&
        label.none { it.isISOControl() } && label == label.trim()

    fun validArchive(servers: List<McpServerConfiguration>) = servers.size <= MAX_MCP_SERVERS &&
        servers.all { validId(it.id) && validLabel(it.label) && it.endpoint.length in 1..MAX_MCP_ENDPOINT_LENGTH } &&
        servers.map { it.id }.distinct().size == servers.size &&
        servers.map { it.label.lowercase() }.distinct().size == servers.size &&
        servers.map { it.endpoint }.distinct().size == servers.size

    fun upsert(
        servers: List<McpServerConfiguration>, id: String?, label: String,
        canonicalEndpoint: String?, createId: () -> String,
    ): McpServerSaveResult {
        val name = label.trim()
        if (!validLabel(name)) return McpServerSaveResult.Failure(McpServerSaveFailure.INVALID_LABEL)
        if (canonicalEndpoint == null || canonicalEndpoint.length !in 1..MAX_MCP_ENDPOINT_LENGTH) {
            return McpServerSaveResult.Failure(McpServerSaveFailure.INVALID_ENDPOINT)
        }
        if (!validArchive(servers) || (id != null && servers.none { it.id == id })) {
            return McpServerSaveResult.Failure(McpServerSaveFailure.STORAGE)
        }
        val conflict = conflict(servers, id, name, canonicalEndpoint)
        if (conflict != null) return McpServerSaveResult.Failure(conflict)
        if (id == null && servers.size >= MAX_MCP_SERVERS) return McpServerSaveResult.Failure(McpServerSaveFailure.FULL)
        val uniqueId = id ?: (1..4).asSequence().map { createId() }
            .firstOrNull { candidate -> validId(candidate) && servers.none { it.id == candidate } }
            ?: return McpServerSaveResult.Failure(McpServerSaveFailure.STORAGE)
        return McpServerSaveResult.Saved(McpServerConfiguration(uniqueId, name, canonicalEndpoint))
    }

    private fun conflict(servers: List<McpServerConfiguration>, id: String?, name: String, endpoint: String) = when {
        servers.any { it.id != id && it.endpoint == endpoint } -> McpServerSaveFailure.DUPLICATE_ENDPOINT
        servers.any { it.id != id && it.label.equals(name, ignoreCase = true) } -> McpServerSaveFailure.DUPLICATE_NAME
        else -> null
    }

    fun replace(servers: List<McpServerConfiguration>, server: McpServerConfiguration) =
        if (servers.any { it.id == server.id }) servers.map { if (it.id == server.id) server else it }
        else servers + server
}

enum class McpServerSaveFailure { INVALID_LABEL, INVALID_ENDPOINT, DUPLICATE_ENDPOINT, DUPLICATE_NAME, FULL, STORAGE }

sealed interface McpServerSaveResult {
    data class Saved(val server: McpServerConfiguration) : McpServerSaveResult
    data class Failure(val reason: McpServerSaveFailure) : McpServerSaveResult
}
