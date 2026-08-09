package com.xposed.doupp.bookmark

import android.content.Context
import com.xposed.doupp.util.HookUtils
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/**
 * 评论书签存储层。
 * 逻辑与 DYHelper 的 CommentBookmarkStore 对齐：
 * - records 以 commentId 为键的有序表
 * - categories 独立存储
 * - 变更后通知 listeners
 * - 持久化到 SharedPreferences（JSON 数组），字段协议一致
 */
object CommentBookmarkStore {

    private const val KEY_BOOKMARKS = "bookmarks_v1"
    private const val KEY_CATEGORIES = "categories_v1"
    private const val SP_NAME = "doupp_comment_bookmark"
    private const val TAG = "CommentBookmark"

    @Volatile
    private var appContext: Context? = null

    private val lock = Any()
    private val records = LinkedHashMap<String, CommentBookmarkRecord>()
    private val categories = ArrayList<String>()
    private val listeners = CopyOnWriteArrayList<Runnable>()

    // 默认分类
    private val defaultCategories = listOf("默认", "重要", "有趣", "待办")

    // ==================== 初始化 / 持久化 ====================

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext ?: context
        load()
    }

    fun isInitialized(): Boolean = appContext != null

    private fun load() {
        val context = appContext ?: return
        synchronized(lock) {
            records.clear()
            categories.clear()
            try {
                val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                val bs = sp.getString(KEY_BOOKMARKS, "[]") ?: "[]"
                val arr = JSONArray(bs)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val record = CommentBookmarkRecord.fromJson(obj)
                    if (record.commentId.isNotEmpty()) {
                        records[record.commentId] = record
                    }
                }
                val cs = sp.getString(KEY_CATEGORIES, "[]") ?: "[]"
                val cArr = JSONArray(cs)
                for (i in 0 until cArr.length()) {
                    val c = cArr.optString(i)
                    if (c.isNotEmpty()) categories.add(c)
                }
            } catch (t: Throwable) {
                HookUtils.log("$TAG load failed: ${t.message}")
            }
        }
    }

    private fun save() {
        val context = appContext ?: return
        try {
            val arr = JSONArray()
            synchronized(lock) {
                for (r in records.values) arr.put(r.toJson())
            }
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
        } catch (t: Throwable) {
            HookUtils.log("$TAG save failed: ${t.message}")
        }
    }

    private fun saveCategories() {
        val context = appContext ?: return
        try {
            val arr = JSONArray()
            synchronized(lock) {
                for (c in categories) arr.put(c)
            }
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CATEGORIES, arr.toString()).apply()
        } catch (t: Throwable) {
            HookUtils.log("$TAG save categories failed: ${t.message}")
        }
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try { l.run() } catch (_: Throwable) {}
        }
    }

    // ==================== 增删查改 ====================

    fun add(record: CommentBookmarkRecord): Boolean {
        if (record.commentId.isEmpty()) return false
        var added = false
        synchronized(lock) {
            if (!records.containsKey(record.commentId)) {
                records[record.commentId] = record
                added = true
            }
        }
        if (added) {
            HookUtils.log("$TAG add bookmark cid=${record.commentId}, awemeId=${record.awemeId}, reply=${record.lastKnownReplyCount}, content=${record.content.take(20)}")
            save()
            notifyChanged()
        }
        return added
    }

    fun remove(commentId: String): Boolean {
        if (commentId.isEmpty()) return false
        var removed = false
        synchronized(lock) { removed = records.remove(commentId) != null }
        if (removed) {
            save()
            notifyChanged()
        }
        return removed
    }

    fun get(commentId: String): CommentBookmarkRecord? {
        if (commentId.isEmpty()) return null
        synchronized(lock) { return records[commentId] }
    }

    fun isBookmarked(commentId: String): Boolean {
        if (commentId.isEmpty()) return false
        synchronized(lock) { return records.containsKey(commentId) }
    }

    fun all(): List<CommentBookmarkRecord> {
        synchronized(lock) { return ArrayList(records.values) }
    }

    fun size(): Int {
        synchronized(lock) { return records.size }
    }

    fun clear() {
        synchronized(lock) { records.clear() }
        save()
        notifyChanged()
    }

    // ==================== 分类 ====================

    fun addCategory(name: String) {
        if (name.isEmpty()) return
        synchronized(lock) {
            if (!categories.contains(name)) {
                categories.add(name)
                saveCategories()
            }
        }
    }

    fun getCategories(): List<String> {
        synchronized(lock) {
            return if (categories.isEmpty()) ArrayList(defaultCategories) else ArrayList(categories)
        }
    }

    // ==================== 回复探测 ====================

    /**
     * 更新回复数。replyCount 大于 lastKnownReplyCount 时标记有新回复。
     */
    fun updateReplyCount(commentId: String, replyCount: Int, notify: Boolean = true): CommentBookmarkRecord? {
        if (commentId.isEmpty() || replyCount < 0) return null
        synchronized(lock) {
            val r = records[commentId] ?: return null
            try {
                val old = r.lastKnownReplyCount
                r.lastCheckTimestamp = System.currentTimeMillis()
                if (replyCount > old) {
                    val diff = replyCount - old
                    HookUtils.log("$TAG new replies cid=$commentId old=$old new=$replyCount diff=$diff")
                    r.hasNewReplies = true
                    r.newReplyCount += diff
                    r.lastKnownReplyCount = replyCount
                    save()
                    notifyChanged()
                    if (notify && r.notificationEnabled) {
                        BookmarkNotifier.notifyNewCommentReply(r)
                    }
                    return r
                } else {
                    r.lastKnownReplyCount = replyCount
                    save()
                    return r
                }
            } catch (t: Throwable) {
                throw t
            }
        }
    }

    /**
     * 更新回复数 + 新回复明细（去重、按时间倒序、最多保留 50 条）。
     */
    fun updateReplyCountAndReplies(
        commentId: String,
        replyCount: Int,
        replies: List<CommentBookmarkReplyItem>,
        notify: Boolean = true
    ): CommentBookmarkRecord? {
        if (commentId.isEmpty() || replyCount < 0) return null
        synchronized(lock) {
            val r = records[commentId] ?: return null
            try {
                val old = r.lastKnownReplyCount
                r.lastCheckTimestamp = System.currentTimeMillis()

                var updated: CommentBookmarkRecord? = null
                if (replies.isNotEmpty()) {
                    r.hasNewReplies = true
                    r.newReplyItems.addAll(replies)
                    // 去重
                    val seen = HashSet<String>()
                    val dedup = ArrayList<CommentBookmarkReplyItem>()
                    for (item in r.newReplyItems) {
                        if (seen.add(item.replyId)) dedup.add(item)
                    }
                    // 按创建时间倒序，最多 50 条
                    dedup.sortByDescending { it.createTime }
                    r.newReplyItems.clear()
                    r.newReplyItems.addAll(dedup.take(50))
                    // 记录已知 reply id
                    for (item in replies) {
                        if (item.replyId.isNotEmpty()) r.knownReplyIds.add(item.replyId)
                    }
                    r.newReplyCount += replies.size
                    HookUtils.log("$TAG new reply items cid=$commentId count=${replies.size} total=${r.newReplyItems.size}")
                    updated = r
                }

                if (replyCount > old) {
                    val diff = replyCount - old
                    HookUtils.log("$TAG reply count increased cid=$commentId old=$old new=$replyCount diff=$diff")
                    r.hasNewReplies = true
                    if (replies.isEmpty()) r.newReplyCount += diff
                    r.lastKnownReplyCount = replyCount
                } else {
                    r.lastKnownReplyCount = replyCount
                }

                save()
                notifyChanged()
                if (notify && r.notificationEnabled && r.hasNewReplies) {
                    BookmarkNotifier.notifyNewCommentReply(r)
                }
                return updated ?: r
            } catch (t: Throwable) {
                throw t
            }
        }
    }

    /**
     * 分批更新回复明细：只把之前未见过的 replyId 加入新回复。
     */
    fun updateReplyItems(commentId: String, replyCount: Int, replies: List<CommentBookmarkReplyItem>, notify: Boolean = true): Boolean {
        if (commentId.isEmpty() || (replies.isEmpty() && replyCount < 0)) return false
        synchronized(lock) {
            val r = records[commentId] ?: return false
            try {
                val known = r.knownReplyIds.toSet()
                val unseen = replies.filter { it.replyId.isNotEmpty() && it.replyId !in known }
                val dedup = LinkedHashMap<String, CommentBookmarkReplyItem>()
                for (item in unseen) dedup[item.replyId] = item
                val newItems = dedup.values.toList()

                for (item in replies) if (item.replyId.isNotEmpty()) r.knownReplyIds.add(item.replyId)

                var changed = false
                if (newItems.isNotEmpty()) {
                    r.hasNewReplies = true
                    r.newReplyItems.addAll(newItems)
                    val seen = HashSet<String>()
                    val dedupAll = ArrayList<CommentBookmarkReplyItem>()
                    for (item in r.newReplyItems) {
                        if (seen.add(item.replyId)) dedupAll.add(item)
                    }
                    dedupAll.sortByDescending { it.createTime }
                    r.newReplyItems.clear()
                    r.newReplyItems.addAll(dedupAll.take(50))
                    r.newReplyCount += newItems.size
                    HookUtils.log("$TAG new reply items cid=$commentId count=${newItems.size} total=${r.newReplyItems.size}")
                    changed = true
                }
                if (replyCount >= 0 && replyCount != r.lastKnownReplyCount) {
                    r.lastKnownReplyCount = replyCount
                    changed = true
                }
                r.lastCheckTimestamp = System.currentTimeMillis()

                if (changed) {
                    save()
                    notifyChanged()
                    if (notify && r.notificationEnabled && r.hasNewReplies) {
                        BookmarkNotifier.notifyNewCommentReply(r)
                    }
                }
                return changed
            } catch (t: Throwable) {
                throw t
            }
        }
    }

    /**
     * 标记已读：清空新回复状态。
     */
    fun markRead(commentId: String) {
        if (commentId.isEmpty()) return
        synchronized(lock) {
            try {
                val r = records[commentId] ?: return
                for (item in r.newReplyItems) {
                    if (item.replyId.isNotEmpty()) r.knownReplyIds.add(item.replyId)
                }
                r.hasNewReplies = false
                r.newReplyCount = 0
                r.newReplyItems.clear()
                r.lastCheckTimestamp = System.currentTimeMillis()
                save()
                notifyChanged()
            } catch (_: Throwable) {}
        }
    }

    // ==================== 监听 ====================

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }
}