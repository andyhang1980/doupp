package com.xposed.doupp.hook

import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils

/**
 * 消息防撤回 Hook
 *
 * Hook 消息撤回相关方法，阻止消息被撤回。
 * 拦截 onMessageRevoked/handleRevokeMessage 等方法，使其不执行。
 */
class AntiRevokeHook : BaseHook {

    @Volatile
    private var installed = false

    override fun tag(): String = "AntiRevokeHook"

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        // 注意：下面三个类名 / 四个方法名都是按经验猜的，40.7.0 全量扫描 0 命中
        // （R8 混淆后类名不带语义，也没有 DexKit 回退）。在拿到真实锚点之前默认关闭，
        // 避免出现「开关是开的、实际什么都没拦」的假开关。设置页开关保留，
        // 用户手动开启也只会多打一行 hooked=0。
        if (!DouSettings.isAntiRevokeEnabled()) {
            HookUtils.log("[${tag()}] 未启用，跳过（锚点为经验猜测，40.7.0 无命中，已默认关）")
            installed = true
            return
        }

        val targets = listOf(
            "com.ss.android.ugc.aweme.message.im.IMManager",
            "com.ss.android.ugc.aweme.message.MessageManager",
            "com.ss.android.ugc.aweme.message.im.MessageRevokeHandler"
        )

        val revokeMethods = listOf(
            "onMessageRevoked", "handleRevokeMessage",
            "revokeMessage", "processRevoke"
        )

        var hooked = 0
        for (className in targets) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                for (methodName in revokeMethods) {
                    for (m in clazz.declaredMethods.filter { it.name == methodName }) {
                        HookUtils.hookReplace(m) {
                            HookUtils.log("[${tag()}] 拦截撤回: $className.$methodName")
                            null
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
