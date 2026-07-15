package com.xposed.doupp.util

import java.net.URI
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * URL 解析工具
 *
 * 适配最新版抖音:
 * - 扩展 CDN 域名列表（新增 bytevcloudcdn, v3-web.douyinvod 等）
 * - 支持 playwm 的多种变体（URL 编码、路径变体）
 * - 支持新版抖音的 download_addr 和 play_addr 双地址模式
 * - 增强水印参数过滤
 *
 * 去水印核心原理:
 * 抖音的带水印视频 URL 中包含 /playwm/ 路径段，
 * 将其替换为 /play/ 即可获得无水印版本。
 * 同时可能需要移除 watermark 相关参数。
 */
object UrlParser {

    /** 抖音视频 CDN 域名特征（扩展） */
    private val VIDEO_DOMAINS = listOf(
        "douyinvod.com",
        "bytevcloudcdn.com",
        "bytecdn.cn",
        "byteimg.com",
        "snssdk.com",
        "douyin.com",
        "amemv.com",
        "pstatp.com",
        "bytedance.com",
        "ibytedtos.com",
        "zijieapi.com",
        "bdurl.net",
        "v3-web.douyinvod",
        "v9-web.douyinvod",
        "v11-web.douyinvod",
        "byteoss.com",
        "tos-cn-i-0813",       // 新版抖音图片 CDN
        "tos-cn-p-0015",       // 新版抖音视频 CDN
        "tos-cn-i-0000",       // 通用字节 CDN
        "bytecdn.com",
        "bdurl.cn"
    )

    /** 图片 CDN 域名特征（扩展） */
    private val IMAGE_DOMAINS = listOf(
        "byteimg.com",
        "pstatp.com",
        "snssdk.com",
        "douyin.com",
        "bytedance.com",
        "ibytedtos.com",
        "tos-cn-i-0813",
        "tos-cn-p-0015",
        "byteoss.com"
    )

