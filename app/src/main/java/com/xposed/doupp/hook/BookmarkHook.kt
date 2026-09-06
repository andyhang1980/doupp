package com.xposed.doupp.hook

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.xposed.doupp.bookmark.BookmarkStores
import com.xposed.doupp.bookmark.CommentBookmarkRecord
import com.xposed.doupp.bookmark.CommentBookmarkStore
import com.xposed.doupp.bookmark.ProfileBookmarkAwemeItem
import com.xposed.doupp.bookmark.ProfileBookmarkRecord
import com.xposed.doupp.bookmark.ProfileBookmarkStore
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.ContextHelper
import com.xposed.doupp.util.DexKitManager
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * 书签 Hook — 初始化书签存储，监听评论与作品流，探测新回复/新作品。
 */
class BookmarkHook : BaseHook {

    companion object {
        private const val TAG = "BookmarkHook"
        private var installed = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            BookmarkStores.ensureInit(ContextHelper.getContext())
            storedReady = BookmarkStores.isReady()

            if (DouSettings.isCommentBookmarkEnabled()) {
                hookCommentDetection(classLoader)
            }
            if (DouSettings.isProfileBookmarkEnabled()) {
                hookProfileDetection(classLoader)
            }

            HookUtils.log("$TAG 安装完成 (store=$storedReady, commentHook=$commentHooked, profileHook=$profileHooked)")
            installed = true
        }
    }

    @Volatile
    private var storedReady = false

    // ==================== 评论书签：探测新回复 ====================

    @Volatile
    private var commentHooked = false

    /** 评论对象指纹 -> [cid, replyCount] */
    private val commentInfo = ConcurrentHashMap<Int, Array<Any?>>()

    /** 已注册长按收藏的 itemView */
    private val longPressedViews = ConcurrentHashMap<Int, Boolean>()

    private fun hookCommentDetection(classLoader: ClassLoader) {
        val knownClasses = listOf(
            "com.ss.android.ugc.aweme.comment.model.Comment",
            "com.ss.android.ugc.aweme.comment.model.CommentModel"
        )
        for (name in knownClasses) {
            val clazz = HookUtils.findClassOrNull(name, classLoader) ?: continue
            if (hookCommentModel(clazz)) {
                HookUtils.log("$TAG 评论模型 Hook 成功: $name")
                commentHooked = true
            }
        }

        if (!commentHooked) {
            try {
                val candidates = DexKitManager.findClassesByStrings(
                    listOf("commentId", "awemeId", "replyComment"),
                    listOf("com.ss.android.ugc.aweme.comment")
                )
                for (name in candidates.take(20)) {
                    val clazz = try { Class.forName(name, false, classLoader) } catch (_: Throwable) { continue }
                    val hasCid = clazz.declaredMethods.any { m ->
                        m.parameterCount == 0 && (m.name.contains("cid", true) || m.name.contains("commentId", true))
                    }
                    if (hasCid && hookCommentModel(clazz)) {
                        HookUtils.log("$TAG DexKit 命中评论模型: $name")
                        commentHooked = true
                        break
                    }
                }
            } catch (_: Throwable) {}
        }

        // 长按评论 -> 收藏/取消收藏
        hookCommentLongPress(classLoader)

        if (!commentHooked) {
            HookUtils.log("$TAG 评论模型未匹配，评论探测暂不可用")
        }
    }

    /** @return true 表示已安装有效探测 */
    private fun hookCommentModel(clazz: Class<*>): Boolean {
        return try {
            val cidMethods = clazz.declaredMethods.filter { m ->
                m.parameterCount == 0 && m.returnType == String::class.java &&
                    (m.name.equals("getcid", true) || m.name.equals("cid", true) ||
                        m.name.contains("commentid", true) || m.name.contains("comment_id", true))
            }
            val replyMethods = clazz.declaredMethods.filter { m ->
                m.parameterCount == 0 &&
                    (m.name.contains("replycount", true) || m.name.contains("replycommenttotal", true) ||
                        m.name.contains("reply_total", true) || m.name.contains("reply_count", true))
            }
            if (cidMethods.isEmpty() && replyMethods.isEmpty()) return false

            val cidMethod = cidMethods.firstOrNull()
            val replyMethod = replyMethods.firstOrNull()

            if (cidMethod != null) {
                XposedBridge.hookMethod(cidMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val cid = param.result as? String ?: return
                            if (!CommentBookmarkStore.isBookmarked(cid)) return
                            commentInfo[System.identityHashCode(param.thisObject)] = arrayOf(cid, null)
                        } catch (_: Throwable) {}
                    }
                })
            }
            if (replyMethod != null) {
                XposedBridge.hookMethod(replyMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val count = (param.result as? Number)?.toInt() ?: return
                            val info = commentInfo[System.identityHashCode(param.thisObject)] ?: return
                            val cid = info[0] as? String ?: return
                            flushCommentReplyCount(cid, count)
                            commentInfo.remove(System.identityHashCode(param.thisObject))
                        } catch (_: Throwable) {}
                    }
                })
            }
            if (commentInfo.size > 500) commentInfo.clear()
            true
        } catch (t: Throwable) {
            HookUtils.log("$TAG hookCommentModel 失败: ${t.message}")
            false
        }
    }

    private fun flushCommentReplyCount(cid: String, count: Int) {
        if (count < 0 || !CommentBookmarkStore.isBookmarked(cid)) return
        val changed = CommentBookmarkStore.updateReplyCount(cid, count)
        if (changed != null) {
            HookUtils.log("$TAG 评论书签回复更新 cid=$cid count=$count")
        }
    }

