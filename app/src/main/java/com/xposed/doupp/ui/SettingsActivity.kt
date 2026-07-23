package com.xposed.doupp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment
import android.widget.Toast
import com.xposed.doupp.KeepAliveService

class SettingsActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragment() {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(com.xposed.doupp.R.xml.prefs)
            DouSettings.init(activity)
            bindPreferenceListeners()
            val sp = preferenceManager.sharedPreferences
            updateAdChildrenEnabled(sp?.getBoolean("remove_ad", true) ?: true)
            updateFilterChildrenEnabled(sp?.getBoolean("video_filter", false) ?: false)
            updateAutoPlayChildrenEnabled(sp?.getBoolean("auto_play", false) ?: false)
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

        private fun safePref(key: String, block: (Any) -> Unit) {
            findPreference(key)?.setOnPreferenceChangeListener { _, newValue ->
                try {
                    block(newValue)
                    KeepAliveService.start(activity)
                    true
                } catch (t: Throwable) {
                    android.util.Log.e("DouSettings", "pref $key error: ${t.message}", t)
                    true
                }
            }
        }

        private val autoPlayChildKeys = arrayOf(
            "auto_play_floating", "auto_play_hide"
        )

        private fun updateAutoPlayChildrenEnabled(enabled: Boolean) {
            for (key in autoPlayChildKeys) {
                findPreference(key)?.isEnabled = enabled
            }
        }

        private fun bindPreferenceListeners() {
            safePref("download_video") { DouSettings.setDownloadVideo(it as Boolean) }
            safePref("download_music") { DouSettings.setDownloadMusic(it as Boolean) }
            safePref("download_image") { DouSettings.setDownloadImage(it as Boolean) }
            safePref("copy_text") { DouSettings.setCopyText(it as Boolean) }
            safePref("remove_ad") { v ->
                val on = v as Boolean
                DouSettings.setRemoveAd(on)
                updateAdChildrenEnabled(on)
            }
            safePref("skip_splash_ad") { DouSettings.setSkipSplashAd(it as Boolean) }
            safePref("block_feed_keywords") { DouSettings.setBlockFeedKeywords(it as Boolean) }
            safePref("block_shopping") { DouSettings.setBlockShopping(it as Boolean) }
            safePref("hide_ad_labels") { DouSettings.setHideAdLabels(it as Boolean) }
            safePref("block_ad_sdk") { DouSettings.setBlockAdSdk(it as Boolean) }
            safePref("ad_keywords") { DouSettings.setAdKeywords(it as String) }
            safePref("block_hot_update") { DouSettings.setBlockHotUpdate(it as Boolean) }
            safePref("save_comment_media") { DouSettings.setSaveCommentMedia(it as Boolean) }
            safePref("save_directory") { DouSettings.setSaveDirectory(it as String) }
            safePref("video_filter") { v ->
                val on = v as Boolean
                DouSettings.setVideoFilterEnabled(on)
                updateFilterChildrenEnabled(on)
            }
            safePref("filter_live") { DouSettings.setFilterLive(it as Boolean) }
            safePref("filter_image") { DouSettings.setFilterImage(it as Boolean) }
            safePref("filter_ad") { DouSettings.setFilterAd(it as Boolean) }
            safePref("filter_long_video") { DouSettings.setFilterLongVideo(it as Boolean) }
            safePref("long_video_seconds") { DouSettings.setLongVideoSeconds((it as String).toInt()) }
            safePref("filter_keywords") { DouSettings.setFilterKeywords(it as String) }
            safePref("double_click_action") { DouSettings.setDoubleClickAction(it as String) }
            safePref("auto_play") { v ->
                val on = v as Boolean
                DouSettings.setAutoPlay(on)
                updateAutoPlayChildrenEnabled(on)
            }
            safePref("auto_play_floating") { DouSettings.setAutoPlayFloating(it as Boolean) }
            safePref("auto_play_hide") { DouSettings.setAutoPlayHide(it as Boolean) }
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
    }
}
