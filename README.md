# DYPP — 抖音增强工具

基于 LSPosed 框架的 Xposed 模块，为抖音（支持 39.6.0 ~ 39.70+）提供无水印保存和增强功能。

## 特点

在抖音**分享面板底部**注入功能按钮栏：

| 功能 | 说明 |
|------|------|
| **无水印下载视频** | 自动去除水印，保存到相册 |
| **下载音乐 MP3** | 提取视频背景音乐并保存为 MP3 |
| **下载图片** | 图集内容逐张下载无水印图片 |
| **复制文案** | 一键复制视频描述文案 |
| **模块设置** | 打开完整设置页面 |

设置页支持：

### 🚫 去除广告（总开关）
- 跳过开屏广告
- 信息流关键词屏蔽（屏蔽含广告关键词的视频）
- 屏蔽购物视频（带货/小黄车/橱窗等）
- 隐藏广告标签角标
- 拦截广告 SDK 初始化
- 自定义广告关键词
- 屏蔽热更新（防止模块被覆盖）

### 🎬 视频过滤（总开关）
- 过滤直播内容
- 过滤图文/图集
- 过滤广告/推广内容
- 过滤长视频（可自定义时长阈值）
- 自定义过滤关键词

### 💬 评论区
- 保存评论区图片到相册

### ⚡ 双击自定义
- 双击屏幕行为可设：点赞 / 评论 / 分享 / 无操作

### ▶️ 自动播放
- 播放页悬浮按钮控制自动连播，设置页无开关

### 📁 存储
- 自定义保存目录

## 适配

- 抖音 39.6.0 ~ 39.70.0+（自动适配 R8 混淆 yyds 包）
- Android 9.0+ (API 28)
- LSPosed 框架 (1.8+ 或 2.0.x)

### 自动适配机制

模块内置 **AdaptationManager**，在首次运行时自动检测抖音版本并执行以下扫描：
1. **Aweme 类** — 通过 DexKit 字符串特征或 ClassFinder 候选列表定位
2. **yyds 混淆包** — 新版抖音 R8 全量混淆后所有应用类在 yyds 包下
3. **分享面板** — 通过 layout/share_bottom_sheet + share_hsv 等 ID 定位
4. **AutoPlayController** — 通过 auto_play_key 字符串特征定位
5. **网络库** — 自动检测 Cronet / OkHttp / ExoPlayer 等版本
6. **热更新框架** — 检测字节跳动自研 / Tinker / Sophix 等
7. **广告 SDK** — 检测穿山甲 / 广点通 / 快手 / 百度 等

适配结果缓存到文件，下次启动直接读取，无需重复扫描。

## 技术架构

- **语言**: Kotlin + Xposed API
- **跨进程配置**: XSharedPreferences + ContentProvider
- **去水印**: 多策略 URL 替换（playwm→play / 水印参数清除 / CDN 适配）
- **类查找**: DexKit 动态搜索 + 硬编码候选列表双兜底
- **延迟加载**: Application.attach Hook + 双重安装策略
- **媒体保存**: MediaStore API (Scoped Storage 兼容 Android 10+)

## 安装

1. 下载 [最新 Release APK](https://github.com/andyhang1980/doupp/releases)
2. 安装 APK（需允许未知来源）
3. 打开 LSPosed 管理器 → 模块 → 启用 **DYPP**
4. 勾选作用域：**抖音**
5. 强制停止抖音或重启手机
6. 打开抖音，在视频分享面板底部即可看到功能按钮

## 构建与发布

### 开发构建

```bash
gradle assembleDebug
```

输出文件：`app/build/outputs/apk/debug/DYPP-<version>.apk`

### 签名发布

```bash
# 设置签名环境变量
$env:KEYSTORE_FILE = "keystore.jks"
$env:KEYSTORE_PASSWORD = "<store密码>"
$env:KEY_ALIAS = "<别名>"
$env:KEY_PASSWORD = "<密钥密码>"

# 构建 release APK（自动签名）
gradle assembleRelease
```

输出文件：`app/build/outputs/apk/release/DYPP-<version>.apk`

### 新建 keystore

```bash
keytool -genkey -v -keystore app/keystore.jks -alias <别名> -keyalg RSA -keysize 2048 -validity 36500
```

### GitHub Release 流程

```bash
# 1. 设置版本号
#   app/build.gradle.kts → versionName / versionCode

# 2. 构建 APK
$env:KEYSTORE_PASSWORD = "<密码>"
$env:KEY_ALIAS = "<别名>"
$env:KEY_PASSWORD = "<密码>"
gradle assembleRelease

# 3. 提交并推送代码
git add -A
git commit -m "v<版本号>: ..."
git push doupp main

# 4. 打标签
git tag v<版本号>
git push doupp v<版本号>

# 5. 创建 Release 并上传 APK
$env:GH_TOKEN = "<GitHub个人访问令牌>"
gh release create v<版本号> "app\build\outputs\apk\release\DYPP-<版本号>.apk" `
  --repo andyhang1980/doupp `
  --title "DYPP v<版本号>" `
  --notes "DYPP v<版本号>"

# 6. 查看所有旧 Release
gh release list --repo andyhang1980/doupp

# 7. 删除旧 Release
$tags = gh release list --repo andyhang1980/doupp --json tagName | ConvertFrom-Json | ForEach-Object { $_.tagName }
foreach ($tag in $tags) {
  gh release delete $tag --repo andyhang1980/doupp --yes
}

# 8. 删除旧本地标签
git tag -l "v1.0.*" | ForEach-Object { git tag -d $_ }

# 9. 删除旧远端标签
git push doupp --delete "refs/tags/v1.0.0" "refs/tags/v1.0.1" # ... 全部旧标签

# 10. 查看 Release
gh release view v<版本号> --repo andyhang1980/doupp --json assets
```

## License

MIT
