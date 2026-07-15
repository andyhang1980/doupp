package com.xposed.doupp.hook

import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.UrlParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 下载流程 Hook — 精准拦截
 *
 * 原则: 只 Hook 设置 URL 的方法，不拦截普通网络请求
 * 只在检测到 playwm 时替换，否则直接放行
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
                                        if (arg is String && arg.contains("playwm")) {
                                            param.args[i] = UrlParser.getNoWatermarkUrl(arg)
                                            HookUtils.log("$TAG: Cronet ${method.name} URL 已替换")
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
                                    val request = param.args[0]
                                    val urlStr = getUrlFromRequest(request)
                                    if (urlStr == null || !urlStr.contains("playwm")) return

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

            if (!urlStr.contains("playwm")) return

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
}
