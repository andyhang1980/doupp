package com.xposed.doupp.hook

import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache

/**
 * 实况照片 Hook — 最小化策略
 *
 * 原则: 不 Hook ImageModel 的所有 getter（每次调用都触发深度反射扫描，开销极大）
 *
 * 新策略: 完全被动 — 不主动 Hook 任何方法
 * 实况照片的检测和下载改为在用户点击保存按钮时（SharePanelHook）按需执行：
 * 从 FeedHook 缓存的 Aweme 对象中提取 image+video URL 对
 *
 * 此 Hook 仅保留为占位符，确保 MainHook 中的注册不需修改
 * 实况照片检测逻辑移至 MediaCache.extractLivePhotoFromAweme()
 */
class LivePhotoHook : BaseHook {

    companion object {
        private const val TAG = "LivePhotoHook"
        private var installed = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            // 最小化策略: 不 Hook 任何方法
            // 实况照片检测改为在保存时按需执行，避免日常使用中的性能损耗
            //
            // 原实现的问题:
            //   - Hook 了 ImageModel 类的所有无参方法（可能 10+ 个 getter）
            //   - 每次 getter 调用都执行 checkForLivePhoto()，对对象做全字段反射扫描
            //   - 包含嵌套对象的递归扫描（findStringField）
            //   - 在信息流滑动时，图片模型 getter 被高频调用，导致严重卡顿
            //
            // 新策略:
            //   - FeedHook 已缓存当前 Aweme 对象
            //   - 用户点击保存按钮时（SharePanelHook.onDownloadClicked）
            //   - 调用 MediaCache.extractLivePhotoFromAweme(aweme) 按需提取
            //   - 零日常开销，只在用户主动操作时执行一次

            HookUtils.log("$TAG: 最小化策略 — 不安装任何 Hook，实况照片检测改为保存时按需执行")
            installed = true
        }
    }
}
