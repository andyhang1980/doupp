package com.xposed.doupp.bookmark

import java.io.Serializable
import org.json.JSONObject

/**
 * 主页书签中的某条新作品。协议对齐 DYHelper 的 ProfileBookmarkAwemeItem。
 */
class ProfileBookmarkAwemeItem(
    val awemeId: String,
    val desc: String,
    val createTime: Long,
    val coverUrl: String
) : Serializable {

    fun toJson(): JSONObject = JSONObject().apply {
        put("awemeId", awemeId)
        put("desc", desc)
        put("createTime", createTime)
        put("coverUrl", coverUrl)
    }

    companion object {
        @JvmStatic
        fun fromJson(obj: JSONObject): ProfileBookmarkAwemeItem {
            return ProfileBookmarkAwemeItem(
                awemeId = obj.optString("awemeId"),
                desc = obj.optString("desc"),
                createTime = obj.optLong("createTime", 0L),
                coverUrl = obj.optString("coverUrl")
            )
        }
    }
}