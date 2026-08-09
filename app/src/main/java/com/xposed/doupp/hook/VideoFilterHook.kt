package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频过滤 Hook — 自动跳过不喜欢的内容类型
 *
 * 功能:
 * - 过滤直播
 * - 过滤图文
 * - 过滤长视频
 * - 过滤广告/关键词
 *
 * 原理:
 * - Hook Feed 流页面切换时，检测当前 Aweme 类型
 * 如果需要过滤则自动滑动到下一个
 */
class VideoFilterHook : BaseHook {

    companion object {
        private const val TAG = "VideoFilter"

        @Volatile
        private var installed = false

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var lastFilterTime = 0L

        private const val FILTER_DEBOUNCE_MS = 1500L

        @Volatile
        private var lastAwemeId: String? = null

        private val hookedMethods = ConcurrentHashMap<String, Boolean>()

        @JvmStatic
        fun triggerFilterSwipe() {
            HookUtils.log("$TAG: 触发官方连播跳过被阻止 (由官方自动播放控制，filter不强制跳过)")
            return
        }

        private fun isMethodHooked(m: java.lang.reflect.Method): Boolean {
            return hookedMethods.containsKey("${m.declaringClass.name}.${m.name}")
        }

        private fun markMethodHooked(m: java.lang.reflect.Method) {
            hookedMethods["${m.declaringClass.name}.${m.name}"] = true
        }
    }

