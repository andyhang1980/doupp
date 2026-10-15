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
 * 抖音商城（电商）适配 Hook。
 *
 * 定位（2026-10 按需求重新校准）：这个 Hook 只管**抖音商城**——
 * 商城入口、运营浮层、商城里跳出去的组件。它**不碰**视频里的直播带货 / 小黄车：
 * 那套视图（ECVideoInnerProductCardView / ECTagLayout / ECDotLayout / ProductPriceView）
 * 早前被挂过隐藏，结果点小黄车弹出商品卡是空的，属于误伤，已经摘掉。
 *
 * 40.7.0 实测（手机上的实际构建，55 dex / 437MB 全量扫描）：
 *   电商代码全部在主包里，语义包名整体保留：
 *     com.ss.android.ugc.aweme.commerce.*   51 个 dex
 *     com.bytedance.android.shopping.*      抖音商城主包
 *     com.bytedance.android.window.shop.*   商城入口/浮层
 *
 * 原版模块（0920131537）的思路是「方法名锚点 + 一处功能」：C0092 case12/13 注册
 * getLiveCommercePlaceholderBind / getLiveAdPlaceholderBind（直播间清爽模式）。
 * 这两个方法名在 40.7.0 上已被 R8 抹掉，所以这里改成按电商语义类名锚定 + 运行时结构识别。
 */
class MallHook : BaseHook {

    companion object {
        private const val TAG = "MallHook"

        @Volatile
        private var installed = false

        // 直播间清爽模式的锚点（LIVE_BOX / LIVE_AD）已搬到 LiveCleanHook，这个类只管商城。
        // 商品卡一律不挂：ECVideoInnerProductCardView / ECTagLayout / ECDotLayout /
        // ProductPriceView 早前挂过，把点小黄车弹出的商品卡 GONE 掉了，妥妥的误伤。
        private val CARD_VIEWS = emptyList<String>()

        // 商城入口 / 浮层。40.7.0 设备版实测：入口类在字节电商 SDK 包里，不在 aweme.commerce 下
        //   com.bytedance.android.window.shop.home.module.ECWindowFloatCartModule     浮层购物车入口
        //   com.ss.android.ugc.aweme.commerce.sdk.bubble.service.MallBubbleServiceImpl 商城气泡
        //   com.bytedance.android.shopping.mall.homepage.component.ECMFTopBarComponent 商城首页顶栏
        //   com.ss.android.ugc.aweme.commerce.sdk.feed.videotomall.ECCommerceComponent  视频跳商城
        // ProductPriceView 不挂：它是商城里商品卡的组成部分，挂了会让商城商品页内容异常。
        private val ENTRY_VIEWS = listOf(
            "com.bytedance.android.window.shop.home.module.ECWindowFloatCartEntryModule",
            "com.ss.android.ugc.aweme.commerce.sdk.bubble.service.MallBubbleServiceImpl",
            "com.bytedance.android.shopping.mall.homepage.component.ECMFTopBarComponent",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.videotomall.ECCommerceComponent"
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

            // 只挂精确类名。早前这里有一段「包内按 Card/Tag/Shop/Product 关键词补捞」，
            // 结果把 feed.video.product 和 feedCard.module.shopcard 两个包扫进来，
            // 商品卡/角标/FeedShopCardPresenter 全被 GONE —— 点小黄车弹出空白就是这段造成的。
            // 关键词补捞太糙，宁可命中少一点也不误伤。
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
     * 挂精确类名列表：命中后 hook 构造函数 + onAttachedToWindow，初始化完直接 GONE。
     *
     * 不做「按包 + 关键词补捞」—— 早前那段扫的是 feed.video.product / shopcard 业务包，
     * 过滤词 Card/Tag/Shop/Product 把正常商品卡一起收拾掉，点小黄车弹出空白就是它造成的。
     * 宁可少命中，也不误伤。
     */
    private fun hideViews(classLoader: ClassLoader, names: List<String>, label: String): String {
        var hit = 0
        for (name in names) {
            val cls = ClassFinder.findClass(classLoader, name) ?: continue
            if (hookHideView(cls)) hit++
        }
        return "$label:$hit "
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
        // 只隐藏自己，不动子布局。
        // 早前这里在清爽模式下还会 clearChildren，把整棵子树都 GONE ——
        // 商城入口里也塞着别的业务视图，子树一空旁边的布局全塌了。
        runCatching {
            v.visibility = View.GONE
            v.alpha = 0f
            v.isClickable = false
        }
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