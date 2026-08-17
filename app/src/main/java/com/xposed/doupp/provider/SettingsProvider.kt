package com.xposed.doupp.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.xposed.doupp.ui.DouSettings

/**
 * 跨进程设置读取桥。
 *
 * 抖音进程无法直接读取本模块 (com.xposed.doupp) 的 SharedPreferences
 * (XSharedPreferences 在部分 LSPosed 版本不可见，MODE_WORLD_READABLE 已被移除)，
 * 因此由模块自身暴露一个 exported ContentProvider，抖音进程通过 contentResolver.call
 * 读取最新设置。每次 call 都实时读取模块内的 SharedPreferences，保证设置即时生效。
 */
class SettingsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.xposed.doupp.settings"
        const val METHOD_ALL = "getAll"
        const val METHOD_SET = "set"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx: Context = context ?: return null
        if (method == METHOD_ALL) {
            val sp = ctx.getSharedPreferences(DouSettings.PREFS_NAME, Context.MODE_PRIVATE)
            val out = Bundle()
            sp.all.forEach { (key, value) ->
                when (value) {
                    is Boolean -> out.putBoolean(key, value)
                    is String -> out.putString(key, value)
                    is Int -> out.putInt(key, value)
                    is Long -> out.putLong(key, value)
                    is Float -> out.putFloat(key, value)
                }
            }
            return out
        }
        if (method == METHOD_SET) {
            // 通用设置写入：extras 中以 key -> value 形式携带要写入的键值
            // 例: content call --uri content://com.xposed.doupp.settings --method set \
            //     --extra auto_play:b:true
            if (extras == null || extras.isEmpty) return Bundle()
            val sp = ctx.getSharedPreferences(DouSettings.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = sp.edit()
            val out = Bundle()
            for (key in extras.keySet()) {
                val v = extras.get(key)
                when (v) {
                    is Boolean -> { editor.putBoolean(key, v); out.putBoolean(key, v) }
                    is String -> { editor.putString(key, v); out.putString(key, v) }
                    is Int -> { editor.putInt(key, v); out.putInt(key, v) }
                    is Long -> { editor.putLong(key, v); out.putLong(key, v) }
                    else -> {}
                }
            }
            editor.commit()
            return out
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/settings"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
