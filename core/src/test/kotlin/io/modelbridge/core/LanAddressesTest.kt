package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetAddress

class LanAddressesTest {
    @Test
    fun `wifi site local ipv4 is preferred and loopback is ignored`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val rmnet = InetAddress.getByName("10.1.2.3")
        val wlan = InetAddress.getByName("192.168.1.23")

        val picked = LanAddresses.pick(
            sequenceOf(
                LanAddresses.Candidate("lo", loopback),
                LanAddresses.Candidate("rmnet0", rmnet),
                LanAddresses.Candidate("wlan0", wlan),
            ),
        )

        assertEquals(wlan, picked)
        assertEquals("http://192.168.1.23:8080/v1", LanAddresses.baseUrl(picked, 8080))
    }

    @Test
    fun `ethernet and fallback site local addresses work`() {
        val eth = InetAddress.getByName("192.168.8.2")
        assertEquals(eth, LanAddresses.pick(sequenceOf(LanAddresses.Candidate("eth0", eth))))

        val cellular = InetAddress.getByName("10.9.8.7")
        assertEquals(cellular, LanAddresses.pick(sequenceOf(LanAddresses.Candidate("rmnet_data", cellular))))
    }

    @Test
    fun `missing or invalid address yields no copyable lan url`() {
        val linkLocal = InetAddress.getByName("169.254.1.2")

        assertNull(LanAddresses.pick(emptySequence()))
        assertNull(LanAddresses.pick(sequenceOf(LanAddresses.Candidate("lo", InetAddress.getByName("127.0.0.1")))))
        assertNull(LanAddresses.pick(sequenceOf(LanAddresses.Candidate("wlan0", linkLocal))))
        assertNull(LanAddresses.baseUrl(null, 8080))
        assertNull(LanAddresses.baseUrl(InetAddress.getByName("192.168.1.2"), 0))
    }
}
