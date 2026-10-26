package ais.tee.data.streambench

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreambenchNetworkGuardTest {
    @Test
    fun publicDnsDropsPrivateFallbackAddresses() {
        val publicAddress = ipv4(93, 184, 216, 34)
        val dns = StreambenchPublicDns(
            FakeDns(
                listOf(
                    ipv4(127, 0, 0, 1),
                    ipv4(10, 0, 0, 8),
                    publicAddress
                )
            )
        )

        assertEquals(listOf(publicAddress), dns.lookup("stream.example"))
    }

    @Test
    fun publicDnsFailsClosedWhenEveryAddressIsPrivate() {
        val dns = StreambenchPublicDns(
            FakeDns(
                listOf(
                    ipv4(192, 168, 1, 10),
                    ipv4(100, 64, 0, 1),
                    ipv6(0xFC)
                )
            )
        )

        assertThrows(UnknownHostException::class.java) {
            dns.lookup("stream.example")
        }
    }

    @Test
    fun addressPolicyRejectsLocalCarrierAndBenchmarkNetworks() {
        listOf(
            ipv4(0, 0, 0, 0),
            ipv4(10, 1, 2, 3),
            ipv4(100, 100, 0, 1),
            ipv4(127, 0, 0, 1),
            ipv4(169, 254, 1, 1),
            ipv4(172, 20, 1, 1),
            ipv4(192, 168, 1, 1),
            ipv4(198, 18, 0, 1),
            ipv6(0xFC),
            ipv6(0xFD),
            ipv6(0x20, 0x01, 0x00, 0x02, 0x00, 0x00)
        ).forEach { address ->
            assertFalse(address.hostAddress, isPublicStreambenchAddress(address))
        }
    }

    @Test
    fun addressPolicyAllowsOrdinaryPublicAddresses() {
        assertTrue(isPublicStreambenchAddress(ipv4(93, 184, 216, 34)))
        assertTrue(isPublicStreambenchAddress(ipv6(0x26, 0x06, 0x47, 0x00)))
    }

    @Test
    fun okhttpCanonicalizationKeepsAllowedRemoteUrlEligible() {
        val canonical = "https://EXAMPLE.com:443/live".toHttpUrl().toString()

        assertEquals("https://example.com/live", canonical)
        assertNotNull(StreambenchM3uParser.validateRemotePlaybackUrl(canonical))
    }

    private class FakeDns(
        private val addresses: List<InetAddress>
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun ipv6(vararg prefix: Int): InetAddress {
        val bytes = ByteArray(16)
        prefix.forEachIndexed { index, value -> bytes[index] = value.toByte() }
        return InetAddress.getByAddress(bytes)
    }
}
