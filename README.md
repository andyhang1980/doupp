# DYPP — 抖音增强工具

基于 LSPosed 框架的 Xposed 模块，为抖音（支持 39.6.0 ~ 40.0.0，40.0.0 已适配，39.8.0 已验证）提供无水印保存和增强功能。

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
- 信息流关键词屏蔽（屏蔽含广告关键词的视频；内置词只剩纯广告词，商品卡/小黄车面板整棵子树不扫描）
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

### 📺 直播
- 直播间清爽模式：把直播间里的电商盒子与直播广告挂件换成空占位，房间布局不变（默认关）

### 🛒 电商管理（抖音商城）
- 拦截商城营销弹窗（`MallDrainageDialog` / `MallDrainageLynxDialog`）
- 隐藏商城推送横幅（`ShoppingInnerPushWidget` / `ShoppingMallLowerPriceWidget`）
- **商品卡与小黄车一律不碰** —— 商品永远能正常看、能加购

### 💬 评论区
- 保存评论区图片到相册

### ⚡ 双击自定义
- 双击屏幕行为可设：点赞 / 评论 / 分享 / 无操作

### ▶️ 自动播放
- 播放页悬浮按钮控制自动连播，设置页无开关

### 📁 存储
- 自定义保存目录

## 适配

- 抖音 40.7.0 已实测适配（55 个 dex / 437MB 全量扫描验证）
- Android 9.0+ (API 28)
- LSPosed 框架 (1.8+ 或 2.0.x)

### 自动适配机制

模块内置 **AdaptationManager**，在首次运行时自动检测抖音版本并执行以下扫描：
1. **Aweme 类** — 候选列表优先，`com.ss.android.ugc.aweme.feed.model.Aweme` 在 40.7.0 仍可用
2. **混淆包发现** — 动态识别 R8 混淆出的包根，不写死包名
3. **分享面板** — 通过 `share_panel` / `share_bottom` / `SharePanel` 字符串特征 + presenter 类锚点定位
4. **AutoPlayController** — 语义类名锚定 + `auto_play_key` 字符串特征（读 dex 字符串池）
5. **网络库** — 自动检测 ttnet(Cronet) / OkHttp / 自研播放器
6. **热更新框架** — 检测字节 ies patch / Tinker / Sophix
7. **广告 SDK** — 检测穿山甲 / 广点通 / 快手 / 百度

适配结果缓存到文件（带 APK 指纹，同版本号重打包会自动重扫），下次启动直接读取。

### 40.7.0 实测结果（全量扫描 55 个 dex 得出）

| 探测项 | 结果 |
|---|---|
| `Lyyds/` 混淆包 | **不存在**。应用类被打散进 `X` / `Y` / `O` / `m` / `ms` / `j6` 等单双字母根，X、Y 覆盖全部 55 个 dex |
| `auto_play_key` | 在。归属 `...feed.plato.business.contentconsumption.autoplay.AutoPlayComponent` 和 `AutoPlayViewModel` |
| `AutoPlayController` | 在 `com.ss.android.ugc.aweme.follow_shoot_fra.play_control`，**不在** autoplay 包下 |
| `AutoPlayConfig` / `shareAddTool` / `share_bar` | 0 命中（已从锚点里移除） |
| `share_panel` | 在，66 个类引用（含混淆类与业务类） |
| `share_bottom_sheet` / `share_hsv` | **已消失**（0 命中），分享面板锚点已换成 `share_panel` / `share_bottom` |
| Cronet | `org/chromium/net/` 0 命中；`com/ttnet/` 14 个 dex、`CronetUrlRequest` 在（字节 ttnet 分支） |
| ExoPlayer / Media3 | **都没有**（0 命中），40.7.0 已换自研播放器 |
| 热更新 | Tinker 0 命中；`com/taobao/sophix/` 0 命中；实际是 `com/ss/android/ugc/aweme/ies` |
| 广告 SDK | 只有穿山甲 `com/bytedance/sdk/openadsdk`（8 个 dex）；广点通 / 快手 / 百度 0 命中 |
| 商城页主体 | Lynx/H5：`com.bytedance.android.shopping.*` 有 `mall/feed/jsb`、`hybrid/bridges` 一堆桥，native 视图类 **0 个** |
| `MallHomeTab` / `BottomMallHomeTab` | 在，但方法名被 R8 全量打散（`LJJLIIIJILLIZJL` 之类），无稳定锚点，入口不隐藏 |
| `MallDrainageDialog` / `MallDrainageLynxDialog` | 在，链到 `IShareShoppingDialog` → `AppCompatDialog`，可挂 `Dialog.show()` 按类名拦截 |
| `ShoppingInnerPushWidget` / `ShoppingMallLowerPriceWidget` | 在，继承 `BaseBannerView` → `FrameLayout`，是 View，可直接置 GONE |
| `playwm` | **0 命中** —— 旧 playwm→play 策略在新版地址结构下失效，已降级为兜底 |
| `watermark` | 在，40 个 dex 引用（**现行去水印主锚点**：`watermark=1→0`，只改值不删参数） |
| `biz_sign` / `x-signature` / `sign` | 在，CDN 直链与 api-play 接口的**时效签名**，模块一律不删不改（删了必 404） |
| `api-play*.amemv.com/aweme/v1/play/` | 下载成功率最高的直链形态（带 `file_id=` 更稳）；douyinvod 的 `tos/cn/...` 直链会 404 |

### 已知待修

- **商城入口（首页顶部「商城」tab）不隐藏**：`MallHomeTab` / `BottomMallHomeTab` / `BaseHomeTab` 的方法名在 40.7.0 被 R8 全量打散，无稳定锚点
- **商城页内内容（banner/商品卡/楼层）无法用 native 类名隐藏**：`com.bytedance.android.shopping.*` 下 0 个 native 视图类，页面主体是 Lynx/H5，有意不做
- 参考插件 `0920131537` 的电商锚点（`getLiveCommercePlaceholderBind` 等）在 40.7.0 全部 0 命中，商城锚点为独立摸排结果
- 商城引流弹窗已装好锚点，但尚未在真实会话里抓到一次拦下的触发日志

## 技术架构

- **语言**: Kotlin + Xposed API
- **跨进程配置**: XSharedPreferences + ContentProvider
- **去水印**: 外科式 URL 改写（`watermark=1→0` 为主、playwm→play 兜底、白名单水印参数清除；
  保留 `biz_sign`/`x-signature` 等签名参数，不做 URI 重建、不做域名替换）
- **下载容错**: 全候选后备链逐个回退 —— 视频 `MediaCache.getVideoUrlCandidates`、音乐 `getMusicUrlCandidates`、
  图片 `getImageUrlCandidatesFromAweme`（每张图各自一组镜像），统一走 `MediaDownloader.downloadMulti`，连接超时 6s
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
