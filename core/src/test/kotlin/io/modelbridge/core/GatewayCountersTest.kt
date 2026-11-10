package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(2 * 100.0 / 3.0, snapshot.successRate, 0.0001)
    }

    @Test
    fun `empty or usage-less traffic has no cache rate`() {
        val counters = GatewayCounters()
        assertNull(counters.snapshot().cacheHitRate)
        counters.record(204, 0)
        assertNull(counters.snapshot().cacheHitRate)
    }

    @Test
    fun `reset clears every derived statistic`() {
        val counters = GatewayCounters()
        counters.record(200, 10, 10, 5, 5)
        counters.reset()

        val snapshot = counters.snapshot()
        assertEquals(0, snapshot.total)
        assertEquals(0, snapshot.success)
        assertEquals(0, snapshot.promptTokens)
        assertEquals(0, snapshot.cachedTokens)
        assertNull(snapshot.cacheHitRate)
    }
}
