package com.xposed.doupp.hook

import android.view.View
import android.view.ViewGroup
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils

/**
 * 商城（电商 / 带货）适配 Hook。
 *
 * 40.7.0 实测（手机上的实际构建，55 dex / 437MB 全量扫描）：
 *   电商代码全部在主包里，语义包名整体保留：
 *     com.ss.android.ugc.aweme.commerce.*   51 个 dex
 *     com.ss.android.ugc.aweme.ecommerce.*  11 个 dex
 *     com.ss.android.ugc.aweme.ec.*          3 个 dex
 *   带货标记字段名也还在：is_ecommerce / isEC / ecVideo / is_shopping /
 *   commerce_info / product_info
 *   没有独立的抖音商城包（com.ss.android.ugec 在主包 0 命中）
 *
 * 四块能力，对应设置里的四个开关：
 *   隐藏商品卡与角标（默认开）— 视频内商品卡 / 电商角标 / 信息流商城卡
 *   直播间清爽模式（默认关）— 电商直播盒子与直播广告中台换成空占位
 *   隐藏商城入口浮层（默认开）— 商城入口与跳转浮层
 *   过滤带货视频（默认关）— 判定可能误伤，由设置页自行决定
 *
 * 原版模块（0920131537）对商城只有直播间清爽模式一处，用的是
 * getLiveCommercePlaceholderBind / getLiveAdPlaceholderBind 两个方法名；
 * 这两个名字在 40.7.0 上已被 R8 抹掉，所以这里改成按电商语义类名锚定。
 */
class MallHook : BaseHook {

