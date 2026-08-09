package com.xposed.doupp.bookmark

import java.io.Serializable
import org.json.JSONObject

/**
 * 评论书签的某一条新回复。
 * 与 DYHelper 的 CommentBookmarkReplyItem 协议一致（JSON 字段可互读）。
 */
class CommentBookmarkReplyItem(
    val replyId: String,
    val authorName: String,
    val content: String,
    val createTime: Long
) : Serializable {

    fun toJson(): JSONObject = JSONObject().apply {
        put("replyId", replyId)
        put("authorName", authorName)
        put("content", content)
        put("createTime", createTime)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommentBookmarkReplyItem) return false
        return replyId == other.replyId &&
            authorName == other.authorName &&
            content == other.content &&
            createTime == other.createTime
    }

    override fun hashCode(): Int {
        var result = replyId.hashCode()
        result = 31 * result + authorName.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + createTime.hashCode()
        return result
    }

    companion object {
        @JvmStatic
        fun fromJson(obj: JSONObject): CommentBookmarkReplyItem {
            return CommentBookmarkReplyItem(
                replyId = obj.optString("replyId"),
                authorName = obj.optString("authorName"),
                content = obj.optString("content"),
                createTime = obj.optLong("createTime", 0L)
            )
        }
    }
}