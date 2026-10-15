package com.xposed.doupp.hook

import android.view.View
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils

/**
 * 抖音商城（电商）适配 Hook。
 *
 * 定位（2026-10 按需求重新校准）：这个 Hook 只管**抖音商城**，而且只动「营销打扰」这一层：
 *   - 商城引流弹窗（MallDrainageDialog / MallDrainageLynxDialog）
 *   - 应用内商城推送横幅（ShoppingInnerPushWidget / ShoppingMallLowerPriceWidget）
 * **不碰**商品卡，也不碰视频里的直播带货 / 小黄车：
 *   ECVideoInnerProductCardView / ECTagLayout / ECDotLayout / ProductPriceView
 *   早前被挂过隐藏，结果点小黄车弹出商品卡是空的，属于误伤，已经摘掉。
 *
 * 40.7.0 实测（手机上的实际构建，55 dex / 437MB 全量扫描）：
 *   - 商城主体是 Lynx/H5：com.bytedance.android.shopping.* 下 mall/feed/jsb、
 *     hybrid/bridges 一大堆，native 视图类 0 个 —— 商城**页内**的 banner / 商品卡 /
 *     楼层靠 native 类名根本藏不掉，硬藏只会误伤，所以本 Hook 只管 native 这层。
 *   - 首页顶部「商城」tab 落在 MallHomeTab / BottomMallHomeTab，但这俩连同
 *     BaseHomeTab 的方法名全被 R8 打散（LJJLIIIJILLIZJL 那种），按方法名挂等于瞎猜，
 *     暂不做，见 README 的已知限制。
 *
 * 原版模块（0920131537）的思路是「方法名锚点 + 一处功能」：C0092 case12/13 注册
 * getLiveCommercePlaceholderBind / getLiveAdPlaceholderBind（直播间清爽模式）。
 * 这两个方法名在 40.7.0 上已被 R8 抹掉（0 命中），所以这里改成
 * 「类名语义锚定 + 稳定框架方法」：弹窗走 Dialog.show()，横幅走 View 生命周期。
 */
class MallHook : BaseHook {

    companion object {
        private const val TAG = "MallHook"

        @Volatile
        private var installed = false

        // 直播间清爽模式的锚点（LIVE_BOX / LIVE_AD）已搬到 LiveCleanHook，这个类只管商城。
        // 商品卡一律不挂：ECVideoInnerProductCardView / ECTagLayout / ECDotLayout /
        // ProductPriceView 早前挂过，把点小黄车弹出的商品卡 GONE 掉了，妥妥的误伤。

        /**
         * 商城引流弹窗。40.7.0 实测这条链是干净的：
         *   MallDrainageDialog / MallDrainageLynxDialog
         *     -> com.ss.android.ugc.aweme.backflow.IShareShoppingDialog
         *     -> androidx.appcompat.app.AppCompatDialog -> android.app.Dialog
         *
         * 也就是说它们是货真价实的 Dialog，按类名在 Dialog.show() 上拦就行，
         * 不用去猜那些被 R8 打散的方法名（这些类的 show/内容方法全是 LIZ / LJ 那种）。
         *
         * 只拦 MallDrainage*（引流=自动弹的营销弹窗），**不拦** ShareShopping*——
         * 那几个是用户主动点「分享」时弹的商品分享卡，拦了会把分享功能弄坏，
         * 和点小黄车弹空商品卡是同一类事故。
         */
        private val MALL_DRAINAGE_DIALOGS = setOf(
            "com.ss.android.ugc.aweme.commerce.sdk.share.view.MallDrainageDialog",
            "com.ss.android.ugc.aweme.commerce.sdk.share.view.MallDrainageLynxDialog"
        )

        /**
         * 应用内商城推送横幅（降价提醒 / 商城内推）。都是 View，直接 GONE：
         *   ShoppingInnerPushWidget      extends innerpush.api.banner.BaseBannerView
         *   ShoppingMallLowerPriceWidget extends innerpush.api.banner.BaseBannerViewNormative
         *
         * 挂横幅不碰商品：这只是「进应用时弹的那条横幅」，商城页面本身照常打开。
         */
        private val MALL_PUSH_BANNERS = listOf(
            "com.ss.android.ugc.aweme.commerce.sdk.push.ShoppingInnerPushWidget",
            "com.ss.android.ugc.aweme.commerce.sdk.push.ShoppingMallLowerPriceWidget"
        )

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

            if (DouSettings.isMallHideEntryEnabled()) {
                result.append(blockMallDrainageDialogs(classLoader))
                result.append(hideViews(classLoader, MALL_PUSH_BANNERS, "商城推送横幅"))
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
    // 商城营销弹窗 + 推送横幅
    // ------------------------------------------------------------------

    /**
     * 拦商城引流弹窗。
     *
     * 挂点选 android.app.Dialog.show() 而不是弹窗自己的方法：这些类的 show/内容方法
     * 在 40.7.0 上全是 R8 打散的（LIZ / LJFF / 反射出来的 ?），按方法名挂等于瞎猜；
     * 而 Dialog.show() 是框架方法，名字永远在，类名又保留了语义（MallDrainage=引流），
     * 两边都稳定才叫锚点。
     *
     * 子类有可能自己重写 show()，所以除了框架那个，再各挂一次目标类自己声明的 show()。
     * before 里直接 setResult 跳过原方法（compat 实现里 before 置 result 即跳过原方法）。
     */
    private fun blockMallDrainageDialogs(classLoader: ClassLoader): String {
        var hit = 0

        fun attach(m: java.lang.reflect.Member) {
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject ?: return
                        if (obj.javaClass.name !in MALL_DRAINAGE_DIALOGS) return
                        HookUtils.log("$TAG: 拦截商城引流弹窗 ${obj.javaClass.simpleName}")
                        param.result = null
                    }
                })
                hit++
            }
        }

        // 1) 框架层：Dialog.show() 拦所有 Dialog，回调里再按类名筛
        runCatching { attach(android.app.Dialog::class.java.getDeclaredMethod("show")) }

        // 2) 目标类自己声明的 show()（防子类重写绕过框架那一层）
        for (name in MALL_DRAINAGE_DIALOGS) {
            val cls = ClassFinder.findClass(classLoader, name) ?: continue
            runCatching {
                cls.declaredMethods.filter { it.name == "show" }.forEach { attach(it) }
            }
        }

        return "引流弹窗:$hit "
    }
}
