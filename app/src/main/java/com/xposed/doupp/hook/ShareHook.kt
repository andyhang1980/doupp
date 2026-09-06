package com.xposed.doupp.hook

import com.xposed.doupp.util.ContextHelper
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaDownloader
import com.xposed.doupp.util.UrlParser
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.compat.XposedHelpers

/**
 * 分享拦截 Hook
 *
 * 只拦截真正的分享 Intent (ACTION_SEND / ACTION_SEND_MULTIPLE)
 * 不拦截抖音内部导航（打开评论、个人页等），避免误触发下载
 */
class ShareHook : BaseHook {

    companion object {
        private const val TAG = "ShareHook"
        private var installed = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        hookShareIntent(classLoader)
        installed = true
    }

    /**
     * Hook 分享 Intent
     * 拦截抖音发出的分享 Intent，提取其中的媒体 URL
     */
    private fun hookShareIntent(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // Hook Activity.startActivity(Intent)
            val activityClass = XposedHelpers.findClass(
                "android.app.Activity",
                classLoader
            )

            XposedBridge.hookAllMethods(activityClass, "startActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val intent = param.args.firstOrNull() as? android.content.Intent ?: return
                        processShareIntent(intent, classLoader)
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: 处理分享Intent失败: ${t.message}")
                    }
                }
            })

            // 同时 Hook startActivityForResult
            XposedBridge.hookAllMethods(activityClass, "startActivityForResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val intent = param.args.filterIsInstance<android.content.Intent>().firstOrNull() ?: return
                        processShareIntent(intent, classLoader)
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: 处理startActivityForResult失败: ${t.message}")
                    }
                }
            })

            HookUtils.log("$TAG: 分享Intent Hook 已安装 (仅 ACTION_SEND)")
        }
    }

    /**
     * 处理分享 Intent
     * 只处理 ACTION_SEND / ACTION_SEND_MULTIPLE，跳过内部导航
     */
    private fun processShareIntent(intent: android.content.Intent, classLoader: ClassLoader) {
        val action = intent.action ?: return
        if (action != android.content.Intent.ACTION_SEND && action != android.content.Intent.ACTION_SEND_MULTIPLE) {
            return
        }

        val extras = intent.extras ?: return

        // 遍历 extras 查找 URL
        for (key in extras.keySet()) {
            val value = extras.get(key) ?: continue

            when (value) {
                is String -> {
                    if (UrlParser.isVideoUrl(value) || UrlParser.isImageUrl(value)) {
                        val noWmUrl = UrlParser.getNoWatermarkUrl(value)
                        HookUtils.log("$TAG: 发现分享URL [$key]")
                        downloadMedia(noWmUrl, classLoader)
                    }
                }
                is CharSequence -> {
                    val str = value.toString()
                    if (UrlParser.isVideoUrl(str) || UrlParser.isImageUrl(str)) {
                        val noWmUrl = UrlParser.getNoWatermarkUrl(str)
                        HookUtils.log("$TAG: 发现分享文本URL")
                        downloadMedia(noWmUrl, classLoader)
                    }
                }
                is android.os.Bundle -> {
                    processBundle(value, classLoader)
                }
            }
        }
    }

    /**
     * 递归处理 Bundle 中的 URL
     */
    private fun processBundle(bundle: android.os.Bundle, classLoader: ClassLoader) {
        for (key in bundle.keySet()) {
            val value = bundle.get(key) ?: continue
            if (value is String && (UrlParser.isVideoUrl(value) || UrlParser.isImageUrl(value))) {
                val noWmUrl = UrlParser.getNoWatermarkUrl(value)
                HookUtils.log("$TAG: Bundle中发现URL [$key]")
                downloadMedia(noWmUrl, classLoader)
            }
        }
    }

    /**
     * 下载媒体文件
     */
    private fun downloadMedia(url: String, classLoader: ClassLoader) {
        try {
            val context = ContextHelper.getContext()
            if (context != null) {
                val isVideo = UrlParser.isVideoUrl(url)
                val ext = if (isVideo) "mp4" else "jpg"
                MediaDownloader.download(context, url, "douyin_share_${System.currentTimeMillis()}.$ext")
                HookUtils.showToast(context, "正在保存无水印${if (isVideo) "视频" else "图片"}...")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 下载失败: ${t.message}")
        }
    }
}
