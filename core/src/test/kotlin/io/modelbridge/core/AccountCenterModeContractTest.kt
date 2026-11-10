package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 首页两个入口必须保持独立目标：账号管理进入 Accounts 模式，任务管理进入 Tasks 模式。
 * Compose 页面不能直接跑 JVM 测试，这里用源码静态证据防止两个入口再次合并成同一页。
 */
class AccountCenterModeContractTest {
    @Test
    fun `home entries open different account center modes`() {
        val root = findProjectRoot()
        val main = File(root, "app/src/main/kotlin/io/modelbridge/app/MainActivity.kt").readText()
        val screen = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/TaskQuotaScreen.kt").readText()
        val home = File(root, "app/src/main/kotlin/io/modelbridge/app/ui/HomeScreen.kt").readText()

        assertTrue(main.contains("AccountCenterMode.Accounts"))
        assertTrue(main.contains("AccountCenterMode.Tasks"))
        assertTrue(screen.contains("enum class AccountCenterMode"))
        assertTrue(screen.contains("AccountCenterMode.Tasks -> TaskCenter"))
        assertTrue(screen.contains("AccountCenterMode.Accounts -> AccountCenter"))
        assertTrue(screen.contains("\"按供应商一键签到与领取任务\""))
        assertTrue(screen.contains("\"每日自动检测一次账号可用性\""))
        assertTrue(home.contains("\"账号管理\""))
        assertTrue(home.contains("\"任务管理\""))
        assertTrue(home.contains("\"签到、任务、额度一目了然\""))
        assertTrue(screen.contains("vms.checkAccountsDaily()"))
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
