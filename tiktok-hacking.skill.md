---
name: tiktok-hacking
description: TikTok Xposed 模块逆向与 Hook 开发工作流程。解包 → 查找混淆类/方法 → 编写 Kotlin Hook → 添加 UI 按钮 → 调试 → 发布。
---

# TikTok Xposed 模块逆向开发流程

## 1. 环境准备

### 工具链
- **adb** — 设备通信、安装 APK、拉取文件
- **jadx-gui** — 反编译 APK，搜索混淆类/方法/字符串
- **Android Studio** — Kotlin 项目编辑与 Gradle 构建
- **MT 管理器** (手机上) — 快速查看 DEX 类结构和资源
- **LSPosed** — Xposed 框架
- **DexKit** (项目已集成) — 运行时动态搜索类

### 设备调试
```powershell
# 安装 APK
E:\ADB\adb.exe install -r path\to\DYPP-<version>.apk

# 强制停止抖音（使 Xposed 模块重载）
E:\ADB\adb.exe shell am force-stop com.ss.android.ugc.aweme

# 查看 Xposed 日志
E:\ADB\adb.exe logcat -s "Dou+"

# 打开模块设置页
E:\ADB\adb.exe shell am start -n com.xposed.doupp/.ui.SettingsActivity
```

## 2. 反编译与类查找

### 提取并反编译抖音 APK
```powershell
# 从设备拉取抖音 APK
E:\ADB\adb.exe shell pm path com.ss.android.ugc.aweme
E:\ADB\adb.exe pull /data/app/com.ss.android.ugc.aweme-xxx/base.apk

# 用 jadx-gui 打开 base.apk 进行反编译分析
```

### jadx-gui 搜索策略
| 目标 | 搜索关键词 |
|------|-----------|
| 分享面板类 | `share`, `SharePanel`, `MorePanel`, `Share`, `Panel` |
| 下载相关 | `Download`, `download`, `VideoSave`, `Save` |
| 广告相关 | `Ad`, `ad`, `Advertisement`, `SplashAd` |
| 视频相关 | `Video`, `Aweme`, `Feed`, `Play` |
| 评论 | `Comment`, `comment`, `Reply` |
| 弹窗/对话框 | `Dialog`, `BottomSheet`, `Popup` |

### 查找策略（从已知类反推）
```
已知: SharePanelHook 注入按钮到分享面板底部

搜索路径:
1. 在 jadx 搜 "分享"（中文资源字符串）→ 找到分享面板布局/类
2. 在 jadx 搜 share_panel / more_panel → 找到布局 ID
3. 从布局 ID 找到对应的 Activity/Fragment
4. 分析 onCreateView / initView → 找到容器 ViewGroup
5. 在模块中反射/直接引用该容器，addView 注入按钮
```

### 查找 Hook 点的策略

```
已知功能 → 猜测类名 → jadx 验证 → 编写 Hook

例: 跳过开屏广告
1. 现象: 开屏有"跳过"按钮
2. 搜索: "跳过"（中文）/ "skip" / "SplashActivity" / "AdActivity"
3. 找到 SplashAdActivity / AdContainer
4. 分析生命周期 → hook onCreate/onStart 自动 finish()
```

## 3. Kotlin Hook 开发模式

### BaseHook 模板
```kotlin
class MyFeatureHook : BaseHook {
    companion object {
        private const val TAG = "MyFeatureHook"
        private var installed = false
    }
    override fun tag() = TAG
    override fun isInstalled(): Boolean = installed
    override fun init(classLoader: ClassLoader) {
        if (installed) return
        // 安装 Hook
        installed = true
    }
}
```

### 在 MainHook 注册
```kotlin
// app/src/main/java/com/xposed/doupp/MainHook.kt
private fun createHooks(): List<BaseHook> {
    return listOf(
        FeedHook(),
        SharePanelHook(),
        MyFeatureHook(),  // ← 添加到这里
        ...
    )
}
```

### Hook 方式

#### hookAllBefore — 在方法执行前拦截
```kotlin
HookUtils.hookAllBefore(targetClass, "methodName") { param ->
    // 修改参数 / 阻止执行
    param.result = null  // 阻止原方法
}
```

#### hookAllAfter — 在方法执行后读取
```kotlin
HookUtils.hookAllAfter(targetClass, "methodName") { param ->
    val result = param.result
    // 修改返回值
    param.result = modifiedResult
}
```

#### XposedBridge.hookMethod — 通用 Hook
```kotlin
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodHook

XposedBridge.hookMethod(method, object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) { }
    override fun afterHookedMethod(param: MethodHookParam) { }
})
```

#### safeHook — 项目封装的异常安全 Hook
```kotlin
HookUtils.safeHook {
    // 这里抛异常不会导致模块崩溃，只会记录日志
    val clazz = Class.forName(className, false, classLoader)
    // ...
}
```

### 反射调用
```kotlin
import de.robv.android.xposed.XposedHelpers

// 调用方法
XposedHelpers.callMethod(obj, "methodName", arg1, arg2)
// 获取字段
XposedHelpers.getObjectField(obj, "fieldName")
// 设置字段
XposedHelpers.setObjectField(obj, "fieldName", value)
// 查找类
val clazz = XposedHelpers.findClass("com.example.Cls", classLoader)
```

### 使用 DexKit 运行时搜索混淆类
```kotlin
// 搜索包含特定字符串的类
val candidates = DexKitManager.findClassesByStrings(listOf("auto_play_key"))
HookUtils.log("$TAG: DexKit 候选: $candidates")

// 搜索方法名
val methodName = DexKitManager.findMethodNameByStrings(clazz.name, listOf("keyword"))
```

