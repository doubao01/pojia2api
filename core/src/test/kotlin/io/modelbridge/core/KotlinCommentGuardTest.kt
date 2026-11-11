package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 静态守卫：块注释内不得再出现斜杠加星号的开注释序列。
 *
 * Kotlin 的块注释允许嵌套，但一旦 KDoc 正文里写了带斜杠星号的路径片段，
 * 其中的闭注释序列会提前结束注释，把后续代码变成注释内容，编译只会报
 * 「Unclosed comment」。本地没有 Android SDK 时这类错误只能由 CI 发现，
 * 这里提前拦住。
 */
class KotlinCommentGuardTest {
    @Test
    fun `no comment closes early through a slash star sequence`() {
        val root = findProjectRoot()
        val offenders = mutableListOf<String>()
        modules.forEach { module ->
            File(root, module).walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    // 先剥离字符串字面量，`"*/*"` 这类 MIME 通配符不是注释。
                    val source = stripStringLiterals(file.readText())
                    blockComments(source).forEach { comment ->
                        // 注释内部若出现新的开注释序列，说明存在嵌套误写
                        if (comment.substring(2, comment.length - 2).contains("/*")) {
                            offenders += "${file.relativeTo(root)}: 块注释内出现开注释序列"
                        }
                    }
                }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    /**
     * 逐个提取块注释区间，按 Kotlin 的嵌套规则计数。
     *
     * 只有真正嵌套的开注释序列才会被计入；相邻两段独立注释不会被误判。
     */
    private fun blockComments(source: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < source.length - 1) {
            if (source[index] == SLASH && source[index + 1] == STAR) {
                val start = index
                var depth = 1
                index += 2
                while (index < source.length - 1 && depth > 0) {
                    when {
                        source[index] == SLASH && source[index + 1] == STAR -> {
                            depth++
                            index += 2
                        }
                        source[index] == STAR && source[index + 1] == SLASH -> {
                            depth--
                            index += 2
                        }
                        else -> index++
                    }
                }
                result += source.substring(start, index)
            } else {
                index++
            }
        }
        return result
    }

    /** 把字符串字面量替换成等长空白，保留偏移且不误判其中的符号。 */
    private fun stripStringLiterals(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val c = source[index]
            if (c == '"' || c == '\'') {
                val quote = c
                out.append(' ')
                index++
                while (index < source.length) {
                    val inner = source[index]
                    out.append(if (inner == '\n') '\n' else ' ')
                    index++
                    if (inner == '\\' && index < source.length) {
                        out.append(' ')
                        index++
                        continue
                    }
                    if (inner == quote) break
                }
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }

    private fun findProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("找不到项目根目录")
    }

    private val modules = listOf(
        "core/src",
        "providers/src",
        "app/src",
        "spi/src",
    )

    private companion object {
        const val SLASH = '/'
        const val STAR = '*'
    }
}
