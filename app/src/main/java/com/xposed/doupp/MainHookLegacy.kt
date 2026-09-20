package com.xposed.doupp

import android.os.Build
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.hook.AdHook
import com.xposed.doupp.hook.AntiRevokeHook
import com.xposed.doupp.hook.AutoPlayButtonHook
import com.xposed.doupp.hook.AutoPlayControllerHook
import com.xposed.doupp.hook.AutoSignInHook
import com.xposed.doupp.hook.BookmarkHook
import com.xposed.doupp.hook.CommentHook
import com.xposed.doupp.hook.DoubleClickHook
import com.xposed.doupp.hook.DownloadDialogHook
import com.xposed.doupp.hook.DownloadHook
import com.xposed.doupp.hook.FeedHook
import com.xposed.doupp.hook.HotUpdateHook
import com.xposed.doupp.hook.ImmersivePlayHook
import com.xposed.doupp.hook.LuckyBagHook
import com.xposed.doupp.hook.LivePhotoHook
import com.xposed.doupp.hook.RegionUnlockHook
import com.xposed.doupp.hook.ShareHook
import com.xposed.doupp.hook.SharePanelHook
import com.xposed.doupp.hook.VideoFilterHook
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.ContextHelper
import com.xposed.doupp.util.DexKitManager
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Dou+ - 传统 Xposed API 82 入口（FPA/太极/LSPatch 等免root框架用）
 *
 * 与 LibXposed 入口 [MainHook] 完全等价：共用同一套 compat 层、Hook 列表与安装流程。
 * assets/xposed_init 声明本类为传统框架的加载入口。
 */
class MainHookLegacy : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        if (packageName !in MainHook.SUPPORTED_PACKAGES) return

        // 切换到传统 Xposed API 后端
        XposedBridge.attachLegacy()

        // 只在主进程安装 Hook
        val processName = lpparam.processName
        if (processName != packageName && !processName.startsWith("$packageName:main")) {
            HookUtils.log("非主进程，跳过: $processName")
            return
        }

        HookUtils.log("========== Dou+ v${MainHook.MODULE_VERSION} (传统API/FPA) ==========")
        HookUtils.log("包名: $packageName, 进程: $processName")

        try {
            // 初始化 DexKit：传入宿主 apk 路径
            val sourceDir = lpparam.appInfo?.sourceDir
            if (sourceDir != null) {
                DexKitManager.init(sourceDir)
            }

            // 初始化 DouSettings — 跨进程读取配置（传统 API 无 getRemotePreferences，走本地/文件兜底）
            DouSettings.initForHookProcess()
            HookUtils.log("DouSettings 初始化完成")

            // 传统 API 的 classLoader 在 handleLoadPackage 时已可用
            installHooks(lpparam.classLoader)
        } catch (t: Throwable) {
            HookUtils.log("模块加载失败: ${t.message}")
            HookUtils.log("堆栈: ${t.stackTraceToString().take(300)}")
        }
    }

    @Volatile
    private var hookInitialized = false

    private fun installHooks(classLoader: ClassLoader) {
        if (hookInitialized) return
        synchronized(this) {
            if (hookInitialized) return
            hookInitialized = true

            HookUtils.log("使用 ClassLoader: ${classLoader.javaClass.name}")

            // 初始化 ContextHelper — 会 Hook Application.attach/onCreate
            ContextHelper.init(classLoader)

            // 策略1: 尝试立即安装
            val hooks = createHooks()
            tryInstallHooks(hooks, classLoader, "立即安装")

            // 策略2: 注册延迟安装回调
            ContextHelper.onApplicationReady { realClassLoader, context ->
                HookUtils.log("========== 延迟安装 Hook ==========")

                DouSettings.setLocalContext(context)
                DouSettings.reload()

                val delayedHooks = createHooks()
                tryInstallHooks(delayedHooks, realClassLoader, "延迟安装")

                // 尝试启动 KeepAliveService（免root框架下无模块进程，失败无害）
                try {
                    KeepAliveService.startFromHook(context)
                } catch (_: Throwable) {}

                // 启动自动适配
                startAdaptationWithToast(realClassLoader)
            }

            HookUtils.log("==============================================")
        }
    }

    private fun startAdaptationWithToast(classLoader: ClassLoader) {
        val douyinVersion = getAppVersion()
        if (douyinVersion == null) {
            HookUtils.log("${MainHook.LOG_TAG}: 无法获取应用版本，跳过适配")
            return
        }
        AdaptationManager.startAdaptation(classLoader, douyinVersion) { results ->
            val successCount = results.count { it.success }
            val totalCount = results.size
            HookUtils.log("${MainHook.LOG_TAG}: 适配完成 [$successCount/$totalCount]")
            for (result in results) {
                HookUtils.log("${MainHook.LOG_TAG}: ${result.feature} - ${result.message}")
            }
        }
    }

    private fun getAppVersion(): String? {
        return try {
            val context = ContextHelper.getContext() ?: return null
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            "${info.versionName}_${info.versionCode}"
        } catch (_: Throwable) {
            null
        }
    }

    private fun createHooks(): List<com.xposed.doupp.hook.BaseHook> {
        return listOf(
            FeedHook(),
            SharePanelHook(),
            AdHook(),
            HotUpdateHook(),
            ShareHook(),
            DownloadHook(),
            CommentHook(),
            LivePhotoHook(),
            DownloadDialogHook(),
            AutoPlayControllerHook(),
            AutoPlayButtonHook(),
            VideoFilterHook(),
            DoubleClickHook(),
            ImmersivePlayHook(),
            BookmarkHook(),
            RegionUnlockHook(),
            AutoSignInHook(),
            AntiRevokeHook(),
            LuckyBagHook(),
        )
    }

    private fun tryInstallHooks(
        hooks: List<com.xposed.doupp.hook.BaseHook>,
        classLoader: ClassLoader,
        phase: String
    ) {
        var successCount = 0
        var failCount = 0

        for (hook in hooks) {
            try {
                if (hook.isInstalled()) {
                    HookUtils.log("[$phase] ${hook::class.simpleName} 已安装，跳过")
                    continue
                }
                hook.init(classLoader)
                successCount++
                HookUtils.log("[$phase] ${hook::class.simpleName} 安装成功")
            } catch (t: Throwable) {
                failCount++
                HookUtils.log("[$phase] ${hook::class.simpleName} 安装失败: ${t.message}")
            }
        }

        HookUtils.log("[$phase] 完成: 成功=$successCount, 失败=$failCount")
    }
}