## 4. 添加 UI 按钮

### 在分享面板添加按钮（SharePanelHook）
```kotlin
// app/src/main/java/com/xposed/doupp/hook/SharePanelHook.kt

// 1. 定义按钮常量
private const val ICON_MY_FEATURE = "ic_my_feature"
private val MY_COLOR = Color.parseColor("#FF5733")

// 2. 在 buttonConfigs 中添加配置
val myConfig = if (DouSettings.isMyFeatureEnabled()) {
    arrayOf(MY_COLOR, ICON_MY_FEATURE, Color.WHITE, "我的功能", Runnable { doSomething(context) })
} else null

val buttonConfigs = listOfNotNull(
    videoConfig, musicConfig, imageConfig, copyConfig,
    myConfig,  // ← 添加到按钮列表
    settingsConfig
)
```

### 添加设置项

#### a) strings.xml — 定义资源字符串
```xml
<string name="pref_my_feature_title">我的功能</string>
<string name="pref_my_feature_summary">功能描述文字</string>
```

#### b) prefs.xml — 添加设置 UI
```xml
<CheckBoxPreference
    android:key="my_feature"
    android:title="@string/pref_my_feature_title"
    android:summary="@string/pref_my_feature_summary"
    android:defaultValue="true" />
```

#### c) DouSettings.kt — 添加常量 + getter + setter + 默认值
```kotlin
// 常量
const val KEY_MY_FEATURE = "my_feature"
// 默认值
private const val DEFAULT_MY_FEATURE = true
// getter
fun isMyFeatureEnabled(): Boolean =
    getPrefs().getBoolean(KEY_MY_FEATURE, DEFAULT_MY_FEATURE)
// setter
fun setMyFeatureEnabled(enabled: Boolean) =
    putBoolean(KEY_MY_FEATURE, enabled)
// saveDefaults
putBoolean(KEY_MY_FEATURE, DEFAULT_MY_FEATURE)
```

#### d) SettingsActivity.kt — 添加监听器
```kotlin
findPreference("my_feature")?.setOnPreferenceChangeListener { _, newValue ->
    DouSettings.setMyFeatureEnabled(newValue as Boolean); true
}
```

#### e) 总开关→子项联动
```kotlin
// 如果 my_feature 是某个总开关的子项:
fun isMyFeatureEnabled(): Boolean =
    isParentEnabled() && getPrefs().getBoolean(KEY_MY_FEATURE, DEFAULT_MY_FEATURE)

// SettingsActivity 中:
findPreference("parent_switch")?.setOnPreferenceChangeListener { _, newValue ->
    val on = newValue as Boolean
    DouSettings.setParentEnabled(on)
    updateMyFeatureChildrenEnabled(on)
    true
}
```

## 5. 构建与调试

### 构建
```powershell
# 开发调试
gradle assembleDebug

# 签名发布
$env:KEYSTORE_PASSWORD = "<密码>"
$env:KEY_ALIAS = "<别名>"
$env:KEY_PASSWORD = "<密码>"
gradle assembleRelease
```

### 调试流程
```
1. 修改 Kotlin 代码
2. gradle assembleDebug
3. adb install -r app-debug.apk
4. adb shell am force-stop com.ss.android.ugc.aweme
5. 打开抖音，复现功能
6. adb logcat -s "Dou+"  查看模块日志
7. 如果报错/崩溃，查看 logcat 完整日志定位问题
```

### 编译错误处理
```
常见错误:
- "Type mismatch" → 检查类型转换（CharSequence vs String, Any? vs 具体类型）
- "Unresolved reference" → 缺少 import
- "ClassNotFoundException" → 类名不对或未在运行时找到，safeHook 会捕获不崩溃
- 确认 Hook 方法签名（参数类型）是否匹配
```

## 6. GitHub 发布

完整流程见 README.md 的「构建与发布」章节。

要点:
```powershell
# 设置 GitHub Token
$env:GH_TOKEN = "ghp_xxxxx"

# 创建 Release
gh release create v<版本> app/build/outputs/apk/release/DYPP-<版本>.apk --repo andyhang1980/doupp --title "DYPP v<版本>"

# 删除旧 Release
gh release list --repo andyhang1980/doupp --json tagName | ConvertFrom-Json | ForEach-Object { gh release delete $_.tagName --repo andyhang1980/doupp --yes }
```

## 7. 常见模式

### 去水印下载
```
流程: FeedHook 缓存 Aweme → SharePanelHook 按钮点击 → MediaCache.getCurrentAweme()
→ MediaCache.getVideoUrlFromAweme(aweme) → UrlParser.getNoWatermarkUrl(url) → MediaDownloader.download()
```

### 设置开关控制按钮显示
```
SharePanelHook 中通过 DouSettings.isXxxEnabled() 决定是否注入按钮:
val config = if (DouSettings.isFeatureEnabled()) { arrayOf(...) } else null
val buttonConfigs = listOfNotNull(config, ...)
```

### 跨进程通信
```
模块设置页（独立进程）→ XSharedPreferences / ContentProvider → Hook 进程（抖音进程）读取设置
DouSettings.initForHookProcess() 在 Hook 进程中初始化
DouSettings.init(activity) 在设置页进程中初始化
```

### 删除设置项
```
删除一个设置项需要修改 4 个文件:
1. prefs.xml — 删除 Preference 条目
2. strings.xml — 删除对应字符串
3. DouSettings.kt — 删除 KEY / getter / setter / 默认值 / saveDefaults
4. SettingsActivity.kt — 删除 setOnPreferenceChangeListener
```
