package io.modelbridge.providers.workbuddy

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * WorkBuddy 官方客户端身份构造。
 *
 * 对齐官方桌面端（国内版 5.8.3）的出站形态：
 * - 会话头族（X-Conversation-ID / -Request-ID / -Root-Request-ID / -Message-ID）
 *   缺任何一个都会被上游渠道校验拦下；
 * - 请求/消息 id 是 32 位无连字符 hex，会话 id 是带连字符的 UUID；
 * - UA 末段是客户端内置 CLI 的版本，不是桌面端自己的版本。
 *
 * 无状态：所有 id 按调用现算，不缓存。会话 id 由调用方传入以保持同一会话恒定。
 */
object WorkBuddyIdentity {

    /** 官方国内版桌面端版本。 */
    const val DESKTOP_VERSION_CN = "5.8.3"

    /** 官方国内版桌面端 UA：桌面端版本 + 内置 CLI 版本。 */
    const val DESKTOP_UA_CN = "WorkBuddy/5.8.3 WorkBuddy/5.8.3 CLI/2.161.0"

    const val CHAT_BASE_CN = "https://copilot.tencent.com"
    const val BILL_BASE_CN = "https://www.codebuddy.cn"
    const val DOMAIN_CN = "www.codebuddy.cn"

    const val CHAT_BASE_GLOBAL = "https://www.workbuddy.ai"
    const val DOMAIN_GLOBAL = "www.workbuddy.ai"

    private val random = SecureRandom()

    /** RFC 9562 UUIDv7：48 位毫秒时间戳打头，后接随机位。 */
    fun uuid7(): String {
        val ms = System.currentTimeMillis() and ((1L shl 48) - 1)
        val randA = random.nextInt(1 shl 12).toLong()
        val randB = random.nextLong() ushr 2
        val most = (ms shl 16) or (0x7L shl 12) or randA
        val least = (0b10L shl 62) or randB
        return UUID(most, least).toString()
    }

    /** 32 位无连字符 hex（UUIDv7 去连字符），官方请求/消息 id 的形态。 */
    fun messageId(): String = uuid7().replace("-", "")

    /** 链路头族：traceparent / b3 / X-B3-* / X-Trace-ID。 */
    fun traceHeaders(withParent: Boolean): Map<String, String> {
        val trace = messageId()
        val span = tokenHex(8)
        val headers = linkedMapOf(
            "X-Trace-ID" to trace,
            "traceparent" to "00-$trace-$span-01",
            "X-B3-TraceId" to trace,
            "X-B3-SpanId" to span,
            "X-B3-Sampled" to "1",
        )
        headers["b3"] = if (withParent) {
            val parent = tokenHex(8)
            headers["X-B3-ParentSpanId"] = parent
            "$trace-$span-1-$parent"
        } else {
            "$trace-$span-1"
        }
        return headers
    }

    /**
     * 按账号稳定派生机器标识。
     *
     * 官方客户端一个进程就是一台设备，但网关是一个进程冒充多个账号，
     * 所以必须按账号派生——照官方在进程维度取一次会让所有账号共用同一个值，
     * 反而把它们关联到同一台机器上。
     */
    fun machineId(uid: String): String = derivedUuid(uid, "machine")

    fun sessionId(uid: String): String = derivedUuid(uid, "session")

    private fun derivedUuid(uid: String, salt: String): String {
        val seed = "$salt:${uid.ifBlank { "anonymous" }}"
        val digest = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        // 版本位与变体位固定成 UUIDv4 的形状，与真实客户端的 machineId 一致
        val shaped = hex.substring(0, 12) + "4" + hex.substring(13, 16) + "a" + hex.substring(17)
        return "${shaped.substring(0, 8)}-${shaped.substring(8, 12)}-${shaped.substring(12, 16)}-" +
            "${shaped.substring(16, 20)}-${shaped.substring(20, 32)}"
    }

    private fun tokenHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    /** 国内版为 cn，其余按国际版处理。 */
    fun isGlobal(region: String, domain: String): Boolean {
        val r = region.trim().lowercase()
        if (r == "global") return true
        val d = domain.trim().lowercase()
        return d.contains("workbuddy.ai") || d.contains("codebuddy.ai")
    }
}
