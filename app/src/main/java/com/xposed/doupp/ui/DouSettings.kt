package com.xposed.doupp.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
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
 *
 * 双端存储兜底:
 * - 若上述策略全失败（如 LSPosed 2.x 无 XSharedPreferences、模块进程未运行），
 *   则使用抖音进程本地的 SharedPreferences（doupp_settings.xml）作为存储，
 *   并定期从模块配置文件同步更新。
 */
object DouSettings {

    const val PREFS_NAME = "douyin_helper_settings"
    const val MODULE_PACKAGE = "com.xposed.doupp"

    /** 抖音进程本地兜底 prefs 名称（位于抖音 data 目录） */
    const val LOCAL_PREFS_NAME = "doupp_settings"

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
    const val KEY_AUTO_PLAY_BUTTON = "auto_play_button"
    const val KEY_AUTO_PLAY_FLOATING = "auto_play_floating"
    const val KEY_AUTO_PLAY_HIDE = "auto_play_hide"
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
    const val KEY_IMMERSIVE_MODE = "immersive_mode"
    const val KEY_BOOKMARK_ENABLED = "bookmark_enabled"
    const val KEY_BOOKMARK_COMMENT = "bookmark_comment"
    const val KEY_BOOKMARK_VIDEO = "bookmark_video"
    const val KEY_BOOKMARK_PROFILE = "bookmark_profile"
    const val KEY_SPARK_ENABLED = "spark_enabled"
    const val KEY_SPARK_MESSAGE = "spark_message"
    const val KEY_REGION_UNLOCK = "region_unlock"
    const val KEY_AUTO_SIGNIN = "auto_signin"
    const val KEY_ANTI_REVOKE = "anti_revoke"
    const val KEY_LUCKY_BAG = "lucky_bag"

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
    private const val DEFAULT_AUTO_PLAY = false
    private const val DEFAULT_AUTO_PLAY_BUTTON = false
    private const val DEFAULT_SAVE_COMMENT_MEDIA = true
    private const val DEFAULT_SAVE_DIRECTORY = "Dou+"
    private const val DEFAULT_VIDEO_FILTER = false
    private const val DEFAULT_FILTER_LIVE = true
    private const val DEFAULT_FILTER_IMAGE = false
    private const val DEFAULT_FILTER_AD = true
    private const val DEFAULT_FILTER_LONG_VIDEO = false
    private const val DEFAULT_LONG_VIDEO_SECONDS = 300
    private const val DEFAULT_DOUBLE_CLICK_ACTION = "like"
    private const val DEFAULT_AUTO_PLAY_FLOATING = true
    private const val DEFAULT_AUTO_PLAY_HIDE = true
    private const val DEFAULT_IMMERSIVE_MODE = false
    private const val DEFAULT_BOOKMARK_ENABLED = true
    private const val DEFAULT_BOOKMARK_COMMENT = true
    private const val DEFAULT_BOOKMARK_VIDEO = true
    private const val DEFAULT_BOOKMARK_PROFILE = true
    private const val DEFAULT_SPARK_ENABLED = false
    private const val DEFAULT_SPARK_MESSAGE = "🔥"
    private const val DEFAULT_REGION_UNLOCK = true
    private const val DEFAULT_AUTO_SIGNIN = true
    private const val DEFAULT_ANTI_REVOKE = true
    private const val DEFAULT_LUCKY_BAG = true

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 是否为模块设置页进程（此进程的 prefs 是文件 prefs，必须保持可写，禁止被替换为只读 ProviderPrefs） */
    @Volatile
    private var isSettingsProcess = false

    /** ProviderPrefs 最近一次成功刷新的时间戳（用于控制实时刷新频率） */
    private var lastProviderRefresh = 0L

    /** ProviderPrefs 两次刷新最小间隔（毫秒） */
    private const val PROVIDER_REFRESH_MS = 3000L

    /** 是否已记录过"使用默认值"日志，避免刷屏 */
    private var defaultLogged = false

    /** ContentProvider 返回的最新设置 Bundle（跨进程读取） */
    @Volatile
    private var providerBundle: android.os.Bundle? = null

    /** 设置页进程持有的 Context，用于把 prefs 文件设为跨进程可读写 */
    private var prefsContext: Context? = null

