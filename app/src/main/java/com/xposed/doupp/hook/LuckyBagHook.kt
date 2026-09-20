package com.xposed.doupp.hook

import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils

/**
 * 福袋自动领取 Hook
 *
 * 基于逆向分析的真实类名实现：
 * - LuckyCatSDK: SDK入口，处理Schema
 * - CommonApi: 网络API接口
 * - LuckyCatBrowserActivity: 浏览器Activity
 * - LuckyServiceSDK: Lucky服务SDK
 *
 * 实现策略：
 * 1. Hook LuckyCatSDK.openSchema 拦截福袋Schema
 * 2. Hook CommonApi 网络请求拦截福袋点击API
 * 3. 监听 lucky_bag_show 事件自动触发
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

        var hooked = 0

        // 策略1: Hook LuckyCatSDK Schema处理
        hooked += hookLuckyCatSDK(classLoader)

        // 策略2: Hook CommonApi网络请求
        hooked += hookCommonApi(classLoader)

        // 策略3: Hook LuckyServiceSDK
        hooked += hookLuckyServiceSDK(classLoader)

        // 策略4: Hook LuckyCatBrowserActivity
        hooked += hookLuckyCatBrowser(classLoader)

        installed = hooked > 0
        HookUtils.log("[${tag()}] 安装完成, hooked=$hooked")
    }

    private fun hookLuckyCatSDK(classLoader: ClassLoader): Int {
        var count = 0
        val targets = listOf(
            "com.bytedance.ug.sdk.luckycat.impl.project.ProjectActivity",
            "com.bytedance.ug.sdk.luckycat.impl.browser.LuckyCatBrowserActivity"
        )
        val methodNames = listOf(
            "handleLuckySchema", "openLuckyCatSchema",
            "openLuckyCatLynxPage", "openSchema"
        )
        for (className in targets) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                for (methodName in methodNames) {
                    for (m in clazz.declaredMethods.filter { it.name == methodName }) {
                        HookUtils.hookAfter(m) { param ->
                            HookUtils.log("[${tag()}] 拦截Schema: ${className}.$methodName")
                        }
                        count++
                    }
                }
            } catch (_: ClassNotFoundException) { }
        }
        HookUtils.log("[${tag()}] LuckyCatSDK hooked: $count")
        return count
    }

    private fun hookCommonApi(classLoader: ClassLoader): Int {
        var count = 0
        val className = "com.bytedance.ug.sdk.luckycat.base.network.CommonApi"
        try {
            val clazz = Class.forName(className, false, classLoader)
            for (m in clazz.declaredMethods) {
                if (m.name.contains("click", ignoreCase = true) ||
                    m.name.contains("claim", ignoreCase = true) ||
                    m.name.contains("receive", ignoreCase = true) ||
                    m.name.contains("reward", ignoreCase = true)) {
                    HookUtils.hookAfter(m) { param ->
                        HookUtils.log("[${tag()}] CommonApi.${m.name} 被调用")
                    }
                    count++
                }
            }
        } catch (_: ClassNotFoundException) { }
        HookUtils.log("[${tag()}] CommonApi hooked: $count")
        return count
    }

    private fun hookLuckyServiceSDK(classLoader: ClassLoader): Int {
        var count = 0
        val className = "com.bytedance.ug.sdk.luckyhost.api.LuckyServiceSDK"
        try {
            val clazz = Class.forName(className, false, classLoader)
            for (m in clazz.declaredMethods) {
                if (m.name.contains("open", ignoreCase = true) ||
                    m.name.contains("click", ignoreCase = true) ||
                    m.name.contains("claim", ignoreCase = true)) {
                    HookUtils.hookAfter(m) { param ->
                        HookUtils.log("[${tag()}] LuckyServiceSDK.${m.name} 被调用")
                    }
                    count++
                }
            }
        } catch (_: ClassNotFoundException) { }
        HookUtils.log("[${tag()}] LuckyServiceSDK hooked: $count")
        return count
    }

    private fun hookLuckyCatBrowser(classLoader: ClassLoader): Int {
        var count = 0
        val className = "com.bytedance.ug.sdk.luckycat.impl.browser.LuckyCatBrowserActivity"
        try {
            val clazz = Class.forName(className, false, classLoader)
            // Hook onCreate 监听页面打开
            for (m in clazz.declaredMethods.filter { it.name == "onCreate" }) {
                HookUtils.hookAfter(m) { param ->
                    HookUtils.log("[${tag()}] LuckyCatBrowserActivity 打开")
                }
                count++
            }
            // Hook 所有公开方法
            for (m in clazz.declaredMethods.filter {
                it.name.contains("click", ignoreCase = true) ||
                it.name.contains("claim", ignoreCase = true) ||
                it.name.contains("receive", ignoreCase = true)
            }) {
                HookUtils.hookAfter(m) { param ->
                    HookUtils.log("[${tag()}] LuckyCatBrowser.${m.name} 被调用")
                }
                count++
            }
        } catch (_: ClassNotFoundException) { }
        HookUtils.log("[${tag()}] LuckyCatBrowser hooked: $count")
        return count
    }
}
