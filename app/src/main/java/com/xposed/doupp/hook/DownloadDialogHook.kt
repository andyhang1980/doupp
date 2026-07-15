package com.xposed.doupp.hook

import com.xposed.doupp.util.ContextHelper
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache
import com.xposed.doupp.util.UrlParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 下载弹窗 Hook — 最小化策略
 *
 * 原则: 不 Hook View.performClick（全局拦截每个点击事件，且遍历 View 树提取文本，开销极大）
 *
 * 保留的 Hook:
 * - Activity.onContextItemSelected — 只在长按菜单弹出后选择时触发
 * - Dialog.onClick — 只在 Dialog 按钮点击时触发
 * 两者都是用户主动操作，调用频率极低
 *
 * 实况照片保存改为从 MediaCache 缓存的 Aweme 对象按需提取
 */
class DownloadDialogHook : BaseHook {

    companion object {
        private const val TAG = "DownloadDialogHook"
        private var installed = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        hookContextMenu(classLoader)
        hookDialogClicks(classLoader)
        installed = true
    }

    /**
     * Hook Activity.onContextItemSelected
     * 拦截长按菜单中的"保存"/"下载"选项
     * 只在长按菜单选择时触发，频率极低
     */
    private fun hookContextMenu(classLoader: ClassLoader) {
        HookUtils.safeHook {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)

            XposedBridge.hookAllMethods(activityClass, "onContextItemSelected", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val menuItem = param.args.firstOrNull() ?: return
                        val title = HookUtils.callMethod(menuItem, "getTitle")?.toString() ?: ""

                        if (title.contains("保存") || title.contains("下载") ||
                            title.contains("save", ignoreCase = true) ||
                            title.contains("download", ignoreCase = true)) {

                            if (tryHandleLivePhotoSave()) {
                                param.result = true
                                return
                            }

                            replaceUrlInArgs(param)
                        }
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: 处理上下文菜单失败: ${t.message}")
                    }
                }
            })

            HookUtils.log("$TAG: ContextMenu Hook 已安装")
        }
    }

    /**
     * Hook Dialog.onClick
     * 只拦截 Dialog 按钮点击，不 Hook 全局 View.performClick
     */
    private fun hookDialogClicks(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 只 Hook Dialog 子类的 onClick — 精确拦截弹窗按钮
            val dialogClasses = listOf(
                "com.google.android.material.bottomsheet.BottomSheetDialog",
                "android.app.Dialog",
                "androidx.appcompat.app.AlertDialog"
            )

            for (className in dialogClasses) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    XposedBridge.hookAllMethods(clazz, "onClick", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (tryHandleLivePhotoSave()) {
                                    param.result = null
                                    return
                                }
                                replaceUrlInArgs(param)
                            } catch (_: Throwable) {}
                        }
                    })
                    HookUtils.log("$TAG: $className onClick Hook 已安装")
                } catch (_: ClassNotFoundException) {}
            }

            // 不再 Hook View.performClick — 全局拦截每个点击事件且遍历 View 树，开销极大
            // 原实现的问题:
            //   - hookAllMethods(View, "performClick") 拦截所有 View 的点击事件
            //   - 每次点击都调用 extractViewText() 递归遍历 View 树
            //   - 抖音 Feed 流中每次滑动、点赞、评论都会触发大量 performClick
            //   - 导致明显卡顿

            HookUtils.log("$TAG: Dialog.onClick Hook 已安装（已移除全局 performClick Hook）")
        }
    }

    /**
     * 尝试处理实况照片保存 — 从缓存的 Aweme 按需提取
     */
    private fun tryHandleLivePhotoSave(): Boolean {
        val context = ContextHelper.getContext() ?: return false

        // 先检查是否有已缓存的实况照片
        val allCached = MediaCache.getAllCachedLivePhotos()
        if (allCached.isNotEmpty()) {
            val latest = allCached.maxByOrNull { it.key } ?: return false
            val (imageUrl, videoUrl) = latest.value

            HookUtils.log("$TAG: 检测到实况照片缓存，直接保存")
            HookUtils.showToast(context, "正在保存实况照片...")

            MediaCache.downloadLivePhoto(context, imageUrl, videoUrl)
            MediaCache.removeLivePhoto(latest.key)
            return true
        }

        // 从当前缓存的 Aweme 对象按需提取实况照片
        val aweme = MediaCache.getCurrentAweme() ?: return false
        val livePhoto = MediaCache.extractLivePhotoFromAweme(aweme) ?: return false

        HookUtils.log("$TAG: 从 Aweme 缓存提取到实况照片")
        HookUtils.showToast(context, "正在保存实况照片...")
        MediaCache.downloadLivePhoto(context, livePhoto.first, livePhoto.second)
        return true
    }

    /**
     * 替换方法参数中的 URL
     */
    private fun replaceUrlInArgs(param: XC_MethodHook.MethodHookParam) {
        for (i in param.args.indices) {
            val arg = param.args[i]
            if (arg is String && arg.contains("playwm")) {
                val newUrl = UrlParser.getNoWatermarkUrl(arg)
                if (newUrl != arg) {
                    param.args[i] = newUrl
                    HookUtils.log("$TAG: 替换参数[$i] URL")
                }
            }
        }
    }
}