    /** 视频文件扩展名 */
    private val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mov", ".m3u8", ".flv", ".webm", ".avi"
    )

    /** 图片文件扩展名 */
    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif"
    )

    /**
     * 获取无水印 URL
     *
     * 核心原理: 抖音带水印视频 URL 包含 /playwm/ 路径段，
     * 将其替换为 /play/ 即可获得无水印版本。
     *
     * 重要约束（来自历史调试经验）:
     * - 绝不移除任何查询参数（移除签名参数如 X-Gorgon 会导致 API 403）
     * - 绝不用 URI 重建 URL（会破坏 URL 编码导致请求失败）
     * - 只做纯粹的字符串替换
     *
     * @param originalUrl 原始 URL
     * @return 无水印 URL，如果无法处理则返回原始 URL
     */
    fun getNoWatermarkUrl(originalUrl: String): String {
        if (originalUrl.isEmpty()) return originalUrl

        var url = originalUrl
        HookUtils.log("UrlParser: 原始 URL: ${url.take(200)}")

        try {
            // 策略0: 检测是否已经是无水印 URL（避免重复处理）
            val wasClean = isCleanUrl(url)

            // 策略1: 替换 playwm 为 play (旧版/新版抖音通用去水印核心)
            // 抖音带水印视频地址路径含 /playwm/，改为 /play/ 即得到无水印版本
            val oldUrl = url
            url = url.replace("/playwm/", "/play/")
            url = url.replace("/playwm?", "/play?")
            url = url.replace("%2Fplaywm%2F", "%2Fplay%2F")
            url = url.replace("%2Fplaywm%3F", "%2Fplay%3F")
            // 额外变体: 路径中含 playwm 但不带斜杠的情形
            url = url.replace("playwm.", "play.")
            url = url.replace("playwm", "play")

            if (url != oldUrl) {
                HookUtils.log("UrlParser: playwm→play 替换成功")
            }

            // 策略1.5: 处理 /mps/logo/ 路径（新版抖音水印路径）
            val oldUrl15 = url
            url = url.replace("/mps/logo/", "/mps/")
            url = url.replace("/logo/", "/")
            if (url != oldUrl15) {
                HookUtils.log("UrlParser: logo路径替换成功")
            }

            // 策略2: 处理播放接口(api-play)的水印参数
            // 抖音播放接口 aweme/v1/play/ 通过 watermark=1 + logo_name=aweme_search_suffix
            // 控制水印。必须把 watermark=1 改为 watermark=0，并移除 logo_name。
            val oldUrl2 = url
            // watermark=1 -> watermark=0（改为关闭，而不是删除参数，避免默认值不确定导致仍带水印）
            url = url.replace(Regex("[?&]watermark=[^&]*"), "&watermark=0")
            // 移除其它水印相关参数（logo_name / logo_type / wm_* 等）
            val watermarkParams = listOf(
                "logo_name", "logo_type", "wm_type", "wm_value",
                "overlay", "need_wm", "show_wm", "wm_quality", "wm_opacity",
                "enable_watermark", "watermark_type", "wm_source", "wm_text",
                "watermark_pos", "watermark_size", "watermark_alpha", "watermark_color"
            )
            for (param in watermarkParams) {
                url = url.replace(Regex("[?&]$param=[^&]*"), "")
            }
            // 修复 URL 中可能出现的 && 和 ?&
            url = url.replace("&&", "&")
            url = url.replace("?&", "?")
            // 如果 URL 末尾是 ? 或 & 则去掉
            if (url.endsWith("?") || url.endsWith("&")) {
                url = url.substring(0, url.length - 1)
            }

            if (url != oldUrl2) {
                HookUtils.log("UrlParser: 移除水印参数: ${watermarkParams.filter { oldUrl2.contains(it) }}")
            }

            // 策略3: 处理新版抖音 CDN URL 结构（不含 playwm 但仍带水印）
            // 新版抖音使用 tos-cn-v 等 CDN，水印可能嵌入在 URL 参数中
            val oldUrl3 = url
            url = processNewDouyinUrl(url)
            if (url != oldUrl3) {
                HookUtils.log("UrlParser: 新版CDN URL处理成功")
            }

            // 策略4: 尝试从 URL 中提取 clean URL（如果 URL 包含多个清晰度选项）
            val oldUrl4 = url
            url = extractCleanUrlFromMultiQuality(url)
            if (url != oldUrl4) {
                HookUtils.log("UrlParser: 从多清晰度URL提取干净源")
            }

            if (url == originalUrl && !wasClean) {
                HookUtils.log("UrlParser: URL 不含已知水印标记，尝试备用策略")
                // 尝试备用策略：直接构造 clean URL
                val backupUrl = buildCleanUrl(originalUrl)
                if (backupUrl != originalUrl) {
                    url = backupUrl
                    HookUtils.log("UrlParser: 备用策略构造成功")
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("UrlParser: 处理URL失败: ${t.message}")
            return originalUrl
        }

        HookUtils.log("UrlParser: 最终 URL: ${url.take(200)}")
        return url
    }

    /**
     * 检测 URL 是否已经是无水印状态
     */
    private fun isCleanUrl(url: String): Boolean {
        val lower = url.lowercase()
        return !lower.contains("playwm") &&
                !lower.contains("watermark") &&
                !lower.contains("logo_name") &&
                !lower.contains("wm_type") &&
                !lower.contains("/mps/logo/")
    }

    /**
     * 处理新版抖音 CDN URL 结构
     * 新版抖音使用 tos-cn-v 等 CDN，可能需要特殊处理
     */
    private fun processNewDouyinUrl(url: String): String {
        var result = url
        // 处理 tos-cn-v CDN 的水印参数
        if (url.contains("tos-cn-v", ignoreCase = true)) {
            // 移除常见水印参数
            result = result.replace(Regex("[?&]wm=[^&]*"), "")
            result = result.replace(Regex("[?&]mark=[^&]*"), "")
            result = result.replace(Regex("[?&]mask=[^&]*"), "")
            // 修复 URL
            result = result.replace("&&", "&").replace("?&", "?")
            if (result.endsWith("?") || result.endsWith("&")) {
                result = result.substring(0, result.length - 1)
            }
        }
        return result
    }

    /**
     * 从多清晰度 URL 中提取干净源
     * 部分 URL 可能包含多个清晰度选项，尝试提取最高清的
     */
    private fun extractCleanUrlFromMultiQuality(url: String): String {
        // 检查是否包含多清晰度分隔符
        if (url.contains("|")) {
            val parts = url.split("|")
            // 优先选择不带水印标记的 URL
            for (part in parts) {
                if (!part.contains("playwm") && !part.contains("watermark")) {
                    return part.trim()
                }
            }
            // 如果都带水印，返回第一个
            return parts.first().trim()
        }
        return url
    }

    /**
     * 备用策略：尝试构造 clean URL
     * 通过替换域名和路径来生成可能的无水印 URL
     */
    private fun buildCleanUrl(url: String): String {
        var result = url
        // 替换 CDN 域名
        val domainReplacements = mapOf(
            "v3-web.douyinvod" to "v1-web.douyinvod",
            "v9-web.douyinvod" to "v1-web.douyinvod",
            "v11-web.douyinvod" to "v1-web.douyinvod",
            "douyinvod.com" to "douyinvod.net"
        )
        for ((old, new) in domainReplacements) {
            result = result.replace(old, new, ignoreCase = true)
        }
        return result
    }

    /**
     * 从 URL 中提取抖音视频 ID
     *
     * @param url 视频 URL
     * @return 视频 ID，如果无法提取则返回 null
     */
    fun extractAwemeId(url: String): String? {
        if (url.isEmpty()) return null

        try {
            // 尝试从 URL 路径中提取
            // 格式: /video/1234567890 或 /v1234567890
            val patterns = listOf(
                Pattern.compile("/video/(\\d+)"),
                Pattern.compile("/v(\\d+)"),
                Pattern.compile("/note/(\\d+)"),  // 图文笔记
                Pattern.compile("aweme_id=(\\d+)"),
                Pattern.compile("item_id=(\\d+)"),
                Pattern.compile("awemeId=(\\d+)"),
                Pattern.compile("itemId=(\\d+)"),
                Pattern.compile("/modal/(\\d+)"),  // 新版模态页
                Pattern.compile("/share/video/(\\d+)")  // 分享页
            )

            for (pattern in patterns) {
                val matcher = pattern.matcher(url)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }

            // 尝试从查询参数中提取
            val uri = URI(url)
            val query = uri.query
            if (query != null) {
                val params = query.split("&").associate {
                    val parts = it.split("=")
                    parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
                }

                val id = params["aweme_id"] ?: params["item_id"] ?: params["awemeId"] ?: params["itemId"]
                if (!id.isNullOrEmpty()) return id
            }
        } catch (t: Throwable) {
            HookUtils.log("UrlParser: 提取AwemeId失败: ${t.message}")
        }

        return null
    }

    /**
     * 判断 URL 是否为视频 URL
     *
     * @param url 要检查的 URL
     * @return true 如果是视频 URL
     */
    fun isVideoUrl(url: String): Boolean {
        if (url.isEmpty()) return false

        val lowerUrl = url.lowercase()

        // 检查文件扩展名
        if (VIDEO_EXTENSIONS.any { lowerUrl.contains(it) }) return true

        // 检查域名特征
        if (VIDEO_DOMAINS.any { lowerUrl.contains(it) }) {
            // 域名匹配后，进一步检查路径特征
            if (lowerUrl.contains("/play") || lowerUrl.contains("/video") ||
                lowerUrl.contains("playwm") || lowerUrl.contains("aweme")) {
                return true
            }
        }

        // 检查特定路径模式
        if (lowerUrl.contains("/playwm/") || lowerUrl.contains("/play/") ||
            lowerUrl.contains("/playwm?") || lowerUrl.contains("/play?")) return true

        return false
    }

    /**
     * 判断 URL 是否为图片 URL
     *
     * @param url 要检查的 URL
     * @return true 如果是图片 URL
     */
    fun isImageUrl(url: String): Boolean {
        if (url.isEmpty()) return false

        val lowerUrl = url.lowercase()

        // 检查文件扩展名
        if (IMAGE_EXTENSIONS.any { lowerUrl.contains(it) }) return true

        // 检查域名特征
        if (IMAGE_DOMAINS.any { lowerUrl.contains(it) }) {
            if (lowerUrl.contains("/image") || lowerUrl.contains("/pic") ||
                lowerUrl.contains("/photo") || lowerUrl.contains("/img") ||
                lowerUrl.contains("/aweme/")) {
                return true
            }
        }

        // 检查特定路径模式
        if (lowerUrl.contains("/aweme/") && lowerUrl.contains("image")) return true

        return false
    }

    /**
     * 判断 URL 是否为实况照片的视频部分
     *
     * @param url 要检查的 URL
     * @return true 如果是实况照片视频 URL
     */
    fun isLivePhotoVideoUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("live") && isVideoUrl(url)
    }

    /**
     * 从 URL 中提取文件名
     *
     * @param url URL
     * @return 文件名，如果无法提取则返回 null
     */
    fun extractFileName(url: String): String? {
        if (url.isEmpty()) return null

        try {
            val uri = URI(url)
            val path = uri.path ?: return null
            val fileName = path.substringAfterLast("/")
            return if (fileName.isNotEmpty()) URLDecoder.decode(fileName, "UTF-8") else null
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * 获取 URL 的域名
     *
     * @param url URL
     * @return 域名
     */
    fun getDomain(url: String): String? {
        return try {
            URI(url).host
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 检查 URL 是否包含水印标记
     */
    fun hasWatermark(url: String): Boolean {
        if (url.isEmpty()) return false
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("playwm") ||
               lowerUrl.contains("%2fplaywm%2f") ||
               lowerUrl.contains("watermark") ||
               lowerUrl.contains("wm_")
    }

    /**
     * 移除图片 URL 中的水印参数
     *
     * 抖音图片 URL 通常包含水印参数，如:
     * - watermark=1
     * - wm_type / wm_value
     * - logo / brand
     * 移除这些参数可获得无水印图片
     */
    fun removeImageWatermark(url: String): String {
        if (url.isEmpty()) return url
        var result = url
        try {
            // 移除水印相关查询参数
            result = result.replace(Regex("[?&]watermark=[^&]*"), "")
            result = result.replace(Regex("[?&]wm_type=[^&]*"), "")
            result = result.replace(Regex("[?&]wm_value=[^&]*"), "")
            result = result.replace(Regex("[?&]logo=[^&]*"), "")
            result = result.replace(Regex("[?&]brand=[^&]*"), "")
            result = result.replace(Regex("[?&]overlay=[^&]*"), "")
            result = result.replace(Regex("[?&]need_wm=[^&]*"), "")
            result = result.replace(Regex("[?&]show_wm=[^&]*"), "")
            // 修复替换后的 URL
            result = result.replace("&&", "&").replace("?&", "?")
            // 尝试替换 ~noop.webp 为 ~noop.image 可获取更高清原图
            result = result.replace("~noop.webp", "~noop.image")
        } catch (_: Throwable) { }
        return result
    }
}
