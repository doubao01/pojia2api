package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 3.10.0 dex 中可直接验证的高频状态文案，防止源码重构时丢失。 */
class UiCopyContractTest {
    @Test
    fun `status copy from 3_10_0 dex remains in the app source`() {
        val root = findProjectRoot()
        val ui = File(root, "app/src/main/kotlin/io/modelbridge/app/ui").walkTopDown()
            .filter { it.extension == "kt" }
            .sumOf { it.readText().length }
        val source = File(root, "app/src/main/kotlin/io/modelbridge/app").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertTrue(ui > 0)
        listOf(
            "一键完成",
            "签到成功",
            "签到失败",
            "额度查询失败",
            "没有可用任务",
            "任务列表拉取失败",
            "配置已导出",
            "正在扫描",
            "正在检测",
            "探测 127.0.0.1 端口…",
            "每日自动检测一次账号可用性",
            "按供应商一键签到与领取任务",
        ).forEach { expected ->
            assertTrue(source.contains(expected), "Missing 3.10.0 UI copy: $expected")
        }
    }

    private fun findProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }
}
