package com.xposed.doupp.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.xposed.doupp.util.HookUtils

/**
 * Dou+ 设置管理
 *
 * 跨进程配置读取:
 * - 模块设置页（本进程）: 用普通 SharedPreferences 写入
 * - 抖音 Hook 进程: 用 XSharedPreferences 读取（LSPosed 自动桥接）
 *
 * LSPosed 2.x 兼容:
 * - 抖音进程通过 new XSharedPreferences(modulePkg, prefsName) 读取
 * - 需在 prefs.xml 中设置 android:worldReadableMode（旧API）
 * - 或通过 LSPosed 的远程 SharedPreferences 机制（新API）
 */
object DouSettings {

    const val PREFS_NAME = "douyin_helper_settings"
    const val MODULE_PACKAGE = "com.xposed.doupp"

    // ==================== Key 定义 ====================
    const val KEY_DOWNLOAD_VIDEO = "download_video"
    const val KEY_DOWNLOAD_MUSIC = "download_music"
    const val KEY_DOWNLOAD_IMAGE = "download_image"
    const val KEY_COPY_TEXT = "copy_text"
    const val KEY_REMOVE_AD = "remove_ad"
    const val KEY_SKIP_SPLASH_AD = "skip_splash_ad"
    const val KEY_BLOCK_FEED_KEYWORDS = "block_feed_keywords"
    const val KEY_BLOCK_SHOPPING = "block_shopping"
    const val KEY_HIDE_AD_LABELS = "hide_ad_labels"
    const val KEY_BLOCK_AD_SDK = "block_ad_sdk"
    const val KEY_AD_KEYWORDS = "ad_keywords"
    const val KEY_BLOCK_HOT_UPDATE = "block_hot_update"
    const val KEY_AUTO_PLAY = "auto_play"
    const val KEY_SAVE_COMMENT_MEDIA = "save_comment_media"
    const val KEY_SAVE_DIRECTORY = "save_directory"
    const val KEY_VIDEO_FILTER = "video_filter"
    const val KEY_FILTER_LIVE = "filter_live"
    const val KEY_FILTER_IMAGE = "filter_image"
    const val KEY_FILTER_AD = "filter_ad"
    const val KEY_FILTER_LONG_VIDEO = "filter_long_video"
    const val KEY_FILTER_KEYWORDS = "filter_keywords"
    const val KEY_LONG_VIDEO_SECONDS = "long_video_seconds"
    const val KEY_DOUBLE_CLICK_ACTION = "double_click_action"

    // ==================== 默认值 ====================
    private const val DEFAULT_DOWNLOAD_VIDEO = true
    private const val DEFAULT_DOWNLOAD_MUSIC = true
    private const val DEFAULT_DOWNLOAD_IMAGE = true
    private const val DEFAULT_COPY_TEXT = true
    private const val DEFAULT_REMOVE_AD = true
    private const val DEFAULT_SKIP_SPLASH_AD = true
    private const val DEFAULT_BLOCK_FEED_KEYWORDS = true
    private const val DEFAULT_BLOCK_SHOPPING = true
    private const val DEFAULT_HIDE_AD_LABELS = true
    private const val DEFAULT_BLOCK_AD_SDK = true
    private const val DEFAULT_BLOCK_HOT_UPDATE = true
    private const val DEFAULT_AUTO_PLAY = true
    private const val DEFAULT_SAVE_COMMENT_MEDIA = true
    private const val DEFAULT_SAVE_DIRECTORY = "Dou+"
    private const val DEFAULT_VIDEO_FILTER = false
    private const val DEFAULT_FILTER_LIVE = true
    private const val DEFAULT_FILTER_IMAGE = false
    private const val DEFAULT_FILTER_AD = true
    private const val DEFAULT_FILTER_LONG_VIDEO = false
    private const val DEFAULT_LONG_VIDEO_SECONDS = 300
    private const val DEFAULT_DOUBLE_CLICK_ACTION = "like"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** ProviderPrefs 最近一次成功刷新的时间戳（用于控制实时刷新频率） */
    private var lastProviderRefresh = 0L