    /** 抖音进程 Context（用于本地 prefs 兜底） */
    @Volatile
    private var localContext: Context? = null

    /** 最近一次从模块文件同步到本地的时间戳 */
    private var lastSyncFromFile = 0L

    /** 两次文件同步的最小间隔 */
    private const val SYNC_FROM_FILE_MS = 5000L

    /**
     * 自动播放运行期状态（内存中）。
     * 抖音进程内通过播放界面按钮切换时，优先用此内存值，避免依赖跨进程文件写入权限；
     * 同时尽量持久化到世界可读写文件。
     */
    @Volatile
    private var autoPlayRuntime: Boolean? = null

    /**
     * 悬浮按钮独立开关状态（与设置的 auto_play 完全独立）。
     * 当悬浮按钮显示时，由它决定是否自动连播；按钮隐藏时回到设置值。
     */
    @Volatile
    private var autoPlayButtonRuntime: Boolean? = null

    /** 自动播放状态的独立世界可读写文件路径（位于模块 data 目录） */
    private fun autoPlayFile(): java.io.File {
        return java.io.File("/data/data/$MODULE_PACKAGE/shared_prefs/.dou_autoplay")
    }

    /** 悬浮按钮独立状态的持久化文件路径（与设置 auto_play 分开） */
    private fun autoPlayButtonFile(): java.io.File {
        return java.io.File("/data/data/$MODULE_PACKAGE/shared_prefs/.dou_autoplay_btn")
    }

