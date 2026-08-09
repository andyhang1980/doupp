package com.xposed.doupp.bookmark

import android.content.Context
import com.xposed.doupp.util.HookUtils

/**
 * 书签各存储层的统一初始化入口。
 *
 * 三处调用方（BookmarkHook.init、分享面板按钮、评论长按）都可能先于
 * Context 就绪执行，这里做幂等初始化，避免 Store 内 appContext 为空导致的静默失败。
 */
object BookmarkStores {

    private const val TAG = "BookmarkStores"

    /**
     * 初始化所有存储层与通知器。
     * 幂等：各 Store 内部已有 appContext 判空保护。
     *
     * @return true 表示全部就绪
     */
    fun ensureInit(context: Context?): Boolean {
        if (context == null) return isReady()
        val app = context.applicationContext ?: return isReady()
        try {
            CommentBookmarkStore.init(app)
            VideoBookmarkStore.init(app)
            ProfileBookmarkStore.init(app)
            BookmarkNotifier.init(app)
            return isReady()
        } catch (t: Throwable) {
            HookUtils.log("$TAG init 失败: ${t.message}")
            return false
        }
    }

    fun isReady(): Boolean =
        CommentBookmarkStore.isInitialized() &&
            VideoBookmarkStore.isInitialized() &&
            ProfileBookmarkStore.isInitialized()
}