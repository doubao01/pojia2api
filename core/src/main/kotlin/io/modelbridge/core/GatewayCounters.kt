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
 *
 * 计数口径：
 * - [Snapshot.total] 覆盖所有请求，含未归类状态。
 * - [Snapshot.success] 只统计 2xx。
 * - [Snapshot.failed] 统计 4xx/5xx 与连接失败（status 0）。
 * - 3xx 与 1xx 计入 total，但不计入 success 或 failed；
 *   [Snapshot.successRate] 因此以「已归类请求数」为分母，避免被这类请求稀释。
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
        /** 已归类（成功或失败）的请求数，成功率的分母。 */
        val classified: Long get() = success + failed

        val averageCostMillis: Long
            get() = if (costSamples == 0L) 0 else totalCostMillis / costSamples

        /** 成功率（0–100）。没有已归类请求时返回 null，界面显示「—」而不是编造 0%。 */
        val successRate: Double?
            get() = if (classified == 0L) null else success * 100.0 / classified

        /** 缓存命中率（0–100）。没有提示词用量时返回 null。 */
        val cacheHitRate: Double?
            get() = if (promptTokens > 0) cachedTokens * 100.0 / promptTokens else null

        val hasTraffic: Boolean get() = total > 0
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
            // 1xx/3xx 计入 total，不归入成功或失败
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
