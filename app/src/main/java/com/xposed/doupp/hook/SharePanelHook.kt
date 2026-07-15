package com.xposed.doupp.hook

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.IconRes
import com.xposed.doupp.util.MediaCache
import com.xposed.doupp.util.MediaDownloader
import com.xposed.doupp.util.UrlParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 分享面板 Hook — 在分享面板底部注入功能按钮
 *
 * 策略: Hook Dialog.show()，检测分享面板 Dialog，延迟注入按钮
 *
 * 抖音 39.5.0 分享面板关键类:
 * - com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel.ui.SharePanelDialog
 * - com.ss.android.ugc.aweme.sharer.ui.BottomSheetSharePanel
 * - com.ss.android.ugc.aweme.sharefeed.dialog.sharepanelmodel.CommonSharePanelModel
 *
 * 功能入口:
 * 1. 无水印下载视频
 * 2. 下载音乐 MP3
 * 3. 下载图片
 * 4. 复制文案
 * 5. 设置
 */
class SharePanelHook : BaseHook {

    companion object {
        private const val TAG = "SharePanelHook"
        private const val BUTTON_TAG = "dou_plus_panel"
        private var installed = false

        /**
         * 图标映射（资源名 -> 功能）。当前为占位，待对照逗音小能手预览页确认后修正。
         * 对应图标列表见 IconPreviewActivity（桌面「Dou++ 图标预览」）。
         */
        private const val ICON_DOWNLOAD_VIDEO = "dyxs_03" // TODO 待映射
        private const val ICON_DOWNLOAD_MUSIC = "dyxs_16" // TODO 待映射
        private const val ICON_DOWNLOAD_IMAGE = "dyxs_22" // TODO 待映射
        private const val ICON_COPY_TEXT     = "dyxs_06" // TODO 待映射
        private const val ICON_SETTINGS      = "dyxs_10" // TODO 待映射

        /** 抖音分享面板相关类名特征 */
        private val SHARE_PANEL_CLASS_KEYWORDS = arrayOf(
            "SharePanelDialog",
            "BottomSheetSharePanel",
            "SharePanelFragment",
            "WithPadSharePanelDialog",
            "LandscapeSharePanelDialog",
            "QrCodeSharePanelDialog",
            "NewSharePanelDialog",
            "SideslipSharePanel",
            "ShareDialog"
        )

        /** 分享面板特征文本关键词 */
        private val SHARE_TEXT_KEYWORDS = arrayOf(
            "分享", "复制链接", "微信", "朋友圈", "QQ", "微博", "私信",
            "收藏", "举报", "不感兴趣", "保存本地", "二维码", "扫码"
        )
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            // 策略1: Hook Dialog.show() — 最可靠的方式
            val dialogClass = XposedHelpers.findClass("android.app.Dialog", classLoader)
            XposedBridge.hookAllMethods(dialogClass, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val dialog = param.thisObject as? Dialog ?: return
                        val dialogClassName = dialog.javaClass.name

                        // 快速类名匹配
                        val isSharePanel = SHARE_PANEL_CLASS_KEYWORDS.any {
                            dialogClassName.contains(it)
                        }

                        if (isSharePanel) {
                            HookUtils.log("$TAG: 检测到分享面板 Dialog: $dialogClassName")
                            injectIntoDialog(dialog)
                        } else {
                            // 非分享面板 Dialog，检查内容是否包含分享关键词
                            dialog.window?.decorView?.post {
                                try {
                                    val decorView = dialog.window?.decorView as? ViewGroup
                                    if (decorView != null && hasShareKeywords(decorView)) {
                                        HookUtils.log("$TAG: 通过文本检测到分享面板: $dialogClassName")
                                        injectIntoDialog(dialog)
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })

            // 策略2: Hook Dialog.setContentView — 某些 Dialog 可能在 show 前设置内容
            XposedBridge.hookAllMethods(dialogClass, "setContentView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val dialog = param.thisObject as? Dialog ?: return
                        val dialogClassName = dialog.javaClass.name

                        if (SHARE_PANEL_CLASS_KEYWORDS.any { dialogClassName.contains(it) }) {
                            // 延迟注入（等内容渲染完成）
                            dialog.window?.decorView?.post {
                                try {
                                    injectIntoDialog(dialog)
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })

            // 策略3: Hook Window.setCallback — 兜底方案，拦截 BottomSheet
            try {
                val windowClass = XposedHelpers.findClass("android.view.Window", classLoader)
                XposedBridge.hookAllMethods(windowClass, "setCallback", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 不在这里直接处理，仅作为日志辅助
                    }
                })
            } catch (_: Throwable) {}

            HookUtils.log("$TAG: Dialog.show/setContentView Hook 已安装")
            installed = true
        }
    }

    // 注: injectIntoDialog 的统一实现见文件下方（先定位可滚动内容区域，
    // 再在其父容器中插入按钮栏，使原有分享内容整体上移）。

    /**
     * 直接向容器注入按钮栏
     */
    private fun injectButtonBar(container: ViewGroup, context: Context) {
        try {
            if (container.findViewWithTag<View>(BUTTON_TAG) != null) return

            val buttonBar = createButtonBar(context)
            buttonBar.tag = BUTTON_TAG

            // 尝试添加到容器末尾
            container.addView(buttonBar)

            HookUtils.log("$TAG: 功能按钮已注入到: ${container.javaClass.name}")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: injectButtonBar 失败: ${t.message}")
        }
    }

    /**
     * 查找分享面板的内容容器
     * 优先查找 RecyclerView/LinearLayout 等列表容器
     */
    private fun findSharePanelContainer(root: ViewGroup): ViewGroup? {
        // 优先级1: 查找包含分享关键词的最近 ViewGroup
        val result = findContainerWithShareText(root, 0)
        if (result != null) return result

        // 优先级2: 查找 RecyclerView
        val rv = findViewByClass(root, "RecyclerView", 0)
        if (rv != null) {
            // 获取 RecyclerView 的父容器
            val parent = rv.parent as? ViewGroup
            if (parent != null) return parent
            return rv
        }

        // 优先级3: 查找 NestedScrollView 或 ScrollView
        val scroll = findViewByClass(root, "NestedScrollView", 0)
            ?: findViewByClass(root, "ScrollView", 0)
        if (scroll != null) {
            val parent = scroll.parent as? ViewGroup
            if (parent != null) return parent
            return scroll
        }

        return null
    }

    /**
     * 查找包含分享关键词的容器 ViewGroup
     */
    private fun findContainerWithShareText(view: ViewGroup, depth: Int): ViewGroup? {
        if (depth > 10) return null
        try {
            // 检查当前 ViewGroup 的直接子 View 中是否有分享关键词
            var keywordCount = 0
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val text = getViewText(child)
                if (text != null) {
                    for (kw in SHARE_TEXT_KEYWORDS) {
                        if (text.contains(kw)) {
                            keywordCount++
                            break
                        }
                    }
                }
            }

            // 如果有 2 个以上子 View 包含分享关键词，这很可能是分享面板容器
            if (keywordCount >= 2) return view

            // 递归搜索子 ViewGroup
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is ViewGroup) {
                    val result = findContainerWithShareText(child, depth + 1)
                    if (result != null) return result
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 获取 View 中的文本
     */
    private fun getViewText(view: View): String? {
        return try {
            if (view is TextView) {
                view.text?.toString()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 检查 View 树中是否包含分享关键词
     */
    private fun hasShareKeywords(view: ViewGroup): Boolean {
        val found = mutableListOf<String>()
        findKeywords(view, SHARE_TEXT_KEYWORDS, found, 0)
        return found.size >= 2
    }

    /**
     * 递归搜索关键词
     */
    private fun findKeywords(
        view: View,
        keywords: Array<String>,
        found: MutableList<String>,
        depth: Int
    ) {
        if (depth > 8 || found.size >= 4) return
        try {
            if (view is TextView) {
                val text = view.text?.toString() ?: return
                for (kw in keywords) {
                    if (text.contains(kw) && kw !in found) {
                        found.add(kw)
                    }
                }
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (found.size >= 4) return
                    findKeywords(view.getChildAt(i), keywords, found, depth + 1)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 按类名查找 View
     */
    private fun findViewByClass(view: ViewGroup, classNamePart: String, depth: Int): ViewGroup? {
        if (depth > 10) return null
        try {
            val className = view.javaClass.name
            if (className.contains(classNamePart)) return view

            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is ViewGroup) {
                    val result = findViewByClass(child, classNamePart, depth + 1)
                    if (result != null) return result
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 创建底部按钮栏 — 图标+文字标签网格样式
     *
     * 关键属性:
     * - 高度 WRAP_CONTENT，确保图标+文字标签完整显示不被裁切
     * - 宽度 MATCH_PARENT，居中显示
     * - 浅灰背景确保可见
     */
    private fun createButtonBar(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (8 * density).toInt(), (8 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt()
            )
            // 高度用 WRAP_CONTENT，保证图标和文字都能完整显示
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // 设置浅色背景确保在浅色分享面板上可见
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            tag = BUTTON_TAG
        }

        // 顶部分隔线
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (0.5f * density).toInt()
            )
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }
        bar.addView(divider)

        // 按钮行 — 水平排列，等宽分布
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
        }

        // 按钮配置: (背景色, 图标资源名, 图标颜色(兜底文字用), 文字标签, 点击动作)
        // 复制文案受 copy_text 开关控制，关闭时不注入该按钮
        val copyConfig = if (DouSettings.isCopyTextEnabled()) {
            arrayOf(Color.parseColor("#FFA502"), ICON_COPY_TEXT, Color.WHITE, "复制文案", Runnable { copyText(context) })
        } else null

        val buttonConfigs = listOfNotNull(
            arrayOf(Color.parseColor("#FF4757"), ICON_DOWNLOAD_VIDEO, Color.WHITE, "下载视频", Runnable { downloadVideo(context) }),
            arrayOf(Color.parseColor("#2ED573"), ICON_DOWNLOAD_MUSIC, Color.WHITE, "下载音乐", Runnable { downloadMusic(context) }),
            arrayOf(Color.parseColor("#1E90FF"), ICON_DOWNLOAD_IMAGE, Color.WHITE, "下载图片", Runnable { downloadImages(context) }),
            copyConfig,
            arrayOf(Color.parseColor("#747D8C"), ICON_SETTINGS, Color.WHITE, "模块设置", Runnable { openSettings(context) })
        )

        for (config in buttonConfigs) {
            val btn = createIconButton(
                context,
                config[0] as Int,      // bgColor
                config[1] as String,   // icon
                config[2] as Int,      // iconColor
                config[3] as String    // label
            )
            btn.setOnClickListener { (config[4] as Runnable).run() }
            buttonRow.addView(btn)
        }

        bar.addView(buttonRow)
        return bar
    }

    /**
     * 创建单个图标+文字按钮
     * 美观设计: 圆形彩色背景 + 逗音小能手图标(drawable) + 文字标签
     */
    private fun createIconButton(context: Context, bgColor: Int, iconName: String, iconColor: Int, label: String): LinearLayout {
        val density = context.resources.displayMetrics.density
        val btnSize = (40 * density).toInt()  // 按钮直径

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            // 图标容器 - 圆形彩色背景
            val iconContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.CENTER
                }

                // 圆形背景
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgColor)
                }

                // 逗音小能手图标（模块自带 drawable）
                val iconView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val d = IconRes.getDrawable(context, iconName)
                    if (d != null) {
                        setImageDrawable(d)
                    } else {
                        // 兜底: 文字首字
                        setImageDrawable(null)
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        gravity = Gravity.CENTER
                        val pad = (8 * density).toInt()
                        setMargins(pad, pad, pad, pad)
                    }
                }
                addView(iconView)
            }
            addView(iconContainer)

            // 文字标签
            val labelView = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#333333"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            addView(labelView)
        }
    }

    // ==================== 功能实现 ====================

    private fun downloadVideo(context: Context) {
        try {
            DouSettings.reload()
            val aweme = MediaCache.getCurrentAweme()
            if (aweme == null) {
                HookUtils.showToast(context, "未检测到视频，请先滑动到视频页面")
                return
            }

            val videoUrl = MediaCache.getVideoUrlFromAweme(aweme)
            if (videoUrl.isNullOrEmpty()) {
                HookUtils.showToast(context, "无法获取视频地址")
                return
            }

            val noWatermarkUrl = UrlParser.getNoWatermarkUrl(videoUrl)
            val awemeId = getAwemeId(aweme)
            val fileName = "douyin_${awemeId ?: System.currentTimeMillis()}.mp4"

            HookUtils.showToast(context, "开始下载视频...")
            HookUtils.log("$TAG: 下载视频: $noWatermarkUrl")

            MediaDownloader.download(context, noWatermarkUrl, fileName,
                onComplete = { uri ->
                    HookUtils.showToast(context, "视频已保存到相册 ✓")
                },
                onError = { t ->
                    HookUtils.showToast(context, "下载失败: ${t.message}")
                }
            )
        } catch (t: Throwable) {
            HookUtils.showToast(context, "下载异常: ${t.message}")
        }
    }

    private fun downloadMusic(context: Context) {
        try {
            DouSettings.reload()
            val aweme = MediaCache.getCurrentAweme()
            if (aweme == null) {
                HookUtils.showToast(context, "未检测到视频，请先滑动到视频页面")
                return
            }

            val musicUrl = MediaCache.getMusicUrlFromAweme(aweme)
            if (musicUrl.isNullOrEmpty()) {
                HookUtils.showToast(context, "无法获取音乐地址")
                return
            }

            val musicTitle = MediaCache.getMusicTitleFromAweme(aweme) ?: "music"
            val safeTitle = musicTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
            val fileName = "${safeTitle}_${System.currentTimeMillis()}.mp3"

            HookUtils.showToast(context, "开始下载音乐...")
            HookUtils.log("$TAG: 下载音乐: $musicUrl")

            MediaDownloader.download(context, musicUrl, fileName,
                onComplete = { uri ->
                    HookUtils.showToast(context, "音乐已保存 ✓")
                },
                onError = { t ->
                    HookUtils.showToast(context, "下载失败: ${t.message}")
                }
            )
        } catch (t: Throwable) {
            HookUtils.showToast(context, "下载异常: ${t.message}")
        }
    }

    private fun downloadImages(context: Context) {
        try {
            DouSettings.reload()
            val aweme = MediaCache.getCurrentAweme()
            if (aweme == null) {
                HookUtils.showToast(context, "未检测到内容，请先滑动到视频页面")
                return
            }

            val imageUrls = MediaCache.getImageUrlsFromAweme(aweme)
            if (imageUrls.isEmpty()) {
                HookUtils.showToast(context, "当前内容不是图集")
                return
            }

            HookUtils.showToast(context, "开始下载 ${imageUrls.size} 张图片...")

            val awemeId = getAwemeId(aweme)
            for ((index, url) in imageUrls.withIndex()) {
                val noWatermarkUrl = UrlParser.removeImageWatermark(url)
                val fileName = "douyin_${awemeId ?: "img"}_${index + 1}.jpg"

                MediaDownloader.download(context, noWatermarkUrl, fileName,
                    onComplete = { uri ->
                        if (index == imageUrls.lastIndex) {
                            HookUtils.showToast(context, "全部 ${imageUrls.size} 张图片已保存 ✓")
                        }
                    },
                    onError = { t ->
                        HookUtils.log("$TAG: 图片 ${index + 1} 下载失败: ${t.message}")
                    }
                )
            }
        } catch (t: Throwable) {
            HookUtils.showToast(context, "下载异常: ${t.message}")
        }
    }

    private fun copyText(context: Context) {
        try {
            val aweme = MediaCache.getCurrentAweme()
            if (aweme == null) {
                HookUtils.showToast(context, "未检测到内容")
                return
            }

            val desc = MediaCache.getAwemeDesc(aweme)
            if (desc.isNullOrEmpty()) {
                HookUtils.showToast(context, "无文案可复制")
                return
            }

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Dou+", desc))
            HookUtils.showToast(context, "文案已复制到剪贴板 ✓")
        } catch (t: Throwable) {
            HookUtils.showToast(context, "复制失败: ${t.message}")
        }
    }

    private fun openSettings(context: Context) {
        try {
            val intent = android.content.Intent().apply {
                component = android.content.ComponentName(
                    "com.xposed.doupp",
                    "com.xposed.doupp.ui.SettingsActivity"
                )
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            HookUtils.showToast(context, "请在 LSPosed 管理器中打开 Dou+ 设置")
        }
    }

    private fun openTelegram(context: Context) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://t.me/+C12HJcbXDgw3OGRl")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            HookUtils.showToast(context, "无法打开电报群组")
        }
    }

