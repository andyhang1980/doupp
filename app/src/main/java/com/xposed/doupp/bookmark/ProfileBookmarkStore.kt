package com.xposed.doupp.bookmark

import android.content.Context
import com.xposed.doupp.util.HookUtils
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页书签存储层。协议对齐 DYHelper 的 ProfileBookmarkStore。
 * 以 uniqueKey (secUid/uid/nickname 优先) 为键。
 */
object ProfileBookmarkStore {

    private const val KEY_BOOKMARKS = "profile_bookmarks_v1"
    private const val SP_NAME = "doupp_profile_bookmark"
    private const val TAG = "ProfileBookmark"

    @Volatile
    private var appContext: Context? = null

    private val lock = Any()
    private val records = LinkedHashMap<String, ProfileBookmarkRecord>()
    private val listeners = CopyOnWriteArrayList<Runnable>()

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
            try {
                val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                val bs = sp.getString(KEY_BOOKMARKS, "[]") ?: "[]"
                val arr = JSONArray(bs)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val r = ProfileBookmarkRecord.fromJson(obj)
                    val key = r.uniqueKey()
                    if (key.isNotEmpty()) records[key] = r
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

    private fun notifyChanged() {
        for (l in listeners) {
            try { l.run() } catch (_: Throwable) {}
        }
    }

    fun add(record: ProfileBookmarkRecord): Boolean {
        val key = record.uniqueKey()
        if (key.isEmpty()) return false
        var added = false
        synchronized(lock) {
            if (!records.containsKey(key)) {
                records[key] = record
                added = true
            }
        }
        if (added) {
            HookUtils.log("$TAG add profile bookmark key=$key nickname=${record.nickname}")
            save()
            notifyChanged()
        }
        return added
    }

    fun remove(key: String): Boolean {
        if (key.isEmpty()) return false
        var removed = false
        synchronized(lock) { removed = records.remove(key) != null }
        if (removed) {
            save()
            notifyChanged()
        }
        return removed
    }

    fun get(key: String): ProfileBookmarkRecord? {
        if (key.isEmpty()) return null
        synchronized(lock) { return records[key] }
    }

    fun isBookmarked(record: ProfileBookmarkRecord?): Boolean {
        if (record == null) return false
        val key = record.uniqueKey()
        if (key.isEmpty()) return false
        synchronized(lock) { return records.containsKey(key) }
    }

    fun all(): List<ProfileBookmarkRecord> {
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

    fun markRead(key: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            try {
                val r = records[key] ?: return
                r.hasNewAweme = false
                r.newAwemeCount = 0
                r.newAwemeItems.clear()
                r.lastCheckTimestamp = System.currentTimeMillis()
                save()
                notifyChanged()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 更新作品快照：将 list 中未见过且属于书签创建之后的新作品标记为新。
     */
    fun updateAwemeSnapshot(key: String, list: List<ProfileBookmarkAwemeItem>, targetCount: Int, notify: Boolean = true): ProfileBookmarkRecord? {
        if (key.isEmpty() || list.isEmpty()) return null
        synchronized(lock) {
            val r = records[key] ?: return null
            try {
                val known = r.knownAwemeIds.toSet()
                // 只看书签时间之后的新作品
                val candidates = list
                    .filter { it.awemeId.isNotEmpty() && it.awemeId !in known && it.createTime * 1000 >= r.createTimestamp }
                val dedup = LinkedHashMap<String, ProfileBookmarkAwemeItem>()
                for (item in candidates) dedup[item.awemeId] = item

                for (item in list) if (item.awemeId.isNotEmpty()) r.knownAwemeIds.add(item.awemeId)

                var updated: ProfileBookmarkRecord? = null
                if (dedup.isNotEmpty()) {
                    r.hasNewAweme = true
                    r.newAwemeCount += dedup.size
                    r.newAwemeItems.addAll(dedup.values)
                    val seen = HashSet<String>()
                    val dedupAll = ArrayList<ProfileBookmarkAwemeItem>()
                    for (item in r.newAwemeItems) {
                        if (seen.add(item.awemeId)) dedupAll.add(item)
                    }
                    dedupAll.sortByDescending { it.createTime }
                    r.newAwemeItems.clear()
                    r.newAwemeItems.addAll(dedupAll.take(50))
                    HookUtils.log("$TAG new profile aweme key=$key user=${r.displayName()} new=${dedup.size}")
                    updated = r
                }
                val newest = list.filter { it.awemeId.isNotEmpty() }.maxByOrNull { it.createTime }
                r.lastAwemeCount = maxOf(targetCount, list.size)
                r.lastCheckTimestamp = System.currentTimeMillis()
                if (newest != null) r.lastNewestAwemeId = newest.awemeId

                save()
                notifyChanged()
                if (notify && updated != null && r.notificationEnabled) {
                    BookmarkNotifier.notifyNewProfileAweme(r)
                }
                return updated ?: r
            } catch (t: Throwable) {
                throw t
            }
        }
    }

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }
}