package io.modelbridge.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val statsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 网关累计统计。
 *
 * 与访问日志分离：日志按 logLimit 裁剪后，累计请求、成功率、耗时、
 * token、缓存命中率与分上游计数仍保留，直到用户主动「重置统计」。
 * 快照可序列化到 SharedPreferences，实现跨进程重启保留。
 */
class GatewayCounters {
    private val total = AtomicLong()
    private val success = AtomicLong()
    private val failed = AtomicLong()
    private val totalCostMillis = AtomicLong()
    private val costSamples = AtomicLong()
    private val promptTokens = AtomicLong()
    private val completionTokens = AtomicLong()
    private val cachedTokens = AtomicLong()
    private val byUpstream = ConcurrentHashMap<String, AtomicLong>()

    @Serializable
    data class Snapshot(
        val total: Long = 0,
        val success: Long = 0,
        val failed: Long = 0,
        val totalCostMillis: Long = 0,
        val costSamples: Long = 0,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val cachedTokens: Long = 0,
        val byUpstream: Map<String, Long> = emptyMap(),
    ) {
        val averageCostMillis: Long
            get() = if (costSamples == 0L) 0 else totalCostMillis / costSamples
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
        upstreamId: String? = null,
    ) {
        total.incrementAndGet()
        when {
            status in 200..299 -> success.incrementAndGet()
            status >= 400 || status == 0 -> failed.incrementAndGet()
        }
        if (costMillis > 0) {
            totalCostMillis.addAndGet(costMillis)
            costSamples.incrementAndGet()
        }
        this.promptTokens.addAndGet(promptTokens.toLong())
        this.completionTokens.addAndGet(completionTokens.toLong())
        this.cachedTokens.addAndGet(cachedTokens.toLong())
        upstreamId?.takeIf { it.isNotBlank() }?.let { key ->
            byUpstream.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        total = total.get(),
        success = success.get(),
        failed = failed.get(),
        totalCostMillis = totalCostMillis.get(),
        costSamples = costSamples.get(),
        promptTokens = promptTokens.get(),
        completionTokens = completionTokens.get(),
        cachedTokens = cachedTokens.get(),
        byUpstream = byUpstream.mapValues { (_, value) -> value.get() },
    )

    /** 从持久化快照恢复，供应用启动时调用。 */
    fun restore(snapshot: Snapshot) {
        total.set(snapshot.total)
        success.set(snapshot.success)
        failed.set(snapshot.failed)
        totalCostMillis.set(snapshot.totalCostMillis)
        costSamples.set(snapshot.costSamples)
        promptTokens.set(snapshot.promptTokens)
        completionTokens.set(snapshot.completionTokens)
        cachedTokens.set(snapshot.cachedTokens)
        byUpstream.clear()
        snapshot.byUpstream.forEach { (key, value) ->
            byUpstream[key] = AtomicLong(value)
        }
    }

    fun encode(): String = statsJson.encodeToString(snapshot())

    /** 损坏存档按空统计处理，不阻断启动。 */
    fun decode(raw: String): Snapshot = runCatching {
        statsJson.decodeFromString<Snapshot>(raw)
    }.getOrDefault(Snapshot())

    fun reset() {
        total.set(0)
        success.set(0)
        failed.set(0)
        totalCostMillis.set(0)
        costSamples.set(0)
        promptTokens.set(0)
        completionTokens.set(0)
        cachedTokens.set(0)
        byUpstream.clear()
    }
}
