package com.xposed.doupp.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体缓存
 *
 * 适配最新版抖音:
 * - 扩展 Video 字段名候选（新增 downloadAddr, playAddr265, bitRate 等）
 * - 扩展 Image 字段名候选
 * - 改进 URL 提取逻辑
 *
 * 用于在不同 Hook 模块之间共享当前正在浏览的媒体信息。
 * 主要场景:
 * - FeedHook 处理 Aweme 数据时缓存当前 Aweme
 * - LivePhotoHook 检测到实况照片时缓存 URL
 * - SharePanelHook 用户点击保存按钮时从缓存读取
 * - DownloadDialogHook 用户长按保存实况时从缓存读取
 */
object MediaCache {

    /** 当前正在浏览的 Aweme 对象 */
    @Volatile
    private var currentAweme: Any? = null

    /** 时长日志节流 */
    private var lastDurLog = 0L

    /** 实况照片 URL 缓存: livePhotoId -> Pair(imageUrl, videoUrl) */
    private val livePhotoCache = ConcurrentHashMap<String, Pair<String, String>>()

    // ==================== Aweme 缓存 ====================

    fun setCurrentAweme(aweme: Any?) {
        currentAweme = aweme
    }

    fun getCurrentAweme(): Any? = currentAweme

    // ==================== 实况照片缓存 ====================

    fun cacheLivePhotoUrls(id: String, imageUrl: String, videoUrl: String) {
        livePhotoCache[id] = Pair(imageUrl, videoUrl)
        HookUtils.log("MediaCache: 缓存实况照片 [$id]")
    }

    fun getLivePhotoUrls(id: String): Pair<String, String>? = livePhotoCache[id]

    fun removeLivePhoto(id: String) {
        livePhotoCache.remove(id)
    }

    fun clearLivePhotoCache() {
        livePhotoCache.clear()
    }

    /** 获取所有缓存的实况照片 (供 DownloadDialogHook 遍历) */
    fun getAllCachedLivePhotos(): Map<String, Pair<String, String>> = livePhotoCache.toMap()

    /**
     * 根据 URL 特征查找匹配的实况照片缓存
     * 当不知道确切 ID 时，通过 URL 匹配
     */
    fun findLivePhotoByUrl(url: String): Pair<String, String>? {
        for ((_, pair) in livePhotoCache) {
            if (url.contains(pair.first) || url.contains(pair.second)) {
                return pair
            }
        }
        return null
    }

    // ==================== 辅助方法 ====================

    /** Video 字段名候选（扩展） */
    private val VIDEO_FIELD_NAMES = listOf("video", "videoModel", "mVideo")

    /** PlayAddr 字段名候选（扩展） */
    private val PLAY_ADDR_FIELD_NAMES = listOf(
        "playAddr", "play_addr",
        "downloadAddr", "download_addr",   // 新版抖音下载地址
        "playAddrH264", "playAddrH265",    // 多码率
        "playAddr265", "playAddrLow",
        "playAddrNormal", "playAddrHigh",
        "bitRate", "bitRateList"
    )

    /** URL List 字段名候选 */
    private val URL_LIST_FIELD_NAMES = listOf("urlList", "url_list", "urls")

    /** Image 字段名候选（扩展） */
    private val IMAGE_FIELD_NAMES = listOf(
        "images", "imageList", "image_list",
        "picList", "pic_list",
        "photoList", "photo_list",
        "imageModels", "image_models"
    )

    /** Music 字段名候选 */
    private val MUSIC_FIELD_NAMES = listOf(
        "music", "musicModel", "mMusic",
        "bgm", "backgroundMusic", "sound"
    )

    /** Music URL 字段名候选 */
    private val MUSIC_URL_FIELD_NAMES = listOf(
        "playUrl", "play_url",
        "url", "urlList", "url_list",
        "musicUrl", "music_url",
        "downloadUrl", "download_url"
    )

    /** Music 标题字段名候选 */
    private val MUSIC_TITLE_FIELD_NAMES = listOf(
        "title", "musicName", "music_name",
        "name", "songName", "song_name"
    )

    /** Aweme 描述字段名候选 */
    private val DESC_FIELD_NAMES = listOf(
        "desc", "description", "content",
        "caption", "text", "shareInfo"
    )

