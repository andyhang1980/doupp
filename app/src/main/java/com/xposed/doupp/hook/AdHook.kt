package com.xposed.doupp.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 广告屏蔽 Hook（模块化开关控制）
 *
 * 各功能独立开关，均受总开关 remove_ad 控制：
 * 1. 跳过开屏广告 (skip_splash_ad): Hook Activity.onCreate，对含 splash/ad 的 Activity 直接 finish
 * 2. 信息流关键词屏蔽 (block_feed_keywords): 周期扫描 TextView，匹配屏蔽词隐藏整个卡片
 * 3. 屏蔽购物视频 (block_shopping): 检测购物车/商品标签并隐藏整个卡片
 * 4. 隐藏广告标签 (hide_ad_labels): 隐藏视频上的"广告""赞助"等标签角标
 * 5. 拦截广告SDK (block_ad_sdk): Hook 广告 SDK 初始化方法，阻止广告加载
 */
class AdHook : BaseHook {

    companion object {
        private const val TAG = "AdHook"
        private var installed = false
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile
        private var scanning = false
        @Volatile
        private var currentScanActivity: Activity? = null

        /** 广告标签关键词（视频左下角的小字标签） */
        private val AD_LABEL_KEYWORDS = setOf(
            "广告", "广告主", "赞助", "推广", "广告推广",
            "商业推广", "合作推广", "品牌广告", "信息流广告"
        )

        /** 广告SDK类名特征（用于拦截初始化） */
        private val AD_SDK_PATTERNS = setOf(
            // 穿山甲 / Pangle
            "com.bytedance.sdk.openadsdk",
            "com.pangle.sdk",
            // 广点通 / GDT
            "com.qq.e.ads",
            "com.qq.e.comm",
            // 快手广告
            "com.kuaishou.mob",
            "com.kwad.sdk",
            // 百度广告
            "com.baidu.mobads",
            // 通用
            "AdView", "AdManager", "AdLoader", "AdService",
            "InterstitialAd", "RewardAd", "SplashAd", "BannerAd",
            "NativeAd", "FeedAd", "FullScreenAd"
        )

        /** 广告SDK初始化方法名 */
        private val AD_SDK_INIT_METHODS = setOf(
            "init", "initWithConfig", "initAdSdk", "initSDK",
            "loadAd", "preloadAd", "showAd", "requestAd",
            "registerAd", "setup", "configure"
        )
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)

