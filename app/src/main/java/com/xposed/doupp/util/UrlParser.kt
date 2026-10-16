package com.xposed.doupp.util

import java.net.URI
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * URL 解析 / 去水印工具
 *
 * 40.7.0 实测证据表（55 dex / 437MB 全量扫描）：
 * - playwm：0 命中（旧路径策略，仅作兼容兜底）
 * - watermark：40 个 dex 引用（主策略）
 * - 签名参数（X-Gorgon Header、_signature、sign、verify_fp、a_bogus、msToken 等）绝不能删
 * - 不做域名替换（换域名必然 403/404），不做 URI 重建（会破坏编码），只做纯字符串外科手术
 *
 * 去水印策略（幂等，可安全重复调用）：
 * 1. 路径段 /playwm/ -> /play/（只替换完整路径段，不碰 query、不碰域名）
 * 2. /mps/logo/ -> /mps/
 * 3. watermark=1|true|yes|on -> watermark=0（保留参数名与位置，只改值）
 * 4. 删除“明确表示叠加水印”的参数（WATERMARK_PARAM_NAMES 白名单，绝不含签名类）
 */
object UrlParser {

    /** 抖音视频 CDN 域名特征（仅用于识别，绝不做域名替换） */
    private val VIDEO_DOMAINS = listOf(
        "douyinvod.com", "douyinvod.net",
        "bytevcloudcdn.com", "bytecdn.cn", "bytecdn.com",
        "byteimg.com", "snssdk.com", "douyin.com",
        "amemv.com", "pstatp.com", "bytedance.com",
        "ibytedtos.com", "zijieapi.com", "bdurl.net", "bdurl.cn",
        "v1-web.douyinvod", "v3-web.douyinvod", "v9-web.douyinvod",
        "v11-web.douyinvod", "v16-web.douyinvod", "v26-web.douyinvod",
        "tos-cn-i-0813", "tos-cn-p-0015", "tos-cn-i-0000",
        "tos-cn-v", "byteoss.com", "muscdn.com", "tiktokcdn.com",
        "douyincdn.com", "zjcdn.com", "ixigua.com"
    )

    /** 图片 CDN 域名特征 */
    private val IMAGE_DOMAINS = listOf(
        "byteimg.com", "pstatp.com", "snssdk.com", "douyin.com",
        "bytedance.com", "ibytedtos.com", "tos-cn-i-0813",
        "tos-cn-p-0015", "byteoss.com", "douyinpic.com",
        "douyincdn.com", "zjcdn.com", "bytecdn.cn"
    )

    private val VIDEO_EXTENSIONS = listOf(".mp4", ".mov", ".m3u8", ".flv", ".webm", ".avi")
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif")

    /**
     * 需要删除的水印参数白名单。
     *
     * 只包含“明确表示叠加水印”的参数名，绝不包含：
     * - watermark（用改值处理，保留参数本身，避免默认值回落）
     * - file_id / video_id / ratio / line / aa / ab / cd（播放必需）
     * - X-Gorgon / _signature / sign / verify_fp / a_bogus / msToken / ttwid（签名校验必需）
     * - 泛化的 wm / mark / mask / logo（可能误伤签名或业务参数）
     */
    private val WATERMARK_PARAM_NAMES = setOf(
        "logo_name", "logo_type", "logo_pos", "logo_position",
        "logo_offset", "logo_overlay",
        "wm_type", "wm_value", "wm_text", "wm_source",
        "wm_area", "wm_offset", "wm_font_size", "wm_fontsize",
        "wm_opacity", "wm_quality", "wm_pos", "wm_position",
        "need_wm", "show_wm", "enable_watermark",
        "watermark_type", "watermark_pos", "watermark_position",
        "watermark_size", "watermark_alpha", "watermark_color",
        "watermark_offset", "watermark_show", "watermark_display",
        "overlay"
    )

    /** 视为“带水印”的 watermark 取值 */
    private val WATERMARK_TRUE_VALUES = setOf("1", "true", "yes", "on")

    /**
     * 获取无水印 URL — 核心入口。
     *
     * 约束：
     * - 绝不移除任何签名参数
     * - 绝不做域名替换
     * - 绝不用 URI 重建 URL（会重编码），只做字符串替换
     * - 幂等：对已处理过的 URL 再调用不会产生副作用
     */
    fun getNoWatermarkUrl(originalUrl: String): String {
        if (originalUrl.isEmpty()) return originalUrl
        if (!originalUrl.startsWith("http", ignoreCase = true)) return originalUrl

        var url = originalUrl

        // 1) 路径段 playwm -> play（只替换完整路径段，不碰 query、不碰域名）
        url = url.replace("/playwm/", "/play/")
            .replace("/playwm?", "/play?")
            .replace("/playwm#", "/play#")

        // 2) 水印 logo 路径变体
        url = url.replace("/mps/logo/", "/mps/")

        // 3) watermark=1|true|yes|on -> watermark=0（保留参数名与位置，只改值）
        url = replaceParamValue(url, "watermark", WATERMARK_TRUE_VALUES, "0")

        // 4) 删除“明确表示叠加水印”的参数
        url = removeQueryParams(url, WATERMARK_PARAM_NAMES)

        if (url != originalUrl) {
            HookUtils.log("UrlParser: 去水印 ${originalUrl.take(140)} -> ${url.take(140)}")
        } else {
            HookUtils.log("UrlParser: 无需处理 ${originalUrl.take(140)}")
        }
        return url
    }