    /**
     * 获取 Aweme 中的视频 URL（优先无水印版本）
     *
     * 去水印核心策略:
     * 1. 收集 Video 对象中所有候选 URL（downloadAddr、各种 playAddr、递归扫描）
     * 2. 优先选择“带水印标记”的 URL（含 playwm / logo_type / /mps/logo/ / watermark），
     *    因为这类 URL 经过 UrlParser 去水印后能得到干净版本
     * 3. 若没有任何带标记 URL，则退回到 downloadAddr 的第一个 URL
     */
    fun getVideoUrlFromAweme(aweme: Any): String? {
        try {
            // 查找 Video 对象（使用 getFieldDeep 查找父类字段，兼容混淆后字段在父类的情况）
            var video: Any? = null
            for (fieldName in VIDEO_FIELD_NAMES) {
                video = getFieldDeep(aweme, fieldName)
                if (video != null) break
            }
            if (video == null) return null

            HookUtils.log("MediaCache: 找到 Video 对象: ${video.javaClass.name}")

            val candidates = mutableListOf<String>()

            fun addFromAddr(addr: Any?) {
                if (addr == null) return
                // urlList 中所有 http 链接都收集（不只取第一个）
                for (listName in URL_LIST_FIELD_NAMES) {
                    val urlList = getFieldDeep(addr, listName) as? List<*>
                    urlList?.filterIsInstance<String>()
                        ?.filter { it.startsWith("http") }
                        ?.let { candidates.addAll(it) }
                }
                if (addr is String && addr.startsWith("http")) candidates.add(addr)
            }

            // downloadAddr（优先使用 getFieldDeep 查找父类字段）
            addFromAddr(getFieldDeep(video, "downloadAddr") ?: getFieldDeep(video, "download_addr"))
            // 各种 playAddr 字段（同样使用 getFieldDeep）
            for (addrName in PLAY_ADDR_FIELD_NAMES) {
                addFromAddr(getFieldDeep(video, addrName))
            }
            // 新增：尝试查找 videoResource 字段（新版抖音结构）
            addFromAddr(getFieldDeep(video, "videoResource"))
            addFromAddr(getFieldDeep(video, "mVideoResource"))
            // 新增：尝试查找 mediaUrl 字段
            addFromAddr(getFieldDeep(video, "mediaUrl"))
            addFromAddr(getFieldDeep(video, "mMediaUrl"))
            // 递归兜底
            findAllUrlsInObject(video, 0, candidates)

            if (candidates.isEmpty()) return null

            HookUtils.log("MediaCache: 候选 URL 数=${candidates.size}")
            candidates.forEachIndexed { i, u ->
                HookUtils.log("MediaCache: 候选[$i] ${u.take(150)}")
            }

            // 0) 优先使用官方播放接口 api-play 的无水印地址（抖音 39.x 去水印核心）
            //    api-play.amemv.com/aweme/v1/play/?...&watermark=1 为带水印，
            //    改为 watermark=0（或直接选取无 watermark=1 的 file_id 变体）即为无水印源。
            val apiPlayUrl = findApiPlayUrl(candidates)
            if (apiPlayUrl != null) {
                HookUtils.log("MediaCache: 选用 api-play 无水印 URL -> ${apiPlayUrl.take(150)}")
                return apiPlayUrl
            }

            // 0.5) 优先用 bitRateList 中各码率的 playAddr（抖音 39.5 的 play_addr/download_addr
            //    已烧入水印，而 bitRateList 的 rendition 才是干净源；同时取最低码率保证兼容性）
            val brUrl = extractBitRatePlayAddr(video)
            if (brUrl != null) {
                HookUtils.log("MediaCache: 选用 bitRateList 干净源 URL -> ${brUrl.take(150)}")
                return brUrl
            }

            // 0.5) 尝试从 videoResource 中提取干净 URL
            val videoResourceUrl = extractUrlFromVideoResource(video)
            if (videoResourceUrl != null) {
                HookUtils.log("MediaCache: 选用 videoResource 干净源 URL -> ${videoResourceUrl.take(150)}")
                return videoResourceUrl
            }

            // 1) 兜底：播放直链干净源。
            //    抖音 39.5 中，未烧水印的播放 rendition 都在视频 CDN 上，且 URL 不含水印标记：
            //    - 水印标记：playwm / watermark=1 / mps/logo / /logo/
            //    - 含上述标记的（含 api-play?watermark=0）在 39.5 仍返回带水印文件，必须排除
            //    只要是不带水印标记、且落在视频 CDN 上的 URL，即视为干净播放源。
            val cleanCdn = candidates.firstOrNull { u -> isCleanPlaybackUrl(u) }
            if (cleanCdn != null) {
                HookUtils.log("MediaCache: 选用 播放直链(无水印) URL -> ${cleanCdn.take(150)}")
                return cleanCdn
            }

            // 1.5) 尝试从 candidates 中选择最长的 URL（通常最高清）
            val longestUrl = candidates.maxByOrNull { it.length }
            if (longestUrl != null && !isLikelyWatermarked(longestUrl)) {
                HookUtils.log("MediaCache: 选用最长 URL -> ${longestUrl.take(150)}")
                return longestUrl
            }

            // 2) 兜底：带水印标记的 URL（playwm/watermark=1 等）做去水印转换
            val marked = candidates.firstOrNull { u ->
                u.contains("playwm", ignoreCase = true) ||
                u.contains("logo_type") ||
                u.contains("/mps/logo/", ignoreCase = true) ||
                u.contains("watermark", ignoreCase = true)
            }
            val chosen = marked ?: candidates.first()
            val result = UrlParser.getNoWatermarkUrl(chosen)
            HookUtils.log("MediaCache: 选用 ${if (marked != null) "带水印标记" else "默认"} URL -> ${result.take(150)}")
            return result
        } catch (t: Throwable) {
            HookUtils.log("MediaCache: getVideoUrl 失败: ${t.message}")
        }
        return null
    }

