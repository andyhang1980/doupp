package com.xposed.doupp.hook

import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.UrlParser
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge

/**
 * 下载流程 Hook — 精准拦截
 *
 * 策略:
 * 1. 拦截含 playwm 的 URL → 直接替换（旧版抖音）
 * 2. 拦截抖音视频 CDN 域名的 URL → 交给 UrlParser 处理（新版抖音 40.x+）
 * 3. 拦截 aweme API 播放接口 URL → 处理 watermark 参数
 *
 * 自动适配策略:
 * 优先使用 AdaptationManager 缓存的类名，找不到时回退到硬编码候选列表
 */
class DownloadHook : BaseHook {

    companion object {
        private const val TAG = "DownloadHook"
        private var installed = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        hookCronetBuilder(classLoader)
        hookOkHttp(classLoader)
        installed = true
    }

    /**
     * Hook Cronet Builder 的 setURL 相关方法
     * 自动适配: 优先读取 AdaptationManager 缓存，找不到时回退硬编码候选列表
     */
    private fun hookCronetBuilder(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 1. 优先使用 AdaptationManager 缓存的类名
            val cachedClasses = AdaptationManager.getAdaptedClasses("cronet")
                .filter { it.contains("Builder") }

            // 2. 回退到硬编码候选列表
            val fallbackClasses = listOf(
                "com.ttnet.org.chromium.net.impl.CronetUrlRequest\$Builder",
                "com.ttnet.org.chromium.net.UrlRequest\$Builder",
                "org.chromium.net.UrlRequest\$Builder"
            )

            val allCandidates = (cachedClasses + fallbackClasses).distinct()

            for (className in allCandidates) {
                try {
                    val builderClass = Class.forName(className, false, classLoader)

                    val urlMethods = builderClass.declaredMethods.filter { m ->
                        m.parameterTypes.any { it == String::class.java } &&
                        (m.name.lowercase().contains("url") ||
                         m.name.lowercase().contains("set"))
                    }

                    for (method in urlMethods) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    for (i in param.args.indices) {
                                        val arg = param.args[i]
                                        if (arg is String && isVideoDownloadUrl(arg)) {
                                            val cleaned = UrlParser.getNoWatermarkUrl(arg)
                                            if (cleaned != arg) {
                                                param.args[i] = cleaned
                                                HookUtils.log("$TAG: Cronet ${method.name} URL 已替换")
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                        })
                    }

                    if (urlMethods.isNotEmpty()) {
                        HookUtils.log("$TAG: Hook Cronet Builder $className (${urlMethods.size} 方法)")
                    }
                    break
                } catch (_: ClassNotFoundException) {}
            }
        }
    }

    /**
     * Hook OkHttp proceed — 只在 URL 含 playwm 时处理
     * 自动适配: 优先读取 AdaptationManager 缓存，找不到时回退硬编码候选列表
     */
    private fun hookOkHttp(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 1. 优先使用 AdaptationManager 缓存的类名
            val cachedClasses = AdaptationManager.getAdaptedClasses("okhttp")

            // 2. 回退到硬编码候选列表
            val fallbackClasses = listOf(
                "okhttp3.internal.http.RealInterceptorChain",
                "okhttp3.RealCall",
                "com.bytedance.retrofit2.client.OkHttpCall"
            )

            val allCandidates = (cachedClasses + fallbackClasses).distinct()

            for (className in allCandidates) {
                try {
                    val clazz = Class.forName(className, false, classLoader)

                    val proceedMethod = try {
                        clazz.getDeclaredMethod("proceed",
                            Class.forName("okhttp3.Request", false, classLoader))
                    } catch (_: Throwable) { null }

                    if (proceedMethod != null) {
                        XposedBridge.hookMethod(proceedMethod, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val request = param.args[0] ?: return
                                    val urlStr = getUrlFromRequest(request)
                                    if (urlStr == null || !isVideoDownloadUrl(urlStr)) return

                                    replaceUrlInOkHttpRequest(request, classLoader)
                                } catch (_: Throwable) {}
                            }
                        })
                        HookUtils.log("$TAG: Hook OkHttp ${className}.proceed()")
                        break
                    }
                } catch (_: ClassNotFoundException) {}
            }
        }
    }

    /**
     * 从 OkHttp Request 中获取 URL 字符串
     */
    private fun getUrlFromRequest(request: Any): String? {
        return try {
            val urlMethod = request::class.java.getMethod("url")
            urlMethod.invoke(request)?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 替换 OkHttp Request 中的 URL
     */
    private fun replaceUrlInOkHttpRequest(request: Any, classLoader: ClassLoader) {
        try {
            val requestClass = request::class.java
            val urlMethod = requestClass.getMethod("url")
            val httpUrl = urlMethod.invoke(request) ?: return
            val urlStr = httpUrl.toString()

            if (!isVideoDownloadUrl(urlStr)) return

            val newUrlStr = UrlParser.getNoWatermarkUrl(urlStr)
            if (newUrlStr == urlStr) return

            val httpUrlClass = httpUrl::class.java
            val parseMethod = try {
                httpUrlClass.getMethod("get", String::class.java)
            } catch (_: NoSuchMethodException) {
                httpUrlClass.getMethod("parse", String::class.java)
            }
            val newHttpUrl = parseMethod.invoke(null, newUrlStr)

            val urlField = try { requestClass.getDeclaredField("url") } catch (_: Throwable) { null }
            if (urlField != null) {
                urlField.isAccessible = true
                urlField.set(request, newHttpUrl)
                HookUtils.log("$TAG: OkHttp URL 已替换")
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 替换 OkHttp URL 失败: ${t.message}")
        }
    }

    /**
     * 判断 URL 是否为需要处理的视频下载 URL
     *
     * 覆盖旧版和新版抖音的视频 URL 模式:
     * - playwm (旧版水印 URL)
     * - watermark=1 (新版抖音参数水印)
     * - aweme/v1/play (播放接口)
     * - douyinvod.com / tos-cn-v / amemv.com (视频 CDN)
     */
    private fun isVideoDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        // 旧版: playwm 路径
        if (lower.contains("playwm")) return true
        // 新版: watermark 参数
        if (lower.contains("watermark=1")) return true
        // 播放接口
        if (lower.contains("amemv.com/aweme/v1/play")) return true
        // 视频 CDN 域名
        if (lower.contains("douyinvod.com") && lower.contains("/play")) return true
        if (lower.contains("tos-cn-v") && lower.contains("/play")) return true
        if (lower.contains("bytevcloudcdn.com") && lower.contains("/play")) return true
        return false
    }
}