    private fun getAwemeId(aweme: Any): String? {
        return try {
            val method = aweme.javaClass.declaredMethods
                .filter { it.parameterCount == 0 && it.returnType == String::class.java }
                .firstOrNull {
                    val name = it.name.lowercase()
                    name == "getawemeid" || name == "awemeid" || name == "getitemid"
                }
            method?.invoke(aweme) as? String
        } catch (_: Throwable) { null }
    }

    /**
     * 查找子 View 在父容器中的索引
     */
    private fun findChildIndex(parent: ViewGroup, target: View): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i) == target) return i
        }
        return -1
    }

    /**
     * 向 Dialog 中注入功能按钮
     * 
     * 策略: 找到分享面板内部的可滚动内容区域，
     * 在其父容器中内容区域之后插入按钮栏，让原有内容自然往上推。
     */
    private fun injectIntoDialog(dialog: Dialog) {
        try {
            val window = dialog.window ?: return
            val decorView = window.decorView as? ViewGroup ?: return

            // 检查是否已注入
            if (decorView.findViewWithTag<View>(BUTTON_TAG) != null) return

            val context = dialog.context ?: return

            // 延迟注入，确保分享面板内容已完全渲染
            decorView.post {
                try {
                    // 再次检查是否已注入
                    if (decorView.findViewWithTag<View>(BUTTON_TAG) != null) return@post

                    // 找到内容布局 (android.R.id.content)
                    val contentView = window.findViewById<ViewGroup>(android.R.id.content)
                    if (contentView == null || contentView.childCount == 0) {
                        HookUtils.log("$TAG: 内容布局为空，注入到 decorView")
                        injectButtonBar(decorView, context)
                        return@post
                    }

                    // 获取分享面板的根布局
                    val rootView = contentView.getChildAt(0) as? ViewGroup
                    if (rootView == null) {
                        HookUtils.log("$TAG: 根布局为空，注入到 contentView")
                        injectButtonBar(contentView, context)
                        return@post
                    }

                    HookUtils.log("$TAG: 根布局: ${rootView.javaClass.name}, childCount=${rootView.childCount}")

                    // 在根布局中查找可滚动内容区域（RecyclerView/ScrollView）
                    val scrollable = findScrollableInternal(rootView, 0)
                    if (scrollable != null) {
                        val parent = scrollable.parent as? ViewGroup
                        if (parent != null) {
                            // 在内容区域的父容器中，内容区域之后插入
                            val index = findChildIndex(parent, scrollable)
                            if (index >= 0) {
                                val buttonBar = createButtonBar(context)
                                buttonBar.tag = BUTTON_TAG
                                parent.addView(buttonBar, index + 1)
                                HookUtils.log("$TAG: 在 ${parent.javaClass.name} 索引 ${index + 1} 注入，原有内容往上推")
                                return@post
                            }
                        }
                    }

                    // 兜底：注入到根布局末尾
                    injectButtonBar(rootView, context)
                } catch (t: Throwable) {
                    HookUtils.log("$TAG: 延迟注入失败: ${t.message}")
                    try {
                        injectButtonBar(decorView, context)
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: injectIntoDialog 失败: ${t.message}")
        }
    }

    private fun findScrollableInternal(view: View, depth: Int): View? {
        if (depth > 8) return null
        val className = view.javaClass.name
        if (className.contains("RecyclerView") ||
            className.contains("ScrollView") ||
            className.contains("NestedScroll") ||
            className.contains("ListView")) {
            return view
        }
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val result = findScrollableInternal(view.getChildAt(i), depth + 1)
            if (result != null) return result
        }
        return null
    }
}
