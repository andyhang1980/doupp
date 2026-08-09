package com.xposed.doupp.bookmark

import java.io.Serializable
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页书签记录（关注某个用户，探测新作品）。
 * 协议对齐 DYHelper 的 ProfileBookmarkRecord。
 */
class ProfileBookmarkRecord(
    val uid: String,
    val secUid: String,
    val nickname: String,
    val signature: String,
    val avatarUrl: String,
    val group: String,
    val remark: String,
    val createTimestamp: Long,
    lastCheckTimestamp: Long,
    lastAwemeCount: Int,
    lastNewestAwemeId: String,
    hasNewAweme: Boolean,
    newAwemeCount: Int,
    knownAwemeIds: MutableSet<String>,
    newAwemeItems: MutableList<ProfileBookmarkAwemeItem>,
    notificationEnabled: Boolean
) : Serializable {

    var lastCheckTimestamp: Long = lastCheckTimestamp
    var lastAwemeCount: Int = lastAwemeCount
    var lastNewestAwemeId: String = lastNewestAwemeId
    var hasNewAweme: Boolean = hasNewAweme
    var newAwemeCount: Int = newAwemeCount
    var knownAwemeIds: MutableSet<String> = knownAwemeIds
    var newAwemeItems: MutableList<ProfileBookmarkAwemeItem> = newAwemeItems
    var notificationEnabled: Boolean = notificationEnabled

    fun uniqueKey(): String {
        return when {
            secUid.isNotEmpty() -> "secUid:$secUid"
            uid.isNotEmpty() -> "uid:$uid"
            nickname.isNotEmpty() -> "nickname:$nickname"
            else -> ""
        }
    }

    fun displayName(): String {
        val n = nickname
        if (n.isNotEmpty()) return n
        val s = secUid
        if (s.isNotEmpty()) return s
        val u = uid
        return if (u.isNotEmpty()) u else "未知用户"
    }

    fun toJson(): JSONObject {
        val knownIds = JSONArray()
        knownAwemeIds.forEach { if (it.isNotEmpty()) knownIds.put(it) }
        val items = JSONArray()
        newAwemeItems.forEach { items.put(it.toJson()) }

        return JSONObject()
            .put("uid", uid)
            .put("secUid", secUid)
            .put("nickname", nickname)
            .put("signature", signature)
            .put("avatarUrl", avatarUrl)
            .put("group", group)
            .put("remark", remark)
            .put("createTimestamp", createTimestamp)
            .put("lastCheckTimestamp", lastCheckTimestamp)
            .put("lastAwemeCount", lastAwemeCount)
            .put("lastNewestAwemeId", lastNewestAwemeId)
            .put("hasNewAweme", hasNewAweme)
            .put("newAwemeCount", newAwemeCount)
            .put("knownAwemeIds", knownIds)
            .put("newAwemeItems", items)
            .put("notificationEnabled", notificationEnabled)
    }

    companion object {
        @JvmStatic
        fun fromJson(obj: JSONObject): ProfileBookmarkRecord {
            val knownIds = LinkedHashSet<String>()
            obj.optJSONArray("knownAwemeIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotEmpty()) knownIds.add(s)
                }
            }
            val items = mutableListOf<ProfileBookmarkAwemeItem>()
            obj.optJSONArray("newAwemeItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { item ->
                        val aw = ProfileBookmarkAwemeItem.fromJson(item)
                        if (aw.awemeId.isNotEmpty()) items.add(aw)
                    }
                }
            }
            return ProfileBookmarkRecord(
                uid = obj.optString("uid"),
                secUid = obj.optString("secUid"),
                nickname = obj.optString("nickname"),
                signature = obj.optString("signature"),
                avatarUrl = obj.optString("avatarUrl"),
                group = obj.optString("group"),
                remark = obj.optString("remark"),
                createTimestamp = obj.optLong("createTimestamp", System.currentTimeMillis()),
                lastCheckTimestamp = obj.optLong("lastCheckTimestamp", 0L),
                lastAwemeCount = obj.optInt("lastAwemeCount", 0),
                lastNewestAwemeId = obj.optString("lastNewestAwemeId"),
                hasNewAweme = obj.optBoolean("hasNewAweme", false),
                newAwemeCount = obj.optInt("newAwemeCount", 0),
                knownAwemeIds = knownIds,
                newAwemeItems = items,
                notificationEnabled = obj.optBoolean("notificationEnabled", true)
            )
        }
    }
}