    /** ProviderPrefs 两次刷新最小间隔（毫秒） */
    private const val PROVIDER_REFRESH_MS = 3000L

    /** 是否已记录过“使用默认值”日志，避免刷屏 */
    private var defaultLogged = false

    /** ContentProvider 返回的最新设置 Bundle（跨进程读取） */
    @Volatile
    private var providerBundle: android.os.Bundle? = null

    /** 设置页进程持有的 Context，用于把 prefs 文件设为跨进程可读写 */
    private var prefsContext: Context? = null

    /**
     * 自动播放运行期状态（内存中）。
     * 抖音进程内通过播放界面按钮切换时，优先用此内存值，避免依赖跨进程文件写入权限；
     * 同时尽量持久化到世界可读写文件。
     */
    @Volatile
    private var autoPlayRuntime: Boolean? = null

    /** 自动播放状态的独立世界可读写文件路径（位于模块 data 目录） */
    private fun autoPlayFile(): java.io.File {
        return java.io.File("/data/data/$MODULE_PACKAGE/shared_prefs/.dou_autoplay")
    }

    /**
     * 初始化设置管理器（模块设置页进程调用）
     */
    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
        // 记录 Context 并把 prefs 文件设为跨进程可读写（让抖音进程能读取/写入用户设置）
        prefsContext = context
        makeWorldAccessible(context)
        // 确保 prefs 文件存在（写入默认值）。抖音进程通过直接文件读取跨进程读取设置，
        // 若文件不存在则读不到任何值；这里用默认值落盘，保证文件一定存在且 world-readable。
        try {
            val f = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
            if (!f.exists()) {
                prefs!!.edit().apply {
                    putBoolean(KEY_DOWNLOAD_VIDEO, DEFAULT_DOWNLOAD_VIDEO)
                    putBoolean(KEY_DOWNLOAD_MUSIC, DEFAULT_DOWNLOAD_MUSIC)
                    putBoolean(KEY_DOWNLOAD_IMAGE, DEFAULT_DOWNLOAD_IMAGE)
                    putBoolean(KEY_COPY_TEXT, DEFAULT_COPY_TEXT)
                    putBoolean(KEY_REMOVE_AD, DEFAULT_REMOVE_AD)
                    putBoolean(KEY_SKIP_SPLASH_AD, DEFAULT_SKIP_SPLASH_AD)
                    putBoolean(KEY_BLOCK_FEED_KEYWORDS, DEFAULT_BLOCK_FEED_KEYWORDS)
                    putBoolean(KEY_BLOCK_SHOPPING, DEFAULT_BLOCK_SHOPPING)
                    putBoolean(KEY_HIDE_AD_LABELS, DEFAULT_HIDE_AD_LABELS)
                    putBoolean(KEY_BLOCK_AD_SDK, DEFAULT_BLOCK_AD_SDK)
                    putBoolean(KEY_BLOCK_HOT_UPDATE, DEFAULT_BLOCK_HOT_UPDATE)
                    putBoolean(KEY_AUTO_PLAY, DEFAULT_AUTO_PLAY)
                    putBoolean(KEY_SAVE_COMMENT_MEDIA, DEFAULT_SAVE_COMMENT_MEDIA)
                    putString(KEY_SAVE_DIRECTORY, DEFAULT_SAVE_DIRECTORY)
                    putString(KEY_AD_KEYWORDS, "")
                }.apply()
                // 文件刚创建，再次把目录与文件设为 world-readable
                makeWorldAccessible(context)
            }
        } catch (_: Throwable) {}
    }

    /**
     * 把模块自身 data 目录及 shared_prefs 文件设为其它进程（抖音）可访问。
     * 默认 /data/data/<module> 目录其它 app 无法遍历，需对目录加可执行位、对文件加可读位。
     */
    private fun makeWorldAccessible(context: Context) {
        try {
            val dataDir = java.io.File(context.applicationInfo.dataDir)
            setAccessible(dataDir)
            val prefsDir = java.io.File(dataDir, "shared_prefs")
            // shared_prefs 目录需可被其它进程（抖音）写入，以便保存自动播放状态
            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)
            prefsDir.setWritable(true, false)
            val prefsFile = java.io.File(prefsDir, "$PREFS_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }
        } catch (_: Throwable) {}
    }

    private fun setAccessible(dir: java.io.File) {
        try {
            dir.setReadable(true, false)
            dir.setExecutable(true, false) // 目录需要可执行位才能被遍历
        } catch (_: Throwable) {}
    }

    /** 写入后同步把文件设为跨进程可读 */
    private fun share() {
        prefsContext?.let { makeWorldAccessible(it) }
    }

    private fun putBoolean(key: String, value: Boolean) {
        getPrefs().edit().putBoolean(key, value).apply()
        share()
    }

    private fun putString(key: String, value: String) {
        getPrefs().edit().putString(key, value).apply()
        share()
    }

    /**
     * 在抖音 Hook 进程中初始化
     * 使用 XSharedPreferences 跨进程读取
     *
     * LSPosed 2.x 兼容策略:
     * 1. 尝试通过 XSharedPreferences 类初始化
     * 2. 如果失败，尝试通过 Context.getSharedPreferences 读取（LSPosed 远程偏好）
     * 3. 如果都失败，使用默认值
     */
    fun initForHookProcess() {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return@synchronized

            // 策略A（首选）: 直接读取模块 data 目录下的 prefs 文件
            // 说明: 本机 LSPosed 未把 de.robv.android.xposed.XSharedPreferences 注入模块类加载器，
            // 但模块 data 目录已被设为 world-readable(0755)、prefs 文件 world-readable(0644)，
            // 因此抖音进程可直接读取该文件，无需依赖 XSharedPreferences 类。
            try {
                val candidates = mutableListOf<java.io.File>()
                candidates.add(java.io.File("/data/data/$MODULE_PACKAGE/shared_prefs/$PREFS_NAME.xml"))
                val ctx0 = com.xposed.doupp.util.ContextHelper.getContext()
                if (ctx0 != null) {
                    try {
                        val moduleInfo = ctx0.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
                        candidates.add(java.io.File(moduleInfo.dataDir, "shared_prefs/$PREFS_NAME.xml"))
                    } catch (_: Throwable) {}
                }
                for (f in candidates) {
                    if (f.exists() && f.canRead()) {
                        prefs = FileBasedPrefs(f)
                        HookUtils.log("DouSettings: 文件读取方式初始化成功 (${f.absolutePath})")
                        return@synchronized
                    }
                }
            } catch (t: Throwable) {
                HookUtils.log("DouSettings: 文件读取方式失败: ${t.message}")
            }

            // 策略0: 尝试 XSharedPreferences（LSPosed 原生跨进程读取，最可靠）
            // 从多个候选类加载器尝试加载，避免只从 XposedBridge 的类加载器加载失败
            try {
                val loaders = arrayOf(
                    DouSettings::class.java.classLoader,
                    de.robv.android.xposed.XposedBridge::class.java.classLoader,
                    Thread.currentThread().contextClassLoader,
                    ClassLoader.getSystemClassLoader()
                )
                // [DBG] 探测 Xposed 类是否可加载
                val probeNames = arrayOf(
                    "de.robv.android.xposed.XSharedPreferences",
                    "de.robv.android.xposed.XposedHelpers",
                    "de.robv.android.xposed.XposedBridge"
                )
                for (name in probeNames) {
                    val found = loaders.any { cl -> try { cl?.loadClass(name) != null } catch (_: Throwable) { false } }
                    HookUtils.log("DouSettings: [DBG] 类 $name 可加载=$found")
                }
                HookUtils.log("DouSettings: [DBG] XposedBridge.classLoader=${de.robv.android.xposed.XposedBridge::class.java.classLoader}")
                var xspClass: Class<*>? = null
                for (cl in loaders) {
                    if (cl == null) continue
                    try {
                        xspClass = cl.loadClass("de.robv.android.xposed.XSharedPreferences")
                        if (xspClass != null) {
                            HookUtils.log("DouSettings: [DBG] XSharedPreferences 命中 classloader=$cl")
                            break
                        }
                    } catch (_: Throwable) {}
                }
                if (xspClass != null) {
                    val constructor = xspClass!!.getConstructor(String::class.java, String::class.java)
                    val xsp = constructor.newInstance(MODULE_PACKAGE, PREFS_NAME)
                    xspClass!!.getMethod("reload").invoke(xsp)
                    prefs = xsp as SharedPreferences
                    val all = xspClass!!.getMethod("getAll").invoke(xsp) as? Map<*, *>
                    HookUtils.log("DouSettings: XSharedPreferences 初始化成功 (keys=${all?.size ?: 0})")
                    return@synchronized
                } else {
                    HookUtils.log("DouSettings: XSharedPreferences 类不可用，跳过")
                }
            } catch (t: Throwable) {
                HookUtils.log("DouSettings: XSharedPreferences 方式失败: ${t.message}")
            }

            // 策略1: 通过模块提供的 ContentProvider 跨进程读取（兜底）
            try {
                if (tryInitFromProvider()) {
                    prefs = ProviderPrefs()
                    return@synchronized
                }
            } catch (t: Throwable) {
                HookUtils.log("DouSettings: ContentProvider 策略异常: ${t.message}")
            }

            // 策略2: 尝试通过 Context 获取 SharedPreferences
            // LSPosed 2.x 会自动桥接 worldReadable SharedPreferences
            try {
                val context = com.xposed.doupp.util.ContextHelper.getContext()
                if (context != null) {
                    // 尝试以 WORLD_READABLE 模式打开（LSPosed 会桥接）
                    @Suppress("DEPRECATED")
                    val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
                    prefs = sp
                    HookUtils.log("DouSettings: 通过 Context SharedPreferences 初始化成功")
                    return@synchronized
                }
            } catch (t: Throwable) {
                HookUtils.log("DouSettings: Context SharedPreferences 方式失败: ${t.message}")
            }

            // 策略3: 直接读取模块的 prefs 文件（不依赖 Context，尝试跨 UID 文件读取）
            try {
                val candidates = mutableListOf<java.io.File>()
                candidates.add(java.io.File("/data/data/$MODULE_PACKAGE/shared_prefs/$PREFS_NAME.xml"))
                val ctx = com.xposed.doupp.util.ContextHelper.getContext()
                if (ctx != null) {
                    try {
                        val pm = ctx.packageManager
                        val moduleInfo = pm.getApplicationInfo(MODULE_PACKAGE, 0)
                        candidates.add(java.io.File(moduleInfo.dataDir, "shared_prefs/$PREFS_NAME.xml"))
                    } catch (_: Throwable) {}
                }
                for (f in candidates) {
                    if (f.exists() && f.canRead()) {
                        HookUtils.log("DouSettings: 尝试直接读取 prefs 文件: ${f.absolutePath}")
                        prefs = FileBasedPrefs(f)
                        HookUtils.log("DouSettings: 文件读取方式初始化成功")
                        return@synchronized
                    }
                }
            } catch (t: Throwable) {
                HookUtils.log("DouSettings: 文件读取方式失败: ${t.message}")
            }

            // 策略4: 所有策略失败，留空由 getPrefs() 在 Context 就绪后惰性重试 ContentProvider
            HookUtils.log("DouSettings: 所有策略失败，留待惰性初始化")
            prefs = null
        }
    }

    /**
     * 基于文件的 SharedPreferences 实现
     * 直接读取 XML 文件，用于跨进程读取
     */
    private class FileBasedPrefs(private val prefsFile: java.io.File) : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        init {
            load()
        }

        private fun load() {
            try {
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(prefsFile)
                val nodes = doc.getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    val key = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                    val value = node.textContent ?: ""
                    map[key] = value
                }
                val boolNodes = doc.getElementsByTagName("boolean")
                for (i in 0 until boolNodes.length) {
                    val node = boolNodes.item(i)
                    val key = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                    val value = node.attributes.getNamedItem("value")?.nodeValue == "true"
                    map[key] = value
                }
                val intNodes = doc.getElementsByTagName("int")
                for (i in 0 until intNodes.length) {
                    val node = intNodes.item(i)
                    val key = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                    val value = node.attributes.getNamedItem("value")?.nodeValue?.toIntOrNull() ?: continue
                    map[key] = value
                }
            } catch (t: Throwable) {
                HookUtils.log("FileBasedPrefs: 加载失败: ${t.message}")
            }
        }

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = NoopEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /**
     * 默认空 SharedPreferences（使用代码中的默认值）
     */
    private class DefaultPrefs : SharedPreferences {
        override fun getAll(): Map<String, *> = emptyMap<String, Any?>()
        override fun getString(key: String, defValue: String?): String? = defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = false
        override fun edit(): SharedPreferences.Editor = NoopEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /**
     * 基于 ContentProvider 返回 Bundle 的 SharedPreferences 实现。
     * 读取实时反映模块设置页的最新值（Bundle 在 refreshFromProvider 时更新）。
     */
    private class ProviderPrefs : SharedPreferences {
        override fun getAll(): Map<String, *> = providerBundle?.keySet()?.associateWith {
            providerBundle?.get(it) ?: Unit
        } ?: emptyMap<String, Any>()

        override fun getString(key: String, defValue: String?): String? =
            providerBundle?.getString(key, defValue) ?: defValue

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int =
            if (providerBundle?.containsKey(key) == true) providerBundle?.getInt(key, defValue) ?: defValue else defValue
        override fun getLong(key: String, defValue: Long): Long =
            if (providerBundle?.containsKey(key) == true) providerBundle?.getLong(key, defValue) ?: defValue else defValue
        override fun getFloat(key: String, defValue: Float): Float =
            if (providerBundle?.containsKey(key) == true) providerBundle?.getFloat(key, defValue) ?: defValue else defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            if (providerBundle?.containsKey(key) == true) providerBundle?.getBoolean(key, defValue) ?: defValue else defValue
        override fun contains(key: String): Boolean = providerBundle?.containsKey(key) ?: false
        override fun edit(): SharedPreferences.Editor = NoopEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class NoopEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = false
        override fun apply() {}
    }

    /**
     * 重新加载配置（抖音进程中调用，确保读取最新值）
     */
    fun reload() {
        try {
            prefs?.let { sp ->
                val className = sp.javaClass.name
                // DefaultPrefs 说明初始化时所有策略都失败了，重新尝试初始化
                if (className.contains("DefaultPrefs")) {
                    HookUtils.log("DouSettings: reload 检测到 DefaultPrefs，重新初始化")
                    prefs = null
                    initForHookProcess()
                    return
                }
                // ProviderPrefs: 重新拉取最新设置
                if (className.contains("ProviderPrefs")) {
                    refreshFromProvider()
                    return
                }
                if (className.contains("XSharedPreferences")) {
                    val reloadMethod = sp.javaClass.getMethod("reload")
                    reloadMethod.invoke(sp)
                } else if (className.contains("FileBasedPrefs")) {
                    // FileBasedPrefs 需要重新加载文件
                    val field = sp.javaClass.getDeclaredField("map")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val map = field.get(sp) as? MutableMap<String, Any?>
                    if (map != null) {
                        map.clear()
                        val loadMethod = sp.javaClass.getDeclaredMethod("load")
                        loadMethod.isAccessible = true
                        loadMethod.invoke(sp)
                    }
                }
                Unit
            }
        } catch (_: Throwable) { }
    }

    private fun getPrefs(): SharedPreferences {
        // 如果尚未初始化，尝试用默认值
        val p = prefs
        if (p != null) {
            // ProviderPrefs / FileBasedPrefs: 带 TTL 的实时刷新，保证设置变更即时生效。
            // 关键修复: FileBasedPrefs 在 init 时把 XML 一次性加载到内存就不再刷新，
            // 导致设置页改开关后，抖音进程感知不到（必须重启抖音才生效）。
            // 这里复用 ProviderPrefs 的 TTL(3s) 周期触发 reload()，让开关修改即时生效。
            if (p is ProviderPrefs || p is FileBasedPrefs) {
                val now = System.currentTimeMillis()
                if (now - lastProviderRefresh > PROVIDER_REFRESH_MS) {
                    lastProviderRefresh = now
                    try { reload() } catch (_: Throwable) {}
                }
            }
            return p
        }
        // 懒加载：运行时若 Context 已可用，尝试通过 ContentProvider 初始化
        if (tryInitFromProvider()) {
            prefs = ProviderPrefs()
            lastProviderRefresh = System.currentTimeMillis()
            return prefs!!
        }
        // 返回默认空 prefs，避免崩溃（下次调用会重试）
        if (!defaultLogged) {
            defaultLogged = true
            HookUtils.log("DouSettings: prefs 尚未初始化，使用默认值（ContentProvider 暂不可用）")
        }
        return DefaultPrefs()
    }

    /**
     * 通过模块提供的 ContentProvider 跨进程读取设置。
     * 每次调用实时查询，保证设置变更即时生效。
     */
    @Synchronized
    private fun tryInitFromProvider(): Boolean {
        if (providerBundle != null) return true
        try {
            val context = com.xposed.doupp.util.ContextHelper.getContext()
            if (context == null) {
                HookUtils.log("DouSettings: [DBG] tryInitFromProvider context==null")
                return false
            }
            val b = context.contentResolver.call(
                Uri.parse("content://$MODULE_PACKAGE.settings"), "getAll", null, null
            )
            HookUtils.log("DouSettings: [DBG] ContentProvider call 返回 b=${if (b == null) "null" else "size=${b.size()}"}")
            if (b != null && !b.isEmpty) {
                providerBundle = b
                HookUtils.log("DouSettings: ContentProvider 初始化成功 (keys=${b.size()})")
                return true
            }
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: ContentProvider 方式失败: ${t.javaClass.name}: ${t.message}")
            val st = t.stackTrace?.take(4)?.joinToString(" | ") { "${it.className}.${it.methodName}(${it.lineNumber})" } ?: ""
            HookUtils.log("DouSettings: [DBG] stack: $st")
        }
        return false
    }

    /** 重新从 ContentProvider 拉取最新设置 */
    fun refreshFromProvider() {
        try {
            val context = com.xposed.doupp.util.ContextHelper.getContext()
            if (context == null) return
            val b = context.contentResolver.call(
                Uri.parse("content://$MODULE_PACKAGE.settings"), "getAll", null, null
            )
            if (b != null) {
                synchronized(this) { providerBundle = b }
                HookUtils.log("DouSettings: ContentProvider 刷新成功 (keys=${b.size()})")
            }
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: ContentProvider 刷新失败: ${t.javaClass.name}: ${t.message}")
        }
    }

    // ==================== 下载功能开关 ====================

    fun isDownloadVideoEnabled(): Boolean =
        getPrefs().getBoolean(KEY_DOWNLOAD_VIDEO, DEFAULT_DOWNLOAD_VIDEO)

    fun isDownloadMusicEnabled(): Boolean =
        getPrefs().getBoolean(KEY_DOWNLOAD_MUSIC, DEFAULT_DOWNLOAD_MUSIC)

    fun isDownloadImageEnabled(): Boolean =
        getPrefs().getBoolean(KEY_DOWNLOAD_IMAGE, DEFAULT_DOWNLOAD_IMAGE)

    fun isCopyTextEnabled(): Boolean =
        getPrefs().getBoolean(KEY_COPY_TEXT, DEFAULT_COPY_TEXT)

    // ==================== 增强功能开关 ====================

    fun isRemoveAdEnabled(): Boolean =
        getPrefs().getBoolean(KEY_REMOVE_AD, DEFAULT_REMOVE_AD)

    fun isSkipSplashAdEnabled(): Boolean =
        isRemoveAdEnabled() && getPrefs().getBoolean(KEY_SKIP_SPLASH_AD, DEFAULT_SKIP_SPLASH_AD)

    fun isBlockFeedKeywordsEnabled(): Boolean =
        isRemoveAdEnabled() && getPrefs().getBoolean(KEY_BLOCK_FEED_KEYWORDS, DEFAULT_BLOCK_FEED_KEYWORDS)

    fun isBlockShoppingEnabled(): Boolean =
        isRemoveAdEnabled() && getPrefs().getBoolean(KEY_BLOCK_SHOPPING, DEFAULT_BLOCK_SHOPPING)

    fun isHideAdLabelsEnabled(): Boolean =
        isRemoveAdEnabled() && getPrefs().getBoolean(KEY_HIDE_AD_LABELS, DEFAULT_HIDE_AD_LABELS)

    fun isBlockAdSdkEnabled(): Boolean =
        isRemoveAdEnabled() && getPrefs().getBoolean(KEY_BLOCK_AD_SDK, DEFAULT_BLOCK_AD_SDK)

    fun isBlockHotUpdateEnabled(): Boolean =
        getPrefs().getBoolean(KEY_BLOCK_HOT_UPDATE, DEFAULT_BLOCK_HOT_UPDATE)

    /**
     * 广告/关键词屏蔽词集合。
     * 内置默认词 + 用户在设置页自定义的词语（逗号/换行/空格分隔）。
     * 全部转为小写，便于 TextView 文本匹配。
     */
    fun getAdKeywords(): Set<String> {
        val builtin = setOf(
            "广告", "自动续火花", "推广",
            "购物", "商品", "直播", "带货", "小店",
            "价格", "优惠", "折扣", "满减", "秒杀",
            "购物车", "购买", "下单", "好物", "推荐好物",
            "橱窗", "小黄车", "进店", "店铺", "领券",
            "优惠券", "正在直播", "链接在评论区"
        )
        val set = builtin.toMutableSet()
        try {
            val custom = getPrefs().getString(KEY_AD_KEYWORDS, "") ?: ""
            custom.split(Regex("[,\\n，\\s]+"))
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .forEach { set.add(it) }
        } catch (_: Throwable) {}
        return set
    }

    fun setAdKeywords(value: String) {
        putString(KEY_AD_KEYWORDS, value)
    }

    fun isAutoPlayEnabled(): Boolean {
        // 优先使用运行期内存值（播放界面按钮切换立即生效）
        autoPlayRuntime?.let { return it }
        // 其次读取独立世界可读文件（跨进程持久化）
        try {
            val f = autoPlayFile()
            if (f.exists()) {
                val txt = f.readText().trim()
                if (txt == "1" || txt == "0") {
                    return txt == "1"
                }
            }
        } catch (_: Throwable) {}
        return getPrefs().getBoolean(KEY_AUTO_PLAY, DEFAULT_AUTO_PLAY)
    }

    // ==================== 评论区 ====================

    fun isSaveCommentMedia(): Boolean =
        getPrefs().getBoolean(KEY_SAVE_COMMENT_MEDIA, DEFAULT_SAVE_COMMENT_MEDIA)

    // ==================== 存储 ====================

    fun getSaveDirectory(): String =
        getPrefs().getString(KEY_SAVE_DIRECTORY, DEFAULT_SAVE_DIRECTORY) ?: DEFAULT_SAVE_DIRECTORY

    // ==================== 写入方法（设置页进程） ====================

    fun setDownloadVideo(enabled: Boolean) =
        putBoolean(KEY_DOWNLOAD_VIDEO, enabled)

    fun setDownloadMusic(enabled: Boolean) =
        putBoolean(KEY_DOWNLOAD_MUSIC, enabled)

    fun setDownloadImage(enabled: Boolean) =
        putBoolean(KEY_DOWNLOAD_IMAGE, enabled)

    fun setCopyText(enabled: Boolean) =
        putBoolean(KEY_COPY_TEXT, enabled)

    fun setRemoveAd(enabled: Boolean) =
        putBoolean(KEY_REMOVE_AD, enabled)

    fun setSkipSplashAd(enabled: Boolean) =
        putBoolean(KEY_SKIP_SPLASH_AD, enabled)

    fun setBlockFeedKeywords(enabled: Boolean) =
        putBoolean(KEY_BLOCK_FEED_KEYWORDS, enabled)

    fun setBlockShopping(enabled: Boolean) =
        putBoolean(KEY_BLOCK_SHOPPING, enabled)

    fun setHideAdLabels(enabled: Boolean) =
        putBoolean(KEY_HIDE_AD_LABELS, enabled)

    fun setBlockAdSdk(enabled: Boolean) =
        putBoolean(KEY_BLOCK_AD_SDK, enabled)

    fun setAutoPlay(enabled: Boolean) {
        // 内存立即生效
        autoPlayRuntime = enabled
        // 持久化到独立世界可读写文件（抖音进程内切换也能保存）
        try {
            val f = autoPlayFile()
            f.parentFile?.let {
                it.setReadable(true, false)
                it.setExecutable(true, false)
                it.setWritable(true, false)
            }
            f.writeText(if (enabled) "1" else "0")
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (_: Throwable) {}
        // 同步写入内部 prefs（供设置页 UI 显示）
        try { putBoolean(KEY_AUTO_PLAY, enabled) } catch (_: Throwable) {}
    }

    fun setSaveCommentMedia(enabled: Boolean) =
        putBoolean(KEY_SAVE_COMMENT_MEDIA, enabled)

    fun setSaveDirectory(dir: String) =
        putString(KEY_SAVE_DIRECTORY, dir)

    fun setBlockHotUpdate(enabled: Boolean) =
        putBoolean(KEY_BLOCK_HOT_UPDATE, enabled)

    // ==================== 视频过滤 ====================

    fun isVideoFilterEnabled(): Boolean =
        getPrefs().getBoolean(KEY_VIDEO_FILTER, DEFAULT_VIDEO_FILTER)

    fun isFilterLive(): Boolean =
        isVideoFilterEnabled() && getPrefs().getBoolean(KEY_FILTER_LIVE, DEFAULT_FILTER_LIVE)

    fun isFilterImage(): Boolean =
        isVideoFilterEnabled() && getPrefs().getBoolean(KEY_FILTER_IMAGE, DEFAULT_FILTER_IMAGE)

    fun isFilterAd(): Boolean =
        isVideoFilterEnabled() && getPrefs().getBoolean(KEY_FILTER_AD, DEFAULT_FILTER_AD)

    fun isFilterLongVideo(): Boolean =
        isVideoFilterEnabled() && getPrefs().getBoolean(KEY_FILTER_LONG_VIDEO, DEFAULT_FILTER_LONG_VIDEO)

    fun getLongVideoSeconds(): Int =
        getPrefs().getInt(KEY_LONG_VIDEO_SECONDS, DEFAULT_LONG_VIDEO_SECONDS)

    fun getFilterKeywords(): Set<String> {
        val builtin = setOf("广告", "推广")
        val set = builtin.toMutableSet()
        try {
            val custom = getPrefs().getString(KEY_FILTER_KEYWORDS, "") ?: ""
            custom.split(Regex("[,\\n，\\s]+"))
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .forEach { set.add(it) }
        } catch (_: Throwable) {}
        return set
    }

    fun setVideoFilterEnabled(enabled: Boolean) =
        putBoolean(KEY_VIDEO_FILTER, enabled)

    fun setFilterLive(enabled: Boolean) =
        putBoolean(KEY_FILTER_LIVE, enabled)

    fun setFilterImage(enabled: Boolean) =
        putBoolean(KEY_FILTER_IMAGE, enabled)

    fun setFilterAd(enabled: Boolean) =
        putBoolean(KEY_FILTER_AD, enabled)

    fun setFilterLongVideo(enabled: Boolean) =
        putBoolean(KEY_FILTER_LONG_VIDEO, enabled)

    fun setLongVideoSeconds(seconds: Int) {
        getPrefs().edit().putInt(KEY_LONG_VIDEO_SECONDS, seconds).apply()
        share()
    }

    fun setFilterKeywords(value: String) =
        putString(KEY_FILTER_KEYWORDS, value)

    // ==================== 双击行为 ====================

    fun getDoubleClickAction(): String =
        getPrefs().getString(KEY_DOUBLE_CLICK_ACTION, DEFAULT_DOUBLE_CLICK_ACTION) ?: DEFAULT_DOUBLE_CLICK_ACTION

    fun setDoubleClickAction(action: String) =
        putString(KEY_DOUBLE_CLICK_ACTION, action)

}

