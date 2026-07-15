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
            val now = System.currentTimeMillis()
            if (now - lastFilterTime < FILTER_DEBOUNCE_MS) return
            lastFilterTime = now

            mainHandler.postDelayed({
                try {
                    val activity = com.xposed.doupp.util.ContextHelper.getCurrentActivity() ?: return@postDelayed
                    val activityName = activity.javaClass.name
                    if (!activityName.contains("MainActivity") && !activityName.contains("main")) return@postDelayed

                    HookUtils.log("$TAG: 触发官方连播跳过")
                    AutoPlayControllerHook.triggerMoveToNext()
                } catch (t: Throwable) {
                    HookUtils.log("$TAG: 官方跳过失败: ${t.message}")
                }
            }, 300)
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
            for (f in listOf("live", "mLive", "liveRoom", "roomInfo")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    if (field.get(aweme) != null) return true
                } catch (_: Throwable) {}
            }
            for (f in listOf("liveId", "roomId", "mLiveId")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val v = field.get(aweme)
                    if (v is String && v.isNotEmpty()) return true
                } catch (_: Throwable) {}
            }
            try {
                val m = cls.declaredMethods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals("isLive", ignoreCase = true)
                }
                if (m != null) {
                    val r = m.invoke(aweme)
                    if (r is Boolean && r) return true
                }
            } catch (_: Throwable) {}
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
            // 仅依据抖音自身的广告标志位判断（字段/方法），
            // 不再用文案关键词（"购物/商品/广告" 在普通视频里太常见，会误杀正常视频）。
            for (f in listOf("isAd", "is_ad", "ad", "isPromotion", "promotion", "awemeType", "aweme_type")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val v = field.get(aweme)
                    if (v is Boolean && v) return true
                    // awemeType == 1 通常代表广告
                    if (v is Number && (f == "awemeType" || f == "aweme_type") && v.toInt() == 1) return true
                } catch (_: Throwable) {}
            }
            for (name in listOf("isAd", "is_ad", "isAdvert", "isPromotion")) {
                try {
                    val m = cls.getDeclaredMethod(name)
                    m.isAccessible = true
                    val r = m.invoke(aweme)
                    if (r is Boolean && r) return true
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
