package com.xposed.doupp.hook

import android.view.View
import android.widget.ImageView
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.ContextHelper
import com.xposed.doupp.util.DexKitManager
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

    /**
     * Hook a comment adapter class by attaching to onCreateViewHolder.
     */
    private fun hookAdapterOnCreateViewHolder(adapterClass: Class<*>) {
        XposedBridge.hookAllMethods(adapterClass, "onCreateViewHolder",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!adapterClass.isInstance(param.thisObject)) return
                    try {
                        val vh = param.result
                        if (vh is androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                            addImageSaveHandler(vh.itemView)
                        }
                    } catch (_: Throwable) {}
                }
            })
    }

    /**
     * Hook a ViewHolder constructor to access itemView.
     */
    private fun hookViewHolderConstructor(vhClass: Class<*>) {
        for (ctor in vhClass.declaredConstructors) {
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val vh = param.thisObject
                        if (vh is androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                            addImageSaveHandler(vh.itemView)
                        } else {
                            val itemViewField = vh::class.java.getDeclaredField("itemView")
                            itemViewField.isAccessible = true
                            val itemView = itemViewField.get(vh) as? android.view.View ?: return
                            addImageSaveHandler(itemView)
                        }
                    } catch (_: Throwable) {}
                }
            })
        }
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
        var hooked = false

        // 策略1: 尝试已知的 39.7 类名（DEX 扫描确认过的）
        val knownCommentClasses = listOf(
            "com.ss.android.ugc.aweme.comment.adapter.CommentAdapter",
            "com.ss.android.ugc.aweme.comment.adapter.CommentAdapterV2",
            "com.ss.android.ugc.aweme.comment.adapter.CommentViewHolder",
            "com.ss.android.ugc.aweme.comment.adapter.CommentViewHolderV2",
            "com.ss.android.ugc.aweme.comment.adapter.CommentItemViewHolder",
            "com.ss.android.ugc.aweme.comment.ui.CommentItemView",
            "com.ss.android.ugc.aweme.comment.ui.longpress.CommentLongPressItemView"
        )
        for (className in knownCommentClasses) {
            try {
                val clazz = Class.forName(className, false, classLoader)
                val isAdapter = className.contains("Adapter", ignoreCase = true)
                if (isAdapter) {
                    hookAdapterOnCreateViewHolder(clazz)
                } else {
                    hookViewHolderConstructor(clazz)
                }
                hooked = true
                HookUtils.log("$TAG: Hook 评论类成功: $className [${if (isAdapter) "Adapter" else "ViewHolder"}]")
            } catch (_: ClassNotFoundException) {
            }
        }

        // 策略2: DexKit 自动发现（适配未来版本）
        if (!hooked) {
            try {
                val dexClasses = DexKitManager.findClassesByStrings(
                    strings = listOf("comment"),
                    packages = listOf("com.ss.android.ugc.aweme.comment.adapter")
                )
                val adapterNames = dexClasses.filter { it.endsWith("Adapter") }
                for (name in adapterNames) {
                    try {
                        val clazz = Class.forName(name, false, classLoader)
                        hookAdapterOnCreateViewHolder(clazz)
                        hooked = true
                        HookUtils.log("$TAG: DexKit 发现 Adapter: $name")
                        break
                    } catch (_: ClassNotFoundException) {}
                }
                if (!hooked) {
                    val vhNames = dexClasses.filter { it.endsWith("ViewHolder") || it.endsWith("ItemView") }
                    for (name in vhNames) {
                        try {
                            val clazz = Class.forName(name, false, classLoader)
                            hookViewHolderConstructor(clazz)
                            hooked = true
                            HookUtils.log("$TAG: DexKit 发现 ViewHolder: $name")
                            break
                        } catch (_: ClassNotFoundException) {}
                    }
                }
            } catch (t: Throwable) {
                HookUtils.log("$TAG: DexKit 搜索评论类失败: ${t.message}")
            }
        }

        if (!hooked) {
            HookUtils.log("$TAG: 未找到评论适配器类，安装备用 ContextMenu Hook")
            hookContextMenuFallback(classLoader)
        }
    }

    private fun addImageSaveHandler(root: View) {
        val rootKey = System.identityHashCode(root)
        if (rootKey in savedViewIds) return
        savedViewIds.add(rootKey)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                val imageViews = mutableListOf<ImageView>()
                collectImageViews(root, imageViews)

                for (iv in imageViews) {
                    if (savedViewIds.contains(System.identityHashCode(iv))) continue
                    savedViewIds.add(System.identityHashCode(iv))
                    iv.setOnLongClickListener { v ->
                        saveCommentImageInternal(iv)
                        true
                    }
                }

                // 监听布局变化，处理 Fresco 延迟加载的图片
                root.viewTreeObserver.addOnGlobalLayoutListener(
                    object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            try {
                                val newImageViews = mutableListOf<ImageView>()
                                collectImageViews(root, newImageViews)
                                for (iv in newImageViews) {
                                    val ivKey = System.identityHashCode(iv)
                                    if (ivKey in savedViewIds) continue
                                    savedViewIds.add(ivKey)
                                    iv.setOnLongClickListener { v ->
                                        saveCommentImageInternal(iv)
                                        true
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                )
            } catch (_: Throwable) {}
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
