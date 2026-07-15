package com.xposed.doupp.util

import android.content.Context
import android.content.pm.PackageManager

/**
 * 抖音版本兼容工具
 *
 * 自动检测抖音版本，根据版本号调整 Hook 策略。
 * 支持版本范围:
 * - 39.x 系列: 当前主要适配目标
 * - 40.x 系列: 预留适配
 * - 未来版本: 动态发现为主，硬编码为辅
 */
object VersionCompat {

    private const val TAG = "VersionCompat"

    /** 当前检测到的抖音版本号 */
    @Volatile
    private var douyinVersionCode: Int = 0

    /** 当前检测到的抖音版本名 */
    @Volatile
    private var douyinVersionName: String = "unknown"

    /** 已知的关键版本节点 */
    private val VERSION_MILESTONES = mapOf(
        390501 to "39.5.0",
        390502 to "39.5.1",
        390503 to "39.5.2",
        39509900 to "39.5.99", // 用户指定的 39.5 版本
        40000000 to "40.0.0"
    )

    /**
     * 初始化版本检测
     */
    fun init(context: Context, packageName: String) {
        try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            douyinVersionCode = info.versionCode
            douyinVersionName = info.versionName ?: "unknown"
            HookUtils.log("$TAG: 抖音版本 $douyinVersionName ($douyinVersionCode)")
            
            // 版本日志
            when {
                douyinVersionCode >= 40000000 -> {
                    HookUtils.log("$TAG: ⚠️ 检测到 40.x 版本，使用动态发现策略")
                }
                douyinVersionCode >= 39500000 -> {
                    HookUtils.log("$TAG: ✓ 检测到 39.5.x 版本，使用增强适配策略")
                }
                douyinVersionCode >= 39000000 -> {
                    HookUtils.log("$TAG: ✓ 检测到 39.x 版本，使用标准适配策略")
                }
                else -> {
                    HookUtils.log("$TAG: ⚠️ 检测到旧版本 $douyinVersionCode，部分功能可能受限")
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 版本检测失败: ${t.message}")
        }
    }

    fun getVersionCode(): Int = douyinVersionCode
    fun getVersionName(): String = douyinVersionName

    /**
     * 是否为 39.5+ 版本
     */
    fun isV395Plus(): Boolean = douyinVersionCode >= 39500000

    /**
     * 是否为 40.x 版本
     */
    fun isV40Plus(): Boolean = douyinVersionCode >= 40000000

    /**
     * 获取 Aweme 类名候选（按版本扩展）
     */
    fun getAwemeClassCandidates(): List<String> {
        val base = mutableListOf(
            "com.ss.android.ugc.aweme.feed.model.Aweme",
            "com.ss.ugc.aweme.Aweme"
        )
        if (isV40Plus()) {
            base.add("com.ss.android.ugc.aweme.feed.model.AwemeV2")
            base.add("com.ss.ugc.aweme.feed.Aweme")
        }
        return base
    }

    /**
     * 获取 Video 字段名候选（按版本扩展）
     */
    fun getVideoFieldCandidates(): List<String> {
        val base = mutableListOf("video", "videoModel", "mVideo", "feedVideo")
        if (isV40Plus()) {
            base.add("videoObject")
            base.add("mediaVideo")
        }
        return base
    }

    /**
     * 获取下载地址字段候选（按版本扩展）
     */
    fun getDownloadAddrCandidates(): List<String> {
        val base = mutableListOf("downloadAddr", "download_addr", "downloadUrl", "download_url")
        if (isV40Plus()) {
            base.add("noWatermarkUrl")
            base.add("originUrl")
        }
        return base
    }

    /**
     * 获取播放地址字段候选（按版本扩展）
     */
    fun getPlayAddrCandidates(): List<String> {
        val base = mutableListOf(
            "playAddr", "play_addr", "playUrl", "play_url",
            "playAddrH264", "play_addr_h264",
            "playAddr265", "play_addr_265",
            "playAddrBytevc1", "play_addr_bytevc1",
            "bitRateList", "bitrate_list"
        )
        if (isV40Plus()) {
            base.add("playAddrList")
            base.add("urlList")
        }
        return base
    }

    /**
     * 获取分享面板类名候选（按版本扩展）
     */
    fun getSharePanelClassKeywords(): Array<String> {
        val base = mutableListOf(
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
        if (isV40Plus()) {
            base.add("ShareBottomSheet")
            base.add("SharePanelV2")
        }
        return base.toTypedArray()
    }

    /**
     * 获取播放器回调包名候选（按版本扩展）
     */
    fun getPlayerCallbackPackages(): List<String> {
        val base = mutableListOf(
            "com.ss.android.ugc.aweme.feed",
            "com.ss.android.ugc.aweme.player",
            "com.ss.android.ugc.aweme.video"
        )
        if (isV40Plus()) {
            base.add("com.ss.android.ugc.aweme.media")
            base.add("com.ss.android.ugc.aweme.playerkit")
        }
        return base
    }
}
