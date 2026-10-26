package ais.tee.data.streambench

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

/** DNS boundary used only by Streambench media requests. */
internal class StreambenchPublicDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val publicAddresses = delegate.lookup(hostname).filter(::isPublicStreambenchAddress)
        if (publicAddresses.isEmpty()) {
            throw UnknownHostException("Streambench hostname did not resolve to a public address")
        }
        return publicAddresses
    }
}

/**
 * Applies the shared strict URL policy to each independently-created media request.
 *
 * OkHttp's DNS guard remains authoritative for the resolved destination. Protocol-changing redirects
 * are separately disabled on the Streambench client before this interceptor is installed.
 */
internal class StreambenchRemoteRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (StreambenchM3uParser.validateRemotePlaybackUrl(request.url.toString()) == null) {
            throw IOException("Streambench blocked a non-public media request")
        }
        return chain.proceed(request)
    }
}

/** Reject addresses that should never be reached from an untrusted imported playlist. */
internal fun isPublicStreambenchAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) return false

    val bytes = address.address
    return when (bytes.size) {
        IPV4_SIZE -> blockedIpv4Ranges.none { it.contains(bytes.toIpv4Long()) }
        IPV6_SIZE -> !isBlockedIpv6(bytes)
        else -> false
    }
}

private fun isBlockedIpv6(bytes: ByteArray): Boolean {
    val first = bytes[0].unsigned()
    val second = bytes[1].unsigned()

    if ((first and 0xFE) == 0xFC) return true
    if (first == 0x20 && second == 0x01) {
        // Documentation prefix (2001:db8::/32) and benchmarking (2001:2::/48).
        if (bytes[2].unsigned() == 0x0D && bytes[3].unsigned() == 0xB8) return true
        if (bytes[2].unsigned() == 0 && bytes[3].unsigned() == 2 &&
            bytes[4].unsigned() == 0 && bytes[5].unsigned() == 0) return true
    }

    val mappedIpv4 = ipv4MappedAddress(bytes)
    return mappedIpv4?.let { address ->
        blockedIpv4Ranges.any { it.contains(address.toIpv4Long()) }
    } == true
}

private fun ipv4MappedAddress(bytes: ByteArray): ByteArray? {
    val hasMappedPrefix = bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xFF.toByte() &&
        bytes[11] == 0xFF.toByte()
    return if (hasMappedPrefix) bytes.copyOfRange(12, 16) else null
}

private fun ByteArray.toIpv4Long(): Long =
    fold(0L) { value, byte -> (value shl 8) or byte.unsigned().toLong() }

private fun Byte.unsigned(): Int = toInt() and 0xFF

private data class Ipv4Range(
    val network: Long,
    val prefixBits: Int
) {
    private val mask: Long = (0xFFFF_FFFFL shl (32 - prefixBits)) and 0xFFFF_FFFFL

    fun contains(address: Long): Boolean = address and mask == network and mask
}

private val blockedIpv4Ranges = listOf(
    Ipv4Range(0x0000_0000L, 8),
    Ipv4Range(0x0A00_0000L, 8),
    Ipv4Range(0x6440_0000L, 10),
    Ipv4Range(0x7F00_0000L, 8),
    Ipv4Range(0xA9FE_0000L, 16),
    Ipv4Range(0xAC10_0000L, 12),
    Ipv4Range(0xC000_0000L, 24),
    Ipv4Range(0xC000_0200L, 24),
    Ipv4Range(0xC0A8_0000L, 16),
    Ipv4Range(0xC612_0000L, 15),
    Ipv4Range(0xC633_6400L, 24),
    Ipv4Range(0xCB00_7100L, 24),
    Ipv4Range(0xE000_0000L, 3)
)

private const val IPV4_SIZE = 4
private const val IPV6_SIZE = 16
