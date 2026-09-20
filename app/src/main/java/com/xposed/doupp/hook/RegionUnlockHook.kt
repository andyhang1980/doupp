package com.xposed.doupp.hook

import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils

/**
 * 地区限制解锁 Hook
 *
 * Hook 地区检查相关方法，解除抖音地区限制。
 * 返回 false 让服务端认为当前地区不受限制。
 */
class RegionUnlockHook : BaseHook {

    @Volatile
    private var installed = false

    override fun tag(): String = "RegionUnlockHook"

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (!DouSettings.isRegionUnlockEnabled()) {
            HookUtils.log("[${tag()}] 未启用，跳过")
            return
        }

        val targets = listOf(
            "com.ss.android.ugc.aweme.region.RegionCheck",
            "com.ss.android.ugc.aweme.geo.GeoRestrictManager",
            "com.ss.android.ugc.aweme.feed.model.FeedItem"
        )

        val methodNames = listOf("isRestricted", "isBlocked", "checkRegion", "isGeoRestricted")

        var hooked = 0
        for (className in targets) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                for (methodName in methodNames) {
                    for (m in clazz.declaredMethods.filter { it.name == methodName }) {
                        HookUtils.hookAfter(m) { param ->
                            if (param.result == true) {
                                HookUtils.log("[${tag()}] $className.$methodName: true -> false")
                                param.result = false
                            }
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
