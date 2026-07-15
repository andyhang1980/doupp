package com.xposed.doupp

import com.xposed.doupp.hook.AdHook
import com.xposed.doupp.hook.AutoPlayButtonHook
import com.xposed.doupp.hook.AutoPlayControllerHook
import com.xposed.doupp.hook.CommentHook
import com.xposed.doupp.hook.CleanModeHook
import com.xposed.doupp.hook.DoubleClickHook
import com.xposed.doupp.hook.DownloadDialogHook
import com.xposed.doupp.hook.DownloadHook
import com.xposed.doupp.hook.FeedHook
import com.xposed.doupp.hook.HotUpdateHook
import com.xposed.doupp.hook.LivePhotoHook
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
 * Dou+ - Xposed 模块入口
 *
 * 作用域: com.ss.android.ugc.aweme (抖音)
 * 功能:
 * - 分享面板底部注入: 无水印下载视频、下载音乐MP3、下载图片、复制文案、设置
 * - 开屏广告跳过
 * - 自动播放下一个视频
 * - 自动保存视频/图集/实况照片
 * - 评论区媒体保存
 *
 * 自动适配:
 * - 检测抖音版本变化，动态扫描关键类名
 * - 适配过程中显示 Toast 提示
 * - 适配结果缓存到文件，下次启动直接读取
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val LOG_TAG = "Dou+"
        const val TARGET_PACKAGE = "com.ss.android.ugc.aweme"
        const val MODULE_VERSION = "3.4.41"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        // 只在主进程安装 Hook（避免子进程重复安装）
        if (lpparam.processName != TARGET_PACKAGE &&
            !lpparam.processName.startsWith("${TARGET_PACKAGE}:main")) {
            HookUtils.log("非主进程，跳过: ${lpparam.processName}")
            return
        }

        HookUtils.log("========== Dou+ v$MODULE_VERSION ==========")
        HookUtils.log("模块加载中... 包名: ${lpparam.packageName}")
        HookUtils.log("进程名: ${lpparam.processName}")

        logLSPosedVersion()
        logDouyinVersion(lpparam)

        try {
            // 初始化 DexKit：传入宿主 apk 路径，供各 Hook 运行时定位混淆类/方法
            DexKitManager.init(lpparam.appInfo.sourceDir)

            // 初始化 DouSettings — 跨进程读取配置
            DouSettings.initForHookProcess()
            HookUtils.log("DouSettings 初始化完成")

            // 初始化 ContextHelper — 会 Hook Application.attach/onCreate
            ContextHelper.init(lpparam)

            // 策略1: 尝试立即安装（只安装不依赖 Application 的 Hook）
            val hooks = createHooks()
            tryInstallHooks(hooks, lpparam.classLoader, "立即安装")

            // 策略2: 注册延迟安装回调
            ContextHelper.onApplicationReady { realClassLoader, _ ->
                HookUtils.log("========== 延迟安装 Hook ==========")

                // 重新加载配置
                DouSettings.reload()

                val delayedHooks = createHooks()
                tryInstallHooks(delayedHooks, realClassLoader, "延迟安装")
            }

            HookUtils.log("==============================================")
        } catch (t: Throwable) {
            HookUtils.log("模块加载失败: ${t.message}")
            HookUtils.log("堆栈: ${t.stackTraceToString().take(300)}")
        }
    }

    /**
     * 启动自动适配流程，显示 Toast 提示用户
     */
    private fun startAdaptationWithToast(classLoader: ClassLoader) {
        val douyinVersion = getDouyinVersion()
        if (douyinVersion == null) {
            HookUtils.log("$LOG_TAG: 无法获取抖音版本，跳过适配")
            return
        }

        AdaptationManager.startAdaptation(classLoader, douyinVersion) { results ->
            val successCount = results.count { it.success }
            val totalCount = results.size
            HookUtils.log("$LOG_TAG: 适配完成 [$successCount/$totalCount]")
            for (result in results) {
                HookUtils.log("$LOG_TAG: ${result.feature} - ${result.message}")
            }
        }
    }

    /**
     * 获取抖音版本号
     */
    private fun getDouyinVersion(): String? {
        return try {
            val context = ContextHelper.getContext()
            if (context != null) {
                val pm = context.packageManager
                val info = pm.getPackageInfo(TARGET_PACKAGE, 0)
                "${info.versionName}_${info.versionCode}"
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 创建所有 Hook 实例
     */
    private fun createHooks(): List<com.xposed.doupp.hook.BaseHook> {
        return listOf(
            FeedHook(),           // Feed 流视频 Hook + Aweme 缓存
            SharePanelHook(),     // 分享面板底部功能按钮（核心入口）
            AdHook(),             // 开屏广告跳过 + 关键词屏蔽 + 购物过滤
            HotUpdateHook(),      // 屏蔽热更新（保护 Hook 不被覆盖）
            ShareHook(),          // 分享拦截 Hook
            DownloadHook(),       // 下载流程 Hook
            CommentHook(),        // 评论区 Hook
            LivePhotoHook(),      // 实况照片 Hook + URL 缓存
            DownloadDialogHook(), // 下载弹窗 Hook
            AutoPlayControllerHook(), // 官方 AutoPlayController 自动连播（39.6 已逆向确认）
            AutoPlayButtonHook(),     // 播放页自动连播开关按钮
            VideoFilterHook(),        // 视频过滤（直播/图文/长视频/关键词），跳过用合成滑动
            CleanModeHook(),          // 清爽模式（隐藏界面元素）
            // DoubleClickHook(),      // 双击屏幕自定义行为（暂未启用）
        )
    }

    /**
     * 尝试安装所有 Hook，记录成功/失败
     */
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

    /**
     * 记录 LSPosed 版本信息
     */
    private fun logLSPosedVersion() {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
            val lsposedVersion = getMethod.invoke(null, "persist.lsposed.version", "unknown") as String
            val apiVersion = getMethod.invoke(null, "persist.lsposed.api", "unknown") as String
            if (lsposedVersion != "unknown") {
                HookUtils.log("LSPosed 版本: $lsposedVersion, API: $apiVersion")
            } else {
                HookUtils.log("LSPosed 版本: 未能检测 (可能是 LSPosed 2.0.x)")
            }
        } catch (_: Throwable) {
            HookUtils.log("LSPosed 版本检测跳过")
        }
    }

    /**
     * 记录抖音版本信息
     */
    private fun logDouyinVersion(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val context = ContextHelper.getContext()
            if (context != null) {
                val pm = context.packageManager
                val info = pm.getPackageInfo(lpparam.packageName, 0)
                HookUtils.log("抖音版本: ${info.versionName} (${info.versionCode})")
            }
        } catch (_: Throwable) {
            // Context 尚未就绪是正常的，跳过
        }
    }
}
