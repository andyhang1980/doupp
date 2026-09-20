package com.xposed.doupp.hook

import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils

/**
 * 自动签到 Hook
 *
 * Hook 每日签到相关方法，自动完成签到任务。
 * 让签到检查返回 true，触发自动签到逻辑。
 */
class AutoSignInHook : BaseHook {

    @Volatile
    private var installed = false

    override fun tag(): String = "AutoSignInHook"

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (!DouSettings.isAutoSignInEnabled()) {
            HookUtils.log("[$tag] 未启用，跳过")
            return
        }

        val targets = listOf(
            "com.ss.android.ugc.aweme.signin.SignInManager",
            "com.ss.android.ugc.aweme.task.DailyTaskManager",
            "com.ss.android.ugc.aweme.task.SignInHelper"
        )

        val trueMethods = listOf(
            "isAutoSignInEnabled", "shouldAutoSignIn",
            "isSignedToday", "hasSignedIn", "isSignInAvailable"
        )

        var hooked = 0
        for (className in targets) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                for (methodName in trueMethods) {
                    for (m in clazz.declaredMethods.filter { it.name == methodName }) {
                        HookUtils.hookReplace(m) {
                            HookUtils.log("[$tag] $className.$methodName -> true")
                            true
                        }
                        hooked++
                    }
                }
            } catch (_: ClassNotFoundException) { }
        }

        installed = hooked > 0
        HookUtils.log("[$tag] 安装完成, hooked=$hooked")
    }
}
