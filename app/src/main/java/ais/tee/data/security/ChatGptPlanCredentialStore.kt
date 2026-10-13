package ais.tee.data.security

import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Device-local SIWC vault. Only a signature-verified token-exchange grant can be stored.
 * No account becomes active implicitly. Preferences are excluded by Android backup allowlists.
 */
internal class ChatGptPlanCredentialStore(private val records: RecordStorage) {
    constructor(context: Context) : this(AndroidRecords(context))

    internal interface RecordStorage {
        fun read(): String?
        fun hasStoredData(): Boolean
        fun write(value: String): Boolean
    }

    /**
     * Local credential snapshot only. [connected] means tokens are stored, not that they are
     * unexpired or accepted by OpenAI. [planUsageGranted] reflects granted scopes, not an
     * active runtime or permission to fall back to metered API-key billing.
     */
    class Account(
        val clientId: String,
        val subject: String,
        val email: String?,
        val connected: Boolean,
        val planUsageGranted: Boolean,
        val expiresAt: Long?,
    ) {
        override fun toString() = "ChatGptPlanAccount(<redacted>)"
    }

    class Credentials internal constructor(
        val idToken: String,
        val accessToken: String,
        val refreshToken: String,
        val scopes: Set<String>,
        val expiresAt: Long,
    ) {
        override fun toString() = "ChatGptPlanCredentials(<redacted>)"
    }

    fun hostId(): String = synchronized(lock) {
        val state = load()
        state.host ?: run {
            val generated = "urn:uuid:" + UUID.randomUUID()
            if (!write(State(generated, state.accounts))) {
                throw IOException("Unable to persist ChatGPT host identity")
            }
            generated
        }
    }

    fun accounts(): List<Account> = synchronized(lock) {
        load().accounts.map { entry ->
            Account(
                entry.clientId, entry.subject, entry.email,
                entry.tokens != null,
                entry.tokens?.scopes?.contains("chatgpt.tokens.use.direct") == true,
                entry.tokens?.expiresAt,
            )
        }
    }

    /** Returns secrets only for the explicitly requested connection. */
    fun credentials(clientId: String): Credentials? = synchronized(lock) {
        load().accounts.firstOrNull { it.clientId == clientId }?.tokens
    }

    /** Atomically replace one issued-client registration without changing another account. */
    fun saveVerified(grant: ChatGptPlanTokenExchange.Grant): Boolean = synchronized(lock) {
        val state = load()
        require(state.host != null) { "Persist a ChatGPT host ID before sign-in" }
        val clientId = grant.identity.clientId
        val subject = grant.identity.subject
        require(clientId.isNotBlank() && subject.isNotBlank())
        val existing = state.accounts.firstOrNull { it.clientId == clientId }
        if (existing != null && existing.subject != subject) return@synchronized false
        if (existing == null && state.accounts.size >= MAX_ACCOUNTS) return@synchronized false
        require(grant.idToken.isNotBlank() && grant.accessToken.isNotBlank() &&
            grant.refreshToken.isNotBlank() && grant.expiresAtEpochSeconds > 0)
        val entry = Entry(
            clientId, subject, grant.identity.email,
            Credentials(grant.idToken, grant.accessToken, grant.refreshToken,
                grant.scopes.toSet(), grant.expiresAtEpochSeconds),
        )
        write(state.copy(accounts = state.accounts.filterNot { it.clientId == clientId } + entry))
    }

    /**
     * Replace one registration's rotated tokens together. Applies only while [usedRefreshToken]
     * is still the stored one, so a stale refresh never overwrites a newer rotation. Keeps the
     * identity and the ID token verified at sign-in.
     */
    fun saveRefreshed(
        clientId: String,
        usedRefreshToken: String,
        refreshed: ChatGptPlanTokenExchange.Refreshed,
    ): Boolean = synchronized(lock) {
        val state = load()
        val entry = state.accounts.firstOrNull { it.clientId == clientId } ?: return@synchronized false
        val current = entry.tokens ?: return@synchronized false
        if (current.refreshToken != usedRefreshToken) return@synchronized false
        require(refreshed.accessToken.isNotBlank() && refreshed.refreshToken.isNotBlank() &&
            refreshed.expiresAtEpochSeconds > 0)
        val tokens = Credentials(current.idToken, refreshed.accessToken, refreshed.refreshToken,
            refreshed.scopes ?: current.scopes, refreshed.expiresAtEpochSeconds)
        write(state.copy(accounts = state.accounts.map {
            if (it.clientId == clientId) Entry(it.clientId, it.subject, it.email, tokens) else it
        }))
    }

