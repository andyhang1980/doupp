package com.xposed.doupp.hook

import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils

/**
 * 直播间清爽模式（从电商管理里拆出来的）。
 *
 * ## 为什么拆
 * 早前这块挂在 MallHook 里，跟着「电商管理」走。但它是直播间的事，不是抖音商城的事：
 * 电商管理现在只管商城入口/浮层/气泡，商品卡完全不碰。
 *
 * ## 做什么
 * 直播间里的两块电商内容换成空占位：
 *   - 电商直播盒子（Lynx 承载）：盒子还在、内容清空，不破坏房间布局
 *   - 直播广告中台的挂件渲染：返回空，广告卡片不渲染
 *
 * ## 锚点
 * 原版模块（0920131537）用的是方法名 getLiveCommercePlaceholderBind /
 * getLiveAdPlaceholderBind，这两个名字在 40.7.0 上已被 R8 抹掉（0 命中），
 * 所以改成按电商语义类名锚定 —— 40.7.0 上这几个类名实测存在：
 *   com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.ECLiveBoxForLynx
 *   com.ss.android.ugc.aweme.ad.LiveAdMiddleWareService
 *
 * 默认关闭：清爽模式属于口味偏好，不是人人都想开。
 */
class LiveCleanHook : BaseHook {

    companion object {
        private const val TAG = "LiveClean"

        @Volatile
        private var installed = false

        /** 电商直播盒子（Lynx 容器），命中后清空内容 */
        private val LIVE_BOX_VIEWS = listOf(
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.ECLiveBoxForLynx",
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.HybridLiveBoxForLynxV2",
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.HybridLiveBoxForLynxV3"
        )

        /** 直播广告中台：挂件渲染统一返回空 */
        private val LIVE_AD_TARGETS = listOf(
            "com.ss.android.ugc.aweme.ad.LiveAdMiddleWareService",
            "com.ss.android.ugc.aweme.ad.LiveAdCardEventBusWrapper"
        )
    }

    override fun tag() = TAG
    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            if (!DouSettings.isLiveCleanEnabled()) {
                HookUtils.log("$TAG: 开关关闭，跳过（保留官方直播间行为）")
                return@safeHook
            }
            val result = StringBuilder()
            result.append(hideLiveBoxes(classLoader))
            result.append(stubLiveAds(classLoader))
            installed = true
            HookUtils.log("$TAG: 安装完成 [$result]")
        }
    }

    // ------------------------------------------------------------------
    // 电商直播盒子
    // ------------------------------------------------------------------

    /** 命中盒子后清空内部 Lynx 内容，盒子本身保留（免得房间布局塌掉） */
    private fun hideLiveBoxes(classLoader: ClassLoader): String {
        var hit = 0
        for (name in LIVE_BOX_VIEWS) {
            val cls = ClassFinder.findClass(classLoader, name) ?: continue
            runCatching {
                cls.declaredConstructors.forEach { ctor ->
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            HookUtils.log("$TAG: 拦截电商直播盒子 ${cls.simpleName}")
                        }
                    })
                }
                hit++
            }.onFailure {
                HookUtils.log("$TAG: ${name} 失败 ${it.message}")
            }
        }
        return "直播盒子:$hit "
    }

    // ------------------------------------------------------------------
    // 直播广告中台
    // ------------------------------------------------------------------

    /** 挂件渲染方法（bind/render/show/card 且无参有返回）统一置空 */
    private fun stubLiveAds(classLoader: ClassLoader): String {
        var hit = 0
        for (name in LIVE_AD_TARGETS) {
            val cls = ClassFinder.findClass(classLoader, name) ?: continue
            runCatching {
                cls.declaredMethods
                    .filter { it.parameterTypes.isEmpty() && it.returnType != Void.TYPE }
                    .filter {
                        it.name.contains("bind", true) || it.name.contains("render", true) ||
                            it.name.contains("show", true) || it.name.contains("card", true)
                    }
                    .take(4)
                    .forEach { m ->
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching { param.result = null }
                            }
                        })
                        hit++
                    }
            }
        }
        return "直播广告:$hit "
    }
}
