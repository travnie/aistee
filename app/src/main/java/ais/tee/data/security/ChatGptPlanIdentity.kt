package ais.tee.data.security

import ais.tee.data.auth.validateChatGptPlanClaims
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JVM/Android RSA signature adapter for the portable shared identity policy.
 * Caller must obtain JWKS only from OpenAI's pinned HTTPS discovery endpoint.
 * JWT-provided URLs and embedded signing keys are never trusted.
 */
internal object ChatGptPlanIdentity {
    /** Only the signature-verifying factory can construct this identity. */
    class Verified private constructor(
        val subject: String,
        val email: String?,
        val clientId: String,
    ) {
        override fun toString(): String = "Verified(<redacted>)"

        companion object {
            fun verify(
                jwt: String,
                jwks: String,
                clientId: String,
                nonce: String,
                now: Long = System.currentTimeMillis() / 1000,
            ): Verified {
                require(jwt.length in 1..16384 && jwks.length in 1..65536)
                require(clientId.isNotBlank() && nonce.isNotBlank())
                val parts = jwt.split('.')
                require(parts.size == 3 && parts.all { it.isNotBlank() })
                val header = parseObject(String(decode(parts[0]), StandardCharsets.UTF_8))
                fun JsonObject.string(name: String): String? =
                    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(header.string("alg") == "RS256")
                require("jku" !in header && "jwk" !in header && "x5u" !in header)
                val kid = header.string("kid")?.takeIf { it.isNotBlank() } ?: error("Missing signing key")
                val keys = (parseObject(jwks)["keys"] as? JsonArray)
                    ?: error("Missing key set")
                val matches = keys.filterIsInstance<JsonObject>().filter { key ->
                    key.string("kid") == kid && key.string("kty") == "RSA" &&
                        (key.string("use") == null || key.string("use") == "sig") &&
                        (key.string("alg") == null || key.string("alg") == "RS256")
                }
                require(matches.size == 1)
                val key = matches.single()
                val modulus = BigInteger(1, decode(key.string("n") ?: error("Missing key material")))
                val exponent = BigInteger(1, decode(key.string("e") ?: error("Missing exponent")))
                require(modulus.bitLength() >= 2048 && exponent > BigInteger.ONE)
                val publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(RSAPublicKeySpec(modulus, exponent))
                val signature = Signature.getInstance("SHA256withRSA")
                signature.initVerify(publicKey)
                signature.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
                require(signature.verify(decode(parts[2])))
                // Claims are evaluated only after verifying the signature.
                val claims = validateChatGptPlanClaims(
                    payload = String(decode(parts[1]), StandardCharsets.UTF_8),
                    clientId = clientId,
                    nonce = nonce,
                    nowEpochSeconds = now,
                )
                return Verified(claims.subject, claims.email, claims.clientId)
            }


            private fun parseObject(value: String): JsonObject =
                Json.parseToJsonElement(value) as? JsonObject ?: error("Invalid JSON")

            private fun decode(value: String): ByteArray {
                require(value.isNotEmpty() && value.length <= 65536 &&
                    value.all { it in 'A'..'Z' || it in 'a'..'z' ||
                        it in '0'..'9' || it == '-' || it == '_' })
                return Base64.getUrlDecoder().decode(value)
            }
        }
    }

    fun verify(
        jwt: String,
        jwks: String,
        clientId: String,
        nonce: String,
        now: Long = System.currentTimeMillis() / 1000,
    ): Verified = Verified.verify(jwt, jwks, clientId, nonce, now)
}
