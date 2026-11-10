package io.modelbridge.core

import java.util.concurrent.atomic.AtomicLong

/**
 * 网关累计统计。
 *
 * 与访问日志分离：日志按 [logLimit] 裁剪后，累计请求、成功率、耗时、
 * token 与缓存命中率仍保留，直到用户主动「重置统计」。
 */
class GatewayCounters {
    private val total = AtomicLong()
    private val success = AtomicLong()
    private val failed = AtomicLong()
    private val costMillis = AtomicLong()
    private val costSamples = AtomicLong()
    private val promptTokens = AtomicLong()
    private val completionTokens = AtomicLong()
    private val cachedTokens = AtomicLong()

    data class Snapshot(
        val total: Long = 0,
        val success: Long = 0,
        val failed: Long = 0,
        val averageCostMillis: Long = 0,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val cachedTokens: Long = 0,
    ) {
        val successRate: Double get() = if (total == 0L) 0.0 else success * 100.0 / total
        val cacheHitRate: Double?
            get() = if (promptTokens > 0) cachedTokens * 100.0 / promptTokens else null
    }

    fun record(
        status: Int,
        costMillis: Long = 0,
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        cachedTokens: Int = 0,
    ) {
        total.incrementAndGet()
        when {
            status in 200..299 -> success.incrementAndGet()
            status >= 400 || status == 0 -> failed.incrementAndGet()
        }
        if (costMillis > 0) {
            this.costMillis.addAndGet(costMillis)
            costSamples.incrementAndGet()
        }
        this.promptTokens.addAndGet(promptTokens.toLong())
        this.completionTokens.addAndGet(completionTokens.toLong())
        this.cachedTokens.addAndGet(cachedTokens.toLong())
    }

    fun snapshot(): Snapshot {
        val requests = total.get()
        val samples = costSamples.get()
        return Snapshot(
            total = requests,
            success = success.get(),
            failed = failed.get(),
            averageCostMillis = if (samples == 0L) 0 else costMillis.get() / samples,
            promptTokens = promptTokens.get(),
            completionTokens = completionTokens.get(),
            cachedTokens = cachedTokens.get(),
        )
    }

    fun reset() {
        total.set(0)
        success.set(0)
        failed.set(0)
        costMillis.set(0)
        costSamples.set(0)
        promptTokens.set(0)
        completionTokens.set(0)
        cachedTokens.set(0)
    }
}
