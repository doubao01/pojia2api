package io.modelbridge.core

/** 访问日志与统计诊断的脱敏工具；不记录请求正文、凭据或策略正文。 */
object LogRedactor {
    private const val MAX_LENGTH = 240
    private const val REDACTED = "<redacted>"

    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val cookieHeader = Regex("(?i)\\bCookie\\s*:\\s*[^;\\r\\n]+")
    private val cookieToken = Regex("(?i)\\b(sid|token|jwt|auth|sessionid)=[^\\s,;&]+")
    private val sensitiveKey = Regex(
        """(?i)"?\b(authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|session|password|secret|instructions?|input[-_]?text|input|output[-_]?text|text|reasoning|system|token|content|messages|prompt)\b"?\s*[:=]\s*""",
    )

    fun redact(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        value = bearer.replace(value, "Bearer $REDACTED")
        value = cookieHeader.replace(value) { match ->
            match.value.substringBefore(':') + ": $REDACTED"
        }
        value = redactFields(value)
        value = bearer.replace(value, "Bearer $REDACTED")
        value = cookieToken.replace(value) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
        if (value.length > MAX_LENGTH) {
            value = value.take(MAX_LENGTH) + "…"
        }
        return value
    }

    /** 按字段名定位敏感值；字符串、数组、对象或普通 token 都整体替换。 */
    private fun redactFields(text: String): String {
        val out = StringBuilder(text.length)
        var cursor = 0
        for (match in sensitiveKey.findAll(text)) {
            if (match.range.first < cursor) continue
            out.append(text, cursor, match.range.first)
            val start = match.range.last + 1
            val end = if (start >= text.length) text.length else sensitiveValueEnd(text, start)
            out.append(match.groupValues[1]).append('=').append(REDACTED)
            cursor = end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun sensitiveValueEnd(text: String, start: Int): Int = when (text[start]) {
        '"', '\'' -> quotedEnd(text, start)
        '[', '{' -> structuredEnd(text, start)
        else -> plainEnd(text, start)
    }

    private fun plainEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] !in " \t,;&") i++
        if (i < text.length && text[i].isWhitespace() &&
            text.substring(start, i).equals("Bearer", ignoreCase = true)
        ) {
            i++
            while (i < text.length && text[i] !in " \t,;&") i++
        }
        return i
    }

    private fun quotedEnd(text: String, start: Int): Int {
        val quote = text[start]
        var escaped = false
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == quote) {
                return i + 1
            }
            i++
        }
        return text.length
    }

    private fun structuredEnd(text: String, start: Int): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == quote) {
                    quote = null
                }
                i++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth <= 0) return i + 1
                }
            }
            i++
        }
        return text.length
    }
}
