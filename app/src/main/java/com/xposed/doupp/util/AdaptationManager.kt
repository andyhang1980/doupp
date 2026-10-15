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
            // 缓存命中也要尊重设置页的「重新扫描电商适配」。
            // 必须另起线程：主流程运行时 ContextHelper 的 context 还没就绪，provider 读不到标记
            Thread {
                runCatching { Thread.sleep(3000) }
                val rescan = runCatching {
                    com.xposed.doupp.ui.DouSettings.consumeRescanFlag()
                }.getOrDefault(false)
                if (rescan) {
                    HookUtils.log("$TAG: 设置页请求重扫，清缓存后重新扫描")
                    invalidateCache()
                    startAdaptation(classLoader, douyinVersion, callback)
                }
            }.apply { isDaemon = true; name = "dyyds-rescan-check"; start() }
            return
        }

        // 缓存未命中，延迟执行动态扫描（避免在抖音启动高峰期大量扫描类导致崩溃）
        Thread {
            try {
                Thread.sleep(3000) // 延迟3秒，等抖音启动完成
            } catch (_: InterruptedException) { return@Thread }

            // 设置页点了「重新扫描电商适配」：标记在模块 prefs，宿主要走 ContentProvider 通道读。
            // 必须放在这里而不是主流程 —— 主流程运行时 ContextHelper 的 context 还没就绪，
            // provider 读不到（表现为"远程 prefs 为空"），标记会被漏掉。
            runCatching {
                if (com.xposed.doupp.ui.DouSettings.consumeRescanFlag()) {
                    invalidateCache()
                    HookUtils.log("$TAG: 设置页请求重扫，已清除适配结果与缓存")
                }
            }

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

            // 9. 适配 yyds 混淆包（新版抖音 R8 混淆类）
            try { results.add(scanYydsPackage(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 扫描yyds包失败: ${t.message}")
            }

            // 10. 适配 SharePanel（新版分享面板）
            try { results.add(adaptSharePanel(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配SharePanel失败: ${t.message}")
            }

            // 11. 适配地区解锁
            try { results.add(adaptRegionUnlock(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配RegionUnlock失败: ${t.message}")
            }

            // 12. 适配自动签到
            try { results.add(adaptAutoSignIn(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配AutoSignIn失败: ${t.message}")
            }

            // 13. 适配消息防撤回
            try { results.add(adaptAntiRevoke(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配AntiRevoke失败: ${t.message}")
            }

            // 14. 适配福袋自动领取
            try { results.add(adaptLuckyBag(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配LuckyBag失败: ${t.message}")
            }

            // 15. 适配商城/电商（40.7.0 全部在主包，语义包名保留）
            try { results.add(adaptMallClasses(classLoader)) } catch (t: Throwable) {
                HookUtils.log("$TAG: 适配商城失败: ${t.message}")
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
            "com.ss.android.ugc.aweme.model.Aweme",
            "com.ss.ugc.aweme.Aweme",
            "com.ss.android.ugc.aweme.feed.model.FeedModel",
            "com.ss.android.ugc.aweme.model.FeedItem"
        )

        // 候选列表优先（40.7.0 上第一个类名仍然存在，没必要全量扫）
        val direct = ClassFinder.findClass(classLoader, candidates)

        // 兜底：按方法名特征找（注意用类名过滤 + 懒加载，不再全量 Class.forName）
        val scanned = if (direct == null) {
            ClassFinder.findByMethodSignature(
                classLoader, "com.ss.android.ugc.aweme",
                listOf("getVideo:Video:", "getAwemeId:String:"),
                nameHints = listOf("Aweme", "FeedModel", "FeedItem")
            )
        } else {
            emptyList()
        }

        val classNames = (listOfNotNull(direct?.name) + scanned.map { it.name }).distinct()
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
     *
     * 40.7.0 实测：`org/chromium/net/` 0 命中，但 `com/ttnet/` 存在（14 个 dex），
     * `CronetUrlRequest` 也在（classes42.dex）——抖音用的是 ttnet 分支的 Cronet，
     * 所以候选表以 ttnet 为主，org.chromium 只作为旧版本兜底。
     */
    private fun adaptCronetClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "Cronet网络库"
        val candidates = listOf(
            "com.ttnet.org.chromium.net.impl.CronetUrlRequest\$Builder",
            "com.ttnet.org.chromium.net.UrlRequest\$Builder",
            "com.ttnet.org.chromium.net.impl.CronetUrlRequest",
            "com.ttnet.org.chromium.net.UrlRequest",
            // 旧版直连 Cronet
            "org.chromium.net.UrlRequest\$Builder",
            "org.chromium.net.UrlRequest"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) { }
        }

        // 包级扫描 + 字符串特征
        if (classNames.isEmpty()) {
            for (pkg in listOf("com.ttnet.org.chromium.net", "org.chromium.net")) {
                classNames.addAll(
                    ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = false, limit = 500)
                        .filter { it.contains("UrlRequest") || it.contains("CronetEngine") }
                        .take(5)
                )
                if (classNames.isNotEmpty()) break
            }
        }
        if (classNames.isEmpty()) {
            classNames.addAll(
                ClassFinder.findByResourceRefs(classLoader, listOf("CronetUrlRequest")).map { it.name }
            )
        }

        adaptationResults["cronet"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个（ttnet 分支）" else "未找到"
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
     * 适配播放器类
     *
     * 40.7.0 实测：`com/google/android/exoplayer2/` 与 `androidx/media3/` 都是 0 命中，
     * 抖音 40.7.0 已经换成自研播放器。原来只探 ExoPlayer/Media3，在新版上永远「未找到」，
     * 所以补一条自研播放器路径。
     */
    private fun adaptExoPlayerClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "播放器"
        val candidates = listOf(
            // ExoPlayer 老版
            "com.google.android.exoplayer2.ExoPlayer",
            "com.google.android.exoplayer2.Player",
            // Media3 迁移期
            "androidx.media3.exoplayer.ExoPlayer",
            "androidx.media3.player.Player",
            // 抖音自研播放器（40.x）
            "com.ss.android.ugc.aweme.player.AwemePlayer",
            "com.ss.android.ugc.aweme.player.PlayerManager",
            "com.ss.android.ugc.aweme.player.selector.PlayerSelector"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) { }
        }

        // 包级扫描：三个位置都扫一遍
        if (classNames.isEmpty()) {
            for (pkg in listOf(
                "com.google.android.exoplayer2",
                "androidx.media3",
                "com.ss.android.ugc.aweme.player"
            )) {
                classNames.addAll(
                    ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = false, limit = 200)
                        .filter { it.endsWith("ExoPlayer") || it.endsWith("Player") || it.endsWith("PlayerManager") }
                        .take(3)
                )
                if (classNames.isNotEmpty()) break
            }
        }

        adaptationResults["exoplayer"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个"
            else "未发现 ExoPlayer/Media3（40.7.0 为自研播放器）"
        )
    }

    /**
     * 适配热更新框架类
     *
     * 40.7.0 实测：Tinker 0 命中、`com/taobao/sophix/` 0 命中、`com/ss/android/ugc/aweme/mira` 0 命中；
     * 实际在用的是 `com/ss/android/ugc/aweme/ies`（classes20.dex），即字节 ies patch 路线。
     * 所以候选表把 ies 提到最前面，Tinker/Sophix 保留作旧版本兜底。
     */
    private fun adaptHotUpdateClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "热更新框架"
        val candidates = listOf(
            "com.ss.android.ugc.aweme.ies.patch.PatchManager",
            "com.ss.android.ugc.aweme.ies.patch.Patch",
            "com.bytedance.ies.patch.PatchManager",
            "com.bytedance.ugc.patch.PatchManager",
            "com.bytedance.boost.patch.PatchLoader",
            "com.bytedance.mira.MiraManager",
            "com.bytedance.hotfix.HotfixManager",
            "com.tencent.tinker.lib.tinker.Tinker",
            "com.taobao.sophix.SophixManager"
        )

        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                Class.forName(name, false, classLoader)
                classNames.add(name)
            } catch (_: ClassNotFoundException) { }
        }

        // 包级扫描：字节 ies / patch 路线
        if (classNames.isEmpty()) {
            for (pkg in listOf(
                "com.ss.android.ugc.aweme.ies",
                "com.bytedance.ies.patch",
                "com.bytedance.ugc.patch",
                "com.bytedance.boost.patch",
                "com.bytedance.mira"
            )) {
                classNames.addAll(
                    ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = false, limit = 300)
                        .filter { it.contains("Patch", true) || it.contains("Hotfix", true) || it.contains("Mira", true) }
                        .take(5)
                )
                if (classNames.isNotEmpty()) break
            }
        }

        adaptationResults["hotupdate"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个（ies patch 路线）"
            else "未发现 Tinker/Sophix（40.7.0 走 ies patch）"
        )
    }

    /**
     * 适配广告SDK类
     *
     * 40.7.0 实测：只有穿山甲 `com/bytedance/sdk/openadsdk` 在（8 个 dex），
     * 广点通 / 快手 / 百度 三家 0 命中。列表保留（换版本还会回来），但不再用 getClassesInPackage
     * 全量加载，改成按类名过滤。
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

        val found = LinkedHashMap<String, MutableList<String>>()
        for (pkg in sdkPackages) {
            val names = ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = false, limit = 100)
            if (names.isNotEmpty()) found[pkg] = names.take(3).toMutableList()
        }
        val classNames = found.values.flatten()
        adaptationResults["adsdk"] = classNames
        val detail = if (found.isEmpty()) "未发现广告 SDK" else found.keys.joinToString(", ")
        return AdaptationResult(feature, classNames, classNames.isNotEmpty(), detail)
    }

    /**
     * 适配 AutoPlayController（官方自动播放控制器）
     *
     * 40.7.0 实测：`auto_play_key` 还在（classes43.dex），但它所在的类搬到了 plato 架构的新包：
     *   com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.*
     *   com.ss.android.ugc.aweme.feed.extend.autoplay.AutoPlayExtendEvent
     * AutoPlay 前缀的语义类名整体保留（AutoPlayConfig / AutoPlayTrigger / AutoPlayViewModel），
     * 所以优先按类名锚定，再用字符串特征补齐。
     */
    private fun adaptAutoPlayController(classLoader: ClassLoader): AdaptationResult {
        val feature = "AutoPlayController"
        val classNames = mutableListOf<String>()

        // 1. 语义类名锚点（最稳：这些类名没有被混淆）
        val anchors = listOf(
            // 40.7.0 实测：AutoPlayController 在 follow_shoot_fra.play_control 下，不在 autoplay 包
            "com.ss.android.ugc.aweme.follow_shoot_fra.play_control.AutoPlayController",
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayComponent",
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayViewModel",
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayTrigger",
            "com.ss.android.ugc.aweme.feed.AutoPlayViewModel",
            "com.ss.android.ugc.aweme.feed.extend.autoplay.AutoPlayExtendEvent",
            "com.bytedance.kmp.autoplay.FeedAutoPlayBridgeComponent"
        )
        ClassFinder.findClass(classLoader, anchors)?.let { classNames.add(it.name) }

        // 2. 新旧包路径下按名字找
        val searchPackages = listOf(
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay",
            "com.ss.android.ugc.aweme.feed.extend.autoplay",
            "com.ss.android.ugc.aweme.feed",
            "com.ss.android.ugc.aweme.auto",
            "com.ss.android.ugc.aweme.player"
        )
        for (pkg in searchPackages) {
            classNames.addAll(
                ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = false, limit = 3000)
                    .filter { it.contains("AutoPlay", ignoreCase = true) }
            )
        }

        // 3. 字符串特征补齐（这次是真读 dex 字符串池 + const-string 指令）
        for (pkg in searchPackages) {
            classNames.addAll(
                ClassFinder.findByStringConstants(classLoader, pkg, listOf("auto_play_key")).map { it.name }
            )
        }

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
            "com.ss.android.ugc.aweme.base.ui.FlippableViewPager",
            "com.ss.android.ugc.aweme.feed.view.RTMainFlippableViewPager",
            "com.ss.android.ugc.aweme.homepage.perfopt.switchsmooth.ui.RTMainFlippableViewPager",
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
    /**
     * 强制下次启动重新扫描（清掉内存结果 + 缓存文件）。
     *
     * 触发方式：设置页「重新扫描电商适配」按钮，或
     * adb shell content call --uri content://com.xposed.doupp.settings --method rescan
     * 场景：模块升级换了锚点、抖音同版本号重打包、用户换了设备。
     */
    fun invalidateCache() {
        adaptationResults.clear()
        adaptationDone = false
        DexStringIndex.clear()
        runCatching {
            cacheFile?.takeIf { it.exists() }?.delete()
            HookUtils.log("$TAG: 适配缓存已清除，下次启动将重新扫描")
        }
    }
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
            // 同版本号重打包也会改适配结果，所以带上 APK 指纹
            json.put("apkStamp", apkStamp())
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

            val cachedStamp = json.optString("apkStamp", "")
            if (cachedStamp.isNotEmpty() && cachedStamp != apkStamp()) {
                HookUtils.log("$TAG: 缓存 APK 指纹不匹配，重新扫描")
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
     * 扫描混淆包。
     *
     * README 里写的「新版抖音所有应用类都在 yyds 包下」这条在 40.7.0 上已经不成立：
     * 55 个 dex 里 `Lyyds/` 零命中，应用类被打散进 X / Y / O / m / ms / j6 这类单双字母根
     * （X、Y 两个根覆盖全部 55 个 dex）。所以改成动态发现混淆包根，同时保留 yyds 兼容旧版本。
     */
    private fun scanYydsPackage(classLoader: ClassLoader): AdaptationResult {
        val feature = "混淆包扫描"
        val classNames = mutableListOf<String>()

        try {
            val roots = ClassFinder.obfuscatedRoots(classLoader)
            HookUtils.log("$TAG: 混淆包根 = $roots")

            // 1. 自动播放相关类（新版落在 feed.plato.business.contentconsumption.autoplay 下）
            val autoPlayAnchors = ClassFinder.classNamesInPackage(
                classLoader,
                "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay",
                includeInner = false, limit = 200
            ) + ClassFinder.classNamesInPackage(
                classLoader, "com.ss.android.ugc.aweme.feed.extend.autoplay",
                includeInner = false, limit = 100
            ) + ClassFinder.classNamesInPackage(
                classLoader, "com.ss.android.ugc.aweme.feed",
                includeInner = false, limit = 4000
            ).filter { it.endsWith(".AutoPlayViewModel") }
            if (autoPlayAnchors.isNotEmpty()) {
                adaptationResults["autoplay_yyds"] = autoPlayAnchors.distinct()
                classNames.addAll(autoPlayAnchors)
            }

            // 2. 分享面板相关类（40.7.0 的锚点：detail_page_panel.quick.presenter.bottom + share.viewmodel）
            val shareAnchors = ArrayList<String>()
            shareAnchors.addAll(
                ClassFinder.classNamesInPackage(
                    classLoader, "com.ss.android.ugc.aweme.feed.detail_page_panel.quick.presenter.bottom",
                    includeInner = false, limit = 300
                ).filter { it.contains("Share", ignoreCase = true) }
            )
            shareAnchors.addAll(
                ClassFinder.classNamesInPackage(
                    classLoader, "com.ss.android.ugc.aweme.share.viewmodel",
                    includeInner = false, limit = 300
                )
            )
            if (shareAnchors.isNotEmpty()) {
                val uniq = shareAnchors.distinct()
                adaptationResults["share_panel"] = uniq
                classNames.addAll(uniq)
                HookUtils.log("$TAG: 分享面板候选 = $uniq")
            }

            // 3. feed / ViewPager 相关类（在混淆根下按名字找）
            val pagerAnchors = ArrayList<String>()
            for (r in roots.take(6)) {
                pagerAnchors.addAll(
                    ClassFinder.classNamesInPackage(classLoader, r, includeInner = false, limit = 40_000)
                        .filter { it.contains("ViewPager") || it.contains("Flippable") }
                        .take(40)
                )
                if (pagerAnchors.size >= 80) break
            }
            if (pagerAnchors.isNotEmpty()) {
                adaptationResults["feed_view_yyds"] = pagerAnchors.distinct()
                classNames.addAll(pagerAnchors)
            }

            // 4. 旧版 yyds 仍然保留一份，兼容还在读这个 key 的代码
            val legacyYyds = ClassFinder.classNamesInPackage(classLoader, "yyds", limit = 2000)
            if (legacyYyds.isNotEmpty()) adaptationResults["yyds"] = legacyYyds
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 混淆包扫描异常: ${t.message}")
        }

        adaptationResults["obfuscated_roots"] = classNames
        return AdaptationResult(
            feature,
            classNames,
            classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "命中 ${classNames.size} 个关键类" else "未发现混淆包"
        )
    }

    /**
     * 适配 SharePanel — 新版抖音 share_bottom_sheet
     *
     * 新版分享面板使用 R.layout.share_bottom_sheet 布局，
     * 包含 share_hsv、share_view、video_view、music_view 等 ID。
     * 检测方法: 搜索字符串 "share_bottom_sheet" 定位关键类。
     */
    private fun adaptSharePanel(classLoader: ClassLoader): AdaptationResult {
        val feature = "SharePanel适配"
        val classNames = mutableListOf<String>()

        try {
            // 1. 语义类名锚点（40.7.0 实测存在）
            ClassFinder.findClass(
                classLoader,
                listOf(
                    "com.ss.android.ugc.aweme.feed.detail_page_panel.quick.presenter.bottom.DPPBottomSharePresenter",
                    "com.ss.android.ugc.aweme.share.viewmodel.FeedPanelStateViewModel",
                    "com.ss.android.ugc.aweme.feedliveshare.ui.FeedShareFragmentPanel"
                )
            )?.let { classNames.add(it.name) }

            // 2. 字符串特征
            //    40.7.0 上 share_bottom_sheet / share_hsv 已经没了（0 命中），
            //    换成仍在的 share_panel / share_bottom / share_bar
            val keywords = listOf("share_panel", "share_bottom", "share_bar", "shareAddTool")
            val searchPackages = listOf(
                "com.ss.android.ugc.aweme.share",
                "com.ss.android.ugc.aweme.feed.detail_page_panel.quick",
                "com.ss.android.ugc.aweme.feedliveshare",
                "com.ss.android.ugc.aweme"
            )
            for (pkg in searchPackages) {
                for (kw in keywords) {
                    classNames.addAll(
                        ClassFinder.findByStringConstants(classLoader, pkg, listOf(kw)).map { it.name }
                    )
                }
            }
            // 3. 旧版锚点保留（旧版本抖音还能命中）
            for (kw in listOf("share_bottom_sheet", "share_hsv")) {
                classNames.addAll(
                    ClassFinder.findByStringConstants(classLoader, "com.ss.android.ugc.aweme", listOf(kw))
                        .map { it.name }
                )
            }
            HookUtils.log("$TAG: 分享面板候选 = ${classNames.distinct()}")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: SharePanel适配异常: ${t.message}")
        }

        val uniq = classNames.distinct()
        if (uniq.isNotEmpty()) {
            val existing = adaptationResults["share_panel"] ?: emptyList()
            adaptationResults["share_panel"] = (existing + uniq).distinct()
        }

        return AdaptationResult(
            feature,
            uniq,
            uniq.isNotEmpty(),
            if (uniq.isNotEmpty()) "适配完成 ${uniq.size} 个" else "未检测到分享面板"
        )
    }

    /**
     * 适配地区解锁类
     */
    private fun adaptRegionUnlock(classLoader: ClassLoader): AdaptationResult {
        val feature = "地区解锁"
        val candidates = listOf(
            "com.ss.android.ugc.aweme.region.RegionCheck",
            "com.ss.android.ugc.aweme.region.RegionManager",
            "com.ss.android.ugc.aweme.geo.GeoRestrictManager",
            "com.ss.android.ugc.aweme.geo.GeoRestrictHelper",
            "com.ss.android.ugc.aweme.feed.model.FeedItem",
            "com.ss.android.ugc.aweme.global.configfeed.GConfigFeedManager"
        )
        val methodNames = listOf(
            "isRestricted", "isBlocked", "checkRegion", "isGeoRestricted",
            "isRegionBlocked", "checkGeoRestrict"
        )
        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                val clazz = Class.forName(name, false, classLoader)
                val hasTarget = clazz.declaredMethods.any { m -> m.name in methodNames }
                if (hasTarget) classNames.add(name)
            } catch (_: ClassNotFoundException) { }
        }
        adaptationResults["region_unlock"] = classNames
        return AdaptationResult(feature, classNames, classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到")
    }

    /**
     * 适配自动签到类
     */
    private fun adaptAutoSignIn(classLoader: ClassLoader): AdaptationResult {
        val feature = "自动签到"
        val candidates = listOf(
            "com.ss.android.ugc.aweme.signin.SignInManager",
            "com.ss.android.ugc.aweme.signin.SignInHelper",
            "com.ss.android.ugc.aweme.task.DailyTaskManager",
            "com.ss.android.ugc.aweme.task.SignInTask",
            "com.ss.android.ugc.aweme.reward.SignInRewardManager"
        )
        val methodNames = listOf(
            "isAutoSignInEnabled", "shouldAutoSignIn",
            "isSignedToday", "hasSignedIn", "isSignInAvailable"
        )
        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                val clazz = Class.forName(name, false, classLoader)
                val hasTarget = clazz.declaredMethods.any { m -> m.name in methodNames }
                if (hasTarget) classNames.add(name)
            } catch (_: ClassNotFoundException) { }
        }
        adaptationResults["auto_signin"] = classNames
        return AdaptationResult(feature, classNames, classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到")
    }

    /**
     * 适配消息防撤回类
     */
    private fun adaptAntiRevoke(classLoader: ClassLoader): AdaptationResult {
        val feature = "消息防撤回"
        val candidates = listOf(
            "com.ss.android.ugc.aweme.message.im.IMManager",
            "com.ss.android.ugc.aweme.message.MessageManager",
            "com.ss.android.ugc.aweme.message.im.MessageRevokeHandler",
            "com.ss.android.ugc.aweme.message.im.chat.ChatManager",
            "com.ss.android.ugc.aweme.im.sdk.chat.ChatServiceManager"
        )
        val methodNames = listOf(
            "onMessageRevoked", "handleRevokeMessage",
            "revokeMessage", "processRevoke", "onRevokeMessage"
        )
        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                val clazz = Class.forName(name, false, classLoader)
                val hasTarget = clazz.declaredMethods.any { m -> m.name in methodNames }
                if (hasTarget) classNames.add(name)
            } catch (_: ClassNotFoundException) { }
        }
        adaptationResults["anti_revoke"] = classNames
        return AdaptationResult(feature, classNames, classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到")
    }

    /**
     * 适配福袋自动领取类
     */
    private fun adaptLuckyBag(classLoader: ClassLoader): AdaptationResult {
        val feature = "福袋自动领取"
        val candidates = listOf(
            "com.ss.android.ugc.aweme.live.luckybag.LuckyBagManager",
            "com.ss.android.ugc.aweme.live.luckybag.LuckyBagViewModel",
            "com.ss.android.ugc.aweme.live.luckybag.LuckyBagHelper",
            "com.ss.android.ugc.aweme.live.model.LuckyBagInfo",
            "com.ss.android.ugc.aweme.live.stream.LiveStreamLuckyBagManager"
        )
        val methodNames = listOf(
            "isLuckyBagAutoClaimEnabled", "shouldAutoClaim",
            "isLuckyBagAvailable", "canClaimLuckyBag"
        )
        val classNames = mutableListOf<String>()
        for (name in candidates) {
            try {
                val clazz = Class.forName(name, false, classLoader)
                val hasTarget = clazz.declaredMethods.any { m -> m.name in methodNames }
                if (hasTarget) classNames.add(name)
            } catch (_: ClassNotFoundException) { }
        }
        adaptationResults["lucky_bag"] = classNames
        return AdaptationResult(feature, classNames, classNames.isNotEmpty(),
            if (classNames.isNotEmpty()) "找到 ${classNames.size} 个" else "未找到")
    }

    /**
     * 宿主 APK 指纹 = versionCode + lastModified + 长度。
     * 抖音同一个版本号重打包很常见，只比版本号会吃到过期缓存。
     */
    private fun apkStamp(): String = runCatching {
        val f = File(ContextHelper.getContext()?.applicationInfo?.sourceDir ?: return "")
        "${f.length()}:${f.lastModified()}"
    }.getOrDefault("")

    /**
     * 显示 Toast
     */
    /**
     * 适配商城/电商类（40.7.0 实测：全部在主包，没有独立商城 App）
     *
     * 电商语义包名整体保留：commerce.* 51 个 dex、ecommerce.* 11 个、ec.* 3 个
     * 带货标记字段名也保留：is_ecommerce / isEC / ecVideo / is_shopping
     */
    private fun adaptMallClasses(classLoader: ClassLoader): AdaptationResult {
        val feature = "商城电商"
        val classNames = mutableListOf<String>()

        // 1) 语义类名锚定（按类名，最稳）
        val anchors = listOf(
            // 视频内商品卡 / 角标
            "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product.ECVideoInnerProductCardView",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product.ECTagLayout",
            "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product.ECDotLayout",
            // 商品卡数据
            "com.ss.android.ugc.aweme.commerce.sdk.feedcard.product.net.ECProductCard",
            "com.ss.android.ugc.aweme.commerce.sdk.feedcard.product.net.ECProductInfo",
            // 信息流商城卡
            "com.ss.android.ugc.aweme.bizmodule.fullfeed.feedCard.module.shopcard.FeedShopCardPresenter",
            "com.ss.android.ugc.aweme.bizmodule.fullfeed.feedCard.view.feedshopcard.FeedNearbyShopView",
            // 电商直播盒子
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.ECLiveBoxForLynx",
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.HybridLiveBoxForLynxV2",
            "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive.HybridLiveBoxForLynxV3",
            // 直播电商状态 / 直播广告
            "com.ss.android.ugc.aweme.commerce.sdk.feed.live.ECCheckliveStatusManager",
            "com.ss.android.ugc.aweme.ad.LiveAdMiddleWareService",
            // 字节电商 SDK 那一支（40.7.0 设备版实测：商城入口/浮层在这里，不在 aweme.commerce 下）
            "com.bytedance.android.window.shop.home.module.ECWindowFloatCartEntryModule",
            "com.bytedance.android.shopping.mall.homepage.component.ECMFTopBarComponent",
            "com.bytedance.android.shopping.mall.integration.impl.ECMallHomepageService",
            "com.bytedance.android.shopping.mall.homepage.card.product.ProductPriceView",
            // 挑战页电商挂件
            "com.ss.android.ugc.aweme.challenge.ui.header.widget.commerce.CommerceLiveCoverWidget"
        )
        for (n in anchors) {
            runCatching {
                Class.forName(n, false, classLoader)
                classNames.add(n)
            }
        }
        HookUtils.log("$TAG: 电商锚点命中 ${classNames.size}/")

        // 2) 电商包下按类名补捞（40.7.0 电商类数量很大，只捞视图/卡片相关）
        if (classNames.size < anchors.size) {
            for (pkg in listOf(
                "com.ss.android.ugc.aweme.commerce.sdk.feed.video.product",
                "com.ss.android.ugc.aweme.commerce.sdk.hybrid.xelement.ecomlive",
                "com.ss.android.ugc.aweme.ecommerce"
            )) {
                classNames.addAll(
                    ClassFinder.classNamesInPackage(classLoader, pkg, includeInner = false, limit = 300)
                        .filter { it.endsWith("View") || it.endsWith("Layout") || it.endsWith("Card") }
                )
            }
        }

        // 3) 字符串特征兜底（feedShopCard / live_commerce）
        if (classNames.isEmpty()) {
            for (kw in listOf("feedShopCard", "live_commerce")) {
                classNames.addAll(
                    ClassFinder.findClassNamesByStrings(classLoader, listOf("com.ss.android.ugc.aweme"), listOf(kw))
                )
            }
        }

        val uniq = classNames.distinct()
        adaptationResults["mall"] = uniq
        return AdaptationResult(
            feature,
            uniq,
            uniq.isNotEmpty(),
            if (uniq.isNotEmpty()) "锚点 ${classNames.size}/，共 ${uniq.size} 个类" else "未发现电商类"
        )
    }
    private fun showToast(message: String) {
        mainHandler.post {
            try {
                val context = ContextHelper.getContext() ?: return@post
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

}
