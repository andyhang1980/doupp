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
        if (!DouSettings.isAntiRevokeEnabled()) {
            HookUtils.log("[$tag] 未启用，跳过")
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
                            HookUtils.log("[$tag] 拦截撤回: $className.$methodName")
                            null
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