    /**
     * 只修改指定参数的值：当该参数的取值命中 [fromValues] 时，替换为 [toValue]。
     * 保留参数名与在 query 中的位置，不重排、不重编码，幂等。
     */
    private fun replaceParamValue(
        url: String,
        name: String,
        fromValues: Set<String>,
        toValue: String
    ): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q)
        val head = url.substring(0, q)
        val query = if (hash >= 0) url.substring(q + 1, hash) else url.substring(q + 1)
        val tail = if (hash >= 0) url.substring(hash) else ""

        var changed = false
        val parts = query.split('&').map { part ->
            if (part.isEmpty()) return@map part
            val eq = part.indexOf('=')
            if (eq <= 0) return@map part
            val key = part.substring(0, eq)
            if (!key.equals(name, ignoreCase = true)) return@map part
            val value = part.substring(eq + 1)
            if (fromValues.contains(value.lowercase())) {
                changed = true
                "$key=$toValue"
            } else {
                part
            }
        }
        if (!changed) return url
        return head + "?" + parts.joinToString("&") + tail
    }

    /**
     * 删除 query 中指定的参数名集合，保留 `?` 与其余参数顺序。
     * 若删空则移除整个 `?`。纯字符串操作，不重编码，幂等。
     */
    private fun removeQueryParams(url: String, names: Set<String>): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q)
        val head = url.substring(0, q)
        val query = if (hash >= 0) url.substring(q + 1, hash) else url.substring(q + 1)
        val tail = if (hash >= 0) url.substring(hash) else ""

        val kept = query.split('&').filter { part ->
            if (part.isEmpty()) return@filter false
            val key = part.substringBefore('=')
            !names.contains(key.lowercase())
        }
        if (kept.size == query.split('&').count { it.isNotEmpty() }) return url
        return if (kept.isEmpty()) head + tail else head + "?" + kept.joinToString("&") + tail
    }

    // ========== 以下为辅助方法，保持兼容 ==========

    fun extractAwemeId(url: String): String? {
        if (url.isEmpty()) return null
        try {
            val patterns = listOf(
                Pattern.compile("/video/(\\d+)"),
                Pattern.compile("/v(\\d+)"),
                Pattern.compile("/note/(\\d+)"),
                Pattern.compile("aweme_id=(\\d+)"),
                Pattern.compile("item_id=(\\d+)"),
                Pattern.compile("awemeId=(\\d+)"),
                Pattern.compile("itemId=(\\d+)"),
                Pattern.compile("/modal/(\\d+)"),
                Pattern.compile("/share/video/(\\d+)")
            )
            for (pattern in patterns) {
                val matcher = pattern.matcher(url)
                if (matcher.find()) return matcher.group(1)
            }
            val uri = URI(url)
            val query = uri.query
            if (query != null) {
                val params = query.split("&").associate {
                    val parts = it.split("=")
                    parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
                }
                return params["aweme_id"] ?: params["item_id"] ?: params["awemeId"] ?: params["itemId"]
            }
        } catch (t: Throwable) {
            HookUtils.log("UrlParser: 提取AwemeId失败: ${t.message}")
        }
        return null
    }

    fun isVideoUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val lowerUrl = url.lowercase()
        if (VIDEO_EXTENSIONS.any { lowerUrl.contains(it) }) return true
        if (VIDEO_DOMAINS.any { lowerUrl.contains(it) }) {
            if (lowerUrl.contains("/play") || lowerUrl.contains("/video") ||
                lowerUrl.contains("aweme")) return true
        }
        if (lowerUrl.contains("/playwm/") || lowerUrl.contains("/play/") ||
            lowerUrl.contains("/playwm?") || lowerUrl.contains("/play?")) return true
        if (lowerUrl.contains("tos-cn-v") && lowerUrl.contains("/play")) return true
        if (lowerUrl.contains("amemv.com") && lowerUrl.contains("aweme/v1/play")) return true
        return false
    }

    fun isImageUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val lowerUrl = url.lowercase()
        if (IMAGE_EXTENSIONS.any { lowerUrl.contains(it) }) return true
        if (IMAGE_DOMAINS.any { lowerUrl.contains(it) }) {
            if (lowerUrl.contains("/image") || lowerUrl.contains("/pic") ||
                lowerUrl.contains("/photo") || lowerUrl.contains("/img") ||
                lowerUrl.contains("/aweme/")) return true
        }
        if (lowerUrl.contains("/aweme/") && lowerUrl.contains("image")) return true
        return false
    }

    fun isLivePhotoVideoUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        return url.lowercase().contains("live") && isVideoUrl(url)
    }

    fun extractFileName(url: String): String? {
        if (url.isEmpty()) return null
        try {
            val uri = URI(url)
            val path = uri.path ?: return null
            val fileName = path.substringAfterLast("/")
            return if (fileName.isNotEmpty()) URLDecoder.decode(fileName, "UTF-8") else null
        } catch (_: Throwable) { return null }
    }

    fun getDomain(url: String): String? = try { URI(url).host } catch (_: Throwable) { null }

    fun hasWatermark(url: String): Boolean {
        if (url.isEmpty()) return false
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("playwm")) return true
        if (lowerUrl.contains("/mps/logo/")) return true
        if (WATERMARK_TRUE_VALUES.any { lowerUrl.contains("watermark=$it") }) return true
        return WATERMARK_PARAM_NAMES.any { lowerUrl.contains("$it=") }
    }

    /** 移除图片 URL 中的水印参数（同样只删水印参数，不碰签名，不换域名） */
    fun removeImageWatermark(url: String): String {
        if (url.isEmpty()) return url
        if (!url.startsWith("http", ignoreCase = true)) return url
        var result = removeQueryParams(url, WATERMARK_PARAM_NAMES)
        result = replaceParamValue(result, "watermark", WATERMARK_TRUE_VALUES, "0")
        // 尝试替换 ~noop.webp 为 ~noop.image 可获取更高清原图
        result = result.replace("~noop.webp", "~noop.image")
        return result
    }
}