    companion object {
        private const val TAG = "MallHook"

        @Volatile
        private var installed = false

        /** 电商相关视图：初始化后置 GONE */
        private val CARD_VIEWS = listOf(
            // 视频内商品卡 + 角标
            "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product.ECVideoInnerProductCardView",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product.ECTagLayout",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product.ECDotLayout",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product.ECProductInnerLayout"
        )

        /** 电商直播盒子（直播间清爽模式目标） */
        private val LIVE_BOX_VIEWS = listOf(
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.ECLiveBoxForLynx",
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.HybridLiveBoxForLynxV2",
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.HybridLiveBoxForLynxV3"
        )

        /** 直播广告中台 */
        private val LIVE_AD_TARGETS = listOf(
            "com.ss.android.ugc.aweme.ad.LiveAdMiddleWareService",
            "com.ss.android.ugc.aweme.ad.LiveAdCardEventBusWrapper"
        )

        /** 信息流商城卡片 presenter */
        private const val SHOP_CARD_PRESENTER =
            "com.ss.android.ugc.aweme.bizmodule.fullfeed.feedCard.module.shopcard.FeedShopCardPresenter"

        // 商城入口 / 浮层。40.7.0 设备版实测：入口类在字节电商 SDK 包里，不在 aweme.commerce 下
        //   com.bytedance.android.window.shop.home.module.ECWindowFloatCartEntryModule  浮层购物车入口
        //   com.ss.android.ugc.aweme.commerce.sdk.bubble.service.MallBubbleServiceImpl   商城气泡
        //   com.bytedance.android.shopping.mall.homepage.component.ECMFTopBarComponent  商城首页顶栏
        //   com.ss.android.ugc.aweme.commerce.sdk.feed.videotomall.ECCommerceComponent   视频跳商城
        private val ENTRY_VIEWS = listOf(
            "com.bytedance.android.window.shop.home.module.ECWindowFloatCartEntryModule",
            "com.ss.android.ugc.aweme.commerce.sdk.bubble.service.MallBubbleServiceImpl",
            "com.bytedance.android.shopping.mall.homepage.component.ECMFTopBarComponent",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.videotomall.ECCommerceComponent",
            "com.bytedance.android.shopping.mall.homepage.card.product.ProductPriceView"
        )

        private val ENTRY_PKGS = listOf(
            "com.bytedance.android.window.shop.home.module",
            "com.ss.android.ugc.aweme.commerce.sdk.bubble",
            "com.bytedance.android.shopping.mall.homepage.component",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.videotomall"
        )

        private val ENTRY_KEYWORDS = listOf("Entry", "Bubble", "FloatCart", "TopBar")

        /** 40.7.0 带货标记字段（补 VideoFilterHook 只认 39.x 字段名的缺口） */
        val MALL_FLAGS = listOf(
            "is_ecommerce", "isEC", "ecVideo", "is_shopping", "isEcomAweme",
            "is_ecom_aweme", "hasEcomGoodsCard", "has_ecom_goods_card"
        )

        /** 非空即视为带货的商品/推广结构字段 */
        val MALL_STRUCT_FIELDS = listOf(
            "commerce_info", "commerceInfo", "product_info", "productInfo",
            "ecomVideoInfo", "ecomFunshoppingComponentStruct", "feedShopCardStruct",
            "commerceStickerInfo"
        )
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            val result = StringBuilder()

            if (DouSettings.isMallHideCardsEnabled()) {
                result.append(hideViews(classLoader, CARD_VIEWS, "商品卡/角标"))
            }
            if (DouSettings.isMallLiveCleanEnabled()) {
                result.append(hideViews(classLoader, LIVE_BOX_VIEWS, "电商直播盒子"))
                result.append(hideLiveAd(classLoader))
            }
            if (DouSettings.isMallHideEntryEnabled()) {
                result.append(hideMallEntry(classLoader))
            }
            if (DouSettings.isMallFilterVideoEnabled()) {
                result.append("带货过滤=已启用(由 VideoFilterHook 判定) ")
            } else {
                result.append("带货过滤=关闭 ")
            }

            installed = true
            HookUtils.log("$TAG: 安装完成 [$result]")

            // 适配结果回读，方便看当前版本命中了什么
            val mall = AdaptationManager.getAdaptedClasses("mall")
            if (mall.isNotEmpty()) {
                HookUtils.log("$TAG: AdaptationManager 电商适配命中 ${mall.size} 个类: ${mall.take(5)}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 隐藏电商视图
    // ------------------------------------------------------------------

    /**
     * 命中策略：先按类名锚定（最稳），锚定不到就在电商相关包下按类名关键词找。
     * 命中后 hook 构造函数 + onAttachedToWindow，初始化完直接 GONE。
     */
    private fun hideViews(classLoader: ClassLoader, names: List<String>, label: String): String {
        var hit = 0
        for (name in names) {
            val cls = ClassFinder.findClass(classLoader, name) ?: continue
            if (hookHideView(cls)) hit++
        }
        // 锚定类缺失时，退到按包 + 关键词找
        if (hit == 0) {
            for (pkg in listOf(
                "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product",
                "com.ss.android.ugc.aweme.bizmodule.fullfeed.feedCard.module.shopcard"
            )) {
                val candidates = ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = true, limit = 200)
                    .filter { it.contains("Card") || it.contains("Tag") || it.contains("Shop") || it.contains("Product") }
                for (n in candidates.take(12)) {
                    val cls = ClassFinder.findClass(classLoader, n) ?: continue
                    if (hookHideView(cls)) hit++
                }
            }
        }
        return "$label:${hit} "
    }

    private fun hookHideView(cls: Class<*>): Boolean {
        var hooked = false
        // 1) 构造完成后隐藏
        runCatching {
            cls.declaredConstructors.forEach { ctor ->
                if (hooked) return@forEach
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { hide(param.thisObject) }
                    }
                })
                hooked = true
            }
        }
        // 2) 挂载到窗口时再隐藏一次（防止 inflate 之后被业务代码改回来）
        runCatching {
            cls.getMethod("onAttachedToWindow").let { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { hide(param.thisObject) }
                    }
                })
            }
        }
        if (hooked) HookUtils.log("$TAG: 已隐藏 ${cls.name}")
        return hooked
    }

    private fun hide(target: Any?) {
        val v = target as? View ?: return
        // 直播间清爽模式下把内部电商子布局也清空，其它模式只隐藏自己
        if (DouSettings.isMallLiveCleanEnabled()) clearChildren(v)
        runCatching {
            v.visibility = View.GONE
            v.alpha = 0f
            v.isClickable = false
        }
    }

    private fun clearChildren(v: View) {
        if (v !is ViewGroup) return
        for (i in 0 until v.childCount) {
            runCatching {
                v.getChildAt(i).visibility = View.GONE
                clearChildren(v.getChildAt(i))
            }
        }
    }

    // ------------------------------------------------------------------
    // 直播广告中台
    // ------------------------------------------------------------------

    private fun hideLiveAd(classLoader: ClassLoader): String {
        var hit = 0
        for (name in LIVE_AD_TARGETS) {
            val cls = ClassFinder.findClass(classLoader, name) ?: continue
            // 直播广告挂件渲染方法统一返回空
            runCatching {
                cls.declaredMethods
                    .filter { it.parameterTypes.isEmpty() && it.returnType != Void.TYPE }
                    .filter { it.name.contains("bind", true) || it.name.contains("render", true) || it.name.contains("show", true) || it.name.contains("card", true) }
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
        return "直播广告:${hit} "
    }

    // ------------------------------------------------------------------
    // 商城入口浮层
    // ------------------------------------------------------------------

    private fun hideMallEntry(classLoader: ClassLoader): String {
        var hit = 0
        // 1) 精确类名锚定
        for (n in ENTRY_VIEWS) {
            val cls = ClassFinder.findClass(classLoader, n) ?: continue
            if (hookHideViewOrService(cls)) hit++
        }
        // 2) 包内按关键词补捞（类名可能带 $ 或变体）
        if (hit == 0) {
            for (pkg in ENTRY_PKGS) {
                val names = ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = true, limit = 200)
                    .filter { n -> ENTRY_KEYWORDS.any { n.contains(it) } }
                for (n in names.take(10)) {
                    val cls = ClassFinder.findClass(classLoader, n) ?: continue
                    if (hookHideViewOrService(cls)) hit++
                }
                if (hit > 0) break
            }
        }
        return "商城入口:${hit} "
    }

    /** 视图就隐藏视图；服务类就把返回值清掉 */
    private fun hookHideViewOrService(cls: Class<*>): Boolean {
        if (View::class.java.isAssignableFrom(cls)) return hookHideView(cls)
        var hooked = false
        runCatching {
            cls.declaredMethods
                .filter { it.parameterTypes.isEmpty() && it.returnType != Void.TYPE }
                .filter { it.name.contains("show", true) || it.name.contains("bubble", true) || it.name.contains("entry", true) }
                .take(4)
                .forEach { m ->
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching { param.result = null }
                        }
                    })
                    hooked = true
                }
        }
        if (hooked) HookUtils.log("$TAG: 已拦截 ${cls.name}")
        return hooked
    }
}