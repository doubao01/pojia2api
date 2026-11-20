package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayCountersTest {
    @Test
    fun `counters accumulate independently of log retention`() {
        val counters = GatewayCounters()
        counters.record(200, 100, promptTokens = 100, completionTokens = 20, cachedTokens = 40)
        counters.record(200, 300, promptTokens = 100, completionTokens = 30, cachedTokens = 60)
        counters.record(500, 0)

        val snapshot = counters.snapshot()
        assertEquals(3, snapshot.total)
        assertEquals(2, snapshot.success)
        assertEquals(1, snapshot.failed)
        assertEquals(200, snapshot.averageCostMillis)
        assertEquals(200, snapshot.promptTokens)
        assertEquals(50, snapshot.completionTokens)
        assertEquals(100, snapshot.cachedTokens)
        assertEquals(50.0, snapshot.cacheHitRate!!, 0.0001)
        assertEquals(2 * 100.0 / 3.0, snapshot.successRate!!, 0.0001)
    }

    @Test
    fun `empty or usage-less traffic has no cache rate`() {
        val counters = GatewayCounters()
        assertNull(counters.snapshot().cacheHitRate)
        counters.record(204, 0)
        assertNull(counters.snapshot().cacheHitRate)
    }

    @Test
    fun `snapshot round trip preserves upstream counts and latency samples`() {
        val counters = GatewayCounters()
        counters.record(200, 100, 100, 20, 40, "codebuddy/a1")
        counters.record(200, 300, 100, 30, 60, "codebuddy/a1")
        counters.record(500, 0, upstreamId = "qoder/a2")

        val restored = GatewayCounters().apply { restore(counters.decode(counters.encode())) }
        val snapshot = restored.snapshot()

        assertEquals(3, snapshot.total)
        assertEquals(200, snapshot.averageCostMillis)
        assertEquals(mapOf("codebuddy/a1" to 2L, "qoder/a2" to 1L), snapshot.byUpstream)
        assertEquals(50.0, snapshot.cacheHitRate!!, 0.0001)
        assertEquals(GatewayCounters.Snapshot(), GatewayCounters().decode("not-json"))
    }

    @Test
    fun `reset clears every derived statistic`() {
        val counters = GatewayCounters()
        counters.record(200, 10, 10, 5, 5, "codebuddy/a1")
        counters.reset()

        val snapshot = counters.snapshot()
        assertEquals(0, snapshot.total)
        assertEquals(0, snapshot.success)
        assertEquals(0, snapshot.promptTokens)
        assertEquals(0, snapshot.cachedTokens)
        assertEquals(emptyMap<String, Long>(), snapshot.byUpstream)
        assertNull(snapshot.cacheHitRate)
    }

    @Test
    fun `redirects count toward total but do not dilute the success rate`() {
        val counters = GatewayCounters()
        counters.record(200)
        counters.record(200)
        counters.record(302)

        val snapshot = counters.snapshot()
        assertEquals(3, snapshot.total)
        assertEquals(2, snapshot.success)
        assertEquals(0, snapshot.failed)
        assertEquals(2, snapshot.classified)
        // 分母只算已归类请求，否则 3xx 会把成功率从 100% 拉到 66.7%
        assertEquals(100.0, snapshot.successRate!!, 0.0001)
    }

    @Test
    fun `success rate is null before any request is classified`() {
        val counters = GatewayCounters()
        counters.record(302)

        val snapshot = counters.snapshot()
        assertEquals(1, snapshot.total)
        assertEquals(0, snapshot.classified)
        // 没有已归类请求时不编造 0%，交给界面显示「—」
        assertNull(snapshot.successRate)
        assertTrue(snapshot.hasTraffic)
    }

    @Test
    fun `connection failures count as failed`() {
        val counters = GatewayCounters()
        counters.record(0)

        val snapshot = counters.snapshot()
        assertEquals(1, snapshot.failed)
        assertEquals(0.0, snapshot.successRate!!, 0.0001)
        assertFalse(GatewayCounters().snapshot().hasTraffic)
    }
}