    /**
     * 从 videoResource 对象中提取干净 URL
     */
    private fun extractUrlFromVideoResource(video: Any): String? {
        try {
            val videoResource = getFieldDeep(video, "videoResource") ?: getFieldDeep(video, "mVideoResource")
            if (videoResource == null) return null

            // 尝试直接获取 URL
            for (listName in URL_LIST_FIELD_NAMES) {
                val urlList = getFieldDeep(videoResource, listName) as? List<*>
                if (urlList != null) {
                    val cleanUrl = urlList.filterIsInstance<String>()
                        .filter { it.startsWith("http") }
                        .firstOrNull { isCleanPlaybackUrl(it) }
                    if (cleanUrl != null) return cleanUrl
                }
            }

            // 尝试递归查找
            return findUrlInObject(videoResource, depth = 0)
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * 判断 URL 是否可能带水印
     */
    private fun isLikelyWatermarked(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("playwm") ||
                lower.contains("watermark") ||
                lower.contains("logo_name") ||
                lower.contains("wm_type") ||
                lower.contains("/mps/logo/")
    }

    /**
     * 从 Video 对象的 bitRateList 中提取最低码率的干净 playAddr URL。
     *
     * 抖音 39.5 中顶层 play_addr / download_addr 已烧入水印，而 bitRateList
     * 里各码率的 rendition（playAddr）是未烧水印的干净源。取最低码率可同时
     * 保证清晰度适中、兼容性最好（避免高码率/高分辨率在某些播放器打不开）。
     */
    private fun extractBitRatePlayAddr(video: Any): String? {
        try {
            val brList = getFieldDeep(video, "bitRateList")
                ?: getFieldDeep(video, "bitrateList")
                ?: return null
            if (brList !is List<*>) return null

            var best: Pair<Int, String>? = null
            for (entry in brList) {
                if (entry == null) continue
                val bitRate = (getFieldDeep(entry, "bitRate") as? Number)?.toInt()
                    ?: (getFieldDeep(entry, "bitrate") as? Number)?.toInt()
                    ?: continue
                val playAddr = getFieldDeep(entry, "playAddr")
                    ?: getFieldDeep(entry, "play_addr")
                    ?: continue
                val urls = collectUrlsFromAddr(playAddr) ?: continue
                val clean = urls.firstOrNull { u -> isCleanPlaybackUrl(u) }
                val url = clean ?: urls.firstOrNull { it.startsWith("http") } ?: continue
                if (best == null || bitRate < best.first) {
                    best = Pair(bitRate, url)
                }
            }
            return best?.second
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * 从候选 URL 中挑选官方播放接口 api-play 的无水印地址（抖音 39.x 去水印核心）。
     *
     * 抖音 39.x 的视频播放走 api-play.amemv.com/aweme/v1/play/ 接口，
     * 该接口通过 watermark=1 参数控制水印：watermark=1 为带水印，
     * 改为 watermark=0（或直接选取不带 watermark=1 的 file_id 变体）即为无水印源。
     * 这里优先取带 watermark=1 的地址交给 UrlParser 统一改为 watermark=0，
     * 若无 watermark=1 则退回到不带该参数的 file_id 变体。
     */
    private fun findApiPlayUrl(candidates: List<String>): String? {
        val apiPlays = candidates.filter {
            it.contains("amemv.com/aweme/v1/play", ignoreCase = true) &&
            !it.contains("/play/dash/", ignoreCase = true)
        }
        if (apiPlays.isEmpty()) return null
        // 优先带 watermark=1 的地址，交由 UrlParser 改为 watermark=0（抖音标准去水印）
        val wm = apiPlays.firstOrNull { it.contains("watermark=1", ignoreCase = true) }
        val chosen = wm
            ?: apiPlays.firstOrNull { !it.contains("watermark=1", ignoreCase = true) }
            ?: apiPlays.first()
        return UrlParser.getNoWatermarkUrl(chosen)
    }

    /**
     * 判断一个 URL 是否为未烧水印的播放源：
     * 落在抖音视频 CDN 上且不含水印标记（playwm / watermark=1 / mps-logo / /logo/）。
     */
    private fun isCleanPlaybackUrl(u: String): Boolean {
        return (u.contains("douyinvod.com", ignoreCase = true) ||
                u.contains("bytevcloudcdn", ignoreCase = true) ||
                u.contains("tiktokcdn", ignoreCase = true) ||
                u.contains("muscdn.com", ignoreCase = true) ||
                u.contains("ibytedtos.com", ignoreCase = true) ||
                u.contains("tos-cn-v", ignoreCase = true))
            && !u.contains("playwm", ignoreCase = true)
            && !u.contains("watermark=1", ignoreCase = true)
            && !u.contains("mps/logo", ignoreCase = true)
            && !u.contains("/logo/", ignoreCase = true)
    }

    /** 从 addr 对象收集所有 http URL（urlList / url_list / urls） */
    private fun collectUrlsFromAddr(addr: Any): List<String>? {
        val out = mutableListOf<String>()
        for (listName in URL_LIST_FIELD_NAMES) {
            val urlList = getFieldDeep(addr, listName) as? List<*>
            urlList?.filterIsInstance<String>()
                ?.filter { it.startsWith("http") }
                ?.let { out.addAll(it) }
        }
        if (addr is String && addr.startsWith("http")) out.add(addr)
        return if (out.isEmpty()) null else out
    }

    /**
     * 从 PlayAddr 对象中提取 URL
     */
    private fun extractUrlFromAddrObject(addrObj: Any): String? {
        // 尝试 urlList 字段
        for (listName in URL_LIST_FIELD_NAMES) {
            val urlList = getField(addrObj, listName) as? List<*>
            val url = urlList?.filterIsInstance<String>()?.firstOrNull { it.isNotEmpty() && it.startsWith("http") }
            if (url != null) return url
        }

        // 尝试直接是 String
        if (addrObj is String && addrObj.startsWith("http")) return addrObj

        return null
    }

    /**
     * 递归在对象中查找 URL
     */
    private fun findUrlInObject(obj: Any, depth: Int): String? {
        if (depth > 5) return null

        val clazz = obj::class.java
        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(obj) ?: continue

                when (value) {
                    is String -> {
                        if (value.startsWith("http") && (
                            value.contains("/play") || value.contains("playwm") ||
                            value.contains("douyinvod") || value.contains(".mp4") ||
                            value.contains("bytevcloudcdn"))) {
                            return value
                        }
                    }
                    is List<*> -> {
                        for (item in value) {
                            if (item is String && item.startsWith("http") &&
                                (item.contains("/play") || item.contains("playwm") ||
                                 item.contains("douyinvod"))) {
                                return item
                            }
                            // 递归处理 List 中的对象
                            if (item != null) {
                                val url = findUrlInObject(item, depth + 1)
                                if (url != null) return url
                            }
                        }
                    }
                    else -> {
                        val url = findUrlInObject(value, depth + 1)
                        if (url != null) return url
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * 递归收集对象中所有可能的视频 URL（不过滤，全部加入 out）
     */
    private fun findAllUrlsInObject(obj: Any, depth: Int, out: MutableList<String>) {
        if (depth > 6) return
        try {
            val clazz = obj::class.java
            for (field in clazz.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(obj) ?: continue
                    when (value) {
                        is String -> {
                            if (value.startsWith("http") &&
                                (value.contains("/play") || value.contains("playwm") ||
                                 value.contains("douyinvod") || value.contains(".mp4") ||
                                 value.contains("bytevcloudcdn") || value.contains("/mps/"))) {
                                if (value !in out) out.add(value)
                            }
                        }
                        is List<*> -> {
                            for (item in value) {
                                if (item is String && item.startsWith("http")) {
                                    if (item !in out) out.add(item)
                                } else if (item != null) {
                                    findAllUrlsInObject(item, depth + 1, out)
                                }
                            }
                        }
                        else -> {
                            findAllUrlsInObject(value, depth + 1, out)
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** 获取 Aweme 中的图片 URL 列表 */
    fun getImageUrlsFromAweme(aweme: Any): List<String> {
        val urls = mutableListOf<String>()
        try {
            for (fieldName in IMAGE_FIELD_NAMES) {
                val imageList = getField(aweme, fieldName)
                if (imageList is List<*>) {
                    for (image in imageList) {
                        if (image == null) continue

                        var found = false
                        // 尝试 urlList 字段
                        for (listName in URL_LIST_FIELD_NAMES) {
                            val urlList = getField(image, listName) as? List<*>
                                ?: getFieldDeep(image, listName) as? List<*>
                            if (urlList != null) {
                                val url = urlList.filterIsInstance<String>().firstOrNull { it.isNotEmpty() && it.startsWith("http") }
                                if (url != null) {
                                    urls.add(url)
                                    found = true
                                    break
                                }
                            }
                        }

                        // 备用: 递归查找（仅当该图片未找到URL时）
                        if (!found) {
                            val url = findUrlInObject(image, depth = 0)
                            if (url != null) urls.add(url)
                        }
                    }
                    if (urls.isNotEmpty()) break
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("MediaCache: getImageUrls 失败: ${t.message}")
        }
        return urls
    }

    /**
     * 获取 Aweme 中的音乐 URL
     * 从 Aweme 对象的 music 字段中提取音频播放地址
     */
    fun getMusicUrlFromAweme(aweme: Any): String? {
        try {
            // 查找 Music 对象
            var music: Any? = null
            for (fieldName in MUSIC_FIELD_NAMES) {
                music = getField(aweme, fieldName)
                if (music != null) break
            }
            if (music == null) {
                HookUtils.log("MediaCache: 未找到 music 字段")
                return null
            }

            // 尝试各种 URL 字段
            for (urlFieldName in MUSIC_URL_FIELD_NAMES) {
                val urlObj = getField(music, urlFieldName) ?: continue
                val url = extractUrlFromObject(urlObj)
                if (url != null) return url
            }

            // 备用: 递归查找音频 URL
            val url = findAudioUrlInObject(music, depth = 0)
            if (url != null) return url
        } catch (t: Throwable) {
            HookUtils.log("MediaCache: getMusicUrl 失败: ${t.message}")
        }
        return null
    }

    /**
     * 获取 Aweme 中的音乐标题
     */
    fun getMusicTitleFromAweme(aweme: Any): String? {
        try {
            var music: Any? = null
            for (fieldName in MUSIC_FIELD_NAMES) {
                music = getField(aweme, fieldName)
                if (music != null) break
            }
            if (music == null) return null

            for (titleField in MUSIC_TITLE_FIELD_NAMES) {
                val title = getField(music, titleField) as? String
                if (!title.isNullOrEmpty()) return title
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 获取 Aweme 的文案描述
     */
    fun getAwemeDesc(aweme: Any): String? {
        try {
            for (fieldName in DESC_FIELD_NAMES) {
                val desc = getField(aweme, fieldName) as? String
                if (!desc.isNullOrEmpty()) return desc
            }

            // 尝试 shareInfo 中的 share_desc
            val shareInfo = getField(aweme, "shareInfo")
            if (shareInfo != null) {
                val shareDesc = getField(shareInfo, "share_desc") as? String
                    ?: getField(shareInfo, "shareDesc") as? String
                if (!shareDesc.isNullOrEmpty()) return shareDesc
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 从对象中提取 URL（字符串或 urlList）
     */
    private fun extractUrlFromObject(obj: Any): String? {
        if (obj is String && obj.startsWith("http")) return obj
        if (obj is List<*>) {
            return obj.filterIsInstance<String>().firstOrNull { it.startsWith("http") }
        }
        // 尝试 urlList 字段
        for (listName in URL_LIST_FIELD_NAMES) {
            val urlList = getField(obj, listName) as? List<*>
            val url = urlList?.filterIsInstance<String>()?.firstOrNull { it.startsWith("http") }
            if (url != null) return url
        }
        return null
    }

    /**
     * 递归在对象中查找音频 URL
     */
    private fun findAudioUrlInObject(obj: Any, depth: Int): String? {
        if (depth > 4) return null
        try {
            for (field in obj.javaClass.declaredFields) {
                field.isAccessible = true
                val value = field.get(obj) ?: continue
                when (value) {
                    is String -> {
                        if (value.startsWith("http") && (
                            value.contains(".mp3") || value.contains(".m4a") ||
                            value.contains(".aac") || value.contains("audio") ||
                            value.contains("music") || value.contains("sound"))) {
                            return value
                        }
                    }
                    is List<*> -> {
                        for (item in value) {
                            if (item is String && item.startsWith("http") &&
                                (item.contains(".mp3") || item.contains(".m4a") ||
                                 item.contains("audio") || item.contains("music"))) {
                                return item
                            }
                            if (item != null) {
                                val url = findAudioUrlInObject(item, depth + 1)
                                if (url != null) return url
                            }
                        }
                    }
                    else -> {
                        val url = findAudioUrlInObject(value, depth + 1)
                        if (url != null) return url
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /** 视频时长字段名候选（用于自动播放进度比判定） */
    private val DURATION_FIELD_NAMES = listOf(
        "duration", "videoDuration", "VideoDuration", "Duration",
        "durationMs", "playDuration", "durationS",
        "videoLength", "length", "realDuration", "time"
    )

    /** 图文每张图片的默认显示时长（秒），用于估算图文总时长 */
    private const val IMAGE_DISPLAY_DURATION_SEC = 3.0

    /** 图文内容最短时长（秒），避免图片太少导致秒跳 */
    private const val MIN_IMAGE_CONTENT_DURATION_SEC = 8.0

    /**
     * 获取当前 Aweme 视频时长（秒），用于自动播放进度比判定。
     * 抖音 Video.duration 通常以毫秒为单位（>1000 视为 ms），否则视为秒。
     * 获取失败返回 null。
     */
    @JvmStatic
    fun getVideoDurationSec(): Double? {
        return try {
            val aweme = getCurrentAweme() ?: return null
            var video: Any? = null
            for (fieldName in VIDEO_FIELD_NAMES) {
                video = getFieldDeep(aweme, fieldName)
                if (video != null) break
            }
            val targets = listOfNotNull(aweme, video)
            val found = mutableListOf<Pair<String, Double>>()
            for (target in targets) {
                for (fieldName in DURATION_FIELD_NAMES) {
                    val v = getFieldDeep(target, fieldName) as? Number ?: continue
                    val d = v.toDouble()
                    if (d <= 0.0) continue
                    val sec = if (d > 1000.0) d / 1000.0 else d
                    found.add(fieldName to sec)
                }
            }
            if (found.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastDurLog > 3000L) {
                    lastDurLog = now
                    HookUtils.log("MediaCache: 时长候选 " + found.joinToString(" | ") { "${it.first}=${it.second}s" })
                }
            }
            // 优先使用 Video 对象上的权威时长字段（video.duration 等），它才是真实播放时长。
            // 若 Video 时长有效则直接采用，避免误选 Aweme 级其它时间字段（如音乐时长、服务器时间等）
            // 导致时长被高估/低估而误判播完。仅在 Video 时长缺失时，才回退到全部候选的最大值。
            val VIDEO_PRIORITY = listOf("duration", "videoDuration", "Duration", "realDuration", "durationMs", "playDuration")
            if (video != null) {
                for (fieldName in VIDEO_PRIORITY) {
                    val v = getFieldDeep(video, fieldName) as? Number ?: continue
                    val d = v.toDouble()
                    if (d <= 0.0) continue
                    val sec = if (d > 1000.0) d / 1000.0 else d
                    if (sec >= 3.0 && sec <= 1800.0) return sec
                }
            }
            found.filter { it.second >= 3.0 && it.second <= 1800.0 }
                .maxByOrNull { it.second }?.second
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取当前内容的总时长（秒），用于自动播放的定时器兜底判断。
     *
     * 优先级:
     * 1. 视频时长（getVideoDurationSec）— 视频内容
     * 2. 图文估算时长（图片数 × 每张时长）— 图文/图片集内容
     *
     * 返回 null 表示无法获取时长（未知内容类型）。
     */
    @JvmStatic
    fun getContentDurationSec(): Double? {
        return try {
            // 1) 优先用视频时长
            val videoDur = getVideoDurationSec()
            if (videoDur != null) return videoDur

            // 2) 无视频时用图文图片数估算
            val aweme = getCurrentAweme() ?: return null
            val imageCount = getImageCount(aweme)
            if (imageCount > 0) {
                val estimated = imageCount * IMAGE_DISPLAY_DURATION_SEC
                // 至少 8 秒，避免 1-2 张图的内容秒跳
                return maxOf(estimated, MIN_IMAGE_CONTENT_DURATION_SEC)
            }

            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 计算 Aweme 中的图片数量 */
    private fun getImageCount(aweme: Any): Int {
        return try {
            for (fieldName in IMAGE_FIELD_NAMES) {
                val imageList = getField(aweme, fieldName)
                if (imageList is List<*> && imageList.isNotEmpty()) {
                    return imageList.size
                }
            }
            0
        } catch (_: Throwable) {
            0
        }
    }

    /** 下载实况照片 (图片 + 视频) */
    fun downloadLivePhoto(context: android.content.Context, imageUrl: String, videoUrl: String) {
        val timestamp = System.currentTimeMillis()
        MediaDownloader.download(context, videoUrl, "live_photo_${timestamp}.mov",
            onComplete = { uri ->
                HookUtils.log("MediaCache: 实况视频已保存")
            },
            onError = { t ->
                HookUtils.log("MediaCache: 实况视频保存失败: ${t.message}")
            }
        )
        MediaDownloader.download(context, imageUrl, "live_photo_${timestamp}.heic",
            onComplete = { uri ->
                HookUtils.showToast(context, "实况照片已保存 ✓")
            },
            onError = { t ->
                HookUtils.log("MediaCache: 实况图片保存失败: ${t.message}")
            }
        )
    }

    /**
     * 从 Aweme 对象中按需提取实况照片 URL
     * 只在用户点击保存按钮时调用一次，不影响日常性能
     *
     * @return Pair(imageUrl, videoUrl) 如果检测到实况照片，否则 null
     */
    fun extractLivePhotoFromAweme(aweme: Any): Pair<String, String>? {
        try {
            val imageUrls = getImageUrlsFromAweme(aweme)
            if (imageUrls.isEmpty()) return null

            // 查找 Aweme 中的视频 URL（实况照片的视频部分）
            val videoUrl = getVideoUrlFromAweme(aweme) ?: findUrlInObject(aweme, depth = 0)
            if (videoUrl == null) return null

            // 如果同时有图片和视频 URL，认为是实况照片
            HookUtils.log("MediaCache: 检测到实况照片 (image=${imageUrls.first()}, video=$videoUrl)")
            return Pair(imageUrls.first(), videoUrl)
        } catch (t: Throwable) {
            HookUtils.log("MediaCache: extractLivePhoto 失败: ${t.message}")
        }
        return null
    }

    /** 判断 URL 是否为视频 */
    fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/play") || lower.contains("playwm") ||
                lower.contains(".mp4") || lower.contains(".mov") ||
                lower.contains("video")
    }

    private fun getField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFieldDeep(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null

        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }
}
