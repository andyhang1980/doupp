package com.xposed.doupp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment
import android.widget.Toast
import com.xposed.doupp.KeepAliveService

/**
 * Dou+ 设置页面
 *
 * 使用传统 PreferenceActivity + PreferenceFragment 实现。
 * 用户可通过 LSPosed 管理器或模块自带入口打开此页面。
 */
class SettingsActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    /**
     * 设置 Fragment
     */
    class SettingsFragment : PreferenceFragment() {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(com.xposed.doupp.R.xml.prefs)

            // 初始化设置管理
            DouSettings.init(activity)

            // 根据“后台保活服务”开关启动/停止保活前台服务
            val sp = preferenceManager.sharedPreferences
            applyKeepAlive(sp?.getBoolean(com.xposed.doupp.ui.DouSettings.KEY_KEEP_ALIVE, true) ?: true)

            // 绑定偏好变更监听
            bindPreferenceListeners()

            // 初始化总开关→子开关的可用性
            val _sp = preferenceManager.sharedPreferences
            updateAdChildrenEnabled(_sp?.getBoolean("remove_ad", true) ?: true)
            updateFilterChildrenEnabled(_sp?.getBoolean("video_filter", false) ?: false)
        }

        private val filterChildKeys = arrayOf(
            "filter_live", "filter_image", "filter_ad", "filter_long_video",
            "long_video_seconds", "filter_keywords"
        )

        private fun updateFilterChildrenEnabled(enabled: Boolean) {
            for (key in filterChildKeys) {
                findPreference(key)?.isEnabled = enabled
            }
        }

        private val adChildKeys = arrayOf(
            "skip_splash_ad", "block_feed_keywords", "block_shopping",
            "hide_ad_labels", "block_ad_sdk", "ad_keywords", "block_hot_update"
        )

        private fun updateAdChildrenEnabled(enabled: Boolean) {
            for (key in adChildKeys) {
                findPreference(key)?.isEnabled = enabled
            }
        }

        private fun bindPreferenceListeners() {
            // 下载功能
            findPreference("download_video")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setDownloadVideo(newValue as Boolean); true
            }
            findPreference("download_music")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setDownloadMusic(newValue as Boolean); true
            }
            findPreference("download_image")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setDownloadImage(newValue as Boolean); true
            }
            findPreference("copy_text")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setCopyText(newValue as Boolean); true
            }

            // 增强功能
            findPreference("remove_ad")?.setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as Boolean
                DouSettings.setRemoveAd(on)
                updateAdChildrenEnabled(on)
                true
            }
            findPreference("skip_splash_ad")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setSkipSplashAd(newValue as Boolean); true
            }
            findPreference("block_feed_keywords")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setBlockFeedKeywords(newValue as Boolean); true
            }
            findPreference("block_shopping")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setBlockShopping(newValue as Boolean); true
            }
            findPreference("hide_ad_labels")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setHideAdLabels(newValue as Boolean); true
            }
            findPreference("block_ad_sdk")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setBlockAdSdk(newValue as Boolean); true
            }
            findPreference("ad_keywords")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setAdKeywords(newValue as String); true
            }
            findPreference("block_hot_update")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setBlockHotUpdate(newValue as Boolean); true
            }
            // 自动保存
            findPreference("auto_save_video")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setAutoSaveVideo(newValue as Boolean); true
            }
            findPreference("auto_save_images")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setAutoSaveImages(newValue as Boolean); true
            }
            findPreference("auto_save_live_photo")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setAutoSaveLivePhoto(newValue as Boolean); true
            }

            // 评论区
            findPreference("save_comment_media")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setSaveCommentMedia(newValue as Boolean); true
            }

            // 存储
            findPreference("save_directory")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setSaveDirectory(newValue as String); true
            }
            findPreference("download_quality")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setDownloadQuality(newValue as String); true
            }

            // 视频过滤
            findPreference("video_filter")?.setOnPreferenceChangeListener { _, newValue ->
                val on = newValue as Boolean
                DouSettings.setVideoFilterEnabled(on)
                updateFilterChildrenEnabled(on)
                true
            }
            findPreference("filter_live")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setFilterLive(newValue as Boolean); true
            }
            findPreference("filter_image")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setFilterImage(newValue as Boolean); true
            }
            findPreference("filter_ad")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setFilterAd(newValue as Boolean); true
            }
            findPreference("filter_long_video")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setFilterLongVideo(newValue as Boolean); true
            }
            findPreference("long_video_seconds")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setLongVideoSeconds((newValue as String).toInt()); true
            }
            findPreference("filter_keywords")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setFilterKeywords(newValue as String); true
            }

            // 双击行为
            findPreference("double_click_action")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setDoubleClickAction(newValue as String); true
            }

            // 界面
            findPreference("show_toast")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setShowToast(newValue as Boolean); true
            }
            findPreference("show_notification")?.setOnPreferenceChangeListener { _, newValue ->
                DouSettings.setShowNotification(newValue as Boolean); true
            }
            findPreference(com.xposed.doupp.ui.DouSettings.KEY_KEEP_ALIVE)?.setOnPreferenceChangeListener { _, newValue ->
                applyKeepAlive(newValue as Boolean); true
            }

            // 电报群组
            findPreference("telegram")?.setOnPreferenceClickListener {
                openTelegram()
                true
            }
        }

        private fun openTelegram() {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://t.me/douplus_group")
                startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(activity, "无法打开电报链接", Toast.LENGTH_SHORT).show()
            }
        }

        private fun applyKeepAlive(enabled: Boolean) {
            val ctx = activity ?: return
            if (enabled) {
                KeepAliveService.start(ctx)
            } else {
                KeepAliveService.stop(ctx)
            }
        }
    }
}