    override fun tag() = TAG
    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            hookAwemeDisplay(classLoader)
            installed = true
            HookUtils.log("$TAG: 安装完成")
        }
    }

    private fun hookAwemeDisplay(classLoader: ClassLoader) {
        try {
            val cachedClasses = com.xposed.doupp.util.AdaptationManager.getAdaptedClasses("aweme")
            val fallbackClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.Aweme",
                "com.ss.ugc.aweme.Aweme",
                "com.ss.android.ugc.aweme.model.Aweme",
                "com.ss.android.ugc.aweme.feed.model.FeedModel"
            )
            val allCandidates = (cachedClasses + fallbackClasses).distinct()
            val awemeClass = ClassFinder.findClass(classLoader, allCandidates) ?: return

            HookUtils.log("$TAG: 找到 Aweme 类: ${awemeClass.name}")

            val idMethod = awemeClass.declaredMethods.firstOrNull { m ->
                val name = m.name.lowercase()
                (name == "getawemeid" || name == "awemeid" || name == "itemid") &&
                    m.parameterTypes.isEmpty()
            }

            if (idMethod != null) {
                XposedBridge.hookMethod(idMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            checkShoppingOnly(param.thisObject)
                            if (!DouSettings.isVideoFilterEnabled()) return
                            val awemeId = param.result as? String ?: return
                            if (awemeId == lastAwemeId) return
                            lastAwemeId = awemeId
                            checkAndFilter(param.thisObject)
                        } catch (_: Throwable) {}
                    }
                })
                HookUtils.log("$TAG: Hook Aweme.${idMethod.name}")
            }

            val videoMethod = awemeClass.declaredMethods.firstOrNull { m ->
                val name = m.name.lowercase()
                (name == "getvideo" || name == "video" || name == "getmvideo") &&
                    m.parameterTypes.isEmpty()
            }

            if (videoMethod != null && videoMethod != idMethod) {
                XposedBridge.hookMethod(videoMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            checkShoppingOnly(param.thisObject)
                            if (!DouSettings.isVideoFilterEnabled()) return
                            val aweme = param.thisObject ?: return
                            val awemeId = getAwemeId(aweme)
                            if (awemeId == lastAwemeId && lastAwemeId != null) return
                            lastAwemeId = awemeId
                            checkAndFilter(aweme)
                        } catch (_: Throwable) {}
                    }
                })
                HookUtils.log("$TAG: Hook Aweme.${videoMethod.name}")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: Hook Aweme 显示失败: ${t.message}")
        }
    }

    private fun getAwemeId(aweme: Any): String? {
        return try {
            val cls = aweme.javaClass
            for (name in listOf("getAwemeId", "awemeId", "getItemId", "itemId", "getId", "id")) {
                try {
                    val m = cls.getDeclaredMethod(name)
                    m.isAccessible = true
                    val r = m.invoke(aweme)
                    if (r is String && r.isNotEmpty()) return r
                } catch (_: Throwable) {}
            }
            for (fname in listOf("awemeId", "itemId", "id", "aid")) {
                try {
                    val f = cls.getDeclaredField(fname)
                    f.isAccessible = true
                    val r = f.get(aweme)
                    if (r is String && r.isNotEmpty()) return r
                } catch (_: Throwable) {}
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 仅针对购物视频（带货/小黄车/电商组件）的独立跳过。
     * 走抖音自身的电商标志（isEcomAweme / hasEcomGoodsCard / 电商组件字段），
     * 不依赖视频文案关键词，适配 39.6~39.8 并尽量抗混淆。
     */
    private fun checkShoppingOnly(aweme: Any) {
        try {
            if (!DouSettings.isBlockShoppingEnabled()) return
            val awemeId = getAwemeId(aweme) ?: return
            if (awemeId == lastAwemeId && lastAwemeId != null) return
            lastAwemeId = awemeId
            if (isShoppingContent(aweme)) {
                HookUtils.log("$TAG: [购物视频] 检测到电商内容，触发表快跳过")
                triggerFilterSwipe()
            }
        } catch (_: Throwable) {}
    }

    /**
     * 依据 39.6~39.8 Aweme 模型中的电商字段判断是否为购物视频。
     * 通过反射逐层兜底，字段名带上序列化名（is_ecom_aweme / has_ecom_goods_card /
     * commerce_sticker_info / feed_shop_card 等），兼容版本间的混淆。
     *
     * 关键（误杀防护）：isEcomAweme / hasEcomGoodsCard 在 39.8 都是 int 型且官方
     * isEcomAweme() 语义为 == 1，因此数字判断必须用 == 1，不能 != 0，
     * 否则字段取值 2/3 等会被误判为电商视频导致普通视频被跳过。
     */
    private fun isShoppingContent(aweme: Any): Boolean {
        return try {
            val cls = aweme.javaClass
            // 1) 官方电商标志（int 语义 == 1 / boolean true）
            val numericFlags = listOf(
                "isEcomAweme", "is_ecom_aweme",
                "hasEcomGoodsCard", "has_ecom_goods_card", "isEcomLive"
            )
            for (f in numericFlags) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val v = field.get(aweme)
                    if (v is Boolean && v) return true
                    if (v is Number && v.toInt() == 1) return true
                } catch (_: Throwable) {}
            }
            // 1.5) 官方 isEcomAweme()/isEcomLive() 方法
            for (m in listOf("isEcomAweme", "isEcomLive", "isShoppingAweme")) {
                try {
                    val method = cls.getDeclaredMethod(m)
                    if (method.returnType == Boolean::class.javaPrimitiveType && method.parameterTypes.isEmpty()) {
                        method.isAccessible = true
                        val r = method.invoke(aweme)
                        if (r is Boolean && r) return true
                    }
                } catch (_: Throwable) {}
            }
            // 2) 非空电商专属组件/卡片对象（仅电商专属，普通视频不会有）
            val objectFlags = listOf(
                "ecomFunshoppingComponentStruct",
                "ecomNonCartComponentStruct", "ecomVideoInfo",
                "commerceStickerInfo", "feedShopCardStruct"
            )
            for (f in objectFlags) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    if (field.get(aweme) != null) return true
                } catch (_: Throwable) {}
            }
            // 3) 方法兜底：getCommerceStickerInfo / getEcomVideoInfo 返回非空
            for (m in listOf("getCommerceStickerInfo", "getEcomVideoInfo", "getFeedShopCardStruct")) {
                try {
                    val method = cls.getDeclaredMethod(m)
                    method.isAccessible = true
                    if (method.invoke(aweme) != null) return true
                } catch (_: Throwable) {}
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkAndFilter(aweme: Any) {
        try {
            if (!DouSettings.isVideoFilterEnabled()) return

            val filterReasons = mutableListOf<String>()

            if (DouSettings.isFilterLive() && isLive(aweme)) {
                filterReasons.add("直播")
            }

            if (DouSettings.isFilterImage() && isImageContent(aweme)) {
                filterReasons.add("图文")
            }

            if (DouSettings.isFilterAd() && isAdContent(aweme)) {
                filterReasons.add("广告")
            }

            if (DouSettings.isFilterLongVideo() && isLongVideo(aweme)) {
                filterReasons.add("长视频")
            }

            if (filterReasons.isEmpty()) {
                val keywords = DouSettings.getFilterKeywords()
                if (keywords.isNotEmpty()) {
                    val desc = MediaCache.getAwemeDesc(aweme)?.lowercase() ?: ""
                    val authorName = getAuthorName(aweme)?.lowercase() ?: ""
                    for (kw in keywords) {
                        if (desc.contains(kw) || authorName.contains(kw)) {
                            filterReasons.add("关键词:$kw")
                            break
                        }
                    }
                }
            }

            if (filterReasons.isNotEmpty()) {
                HookUtils.log("$TAG: 过滤内容 [${filterReasons.joinToString(",")}]")
                triggerFilterSwipe()
            }
        } catch (_: Throwable) {}
    }

    private fun isLive(aweme: Any): Boolean {
        return try {
            val cls = aweme.javaClass
            // 39.8 的 Aweme.isLive() 语义反直觉：awemeType==101 才是直播，
            // 其它类型 isLive() 返回 true，因此不能直接调用该方法（会把普通视频误判为直播）。
            // 正确判断：awemeType == 101。
            try {
                val typeField = cls.getDeclaredField("awemeType")
                typeField.isAccessible = true
                val tv = typeField.get(aweme)
                if (tv is Number) {
                    if (tv.toInt() == 101) return true
                    return false
                }
            } catch (_: Throwable) {}
            // 明确直播标志兜底
            for (f in listOf("isDetailLive", "isEcomLive", "isLiveReplay")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val v = field.get(aweme)
                    if (v is Boolean && v) return true
                } catch (_: Throwable) {}
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isImageContent(aweme: Any): Boolean {
        return try {
            val imageUrls = MediaCache.getImageUrlsFromAweme(aweme)
            if (imageUrls.isEmpty()) return false
            // 必须是“没有视频”的内容才视为图集，避免把普通视频误判
            val videoUrl = MediaCache.getVideoUrlFromAweme(aweme)
            videoUrl == null
        } catch (_: Throwable) {
            false
        }
    }

    private fun isAdContent(aweme: Any): Boolean {
        return try {
            val cls = aweme.javaClass
            // 仅依据抖音自身的广告标志位判断（字段/方法）。
            // 不再用 awemeType==1 判定广告 —— 39.8 中 awemeType 语义不明，
            // 普通视频也可能是 1，会造成误杀。
            for (f in listOf("isAd", "is_ad", "ad", "isPromotion", "promotion", "isAdvert", "is_advert")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val v = field.get(aweme)
                    if (v is Boolean && v) return true
                    if (v is Number && v.toInt() == 1) return true
                } catch (_: Throwable) {}
            }
            for (name in listOf("isAd", "is_ad", "isAdvert", "isPromotion")) {
                try {
                    val m = cls.getDeclaredMethod(name)
                    if (m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                        m.isAccessible = true
                        val r = m.invoke(aweme)
                        if (r is Boolean && r) return true
                    }
                } catch (_: Throwable) {}
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isLongVideo(aweme: Any): Boolean {
        return try {
            val dur = MediaCache.getVideoDurationSec() ?: return false
            val threshold = DouSettings.getLongVideoSeconds()
            dur > threshold
        } catch (_: Throwable) {
            false
        }
    }

    private fun getAuthorName(aweme: Any): String? {
        return try {
            val cls = aweme.javaClass
            for (f in listOf("author", "mAuthor", "user", "mUser")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val authorObj = field.get(aweme) ?: continue
                    val authorCls = authorObj.javaClass
                    for (nameField in listOf("nickname", "nickName", "name", "userName", "username")) {
                        try {
                            val nf = authorCls.getDeclaredField(nameField)
                            nf.isAccessible = true
                            val name = nf.get(authorObj)
                            if (name is String && name.isNotEmpty()) return name
                        } catch (_: Throwable) {}
                    }
                    for (nameMethod in listOf("getNickname", "getNickName", "getName", "getUserName")) {
                        try {
                            val nm = authorCls.getDeclaredMethod(nameMethod)
                            nm.isAccessible = true
                            val name = nm.invoke(authorObj)
                            if (name is String && name.isNotEmpty()) return name
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
