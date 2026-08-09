package com.xposed.doupp.bookmark

import java.io.Serializable
import org.json.JSONArray
import org.json.JSONObject

/**
 * 评论书签。字段与 JSON 协议对齐 DYHelper 的 CommentBookmarkRecord。
 * 核心标识字段（commentId/awemeId/content/authorName 等）只读，
 * 探测状态字段（回复数/新回复等）可变。
 */
class CommentBookmarkRecord(
    val commentId: String,
    val awemeId: String,
    val content: String,
    val authorName: String,
    val commentCreateTime: Long,
    val createTimestamp: Long,
    val notificationEnabled: Boolean,
    val userTag: String,
    val remark: String,
    lastCheckTimestamp: Long,
    lastKnownReplyCount: Int,
    hasNewReplies: Boolean,
    newReplyCount: Int,
    knownReplyIds: MutableSet<String>,
    newReplyItems: MutableList<CommentBookmarkReplyItem>,
    val awemeAuthorId: String?,
    val awemeAuthorSecUid: String?,
    val awemeTitle: String?,
    val commentSecUid: String?,
    val coverUrl: String?,
    val aweType: Int
) : Serializable {

    var lastCheckTimestamp: Long = lastCheckTimestamp
    var lastKnownReplyCount: Int = lastKnownReplyCount
    var hasNewReplies: Boolean = hasNewReplies
    var newReplyCount: Int = newReplyCount
    var knownReplyIds: MutableSet<String> = knownReplyIds
    var newReplyItems: MutableList<CommentBookmarkReplyItem> = newReplyItems

    fun toJson(): JSONObject {
        val obj = JSONObject()
            .put("commentId", commentId)
            .put("awemeId", awemeId)
            .put("content", content)
            .put("authorName", authorName)
            .put("commentCreateTime", commentCreateTime)
            .put("createTimestamp", createTimestamp)
            .put("notificationEnabled", notificationEnabled)
            .put("userTag", userTag)
            .put("remark", remark)
            .put("lastCheckTimestamp", lastCheckTimestamp)
            .put("lastKnownReplyCount", lastKnownReplyCount)
            .put("hasNewReplies", hasNewReplies)
            .put("newReplyCount", newReplyCount)

        val knownIds = JSONArray()
        knownReplyIds.forEach { if (it.isNotEmpty()) knownIds.put(it) }
        obj.put("knownReplyIds", knownIds)

        val items = JSONArray()
        newReplyItems.forEach { items.put(it.toJson()) }
        obj.put("newReplyItems", items)

        obj.put("awemeAuthorId", awemeAuthorId ?: "")
        obj.put("awemeAuthorSecUid", awemeAuthorSecUid ?: "")
        obj.put("awemeTitle", awemeTitle ?: "")
        obj.put("commentSecUid", commentSecUid ?: "")
        obj.put("coverUrl", coverUrl ?: "")
        obj.put("aweType", aweType)
        return obj
    }

    companion object {
        @JvmStatic
        fun fromJson(obj: JSONObject): CommentBookmarkRecord {
            val knownIds = LinkedHashSet<String>()
            obj.optJSONArray("knownReplyIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotEmpty()) knownIds.add(s)
                }
            }

            val items = mutableListOf<CommentBookmarkReplyItem>()
            obj.optJSONArray("newReplyItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { item ->
                        val reply = item.optString("replyId")
                        if (reply.isNotEmpty()) {
                            items.add(CommentBookmarkReplyItem.fromJson(item))
                        }
                    }
                }
            }

            return CommentBookmarkRecord(
                commentId = obj.optString("commentId"),
                awemeId = obj.optString("awemeId"),
                content = obj.optString("content"),
                authorName = obj.optString("authorName"),
                commentCreateTime = obj.optLong("commentCreateTime"),
                createTimestamp = obj.optLong("createTimestamp", System.currentTimeMillis()),
                notificationEnabled = obj.optBoolean("notificationEnabled", true),
                userTag = obj.optString("userTag"),
                remark = obj.optString("remark"),
                lastCheckTimestamp = obj.optLong("lastCheckTimestamp"),
                lastKnownReplyCount = obj.optInt("lastKnownReplyCount"),
                hasNewReplies = obj.optBoolean("hasNewReplies"),
                newReplyCount = obj.optInt("newReplyCount"),
                knownReplyIds = knownIds,
                newReplyItems = items,
                awemeAuthorId = obj.nullIfEmpty("awemeAuthorId"),
                awemeAuthorSecUid = obj.nullIfEmpty("awemeAuthorSecUid"),
                awemeTitle = obj.nullIfEmpty("awemeTitle"),
                commentSecUid = obj.nullIfEmpty("commentSecUid"),
                coverUrl = obj.nullIfEmpty("coverUrl"),
                aweType = obj.optInt("aweType", 10500)
            )
        }

        private const val NONE = "__none__"

        private fun JSONObject.nullIfEmpty(key: String): String? {
            val v = optString(key, NONE)
            return if (v.isEmpty() || v == "null" || v == NONE) null else v
        }
    }
}