/**
     * 长按评论 -> 收藏/取消收藏。
     * 策略: Hook 评论绑定方法（参数含 Comment 对象），把评论绑到 itemView 上，
     * 设置 onLongClick 收藏。
     */
    private fun hookCommentLongPress(classLoader: ClassLoader) {
        try {
            val commentClasses = listOf(
                "com.ss.android.ugc.aweme.comment.model.Comment",
                "com.ss.android.ugc.aweme.comment.model.CommentModel"
            ).mapNotNull { HookUtils.findClassOrNull(it, classLoader) }

            val viewClasses = listOf(
                "com.ss.android.ugc.aweme.comment.view.CommentItemView",
                "com.ss.android.ugc.aweme.comment.adapter.CommentAdapter",
                "com.ss.android.ugc.aweme.comment.adapter.CommentViewHolder",
                "com.ss.android.ugc.aweme.comment.adapter.binding",
                "com.ss.android.ugc.aweme.comment.adapter.binding.CommentRightItemViewBinder",
                "com.ss.android.ugc.aweme.comment.adapter.CommentRightItemViewBinder",
                "com.ss.android.ugc.aweme.comment.adapter.CommentLeftItemViewBinder",
                "com.ss.android.ugc.aweme.comment.view.CommentSubItemView",
                "com.ss.android.ugc.aweme.comment.ui.CommentListItemView"
            )
            val viewClazzes = viewClasses.mapNotNull { HookUtils.findClassOrNull(it, classLoader) }

            for (vc in viewClazzes) {
                for (m in vc.declaredMethods) {
                    // 绑定方法特征: 参数中含 Comment 模型类型的实例
                    val hasCommentParam = m.parameterTypes.any { pt ->
                        commentClasses.any { it.isAssignableFrom(pt) || pt.isAssignableFrom(it) }
                    }
                    if (!hasCommentParam) continue
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val view = findViewFromBindTarget(param) ?: return
                                    val comment = param.args.firstOrNull { c ->
                                        c != null && commentClasses.any { it.isInstance(c) }
                                    } ?: return
                                    attachLongPressHandler(view, comment)
                                } catch (_: Throwable) {}
                            }
                        })
                        HookUtils.log("$TAG 评论长按绑定 Hook: ${vc.name}.${m.name}")
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG hookCommentLongPress 失败: ${t.message}")
        }
    }

    /** 从绑定回调里取 itemView（本身是 View，或首参数是 ViewHolder） */
    private fun findViewFromBindTarget(param: XC_MethodHook.MethodHookParam): View? {
        if (param.thisObject is View) return param.thisObject as View
        for (a in param.args) {
            when (a) {
                is View -> return a
                is RecyclerView.ViewHolder -> return a.itemView
                else -> {
                    val itemView = getField(a, "itemView") as? View ?: continue
                    return itemView
                }
            }
        }
        return null
    }

    private fun attachLongPressHandler(view: View, comment: Any) {
        try {
            val key = System.identityHashCode(view)
            if (longPressedViews[key] == true) return
            longPressedViews[key] = true

            view.setOnLongClickListener {
                try {
                    handleCommentLongPress(comment)
                } catch (_: Throwable) {}
                true
            }
        } catch (_: Throwable) {}
    }

    private fun handleCommentLongPress(comment: Any) {
        val context = ContextHelper.getContext() ?: return
        if (!DouSettings.isCommentBookmarkEnabled()) return

        val cid = readString(comment, listOf("cid", "commentId", "comment_id")) ?: return
        val existing = CommentBookmarkStore.get(cid)
        if (existing != null) {
            CommentBookmarkStore.remove(cid)
            HookUtils.showToast(context, "已取消收藏该评论")
            return
        }

        val record = buildCommentRecord(comment, cid)
        if (CommentBookmarkStore.add(record)) {
            HookUtils.showToast(context, "已收藏评论 ✓ 有新回复将提醒")
            HookUtils.log("$TAG 长按收藏评论 cid=$cid")
        } else {
            HookUtils.showToast(context, "收藏失败")
        }
    }

    private fun buildCommentRecord(comment: Any, cid: String): CommentBookmarkRecord {
        val content = readString(comment, listOf("text", "content", "contentText", "richText", "comment")) ?: ""
        val authorName = extractAuthorName(comment)
        val createTime = readLong(comment, listOf("createTime", "create_time", "createTimeInSec", "time")) ?: 0L
        val replyCount = readInt(comment, listOf("replyCommentTotal", "replyCommentTotalCount", "replyCount", "reply_total")) ?: 0

        val aweme = MediaCache.getCurrentAweme()
        val awemeId = aweme?.let { readString(it, listOf("awemeId", "aweme_id", "itemId", "item_id")) } ?: ""
        val author = aweme?.let { it -> authorFor(it) }
        val awemeAuthorId = author?.let { getField(it, "uid") as? String } ?: ""
        val awemeAuthorSecUid = author?.let { getField(it, "secUid") as? String } ?: ""
        val awemeTitle = aweme?.let { readString(it, listOf("desc", "description")) } ?: ""
        val commentSecUid = run {
            val u = getField(comment, "user") ?: getField(comment, "commentUser") ?: getField(comment, "author")
            u?.let { getField(it, "secUid") as? String }
        } ?: ""

        return CommentBookmarkRecord(
            commentId = cid,
            awemeId = awemeId,
            content = content,
            authorName = authorName,
            commentCreateTime = createTime,
            createTimestamp = System.currentTimeMillis(),
            notificationEnabled = true,
            userTag = "",
            remark = "",
            lastCheckTimestamp = System.currentTimeMillis(),
            lastKnownReplyCount = replyCount,
            hasNewReplies = false,
            newReplyCount = 0,
            knownReplyIds = LinkedHashSet(),
            newReplyItems = mutableListOf(),
            awemeAuthorId = awemeAuthorId.ifEmpty { null },
            awemeAuthorSecUid = awemeAuthorSecUid.ifEmpty { null },
            awemeTitle = awemeTitle.ifEmpty { null },
            commentSecUid = commentSecUid.ifEmpty { null },
            coverUrl = null,
            aweType = 0
        )
    }

    private fun extractAuthorName(comment: Any): String {
        // 评论 user 里的 nickname
        val user = getField(comment, "user") ?: getField(comment, "commentUser") ?: getField(comment, "author")
        user?.let { u ->
            val nick = readString(u, listOf("nickname", "nickName", "name"))
            if (!nick.isNullOrEmpty() && nick != "抖音用户") return nick
        }
        return "评论用户"
    }

    private fun authorFor(aweme: Any): Any? {
        return getField(aweme, "author") ?: getField(aweme, "mAuthor")
    }

    private fun readString(obj: Any?, names: List<String>): String? {
        if (obj == null) return null
        for (n in names) {
            getField(obj, n)?.let { if (it is String && it.isNotEmpty()) return it }
        }
        // getter 兜底
        for (n in names) {
            val v = try {
                obj.javaClass.declaredMethods.firstOrNull { m ->
                    m.parameterCount == 0 && m.returnType == String::class.java &&
                        (m.name.equals(n, true) || m.name.equals("get$n", true))
                }?.invoke(obj)
            } catch (_: Throwable) { null }
            if (v is String && v.isNotEmpty()) return v
        }
        return null
    }

    private fun readLong(obj: Any?, names: List<String>): Long? {
        if (obj == null) return null
        for (n in names) {
            val v = getField(obj, n) as? Number ?: continue
            return v.toLong()
        }
        for (n in names) {
            val v = try {
                obj.javaClass.declaredMethods.firstOrNull { m ->
                    m.parameterCount == 0 && (m.name.equals(n, true) || m.name.equals("get$n", true))
                }?.invoke(obj) as? Number
            } catch (_: Throwable) { null }
            if (v != null) return v.toLong()
        }
        return null
    }

    private fun readInt(obj: Any?, names: List<String>): Int? = readLong(obj, names)?.toInt()

    // ==================== 主页书签：探测新作品 ====================

    @Volatile
    private var profileHooked = false

    private fun hookProfileDetection(classLoader: ClassLoader) {
        try {
            val cached = AdaptationManager.getAdaptedClasses("aweme")
            val awemeClass = ClassFinder.findClass(
                classLoader,
                cached + listOf(
                    "com.ss.android.ugc.aweme.feed.model.Aweme",
                    "com.ss.ugc.aweme.Aweme",
                    "com.ss.android.ugc.aweme.model.Aweme"
                )
            ) ?: run {
                HookUtils.log("$TAG 未找到 Aweme 类，主页探测跳过")
                return
            }

            val authorMethods = awemeClass.declaredMethods.filter { m ->
                m.parameterCount == 0 &&
                    (m.name.equals("getAuthor", true) || m.name.equals("author", true))
            }
            val target = if (authorMethods.isNotEmpty()) authorMethods else listOf(
                awemeClass.declaredMethods.firstOrNull { m ->
                    m.parameterCount == 0 && m.returnType == String::class.java &&
                        (m.name.contains("awemeId", true) || m.name.contains("awemeid", true))
                } ?: return
            )

            for (m in target) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val author = if (authorMethods.isNotEmpty()) param.result
                            else getField(param.thisObject, "author")
                            if (author != null) param.thisObject?.let { syncAuthorAweme(it, author) }
                        } catch (_: Throwable) {}
                    }
                })
            }
            profileHooked = true
            HookUtils.log("$TAG 主页书签探测 Hook 已安装 (aweme=${awemeClass.name})")
        } catch (t: Throwable) {
            HookUtils.log("$TAG hookProfileDetection 失败: ${t.message}")
        }
    }

    private fun syncAuthorAweme(aweme: Any, author: Any?) {
        if (ProfileBookmarkStore.size() == 0 || author == null) return
        val secUid = getField(author, "secUid") as? String ?: ""
        val uid = getField(author, "uid") as? String ?: ""

        var record: ProfileBookmarkRecord? = null
        var key: String? = null
        if (secUid.isNotEmpty()) {
            key = "secUid:$secUid"
            record = ProfileBookmarkStore.get(key)
        }
        if (record == null && uid.isNotEmpty()) {
            key = "uid:$uid"
            record = ProfileBookmarkStore.get(key)
        }
        if (record == null || key == null) return

        val awemeId = getField(aweme, "awemeId") as? String ?: ""
        if (awemeId.isEmpty()) return

        val desc = getField(aweme, "desc") as? String ?: ""
        val createTime = (getField(aweme, "createTime") as? Number)?.toLong() ?: 0L
        val item = ProfileBookmarkAwemeItem(awemeId, desc, createTime, extractCoverUrl(aweme))
        val changed = ProfileBookmarkStore.updateAwemeSnapshot(key, listOf(item), targetCount = record.lastAwemeCount)
        if (changed != null) {
            HookUtils.log("$TAG 主页书签命中 author=${record.displayName()} new=${changed.newAwemeItems.size}")
        }
    }

    private fun extractCoverUrl(aweme: Any): String {
        return try {
            val video = getField(aweme, "video") ?: return ""
            val cover = getField(video, "cover") ?: return ""
            val urlList = getField(cover, "urlList") as? List<*>
                ?: getField(cover, "url_list") as? List<*>
                ?: return ""
            urlList.filterIsInstance<String>().firstOrNull { it.startsWith("http") } ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun getField(obj: Any?, name: String): Any? = HookUtils.getFieldDeep(obj, name)
}