    /**
     * 设置抖音进程的 Context，用于本地 prefs 兜底。
     * 在 ContextHelper.onApplicationReady 回调中调用。
     */
    fun setLocalContext(ctx: Context) {
        localContext = ctx
        // context 就绪后，尝试从模块文件同步设置到本地 SharedPreferences
        try {
            val localSp = ctx.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs == null || prefs is DefaultPrefs) {
                prefs = localSp
            }
            syncFromModuleFile()
        } catch (_: Throwable) {}
    }

    /**
     * 从模块的预配文件同步到抖音本地 prefs（跨 UID 文件读取兜底）。
     * 仅在本地 prefs 初始化后且模块文件可读时执行。
     */
    private fun syncFromModuleFile() {
        val local = prefs
        if (local == null || local === DefaultPrefs()) return
        val now = System.currentTimeMillis()
        if (now - lastSyncFromFile < SYNC_FROM_FILE_MS) return
        lastSyncFromFile = now
        var fileFound = false
        try {
            val candidates = mutableListOf<java.io.File>()
            candidates.add(java.io.File("/data/data/$MODULE_PACKAGE/shared_prefs/$PREFS_NAME.xml"))
            // 尝试通过 Context 获取真实 dataDir
            val ctx = localContext ?: com.xposed.doupp.util.ContextHelper.getContext()
            if (ctx != null) {
                try {
                    val moduleInfo = ctx.packageManager.getApplicationInfo(MODULE_PACKAGE, 0)
                    candidates.add(java.io.File(moduleInfo.dataDir, "shared_prefs/$PREFS_NAME.xml"))
                } catch (_: Throwable) {}
            }
            var sourceFile: java.io.File? = null
            for (f in candidates) {
                if (f.exists() && f.canRead()) {
                    sourceFile = f
                    fileFound = true
                    break
                }
            }
            if (sourceFile == null) {
                HookUtils.log("DouSettings: syncFromModuleFile 模块 prefs 文件不存在或不可读")
                return
            }
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(sourceFile)
            val editor = local.edit()
            var count = 0
            val boolNodes = doc.getElementsByTagName("boolean")
            for (i in 0 until boolNodes.length) {
                val node = boolNodes.item(i)
                val key = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                val value = node.attributes.getNamedItem("value")?.nodeValue == "true"
                editor.putBoolean(key, value); count++
            }
            val stringNodes = doc.getElementsByTagName("string")
            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                val key = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                val value = node.textContent ?: ""
                editor.putString(key, value); count++
            }
            editor.apply()
            HookUtils.log("DouSettings: syncFromModuleFile 成功，同步 $count 个值 (${sourceFile.absolutePath})")
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: syncFromModuleFile 异常: ${t.message} (fileExists=$fileFound)")
        }
    }

    /**
     * 初始化设置管理器（模块设置页进程调用）
     */
    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    // 模块进程统一使用文件 prefs：与 SettingsProvider（抖音进程通过它跨进程读取）
                    // 读取的是同一份文件，保证设置即时生效。getRemotePreferences 走 LSPosed 数据库，
                    // 与 provider 读的文件不一致，故模块进程内不采用。
                    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
        isSettingsProcess = true
        // 记录 Context 并把 prefs 文件设为跨进程可读写（让抖音进程能读取/写入用户设置）。
        // 使用 Remote Preferences 时文件方式仅作兜底，不影响主链路。
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
                    putBoolean(KEY_AUTO_PLAY_FLOATING, DEFAULT_AUTO_PLAY_FLOATING)
                    putBoolean(KEY_AUTO_PLAY_HIDE, DEFAULT_AUTO_PLAY_HIDE)
                    putBoolean(KEY_SAVE_COMMENT_MEDIA, DEFAULT_SAVE_COMMENT_MEDIA)
                    putString(KEY_SAVE_DIRECTORY, DEFAULT_SAVE_DIRECTORY)
                    putString(KEY_AD_KEYWORDS, "")
                    putBoolean(KEY_IMMERSIVE_MODE, DEFAULT_IMMERSIVE_MODE)
                    putBoolean(KEY_BOOKMARK_ENABLED, DEFAULT_BOOKMARK_ENABLED)
                    putBoolean(KEY_BOOKMARK_COMMENT, DEFAULT_BOOKMARK_COMMENT)
                    putBoolean(KEY_BOOKMARK_VIDEO, DEFAULT_BOOKMARK_VIDEO)
                    putBoolean(KEY_BOOKMARK_PROFILE, DEFAULT_BOOKMARK_PROFILE)
                }.apply()
                // 文件刚创建，再次把目录与文件设为 world-readable
                makeWorldAccessible(context)
            }
        } catch (_: Throwable) {}
    }

    /**
     * 把模块自身 data 目录及 shared_prefs 文件设为其它进程（抖音）可访问。
     * 默认 /data/data/<module> 目录其它 app 无法遍历，需对目录加可执行位、对文件加可读位。
     *
     * 同时使用 Java API 和 Runtime.chmod（兜底，某些 ROM 上 Java API 静默失败）。
     */
    private fun makeWorldAccessible(context: Context) {
        try {
            val dataDir = context.applicationInfo.dataDir
            setAccessible(java.io.File(dataDir))
            chmod("$dataDir/shared_prefs", "755")
            chmod("$dataDir/shared_prefs/$PREFS_NAME.xml", "644")
            val prefsFile = java.io.File(dataDir, "shared_prefs/$PREFS_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }
        } catch (_: Throwable) {}
    }

    private fun chmod(path: String, mode: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", mode, path))
        } catch (_: Throwable) {}
    }

    private fun setAccessible(dir: java.io.File) {
        try {
            dir.setReadable(true, false)
            dir.setExecutable(true, false) // 目录需要可执行位才能被遍历
            chmod(dir.absolutePath, "755")
        } catch (_: Throwable) {}
    }

    /** 写入后同步把文件设为跨进程可读 */
    private fun share() {
        prefsContext?.let { makeWorldAccessible(it) }
    }

    private fun putBoolean(key: String, value: Boolean) {
        getPrefs().edit().putBoolean(key, value).apply()
    }

    private fun putString(key: String, value: String) {
        getPrefs().edit().putString(key, value).apply()
    }

    /**
     * 把设置页框架的 SharedPreferences 全量同步到模块 prefs。
     * 关键修复: 设置页把值存在框架自己的 prefs（com.xposed.doupp_preferences.xml），
     * safePref 仅在"值发生变更"时才镜像到模块 prefs。若用户上次会话已把
     * double_click_action 等设为非默认值，本次打开设置页（值未再变化）不会触发监听器，
     * 模块 prefs 就缺失该 key，抖音读到默认值。因此在每次打开设置页时全量同步一次。
     */
    fun syncFromFramework(frameworkSp: SharedPreferences) {
        try {
            val sp = prefs ?: return
            val all = frameworkSp.all
            if (all.isEmpty()) {
                HookUtils.log("DouSettings: syncFromFramework 框架 prefs 为空")
                return
            }
            val editor = sp.edit()
            var count = 0
            for ((k, v) in all) {
                // 只填充模块 prefs 中缺失的 key，不覆盖已存在的值。
                // 否则框架 prefs 的旧默认值会覆盖用户显式设置（如 auto_play），
                // 导致抖音读到错误的开关状态。
                if (sp.contains(k)) continue
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(k, v as Set<String>)
                    }
                }
                count++
            }
            editor.apply()
            share()
            HookUtils.log("DouSettings: syncFromFramework 补充 ${count} 个缺失值到模块 prefs")
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: syncFromFramework 异常: ${t.message}")
        }
    }

    /**
     * 把模块 prefs 同步到设置页框架 prefs，让 UI 显示模块的真实值。
     * 解决"设置页 UI 显示开启但模块实际是关闭"的两套 prefs 不同步问题。
     * 在打开设置页时调用。
     */
    fun syncFromModule(frameworkSp: SharedPreferences) {
        try {
            val sp = prefs ?: return
            val editor = frameworkSp.edit()
            var count = 0
            for ((k, v) in sp.all) {
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is String -> editor.putString(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Float -> editor.putFloat(k, v)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(k, v as Set<String>)
                    }
                }
                count++
            }
            editor.apply()
            HookUtils.log("DouSettings: syncFromModule 同步 ${count} 个值到框架 prefs")
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: syncFromModule 异常: ${t.message}")
        }
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

            // 策略0（首选）: LibXposed API 101 官方跨进程桥接
            // getRemotePreferences 由 LSPosed 框架直接桥接模块的 SharedPreferences，
            // 无需 world-readable 文件权限，也不依赖 ContentProvider 常驻。
            try {
                val remote = com.xposed.doupp.compat.XposedBridge.getRemotePreferences(PREFS_NAME)
                if (remote != null && remote.all.isNotEmpty()) {
                    prefs = remote
                    HookUtils.log("DouSettings: 远程 SharedPreferences 初始化成功 (getRemotePreferences, ${remote.all.size} keys)")
                    return@synchronized
                }
                if (remote != null) {
                    HookUtils.log("DouSettings: 远程 prefs 为空，跳过，尝试其它策略")
                } else {
                    HookUtils.log("DouSettings: getRemotePreferences 不可用，尝试其它策略")
                }
            } catch (t: Throwable) {
                HookUtils.log("DouSettings: getRemotePreferences 失败: ${t.message}")
            }

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

            // 策略0: 已移除 — API 101 不再注入 legacy de.robv.android.xposed.XSharedPreferences，
            // 跨进程读取由策略A(直接读文件)/策略1(ContentProvider) 覆盖。

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

            // 策略4（兜底）: 使用抖音进程本地的 SharedPreferences
            // 当上述所有跨进程策略都失败时（模块进程未运行、XSharedPreferences 不可用、
            // 文件跨 UID 不可读），使用宿主（抖音）自身 data 目录下的 prefs 文件。
            // 此文件由本 hook 进程创建并维护，天然可读可写。
            try {
                val ctx = localContext ?: com.xposed.doupp.util.ContextHelper.getContext()
                if (ctx != null) {
                    val localSp = ctx.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                    prefs = localSp
                    HookUtils.log("DouSettings: 抖音本地 SharedPreferences 初始化成功")
                    // 尝试从模块文件同步初始值（静默失败不影响启动）
                    syncFromModuleFile()
                    return@synchronized
                }
            } catch (t: Throwable) {
                HookUtils.log("DouSettings: 本地 SharedPreferences 策略失败: ${t.message}")
            }

            // 策略5: 所有策略失败，兜底 DefaultPrefs 避免每次 getPrefs 都重试 ContentProvider 刷屏
            HookUtils.log("DouSettings: 所有策略失败，使用默认值")
            prefs = DefaultPrefs()
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
                } else if (className.contains("SharedPreferencesImpl")) {
                    // 设置页进程的 prefs 就是模块文件本身，必须保持可写，禁止替换为只读 ProviderPrefs
                    if (isSettingsProcess) {
                        return
                    }
                    // 本地 SharedPreferences 兜底：尝试通过 ContentProvider 获取最新设置
                    //（模块进程可能已被 settings 页面或 KeepAliveService 启动）。
                    // 若 ContentProvider 可用，切换到 ProviderPrefs 实现实时读取。
                    if (tryInitFromProvider()) {
                        prefs = ProviderPrefs()
                        HookUtils.log("DouSettings: ContentProvider 可用，切换到实时模式")
                    }
                    // 若 ContentProvider 不可用，尝试从模块文件同步
                    syncFromModuleFile()
                }
                Unit
            }
        } catch (_: Throwable) { }
    }

    private fun getPrefs(): SharedPreferences {
        // 如果尚未初始化，尝试用默认值
        val p = prefs
        if (p != null) {
            // DefaultPrefs 兜底也要定期重试：init 时 context 为 null 导致
            // ContentProvider/本地 prefs 均失败，但应用启动后 Context 就绪、
            // 模块进程运行后 Provider 可用，应切换到实时模式。
            if (p is DefaultPrefs) {
                val now = System.currentTimeMillis()
                if (now - lastProviderRefresh > PROVIDER_REFRESH_MS) {
                    lastProviderRefresh = now
                    try {
                        if (tryInitFromProvider()) {
                            prefs = ProviderPrefs()
                            HookUtils.log("DouSettings: DefaultPrefs->ContentProvider 升级成功")
                        } else {
                            val ctx = localContext ?: com.xposed.doupp.util.ContextHelper.getContext()
                            if (ctx != null) {
                                val localSp = ctx.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                                prefs = localSp
                                HookUtils.log("DouSettings: DefaultPrefs->本地 SharedPreferences 升级成功")
                                syncFromModuleFile()
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
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
            // 本地 SharedPreferences 兜底：定期尝试通过 ContentProvider 或文件同步
            if (p is SharedPreferences && p.javaClass.name.contains("SharedPreferencesImpl")) {
                // 设置页进程的 prefs 就是模块文件本身，必须保持可写，禁止替换为只读 ProviderPrefs
                if (!isSettingsProcess) {
                    val now = System.currentTimeMillis()
                    if (now - lastProviderRefresh > PROVIDER_REFRESH_MS) {
                        lastProviderRefresh = now
                        if (tryInitFromProvider()) {
                            prefs = ProviderPrefs()
                            HookUtils.log("DouSettings: ContentProvider 可用，切换到实时模式")
                        } else {
                            syncFromModuleFile()
                        }
                    }
                }
            }
            // 可能已被上面的升级逻辑替换，返回最新 prefs
            return prefs ?: p
        }
        // 懒加载：运行时若 Context 已可用，尝试通过 ContentProvider 或本地 prefs 初始化
        if (tryInitFromProvider()) {
            prefs = ProviderPrefs()
            lastProviderRefresh = System.currentTimeMillis()
            return prefs!!
        }
        // 尝试初始化抖音本地 SharedPreferences（localContext 此时可能已就绪）
        try {
            val ctx = localContext ?: com.xposed.doupp.util.ContextHelper.getContext()
            if (ctx != null) {
                val localSp = ctx.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
                prefs = localSp
                lastProviderRefresh = System.currentTimeMillis()
                syncFromModuleFile()
                return prefs!!
            }
        } catch (_: Throwable) {}
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
                return false
            }
            val b = context.contentResolver.call(
                Uri.parse("content://$MODULE_PACKAGE.settings"), "getAll", null, null
            )
            if (b != null && !b.isEmpty) {
                providerBundle = b
                HookUtils.log("DouSettings: ContentProvider 初始化成功 (keys=${b.size()})")
                return true
            }
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: ContentProvider 方式失败: ${t.javaClass.name}: ${t.message}")
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
                // 把 provider 权威值同步到抖音本地 prefs，供下次进程启动早期（provider 未就绪时）兜底使用。
                // 跨 UID 直接读模块文件被 SELinux 拦截，本地 prefs 是唯一可靠的启动早期通道。
                syncProviderToLocal(b)
            }
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: ContentProvider 刷新失败: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 把 ContentProvider 读取到的权威设置写入抖音本地 prefs（LOCAL_PREFS_NAME）。
     * 抖音进程下次启动时，即使模块进程/ContentProvider 尚未就绪，
     * isAutoPlayEnabled() 的本地 fallback 也能读到正确的值，避免官方误取消连播。
     */
    private fun syncProviderToLocal(b: Bundle) {
        try {
            val ctx = localContext ?: com.xposed.doupp.util.ContextHelper.getContext() ?: return
            val local = ctx.getSharedPreferences(LOCAL_PREFS_NAME, Context.MODE_PRIVATE)
            val editor = local.edit()
            var count = 0
            val keys = listOf(
                KEY_AUTO_PLAY, KEY_AUTO_PLAY_FLOATING, KEY_AUTO_PLAY_HIDE,
                KEY_DOWNLOAD_VIDEO, KEY_DOWNLOAD_MUSIC, KEY_DOWNLOAD_IMAGE,
                KEY_COPY_TEXT, KEY_REMOVE_AD, KEY_SKIP_SPLASH_AD,
                KEY_BLOCK_FEED_KEYWORDS, KEY_BLOCK_SHOPPING, KEY_HIDE_AD_LABELS,
                KEY_BLOCK_AD_SDK, KEY_BLOCK_HOT_UPDATE, KEY_SAVE_COMMENT_MEDIA,
                KEY_VIDEO_FILTER, KEY_FILTER_LIVE, KEY_FILTER_IMAGE,
                KEY_FILTER_AD, KEY_FILTER_LONG_VIDEO, KEY_BOOKMARK_ENABLED,
                KEY_BOOKMARK_COMMENT, KEY_BOOKMARK_VIDEO, KEY_BOOKMARK_PROFILE,
                KEY_REGION_UNLOCK, KEY_AUTO_SIGNIN, KEY_ANTI_REVOKE, KEY_LUCKY_BAG
            )
            for (key in keys) {
                if (b.containsKey(key)) {
                    when (val v = b.get(key)) {
                        is Boolean -> editor.putBoolean(key, v)
                        is Int -> editor.putInt(key, v)
                        is Long -> editor.putLong(key, v)
                        is String -> editor.putString(key, v)
                        else -> {}
                    }
                    count++
                }
            }
            // 字符串/整数类设置也一并同步
            for (key in listOf(KEY_SAVE_DIRECTORY, KEY_AD_KEYWORDS, KEY_FILTER_KEYWORDS, KEY_LONG_VIDEO_SECONDS, KEY_DOUBLE_CLICK_ACTION)) {
                if (b.containsKey(key)) {
                    when (val v = b.get(key)) {
                        is String -> editor.putString(key, v)
                        is Int -> editor.putInt(key, v)
                        else -> {}
                    }
                    count++
                }
            }
            editor.apply()
            if (count > 0) HookUtils.log("DouSettings: 已同步 $count 个设置到本地 prefs")
        } catch (_: Throwable) {}
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

    /** ContentProvider 读取缓存（避免频繁 IPC）。1s TTL 在实时性与性能间折中。 */
    @Volatile
    private var cachedAutoPlayFromProvider: Boolean? = null
    @Volatile
    private var lastAutoPlayProviderRead = 0L
    private const val AUTOPLAY_PROVIDER_TTL_MS = 1000L

    fun isAutoPlayEnabled(): Boolean {
        // 0. 悬浮按钮显示时，由按钮独立开关状态决定（不再读设置的 auto_play）
        if (autoPlayButtonShown) {
            val btnState = isAutoPlayButtonEnabled()
            HookUtils.log("DouSettings: isAutoPlayEnabled -> $btnState (悬浮按钮)")
            return btnState
        }

        // 1. 运行期内存值（设置页/悬浮按钮同进程内切换立即生效，仅会话级，不跨进程）。
        autoPlayRuntime?.let { return it }

        // 2. ContentProvider（跨进程权威源）。带 1s 缓存避免频繁 IPC。
        val now = System.currentTimeMillis()
        val cached = cachedAutoPlayFromProvider
        if (cached != null && now - lastAutoPlayProviderRead < AUTOPLAY_PROVIDER_TTL_MS) {
            return cached
        }
        val fromProvider = readAutoPlayFromProvider()
        if (fromProvider != null) {
            cachedAutoPlayFromProvider = fromProvider
            lastAutoPlayProviderRead = now
            HookUtils.log("DouSettings: isAutoPlayEnabled -> $fromProvider (ContentProvider)")
            return fromProvider
        }

        // 3. 本地 SharedPreferences 兜底（通过 getPrefs 多种策略初始化）。
        val fromPrefs = getPrefs().getBoolean(KEY_AUTO_PLAY, DEFAULT_AUTO_PLAY)
        HookUtils.log("DouSettings: isAutoPlayEnabled -> $fromPrefs (prefs fallback)")
        return fromPrefs
    }

    // ==================== 悬浮按钮独立开关 ====================

    /** 悬浮按钮当前是否显示（由 AutoPlayButtonHook 在注入/移除时维护） */
    @Volatile
    private var autoPlayButtonShown = false

    fun setAutoPlayButtonShown(shown: Boolean) {
        autoPlayButtonShown = shown
    }

    fun isAutoPlayButtonShown(): Boolean = autoPlayButtonShown

    /**
     * 悬浮按钮的独立开关状态。优先运行期内存值，其次持久化文件。
     * 与设置里的 auto_play 完全独立，互不干扰。
     */
    fun isAutoPlayButtonEnabled(): Boolean {
        autoPlayButtonRuntime?.let { return it }
        try {
            val f = autoPlayButtonFile()
            if (f.exists()) {
                val v = f.readText().trim()
                if (v == "1" || v == "0") return v == "1"
            }
        } catch (_: Throwable) {}
        return DEFAULT_AUTO_PLAY_BUTTON
    }

    fun setAutoPlayButton(enabled: Boolean) {
        autoPlayButtonRuntime = enabled
        HookUtils.log("DouSettings: setAutoPlayButton($enabled)")
        try {
            val f = autoPlayButtonFile()
            f.parentFile?.let {
                it.setReadable(true, false)
                it.setExecutable(true, false)
                it.setWritable(true, false)
            }
            f.writeText(if (enabled) "1" else "0")
            f.setReadable(true, false)
            f.setWritable(true, false)
        } catch (_: Throwable) {}
    }

    /** 通过 ContentProvider 读取 KEY_AUTO_PLAY。返回 null 表示不可用。 */
    private fun readAutoPlayFromProvider(): Boolean? {
        return try {
            val context = com.xposed.doupp.util.ContextHelper.getContext() ?: return null
            val b = context.contentResolver.call(
                Uri.parse("content://$MODULE_PACKAGE.settings"), "getAll", null, null
            ) ?: return null
            if (b.containsKey(KEY_AUTO_PLAY)) b.getBoolean(KEY_AUTO_PLAY) else null
        } catch (t: Throwable) {
            HookUtils.log("DouSettings: readAutoPlayFromProvider 失败: ${t.message}")
            null
        }
    }

    // ==================== 评论区 ====================

    fun isSaveCommentMedia(): Boolean =
        getPrefs().getBoolean(KEY_SAVE_COMMENT_MEDIA, DEFAULT_SAVE_COMMENT_MEDIA)

    // ==================== 自动播放 ====================

    fun isAutoPlayFloating(): Boolean =
        getPrefs().getBoolean(KEY_AUTO_PLAY_FLOATING, DEFAULT_AUTO_PLAY_FLOATING)

    fun isAutoPlayHide(): Boolean =
        getPrefs().getBoolean(KEY_AUTO_PLAY_HIDE, DEFAULT_AUTO_PLAY_HIDE)

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
        HookUtils.log("DouSettings: setAutoPlay($enabled)")
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

    fun setAutoPlayFloating(enabled: Boolean) =
        putBoolean(KEY_AUTO_PLAY_FLOATING, enabled)

    fun setAutoPlayHide(enabled: Boolean) =
        putBoolean(KEY_AUTO_PLAY_HIDE, enabled)

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

    /** 诊断: 打印当前 prefs 实际读到的所有 key 与 double_click_action 值 */
    fun debugPrefsKeys(): String {
        return try {
            val p = prefs
            if (p == null) return "prefs=null"
            val all = p.all
            val dca = p.getString(KEY_DOUBLE_CLICK_ACTION, null)
            "type=${p.javaClass.simpleName} keys=${all.size} double_click_action=${dca}"
        } catch (t: Throwable) {
            "err=${t.message}"
        }
    }

    fun setDoubleClickAction(action: String) =
        putString(KEY_DOUBLE_CLICK_ACTION, action)

    // ==================== 沉浸式播放 ====================

    /**
     * 沉浸式纯净播放开关。
     * 播放视频时隐藏视频画面以外的抖音界面与系统栏，只保留视频画面；
     * 暂停后恢复完整界面并显示悬浮下载按钮。
     * 默认关闭（避免影响其它功能）。
     */
    fun isImmersiveModeEnabled(): Boolean =
        getPrefs().getBoolean(KEY_IMMERSIVE_MODE, DEFAULT_IMMERSIVE_MODE)

    fun setImmersiveMode(enabled: Boolean) =
        putBoolean(KEY_IMMERSIVE_MODE, enabled)

    // ==================== 书签 ====================

    fun isBookmarkEnabled(): Boolean =
        getPrefs().getBoolean(KEY_BOOKMARK_ENABLED, DEFAULT_BOOKMARK_ENABLED)

    fun isCommentBookmarkEnabled(): Boolean =
        isBookmarkEnabled() && getPrefs().getBoolean(KEY_BOOKMARK_COMMENT, DEFAULT_BOOKMARK_COMMENT)

    fun isVideoBookmarkEnabled(): Boolean =
        isBookmarkEnabled() && getPrefs().getBoolean(KEY_BOOKMARK_VIDEO, DEFAULT_BOOKMARK_VIDEO)

    fun isProfileBookmarkEnabled(): Boolean =
        isBookmarkEnabled() && getPrefs().getBoolean(KEY_BOOKMARK_PROFILE, DEFAULT_BOOKMARK_PROFILE)

    fun setBookmarkEnabled(enabled: Boolean) =
        putBoolean(KEY_BOOKMARK_ENABLED, enabled)

    fun setCommentBookmark(enabled: Boolean) =
        putBoolean(KEY_BOOKMARK_COMMENT, enabled)

    fun setVideoBookmark(enabled: Boolean) =
        putBoolean(KEY_BOOKMARK_VIDEO, enabled)

    fun setProfileBookmark(enabled: Boolean) =
        putBoolean(KEY_BOOKMARK_PROFILE, enabled)

    // ==================== 自动续火花 ====================

    fun isSparkEnabled(): Boolean =
        getPrefs().getBoolean(KEY_SPARK_ENABLED, DEFAULT_SPARK_ENABLED)

    fun setSparkEnabled(enabled: Boolean) =
        putBoolean(KEY_SPARK_ENABLED, enabled)

    fun getSparkMessage(): String =
        getPrefs().getString(KEY_SPARK_MESSAGE, DEFAULT_SPARK_MESSAGE) ?: DEFAULT_SPARK_MESSAGE

    fun setSparkMessage(message: String) =
        putString(KEY_SPARK_MESSAGE, message)

    // ==================== 地区解锁 ====================

    fun isRegionUnlockEnabled(): Boolean =
        getPrefs().getBoolean(KEY_REGION_UNLOCK, DEFAULT_REGION_UNLOCK)

    fun setRegionUnlock(enabled: Boolean) =
        putBoolean(KEY_REGION_UNLOCK, enabled)

    // ==================== 自动签到 ====================

    fun isAutoSignInEnabled(): Boolean =
        getPrefs().getBoolean(KEY_AUTO_SIGNIN, DEFAULT_AUTO_SIGNIN)

    fun setAutoSignIn(enabled: Boolean) =
        putBoolean(KEY_AUTO_SIGNIN, enabled)

    // ==================== 消息防撤回 ====================

    fun isAntiRevokeEnabled(): Boolean =
        getPrefs().getBoolean(KEY_ANTI_REVOKE, DEFAULT_ANTI_REVOKE)

    fun setAntiRevoke(enabled: Boolean) =
        putBoolean(KEY_ANTI_REVOKE, enabled)

    // ==================== 福袋自动领取 ====================

    fun isLuckyBagEnabled(): Boolean =
        getPrefs().getBoolean(KEY_LUCKY_BAG, DEFAULT_LUCKY_BAG)

    fun setLuckyBag(enabled: Boolean) =
        putBoolean(KEY_LUCKY_BAG, enabled)

}

