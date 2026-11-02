package ais.tee.data.model

/** Untrusted public metadata, scoped to one MCP resource; never a connection or grant. */
data class McpProtectedResourceMetadata(
    val resource: String,
    val authorizationServers: List<String>,
    val scopes: List<String>,
) {
    override fun toString(): String = "McpProtectedResourceMetadata(redacted)"
}

/** Validated discovery binding, not proof that an issuer is trusted or sign-in is supported. */
data class McpAuthorizationServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val supportsS256: Boolean,
    val supportsClientMetadata: Boolean,
    val hasRegistrationEndpoint: Boolean,
) {
    override fun toString(): String = "McpAuthorizationServerMetadata(redacted)"
}
