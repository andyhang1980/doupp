package com.xposed.doupp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(com.xposed.doupp.R.xml.prefs, rootKey)
            DouSettings.init(requireActivity())
            bindPreferenceListeners()
            val sp = preferenceManager.sharedPreferences
            updateAdChildrenEnabled(sp?.getBoolean("remove_ad", true) ?: true)
            updateFilterChildrenEnabled(sp?.getBoolean("video_filter", false) ?: false)
        }

        private val filterChildKeys = arrayOf(
            "filter_live", "filter_image", "filter_ad", "filter_long_video",
            "long_video_seconds", "filter_keywords"
        )

        private fun updateFilterChildrenEnabled(enabled: Boolean) {
            for (key in filterChildKeys) {
                findPreference<Preference>(key)?.isEnabled = enabled
            }
        }

        private val adChildKeys = arrayOf(
            "skip_splash_ad", "block_feed_keywords", "block_shopping",
            "hide_ad_labels", "block_ad_sdk", "ad_keywords", "block_hot_update"
        )

        private fun updateAdChildrenEnabled(enabled: Boolean) {
            for (key in adChildKeys) {
                findPreference<Preference>(key)?.isEnabled = enabled
            }
        }

        private fun bindPreferenceListeners() {
            findPreference<CheckBoxPreference>("download_video")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setDownloadVideo(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("download_music")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setDownloadMusic(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("download_image")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setDownloadImage(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("copy_text")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setCopyText(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("remove_ad")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val on = newValue as Boolean
                    DouSettings.setRemoveAd(on)
                    updateAdChildrenEnabled(on)
                    true
                }
            findPreference<CheckBoxPreference>("skip_splash_ad")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setSkipSplashAd(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("block_feed_keywords")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setBlockFeedKeywords(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("block_shopping")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setBlockShopping(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("hide_ad_labels")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setHideAdLabels(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("block_ad_sdk")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setBlockAdSdk(newValue as Boolean); true
                }
            findPreference<EditTextPreference>("ad_keywords")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setAdKeywords(newValue as String); true
                }
            findPreference<CheckBoxPreference>("block_hot_update")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setBlockHotUpdate(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("save_comment_media")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setSaveCommentMedia(newValue as Boolean); true
                }
            findPreference<EditTextPreference>("save_directory")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setSaveDirectory(newValue as String); true
                }
            findPreference<CheckBoxPreference>("video_filter")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    val on = newValue as Boolean
                    DouSettings.setVideoFilterEnabled(on)
                    updateFilterChildrenEnabled(on)
                    true
                }
            findPreference<CheckBoxPreference>("filter_live")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setFilterLive(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("filter_image")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setFilterImage(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("filter_ad")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setFilterAd(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("filter_long_video")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setFilterLongVideo(newValue as Boolean); true
                }
            findPreference<ListPreference>("long_video_seconds")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setLongVideoSeconds((newValue as String).toInt()); true
                }
            findPreference<EditTextPreference>("filter_keywords")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setFilterKeywords(newValue as String); true
                }
            findPreference<ListPreference>("double_click_action")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setDoubleClickAction(newValue as String); true
                }
            findPreference<CheckBoxPreference>("auto_play_floating")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setAutoPlayFloating(newValue as Boolean); true
                }
            findPreference<CheckBoxPreference>("auto_play_hide")
                ?.setOnPreferenceChangeListener { _, newValue ->
                    DouSettings.setAutoPlayHide(newValue as Boolean); true
                }
            findPreference<Preference>("telegram")
                ?.setOnPreferenceClickListener {
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
