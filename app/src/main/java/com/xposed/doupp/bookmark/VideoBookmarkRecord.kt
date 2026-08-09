package com.xposed.doupp.bookmark

import java.io.Serializable
import org.json.JSONObject

/**
 * 作品书签。字段与 JSON 协议对齐 DYHelper 的 VideoBookmarkRecord。
 */
class VideoBookmarkRecord(
    val awemeId: String,
    val typeLabel: String,
    val title: String,
    val authorName: String,
    val authorUid: String,
    val authorSecUid: String,
    val coverUrl: String,
    val shareUrl: String,
    val diggCount: Long,
    val commentCount: Long,
    val collectCount: Long,
    val createTime: Long,
    val createTimestamp: Long
) : Serializable {

    fun displayAuthor(): String {
        val raw = authorName.removePrefix("@")
        return if (raw.isEmpty()) "未知作者" else raw
    }

    fun displayTitle(): String {
        val t = title.trim()
        if (t.isNotEmpty() && t != "无描述" && t != "未知") return t
        return if (typeLabel.isEmpty()) "作品" else typeLabel
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("awemeId", awemeId)
        put("typeLabel", typeLabel)
        put("title", title)
        put("authorName", authorName)
        put("authorUid", authorUid)
        put("authorSecUid", authorSecUid)
        put("coverUrl", coverUrl)
        put("shareUrl", shareUrl)
        put("diggCount", diggCount)
        put("commentCount", commentCount)
        put("collectCount", collectCount)
        put("createTime", createTime)
        put("createTimestamp", createTimestamp)
    }

    companion object {
        const val TYPE_VIDEO = "作品"
        const val TYPE_PHOTO = "图集"
        const val TYPE_LIVE = "直播"
        const val TYPE_MUSIC = "音乐"
        const val TYPE_OTHER = "其他"

        @JvmStatic
        fun fromJson(obj: JSONObject): VideoBookmarkRecord {
            val tl = obj.optString("typeLabel", "作品")
            return VideoBookmarkRecord(
                awemeId = obj.optString("awemeId"),
                typeLabel = if (tl.isEmpty()) "作品" else tl,
                title = obj.optString("title"),
                authorName = obj.optString("authorName"),
                authorUid = obj.optString("authorUid"),
                authorSecUid = obj.optString("authorSecUid"),
                coverUrl = obj.optString("coverUrl"),
                shareUrl = obj.optString("shareUrl"),
                diggCount = obj.optLong("diggCount", 0L),
                commentCount = obj.optLong("commentCount", 0L),
                collectCount = obj.optLong("collectCount", 0L),
                createTime = obj.optLong("createTime", 0L),
                createTimestamp = obj.optLong("createTimestamp", System.currentTimeMillis())
            )
        }
    }
}