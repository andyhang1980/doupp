package com.xposed.doupp.bookmark

import android.content.Context
import com.xposed.doupp.util.HookUtils
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/**
 * 作品书签存储层。协议对齐 DYHelper 的 VideoBookmarkStore。
 */
object VideoBookmarkStore {

    private const val KEY_BOOKMARKS = "bookmarks_v1"
    private const val SP_NAME = "doupp_video_bookmark"
    private const val TAG = "VideoBookmark"

    @Volatile
    private var appContext: Context? = null

    private val lock = Any()
    private val records = LinkedHashMap<String, VideoBookmarkRecord>()
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
                    val r = VideoBookmarkRecord.fromJson(obj)
                    if (r.awemeId.isNotEmpty()) records[r.awemeId] = r
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

    fun add(record: VideoBookmarkRecord): Boolean {
        if (record.awemeId.isEmpty()) return false
        var added = false
        synchronized(lock) {
            val existed = records.containsKey(record.awemeId)
            records[record.awemeId] = record
            added = !existed
        }
        save()
        notifyChanged()
        HookUtils.log("$TAG save video bookmark awemeId=${record.awemeId}, added=$added")
        return added
    }

    fun remove(awemeId: String): Boolean {
        if (awemeId.isEmpty()) return false
        var removed = false
        synchronized(lock) { removed = records.remove(awemeId) != null }
        if (removed) {
            save()
            notifyChanged()
        }
        return removed
    }

    fun isBookmarked(awemeId: String): Boolean {
        if (awemeId.isEmpty()) return false
        synchronized(lock) { return records.containsKey(awemeId) }
    }

    fun all(): List<VideoBookmarkRecord> {
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

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }
}