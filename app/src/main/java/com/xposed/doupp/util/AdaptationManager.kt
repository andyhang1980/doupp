package com.xposed.doupp.util

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动适配管理器
 *
 * 检测抖音版本变化，动态扫描关键类名并缓存。
 * 适配过程中显示 Toast 提示，完成后显示结果。
 */
object AdaptationManager {

    private const val TAG = "AdaptationManager"
    private const val CACHE_FILE_NAME = "class_adaptation_cache.json"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 适配结果缓存: key=功能名, value=找到的类名列表 */
    private val adaptationResults = ConcurrentHashMap<String, List<String>>()

    /** 适配状态 */
    @Volatile
    private var adaptationDone = false

    /** 缓存文件 */
    private var cacheFile: File? = null

    /**
     * 适配结果数据类
     */
    data class AdaptationResult(
        val feature: String,
        val classNames: List<String>,
        val success: Boolean,
        val message: String
    )

    /**
     * 启动自动适配流程
     * 在 MainHook 中调用，显示 Toast 提示用户
     */
    fun startAdaptation(classLoader: ClassLoader, douyinVersion: String, callback: (List<AdaptationResult>) -> Unit) {
        if (adaptationDone) {
            callback(adaptationResults.entries.map { entry ->
                AdaptationResult(entry.key, entry.value, entry.value.isNotEmpty(), if (entry.value.isNotEmpty()) "已缓存" else "未找到")
            })
            return
        }

        // 初始化缓存文件 — 使用模块自己的 data 目录（LSPosed 桥接后可写）
        try {
            val ctx = ContextHelper.getContext()
            if (ctx != null) {
                cacheFile = File(ctx.applicationInfo.dataDir, "shared_prefs/$CACHE_FILE_NAME")
            } else {
                cacheFile = File("/data/local/tmp/$CACHE_FILE_NAME")
            }
        } catch (_: Throwable) {
            cacheFile = File("/data/local/tmp/$CACHE_FILE_NAME")
        }

        // 先尝试加载缓存
        if (loadCache(douyinVersion)) {
            HookUtils.log("$TAG: 使用缓存的适配结果")
            adaptationDone = true
            callback(adaptationResults.entries.map { entry ->
                AdaptationResult(entry.key, entry.value, entry.value.isNotEmpty(), "缓存命中")
            })
            return
        }

        // 缓存未命中，延迟执行动态扫描（避免在抖音启动高峰期大量扫描类导致崩溃）
        Thread {
            try {
                Thread.sleep(3000) // 延迟3秒，等抖音启动完成
            } catch (_: InterruptedException) { return@Thread }

            mainHandler.post { showToast("正在适配抖音 v$douyinVersion ...") }

            val results = mutableListOf<AdaptationResult>()

            // 1. 适配 Aweme 类（Feed流）
            try { results.add(adaptAwemeClass(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配Aweme失败: ${t.message}")
            }

            // 2. 适配 Cronet 类（下载）
            try { results.add(adaptCronetClasses(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配Cronet失败: ${t.message}")
            }

            // 3. 适配 OkHttp 类（下载）
            try { results.add(adaptOkHttpClasses(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配OkHttp失败: ${t.message}")
            }

            // 4. 适配 ExoPlayer 类（自动播放）
            try { results.add(adaptExoPlayerClasses(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配ExoPlayer失败: ${t.message}")
            }

            // 5. 适配热更新框架类
            try { results.add(adaptHotUpdateClasses(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配热更新失败: ${t.message}")
            }

            // 6. 适配广告SDK类
            try { results.add(adaptAdSdkClasses(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配广告SDK失败: ${t.message}")
            }

            // 7. 适配 AutoPlayController（官方自动播放控制器）
            try { results.add(adaptAutoPlayController(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配AutoPlayController失败: ${t.message}")
            }

            // 8. 适配 VerticalViewPager（视频过滤用）
            try { results.add(adaptVerticalViewPager(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配ViewPager失败: ${t.message}")
            }

            // 保存缓存
            saveCache(douyinVersion)

            adaptationDone = true

            // 显示结果 Toast
            val successCount = results.count { it.success }
            val totalCount = results.size
            val detail = results.joinToString("\n") { r ->
                val icon = if (r.success) "✓" else "✗"
                "$icon ${r.feature}: ${r.message}"
            }

            mainHandler.post {
                showToast("适配完成 [$successCount/$totalCount]\n$detail")
            }

            HookUtils.log("$TAG: 适配完成: $detail")
            callback(results)
        }.start()
    }

    /**
     * 适配 Aweme 类
     */
    private fun adaptAwemeClass(classLoader: ClassLoader): AdaptationResult {
        val feature = "Aweme类"
        val candidates = listOf(
            "com.ss.android.ugc.aweme.feed.model.Aweme",
            "com.ss.ugc.aweme.Aweme",
            "com.ss.android.ugc.aweme.model.Aweme",
            "com.ss.android.ugc.aweme.feed.model.FeedModel",
            "com.ss.android.ugc.aweme.model.FeedItem"
        )

        // 先用 ClassFinder 动态扫描
        val found = ClassFinder.findByMethodSignature(
            classLoader,
            "com.ss.android.ugc.aweme",
            listOf("getVideo:Video:", "getAwemeId:String:")
        )

        val classNames = if (found.isNotEmpty()) {
            found.map { it.name }
        } else {
            // 回退到候选列表
            val result = ClassFinder.findClass(classLoader, candidates)
            if (result != null) listOf(result.name) else emptyList()
        }

        adaptationResults["aweme"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到"
        )
    }

    /**
     * 适配 Cronet 类
     */
    private fun adaptCronetClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "Cronet网络库"
        val candidates = listOf(
            "com.ttnet.org.chromium.net.impl.CronetUrlRequest\$Builder",
            "com.ttnet.org.chromium.net.UrlRequest\$Builder",
            "org.chromium.net.UrlRequest\$Builder",
            "com.ttnet.org.chromium.net.impl.CronetUrlRequest",
            "com.ttnet.org.chromium.net.UrlRequest"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) {}
        }

        // 动态扫描 ttnet 包
        if (classNames.isEmpty()) {
            val found = ClassFinder.getClassesInPackage(classLoader, "com.ttnet.org.chromium.net")
            classNames.addAll(found.take(5).map { it.name })
        }

        adaptationResults["cronet"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到"
        )
    }

    /**
     * 适配 OkHttp 类
     */
    private fun adaptOkHttpClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "OkHttp网络库"
        val candidates = listOf(
            "okhttp3.internal.http.RealInterceptorChain",
            "okhttp3.RealCall",
            "com.bytedance.retrofit2.client.OkHttpCall",
            "okhttp3.OkHttpClient",
            "okhttp3.Request"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) {}
        }

        // 动态扫描 okhttp3 包
        if (classNames.none { it.startsWith("okhttp3") }) {
            val found = ClassFinder.getClassesInPackage(classLoader, "okhttp3")
            classNames.addAll(found.take(5).map { it.name })
        }

        adaptationResults["okhttp"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到"
        )
    }

    /**
     * 适配 ExoPlayer 类
     */
    private fun adaptExoPlayerClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "ExoPlayer播放器"
        val candidates = listOf(
            "com.google.android.exoplayer2.Player\$EventListener",
            "com.google.android.exoplayer2.ExoPlayer\$EventListener",
            "com.google.android.exoplayer2.analytics.AnalyticsListener",
            "com.google.android.exoplayer2.ExoPlayer",
            "com.google.android.exoplayer2.Player",
            // Media3 迁移后的类名
            "androidx.media3.exoplayer.ExoPlayer",
            "androidx.media3.player.Player\$EventListener",
            "androidx.media3.exoplayer.analytics.AnalyticsListener"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) {}
        }

        // 动态扫描 exoplayer2 和 media3 包
        if (classNames.isEmpty()) {
            val exoFound = ClassFinder.getClassesInPackage(classLoader, "com.google.android.exoplayer2")
            classNames.addAll(exoFound.take(3).map { it.name })

            val media3Found = ClassFinder.getClassesInPackage(classLoader, "androidx.media3")
            classNames.addAll(media3Found.take(3).map { it.name })
        }

        adaptationResults["exoplayer"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到"
        )
    }

    /**
     * 适配热更新框架类
     */
    private fun adaptHotUpdateClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "热更新框架"
        val candidates = listOf(
            "com.bytedance.ies.patch.PatchManager",
            "com.bytedance.ugc.patch.PatchManager",
            "com.bytedance.boost.patch.PatchLoader",
            "com.bytedance.boost.multidex.BoostMultiDex",
            "com.bytedance.mira.MiraManager",
            "com.bytedance.hotfix.HotfixManager",
            "com.bytedance.patch.PatchLoader",
            "com.tencent.tinker.lib.tinker.Tinker",
            "com.tencent.tinker.lib.tinker.TinkerInstaller",
            "com.taobao.sophix.SophixManager",
            "com.meituan.robust.PatchExecutor",
            "com.qihoo360.replugin.RePlugin",
            "com.didi.virtualapk.PluginManager",
            "com.android.dex.Dex",
            "dalvik.system.DexClassLoader"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) {}
        }

        // 动态扫描字节跳动热更新相关包
        val bytedancePatchPackages = listOf(
            "com.bytedance.ies.patch",
            "com.bytedance.ugc.patch",
            "com.bytedance.boost.patch",
            "com.bytedance.mira",
            "com.bytedance.hotfix"
        )
        for (pkg in bytedancePatchPackages) {
            if (classNames.none { it.startsWith(pkg) }) {
                val found = ClassFinder.getClassesInPackage(classLoader, pkg)
                classNames.addAll(found.map { it.name })
            }
        }

        adaptationResults["hotupdate"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到"
        )
    }

    /**
     * 适配广告SDK类
     */
    private fun adaptAdSdkClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "广告SDK"
        val sdkPackages = listOf(
            "com.bytedance.sdk.openadsdk",
            "com.pangle.sdk",
            "com.qq.e.ads",
            "com.qq.e.comm",
            "com.kuaishou.mob",
            "com.kwad.sdk",
            "com.baidu.mobads"
        )

        val classNames = mutableListOf<String>()
        for (pkg in sdkPackages) {
            val found = ClassFinder.getClassesInPackage(classLoader, pkg)
            classNames.addAll(found.take(3).map { it.name })
        }

        adaptationResults["adsdk"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到"
        )
    }

    /**
     * 适配 AutoPlayController（官方自动播放控制器）
     *
     * 搜索特征（参考 FreedomPlus）:
     * - 类中包含 "auto_play_key" 字符串常量
     * - 有返回 QLiveData 类型的方法
     * - 有 "normal"、"swipe" 等字符串
     */
    private fun adaptAutoPlayController(classLoader: ClassLoader): AdaptationResult {
        val feature = "AutoPlayController"
        val classNames = mutableListOf<String>()

        // 1. 通过字符串特征搜索
        val searchPackages = listOf(
            "com.ss.android.ugc.aweme.feed",
            "com.ss.android.ugc.aweme.auto",
            "com.ss.android.ugc.aweme.player",
            "com.ss.android.ugc.aweme"
        )

        for (pkg in searchPackages) {
            val found = ClassFinder.findByStringConstants(
                classLoader, pkg,
                listOf("auto_play_key")
            )
            classNames.addAll(found.map { it.name })
        }

        // 2. 如果字符串搜索没找到，用字段/方法特征搜索
        if (classNames.isEmpty()) {
            for (pkg in searchPackages) {
                // 找有 QLiveData/LiveData 类型字段的类
                val fieldFound = ClassFinder.findByFieldSignature(
                    classLoader, pkg,
                    listOf("QLiveData:LiveData", "liveData:LiveData")
                )
                classNames.addAll(fieldFound.map { it.name })
            }
        }

        // 3. 去重
        val uniqueNames = classNames.distinct()
        adaptationResults["autoplay"] = uniqueNames
        return AdaptationResult(
            feature,
            uniqueNames,
            uniqueNames.isNotEmpty(),
            if (uniqueNames.isNotEmpty()) "找到 ${uniqueNames.size} 个" else "未找到"
        )
    }

    /**
     * 适配 VerticalViewPager / Feed 流 ViewPager
     *
     * 用于视频过滤、自动播放翻页等。
     * 搜索特征: 竖向 ViewPager / FlippableViewPager
     */
    private fun adaptVerticalViewPager(classLoader: ClassLoader): AdaptationResult {
        val feature = "VerticalViewPager"
        val candidates = listOf(
            "com.ss.android.ugc.aweme.feed.view.FlippableViewPager",
            "com.ss.android.ugc.aweme.feed.view.RTMainFlippableViewPager",
            "com.ss.android.ugc.aweme.feed.ui.FeedViewPager",
            "com.bytedance.ies.uikit.viewpager.VerticalViewPager",
            "androidx.viewpager.widget.ViewPager"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) {}
        }

        // 动态搜索: feed.view 包下含 ViewPager 的类
        if (classNames.isEmpty()) {
            val found = ClassFinder.getClassesInPackage(
                classLoader, "com.ss.android.ugc.aweme.feed.view"
            )
            classNames.addAll(found
                .filter { it.name.contains("ViewPager", ignoreCase = true) ||
                          it.name.contains("Flippable", ignoreCase = true) }
                .map { it.name }
            )
        }

        adaptationResults["viewpager"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到"
        )
    }

    /**
     * 获取适配结果（供 Hook 使用）
     */
    fun getAdaptedClasses(feature: String): List<String> {
        return adaptationResults[feature] ?: emptyList()
    }

    /**
     * 获取适配后的第一个类名
     */
    fun getFirstAdaptedClass(feature: String): String? {
        return adaptationResults[feature]?.firstOrNull()
    }

    /**
     * 保存缓存到文件
     */
    private fun saveCache(douyinVersion: String) {
        try {
            val json = JSONObject()
            json.put("version", douyinVersion)
            json.put("timestamp", System.currentTimeMillis())

            val resultsJson = JSONObject()
            for ((feature, classes) in adaptationResults) {
                val arr = org.json.JSONArray()
                classes.forEach { cls -> arr.put(cls) }
                resultsJson.put(feature, arr)
            }
            json.put("results", resultsJson)

            cacheFile?.let { file ->
                file.parentFile?.mkdirs()
                file.writeText(json.toString())
                file.setReadable(true, false)
                HookUtils.log("$TAG: 缓存已保存: ${file.absolutePath}")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 保存缓存失败: ${t.message}")
        }
    }

    /**
     * 从文件加载缓存
     * @return true 如果缓存命中且版本匹配
     */
    private fun loadCache(douyinVersion: String): Boolean {
        try {
            val file = cacheFile ?: return false
            if (!file.exists()) return false

            val json = JSONObject(file.readText())
            val cachedVersion = json.optString("version", "")

            if (cachedVersion != douyinVersion) {
                HookUtils.log("$TAG: 缓存版本不匹配 (缓存=$cachedVersion, 当前=$douyinVersion)")
                return false
            }

            val resultsJson = json.optJSONObject("results") ?: return false
            for (feature in resultsJson.keys()) {
                val arr = resultsJson.optJSONArray(feature) ?: continue
                val classes = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.let { classes.add(it) }
                }
                adaptationResults[feature] = classes
            }

            HookUtils.log("$TAG: 缓存加载成功，版本: $douyinVersion")
            return true
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 加载缓存失败: ${t.message}")
            return false
        }
    }

    /**
     * 显示 Toast
     */
    private fun showToast(message: String) {
        mainHandler.post {
            try {
                val context = ContextHelper.getContext() ?: return@post
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

}
