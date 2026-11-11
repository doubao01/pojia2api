package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 静态守卫：私有 Composable 不得引用外层作用域变量。
 *
 * 本地没有 Android SDK，`:app` 无法编译，这类作用域错误只能由 CI 发现。
 * 这里用保守的文本分析提前拦住最常见的形态：私有函数体里出现只在
 * 调用方作用域可见的局部变量名，且既不是参数也不是函数内局部变量。
 */
class ComposableScopeTest {
    private val outerNames = listOf(
        "settings", "vms", "accounts", "models", "port", "running",
        "lanAddress", "accessKey", "stats", "entries", "state", "credits",
        "checks", "cooldowns", "groups", "customRelays", "scanned", "scanning",
        "transfer", "policies", "allModels", "enabledAccounts", "accountIds",
    )

    @Test
    fun `private composables do not capture outer local state`() {
        val root = findProjectRoot()
        val base = File(root, "app/src/main/kotlin/io/modelbridge/app/ui")
        assertTrue(base.isDirectory, "找不到 UI 目录：$base")

        val violations = mutableListOf<String>()
        base.listFiles { file -> file.extension == "kt" }.orEmpty().forEach { file ->
            val source = file.readText()
            for (match in Regex("""private fun (\w+)\s*\(""").findAll(source)) {
                val name = match.groupValues[1]
                val body = functionBody(source, match.range.last + 1) ?: continue
                val params = parameterList(source, match.range.last + 1)
                val locals = Regex("""\b(?:val|var)\s+(\w+)""")
                    .findAll(body)
                    .map { it.groupValues[1] }
                    .toSet()
                val captured = outerNames.filter { candidate ->
                    candidate !in params &&
                        candidate !in locals &&
                        // 只匹配独立标识符，避免把 group.accounts 当成 accounts
                        Regex("""(?<![\w.])$candidate\b""").containsMatchIn(body)
                }
                if (captured.isNotEmpty()) {
                    violations += "${file.name}#$name 捕获了外层变量：${captured.joinToString()}"
                }
            }
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    private fun parameterList(source: String, start: Int): Set<String> {
        var index = start
        var depth = 1
        val text = StringBuilder()
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return Regex("""(\w+)\s*:""")
                        .findAll(text)
                        .map { it.groupValues[1] }
                        .toSet()
                }
            }
            text.append(source[index])
            index++
        }
        return emptySet()
    }

    private fun functionBody(source: String, start: Int): String? {
        var index = start
        while (index < source.length && source[index] != '{') {
            if (source[index] == '=') return null // 表达式体，没有外层捕获风险
            index++
        }
        if (index >= source.length) return null
        val begin = index + 1
        var depth = 1
        index = begin
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        return source.substring(begin, (index - 1).coerceAtLeast(begin))
    }

    private fun findProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("找不到项目根目录")
    }
}