    /**
     * LOCAL disconnect, not a revocation request. Retain the issued client ID and subject for
     * future reauthorization, but discard all secret tokens for the selected registration.
     */
    fun disconnectLocally(clientId: String): Boolean = clearTokens(clientId, null)

    /**
     * Clear after the server rejected or revoked [usedRefreshToken]; a newer grant saved
     * meanwhile (e.g. a re-sign-in) is left alone. True when nothing stale remains.
     */
    fun clearRejected(clientId: String, usedRefreshToken: String): Boolean =
        clearTokens(clientId, usedRefreshToken)

    private fun clearTokens(clientId: String, onlyIfRefreshToken: String?): Boolean = synchronized(lock) {
        val state = load()
        val entry = state.accounts.firstOrNull { it.clientId == clientId } ?: return@synchronized true
        val tokens = entry.tokens ?: return@synchronized true
        if (onlyIfRefreshToken != null && tokens.refreshToken != onlyIfRefreshToken) return@synchronized true
        write(state.copy(accounts = state.accounts.map {
            if (it.clientId == clientId) Entry(it.clientId, it.subject, it.email, null) else it
        }))
    }

    private fun load(): State {
        val raw = records.read()
        if (raw == null) {
            if (records.hasStoredData()) throw IOException("ChatGPT vault cannot be decrypted")
            return State(null, emptyList())
        }
        if (raw.length > MAX_LENGTH) throw IOException("Oversized ChatGPT vault")
        return try {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid vault")
            require(obj.long("version") == 1L)
            val host = obj.str("host")
            require(host.startsWith("urn:uuid:") ||
                host.startsWith("urn:ietf:params:oauth:jwk-thumbprint:") || host.startsWith("did:key:"))
            val items = obj["accounts"] as? JsonArray ?: error("Missing accounts")
            require(items.size <= MAX_ACCOUNTS)
            val accounts = items.map { item ->
                val entry = item as? JsonObject ?: error("Invalid account")
                val clientId = entry.str("client_id")
                val subject = entry.str("sub")
                require(clientId.isNotBlank() && subject.isNotBlank())
                val token = when (val value = entry["tokens"]) {
                    null -> null
                    is JsonObject -> Credentials(
                        value.str("id_token"), value.str("access_token"), value.str("refresh_token"),
                        (value["scopes"] as? JsonArray ?: error("Missing scopes")).map {
                            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                                ?: error("Invalid scope")
                        }.toSet(),
                        value.long("expires_at"),
                    ).also {
                        require(it.idToken.isNotBlank() && it.accessToken.isNotBlank() &&
                            it.refreshToken.isNotBlank() && it.expiresAt > 0)
                    }
                    else -> error("Invalid tokens")
                }
                Entry(clientId, subject, entry.optStr("email"), token)
            }
            require(accounts.map { it.clientId }.distinct().size == accounts.size)
            State(host, accounts)
        } catch (_: Exception) {
            throw IOException("Invalid stored ChatGPT account record")
        }
    }

    private fun write(state: State): Boolean {
        val raw = buildJsonObject {
            put("version", 1)
            put("host", state.host ?: error("Missing host"))
            put("accounts", JsonArray(state.accounts.map { account ->
                buildJsonObject {
                    put("client_id", account.clientId)
                    put("sub", account.subject)
                    account.email?.let { put("email", it) }
                    account.tokens?.let { secret ->
                        put("tokens", buildJsonObject {
                            put("id_token", secret.idToken)
                            put("access_token", secret.accessToken)
                            put("refresh_token", secret.refreshToken)
                            put("scopes", JsonArray(secret.scopes.sorted().map(::JsonPrimitive)))
                            put("expires_at", secret.expiresAt)
                        })
                    }
                }
            }))
        }.toString()
        if (raw.length > MAX_LENGTH) throw IOException("Oversized ChatGPT vault")
        return records.write(raw)
    }

    private fun JsonObject.str(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Missing string")
    private fun JsonObject.optStr(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(name: String): Long =
        (this[name] as? JsonPrimitive)?.longOrNull ?: error("Missing integer")

    private data class State(val host: String?, val accounts: List<Entry>)
    private class Entry(val clientId: String, val subject: String, val email: String?, val tokens: Credentials?)

    private class AndroidRecords(context: Context) : RecordStorage {
        private val encrypted = EncryptedJsonStore(
            context, "chatgpt_plan_credentials", "aistee-chatgpt-plan-credentials")
        override fun read(): String? = encrypted.read()
        override fun hasStoredData(): Boolean = encrypted.hasStoredData()
        override fun write(value: String): Boolean = encrypted.write(value)
    }

    companion object {
        private val lock = Any()
        const val MAX_ACCOUNTS = 12
        private const val MAX_LENGTH = 262_144
    }
}
