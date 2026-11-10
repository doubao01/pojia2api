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

        val settings = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/SettingsScreen.kt").readText()
        val homeLayout = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/HomeScreen.kt").readText()
        val models = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/ModelsScreen.kt").readText()
        val logs = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/LogScreen.kt").readText()
        val viewModels = File(root, "app/src/main/kotlin/io/modelbridge/app/ViewModels.kt").readText()
        val webLogin = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/WebLoginDialog.kt").readText()
        val deviceLogin = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/DeviceLoginDialog.kt").readText()
        val policy = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/PolicyScreen.kt").readText()
        val runner = File(root, "app/src/main/kotlin/io/modelbridge/app/GatewayRunner.kt").readText()
        assertTrue(settings.contains("统计已重置"))
        assertTrue(settings.contains("已导入 "))
        assertTrue(homeLayout.contains("StatPairRow"))
        assertTrue(homeLayout.contains("leftLabel = \"累计请求\""))
        assertTrue(homeLayout.contains("rightLabel = \"成功率\""))
        assertTrue(homeLayout.contains("leftLabel = \"缓存命中率\""))
        assertTrue(homeLayout.contains("rightLabel = \"平均耗时\""))
        assertTrue(logs.contains("最近 "))
        assertTrue(logs.contains(" 条 · 新的在上"))
        assertTrue(logs.contains("暂无日志"))
        assertTrue(models.contains("还没有可用模型"))
        assertTrue(models.contains("已登录但没有拉到模型，打开「日志」看具体原因"))
        assertTrue(webLogin.contains("我已在网页登录完成"))
        assertTrue(webLogin.contains("还没读到登录信息，请先在网页里完成登录"))
        assertTrue(deviceLogin.contains("正在获取授权链接…"))
        assertTrue(viewModels.contains("在下方页面确认授权，确认后自动完成登录"))
        assertTrue(viewModels.contains("授权超时，请重新发起登录"))
        assertTrue(viewModels.contains("输入手机号获取验证码"))
        assertTrue(viewModels.contains("验证码已发送"))
        assertTrue(viewModels.contains("验证码不正确，请检查后重试"))
        assertTrue(policy.contains("按「映射后的实际上游模型 ID」精确匹配"))
        assertTrue(policy.contains("三个协议的注入位置不同：chat 作为独立 system 消息前置；responses 写进 instructions；messages 写进 system。客户端原有内容一律保留。"))
        assertTrue(policy.contains("策略正文只存在本机，不会上传。"))
        assertTrue(runner.contains("未注入策略："))
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
