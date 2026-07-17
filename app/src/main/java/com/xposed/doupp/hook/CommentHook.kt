package com.xposed.doupp.hook

import android.view.View
import android.widget.ImageView
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.ContextHelper
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaDownloader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class CommentHook : BaseHook {

    companion object {
        private const val TAG = "CommentHook"
        private var installed = false
        private val savedViewIds = mutableSetOf<Int>()
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        hookClipboard(classLoader)
        if (DouSettings.isSaveCommentMedia()) {
            hookCommentImageSave(classLoader)
        }

        installed = true
    }

    private fun hookClipboard(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val clipboardClass = HookUtils.findClassOrNull(
                "android.content.ClipboardManager", classLoader) ?: return@safeHook

            HookUtils.hookAllBefore(clipboardClass, "setPrimaryClip") { param ->
                val clip = param.args.firstOrNull() as? android.content.ClipData
                    ?: return@hookAllBefore
                val text = clip.getItemAt(0)?.text?.toString() ?: return@hookAllBefore

                if (text.contains("playwm") || text.contains("douyin.com")) {
                    HookUtils.log("$TAG: 剪贴板中发现抖音链接")
                }
            }
            HookUtils.log("$TAG: ClipboardManager Hook 已安装")
        }
    }

    private fun hookCommentImageSave(classLoader: ClassLoader) {
        val adapterClasses = listOf(
            "com.ss.android.ugc.aweme.comment.ui.CommentListAdapter",
            "com.ss.android.ugc.aweme.comment.adapter.CommentAdapter",
            "com.ss.android.ugc.aweme.comment.adapter.CommentListAdapter",
            "com.ss.android.ugc.aweme.comment.widget.CommentItemView"
        )

        var hooked = false
        for (className in adapterClasses) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                val bindMethods = clazz.declaredMethods.filter { m ->
                    m.name.lowercase().contains("bind") &&
                    m.parameterTypes.any { View::class.java.isAssignableFrom(it) }
                }
                for (method in bindMethods) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                val view = param.args.firstOrNull { it is View } as? View ?: return
                                addImageSaveHandler(view)
                            } catch (_: Throwable) {}
                        }
                    })
                    hooked = true
                }
                if (hooked) {
                    HookUtils.log("$TAG: Hook 评论 Item 绑定: $className (${bindMethods.size} 方法)")
                    break
                }
            } catch (_: ClassNotFoundException) {}
        }

        if (!hooked) {
            HookUtils.log("$TAG: 未找到评论适配器类，安装备用 ContextMenu Hook")
            hookContextMenuFallback(classLoader)
        }
    }

    private fun addImageSaveHandler(root: View) {
        if (root.id in savedViewIds) return
        savedViewIds.add(root.id)
        savedViewIds.add(System.identityHashCode(root))

        val imageViews = mutableListOf<ImageView>()
        collectImageViews(root, imageViews)

        for (iv in imageViews) {
            iv.setOnLongClickListener { v ->
                saveCommentImageInternal(iv)
                true
            }
        }
    }

    private fun collectImageViews(view: View, result: MutableList<ImageView>) {
        if (view is ImageView) {
            result.add(view)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                collectImageViews(view.getChildAt(i), result)
            }
        }
    }

    private fun saveCommentImageInternal(imageView: ImageView) {
        try {
            val context = ContextHelper.getContext() ?: return
            if (!DouSettings.isSaveCommentMedia()) return

            val drawable = imageView.drawable ?: return
            HookUtils.showToast(context, "正在保存评论图片...")

            val imageUrl = extractImageUrl(imageView)
            if (imageUrl != null) {
                val fileName = "comment_${System.currentTimeMillis()}.jpg"
                MediaDownloader.download(context, imageUrl, fileName,
                    onComplete = { HookUtils.showToast(context, "评论图片已保存 ✓") },
                    onError = { HookUtils.showToast(context, "保存失败: ${it.message}") }
                )
            } else {
                HookUtils.showToast(context, "无法获取图片地址")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 保存评论图片异常: ${t.message}")
        }
    }

    private fun extractImageUrl(imageView: ImageView): String? {
        val tag = imageView.tag
        if (tag is String && tag.startsWith("http")) return tag

        val contentDesc = imageView.contentDescription?.toString()
        if (contentDesc != null && contentDesc.startsWith("http")) return contentDesc

        return null
    }

    private fun hookContextMenuFallback(classLoader: ClassLoader) {
        try {
            val activityClass = Class.forName("android.app.Activity", false, classLoader)
            XposedBridge.hookAllMethods(activityClass, "onContextItemSelected",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            if (!DouSettings.isSaveCommentMedia()) return
                            val menuItem = param.args.firstOrNull() ?: return
                            val title = try {
                                menuItem::class.java.getMethod("getTitle")
                                    .invoke(menuItem)?.toString() ?: ""
                            } catch (_: Throwable) { "" }

                            if (title.contains("保存") ||
                                title.contains("save", ignoreCase = true)) {
                                saveCommentFromContext(menuItem)
                            }
                        } catch (_: Throwable) {}
                    }
                })
            HookUtils.log("$TAG: ContextMenu 备用 Hook 已安装")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: ContextMenu 备用 Hook 失败: ${t.message}")
        }
    }

    private fun saveCommentFromContext(menuItem: Any) {
        try {
            val context = ContextHelper.getContext() ?: return
            val intent = try {
                menuItem::class.java.getMethod("getIntent")
                    .invoke(menuItem) as? android.content.Intent
            } catch (_: Throwable) { null }
            val data = intent?.data?.toString()
            if (data != null && data.startsWith("http")) {
                val fileName = "comment_${System.currentTimeMillis()}.jpg"
                MediaDownloader.download(context, data, fileName,
                    onComplete = { HookUtils.showToast(context, "评论图片已保存 ✓") },
                    onError = { HookUtils.showToast(context, "保存失败: ${it.message}") }
                )
            }
        } catch (_: Throwable) {}
    }
}
