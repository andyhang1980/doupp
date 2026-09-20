package com.xposed.doupp.hook

import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils

/**
 * 福袋自动领取 Hook
 *
 * Hook 直播间福袋相关方法，自动领取福袋奖励。
 * 让福袋可用检查返回 true，触发自动领取逻辑。
 */
class LuckyBagHook : BaseHook {

    @Volatile
    private var installed = false

    override fun tag(): String = "LuckyBagHook"

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (!DouSettings.isLuckyBagEnabled()) {
            HookUtils.log("[${tag()}] 未启用，跳过")
            return
        }

        val targets = listOf(
            "com.ss.android.ugc.aweme.live.luckybag.LuckyBagManager",
            "com.ss.android.ugc.aweme.live.luckybag.LuckyBagViewModel",
            "com.ss.android.ugc.aweme.live.luckybag.LuckyBagHelper"
        )

        val trueMethods = listOf(
            "isLuckyBagAutoClaimEnabled", "shouldAutoClaim",
            "isLuckyBagAvailable", "canClaimLuckyBag"
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

        installed = hooked > 0
        HookUtils.log("[${tag()}] 安装完成, hooked=$hooked")
    }
}
