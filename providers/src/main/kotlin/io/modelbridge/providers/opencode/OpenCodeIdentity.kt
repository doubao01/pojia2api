package io.modelbridge.providers.opencode

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OpenCode 官方 CLI 会话标识派生。
 *
 * 上游用一组会话头识别客户端，缺了会判为非法请求。官方 CLI 的 id 全部由
 * 一个 seed 单向哈希派生，不是随机 UUID：
 *
 * ```
 * sessionId = "ses_" + hex(h[0..6] 变形) + base62(h[6..22], 14 位)
 * projectId = hex(SHA256("opencode-project\0" + seed))[0..20]
 * ```
 *
 * 其中 `h = SHA256("ses\0" + seed)`。会话 id 固定 30 字符：`ses_` + 12 hex + 14 base62。
 *
 * 派生而不是随机：同一 seed 恒得同一组 id，多账号之间天然隔离，也不会因为
 * 每次请求换 id 而暴露「每次都是一次性会话」的非人类形态。
 */
object OpenCodeIdentity {

    const val INFERENCE_BASE = "https://opencode.ai/inference"

    /** 官方 CLI 的 UA：`opencode/latest/<版本>/cli`。 */
    const val USER_AGENT = "opencode/latest/2.0.29/cli"

    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val random = SecureRandom()

    /** 24 位小写 hex 的随机 seed，与官方 CLI 的缺省 seed 形态一致。 */
    fun newSeed(): String = buildString(24) {
        repeat(24) { append("0123456789abcdef"[random.nextInt(16)]) }
    }

    /** `ses_` + 12 hex + 14 base62，共 30 字符。 */
    fun sessionId(seed: String): String {
        val digest = sha256("ses\u0000$seed")
        val head = buildString(12) {
            val value = ((digest[0].toLong() and 0xFF) shl 28) or
                ((digest[1].toLong() and 0xFF) shl 20) or
                ((digest[2].toLong() and 0xFF) shl 12) or
                ((digest[3].toLong() and 0xFF) shl 4) or
                ((digest[4].toLong() and 0xFF) shr 4)
            // 取反后保留低 48 位，得到 UUIDv7 的前 6 字节
            val shaped = ((value shl 12) or 1L).inv() and 0xFFFFFFFFFFFFL
            for (shift in intArrayOf(40, 32, 24, 16, 8, 0)) {
                append("%02x".format(((shaped shr shift) and 0xFF).toInt()))
            }
        }
        // 后 16 字节按 base62 编码成 14 位，低位先出，最后反转
        var number = java.math.BigInteger(1, digest.copyOfRange(6, 22))
        val base = java.math.BigInteger.valueOf(62)
        val chars = CharArray(14)
        for (index in 13 downTo 0) {
            val result = number.divideAndRemainder(base)
            chars[index] = BASE62[result[1].toInt()]
            number = result[0]
        }
        return "ses_$head${String(chars)}"
    }

    /** `SHA256("opencode-project\0" + seed)` 前 20 字节的 hex。 */
    fun projectId(seed: String): String =
        sha256("opencode-project\u0000$seed").copyOfRange(0, 20)
            .joinToString("") { "%02x".format(it) }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