            // === 功能1: 跳过开屏广告 ===
            if (DouSettings.isSkipSplashAdEnabled()) {
                XposedBridge.hookAllMethods(activityClass, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!DouSettings.isSkipSplashAdEnabled()) return
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name.lowercase()
                        val isAd = name.contains("splash") ||
                                name.matches(Regex(".*\\bad\\b.*")) ||
                                name.contains("launch") && name.contains("ad")
                        if (isAd) {
                            mainHandler.postDelayed({
                                try {
                                    activity.finish()
                                    HookUtils.log("$TAG: [开屏广告] 跳过: ${activity.javaClass.name}")
                                } catch (_: Throwable) {}
                            }, 200)
                        }
                    }
                })
            }

            // === 功能5: 拦截广告SDK初始化 ===
            if (DouSettings.isBlockAdSdkEnabled()) {
                hookAdSdkInit(classLoader)
            }

            // === 信息流扫描（功能2/3/4共用） ===
            XposedBridge.hookAllMethods(activityClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isFeedActivity(activity)) return
                    if (currentScanActivity !== activity) {
                        currentScanActivity = activity
                        scanning = true
                        mainHandler.postDelayed({ scanLoop(activity) }, 800)
                        HookUtils.log("$TAG: 启动信息流广告扫描")
                    }
                }
            })

            installed = true
            HookUtils.log("$TAG: Hook 已安装 (skipSplash=${DouSettings.isSkipSplashAdEnabled()}, feedKw=${DouSettings.isBlockFeedKeywordsEnabled()}, shopping=${DouSettings.isBlockShoppingEnabled()}, adLabel=${DouSettings.isHideAdLabelsEnabled()}, adSdk=${DouSettings.isBlockAdSdkEnabled()})")
        }
    }

    private fun hookAdSdkInit(classLoader: ClassLoader) {
        // 尝试Hook常见的广告SDK类
        for (pkg in AD_SDK_PATTERNS) {
            try {
                val clazz = XposedHelpers.findClass(pkg, classLoader)
                // Hook所有public方法，拦截疑似初始化/加载方法
                for (method in clazz.declaredMethods) {
                    if (AD_SDK_INIT_METHODS.any { method.name.lowercase().contains(it.lowercase()) }) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!DouSettings.isBlockAdSdkEnabled()) return
                                HookUtils.log("$TAG: [广告SDK] 拦截 ${clazz.simpleName}.${method.name}")
                                param.result = null
                            }
                        })
                        HookUtils.log("$TAG: [广告SDK] 已Hook: ${clazz.name}.${method.name}")
                    }
                }
            } catch (_: Throwable) {
                // 类不存在，跳过
            }
        }

        // 额外Hook通用的 ad 相关类（通过ClassLoader搜索）
        try {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", classLoader),
                "currentActivityThread"
            )
            val mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication")
            val info = XposedHelpers.getObjectField(mBoundApplication, "appInfo") as? android.content.pm.ApplicationInfo
            val pkgName = info?.packageName ?: return

            // 对目标进程中的所有类进行扫描（仅对抖音主进程生效）
            if (pkgName == "com.ss.android.ugc.aweme") {
                HookUtils.log("$TAG: [广告SDK] 目标进程: $pkgName，准备扫描广告相关类")
            }
        } catch (_: Throwable) {}
    }

    private fun isFeedActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name.lowercase()
        return name.contains("main") || name.contains("home") || name.contains("feed")
    }

    private fun scanLoop(activity: Activity) {
        if (currentScanActivity != null && currentScanActivity !== activity) {
            HookUtils.log("$TAG: Activity 已切换，停止旧扫描")
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            scanning = false
            currentScanActivity = null
            HookUtils.log("$TAG: Activity 已销毁，停止扫描")
            return
        }

        if (!isFeedActivity(activity)) {
            scanning = false
            currentScanActivity = null
            HookUtils.log("$TAG: 离开播放页，停止扫描")
            return
        }

        // 侧边栏打开时停止扫描，避免误杀菜单项
        if (isSidePanelOpen(activity)) {
            HookUtils.log("$TAG: 侧边栏已打开，跳过本次扫描")
            mainHandler.postDelayed({ scanLoop(activity) }, 700)
            return
        }

        val shouldScan = DouSettings.isBlockFeedKeywordsEnabled() ||
                DouSettings.isBlockShoppingEnabled() ||
                DouSettings.isHideAdLabelsEnabled()

        if (shouldScan) {
            try {
                val decor = activity.window?.decorView as? ViewGroup
                if (decor != null) {
                    val kw = if (DouSettings.isBlockFeedKeywordsEnabled()) DouSettings.getAdKeywords() else emptySet()
                    scanView(decor, kw)
                }
            } catch (_: Throwable) {}
        }
        mainHandler.postDelayed({ scanLoop(activity) }, 700)
    }

    /**
     * 检测侧边栏/抽屉是否已打开。
     * 抖音侧边栏特征：
     * - 占据屏幕大部分宽度（>40%）
     * - 位于屏幕左侧（x < 屏幕宽*20%）
     * - 不是 content 视图（android.R.id.content）
     */
    private fun isSidePanelOpen(activity: Activity): Boolean {
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return false
            val screenW = decor.width
            val screenH = decor.height
            if (screenW <= 0 || screenH <= 0) return false

            for (i in 0 until decor.childCount) {
                val child = decor.getChildAt(i) ?: continue
                // 跳过 content 容器（主内容区域）
                if (child.id == android.R.id.content) continue
                // 侧边栏特征：宽度大、高度大、位于左侧
                val w = child.width
                val h = child.height
                val x = child.x
                if (w > screenW * 0.4f && h > screenH * 0.7f && x < screenW * 0.2f) {
                    HookUtils.log("$TAG: 检测到侧边栏打开: ${child.javaClass.simpleName} w=${w} h=${h} x=${x.toInt()}")
                    return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun scanView(view: View, keywords: Set<String>) {
        if (view.visibility != View.VISIBLE) return

        val className = view.javaClass.name
        // 跳过侧边栏/抽屉/导航菜单/面板等覆盖层容器（避免扫描菜单项导致空白）
        val skipClassPatterns = setOf("DrawerLayout", "SlidingPaneLayout", "NavigationView",
            "drawer", "slide", "nav", "panel", "menu", "sidebar", "sheet", "scrim")
        if (skipClassPatterns.any { className.contains(it, ignoreCase = true) }) return

        // === 功能2: 信息流关键词屏蔽 ===
        if (view is TextView && keywords.isNotEmpty()) {
            val text = try { view.text?.toString()?.lowercase() } catch (_: Throwable) { null }
            if (!text.isNullOrEmpty()) {
                val hit = keywords.firstOrNull { text.contains(it) }
                if (hit != null) {
                    hideCard(view, "关键词[$hit]")
                    return
                }
            }
        }

        // === 功能3: 屏蔽购物视频 ===
        if (DouSettings.isBlockShoppingEnabled() && view is TextView) {
            val text = try { view.text?.toString() } catch (_: Throwable) { null }
            if (!text.isNullOrEmpty()) {
                val lower = text.lowercase()
                val isShopping = lower.contains("购物车") || lower.contains("小黄车") ||
                        lower.contains("立即购买") || lower.contains("点击购买") ||
                        lower.contains("领券") || lower.contains("优惠券")
                if (isShopping) {
                    hideCard(view, "购物视频")
                    return
                }
            }
        }

        // === 功能4: 隐藏广告标签 ===
        if (DouSettings.isHideAdLabelsEnabled() && view is TextView) {
            val text = try { view.text?.toString() } catch (_: Throwable) { null }
            if (!text.isNullOrEmpty()) {
                val isAdLabel = AD_LABEL_KEYWORDS.any { text.trim() == it }
                if (isAdLabel) {
                    // 隐藏标签本身（不隐藏整个卡片，因为标签只是角标）
                    try {
                        view.visibility = View.GONE
                        HookUtils.log("$TAG: [广告标签] 隐藏标签: $text")
                    } catch (_: Throwable) {}
                    return
                }
            }
        }

        // 递归扫描子视图
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scanView(view.getChildAt(i), keywords)
            }
        }
    }

    /**
     * 隐藏命中的卡片容器，但不向上传播太远以免误隐藏正常视频。
     * 只向上找1层（直接父容器），避免把整个 Feed 卡片都隐藏。
     */
    private fun hideCard(view: View, reason: String) {
        HookUtils.log("$TAG: 检测到$reason，隐藏")
        try {
            val parent = view.parent as? View ?: view
            parent.visibility = View.GONE
        } catch (_: Throwable) {
            try { view.visibility = View.GONE } catch (_: Throwable) {}
        }
    }
}
