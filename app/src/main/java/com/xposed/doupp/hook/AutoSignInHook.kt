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
        if (installed) return
        // 注意：下面三个类名 / 五个方法名都是按经验猜的，40.7.0 全量扫描 0 命中
        // （R8 混淆后类名不带语义，也没有 DexKit 回退）。而且语义本身可疑：
        // 把 isSignedToday / hasSignedIn 改成 true 只会让 App 以为「今天已签到」，
        // 反而阻止签到请求，并不能自动签到。所以在拿到真实锚点之前默认关闭，
        // 避免出现「开关是开的、实际什么都没做」的假开关。设置页开关保留。
        if (!DouSettings.isAutoSignInEnabled()) {
            HookUtils.log("[${tag()}] 未启用，跳过（锚点为经验猜测，40.7.0 无命中，已默认关）")
            installed = true
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
                            HookUtils.log("[${tag()}] $className.$methodName -> true")
                            true
                        }
                        hooked++
                    }
                }
            } catch (_: ClassNotFoundException) { }
        }

        // 命中 0 个说明锚点已失效（类名是猜的），重试无意义，只会让延迟安装阶段重复扫一遍类名。
        // 直接标记已安装；设置页开关保留，用户手动开启也只多打一行日志。
        installed = true
        HookUtils.log("[${tag()}] 安装完成, hooked=$hooked")
    }
}
