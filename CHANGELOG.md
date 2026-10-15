# DYPP 更新日志

## Unreleased (抖音 40.7.0 适配)

### 新增

- **电商管理（范围＝抖音商城，不碰直播带货）** — `MallHook` 只做两件事，锚点都经 40.7.0 真机验证：
  - 拦截商城营销弹窗：`MallDrainageDialog` / `MallDrainageLynxDialog`，挂 `android.app.Dialog.show()`
    框架方法（名字永远在）+ 按类名筛子，两边都稳定才叫锚点。**故意不拦** `ShareShopping*` ——
    那是用户主动点分享时弹的商品分享卡，拦了会把分享弄坏，和点小黄车弹空卡是同一类事故
  - 隐藏商城推送横幅：`ShoppingInnerPushWidget` / `ShoppingMallLowerPriceWidget`
    （继承 `BaseBannerView` → `FrameLayout`），直接置 GONE
  - 关开关默认开（`mall_hide_entry`，设置页改名「拦截商城营销弹窗与推送横幅」）
- **直播分类** — 设置页新增「直播」分类；**直播间清爽模式**从电商管理里拆出来独立成
  `LiveCleanHook`（key `live_clean`，默认关），把直播间电商盒子与直播广告挂件换成空占位，房间布局不变。
  上一版该开关误放在电商管理分类下且 key 撞词没插进 prefs.xml，这次补齐。

### 修复

- **点小黄车弹出的商品卡是空的** — `MallHook` 两处误伤：精确名单挂了
  `ECVideoInnerProductCardView` / `ECTagLayout` / `ProductPriceView`（正是小黄车商品卡），
  外加 `hideViews` 按包关键词补捞（`feed.video.product` / `shopcard` + Card|Tag|Shop|Product）
  把商品卡和 presenter 一起 GONE。已全部移除：**商品卡一律不碰，宁可少命中也不误伤**

- **所有开关偶发失效（DouSettings 读不到模块设置）** — 根因是跨进程读取链路整条断的：
  - LSPosed 对 `xposedminversion > 92` 的模块会把宿主的 `ContextImpl.getPreferencesDir()`
    重定向到 `<misc>/prefs/<模块包名>`，所以模块 prefs **根本不在** `/data/data/com.xposed.doupp/shared_prefs/`
    —— 原实现「手工 chmod 成 world-readable 再直接读文件」这条路必然是空
  - `contentResolver.call("content://com.xposed.doupp.settings")` 在宿主进程报
    `Unknown authority`：Android 11+ 包可见性让抖音进程看不到模块包，除非抖音自己声明 `<queries>`
    （改不了宿主的 manifest），所以 ContentProvider 兜底也白搭
  - `getRemotePreferences()` 返回非空但**全空**（该通道依赖 LSPosed 侧 prefs 镜像，本模块没喂进去）
  结果就是每次都掉进策略 5「所有策略失败，使用默认值」→ 用户开的开关当次启动全被忽略
  （真机日志实锤：`live_clean=true` 但 `LiveClean: 开关关闭，跳过`）。
  修法：新增两条真正能走通的读取路径 ——
  1. `de.robv.android.xposed.XSharedPreferences(MODULE_PACKAGE, "douyin_helper_settings")`
     （LSPosed 自带类，自己走文件服务拿正确路径与 SELinux 许可，反射调用不硬引依赖）
  2. 通过 `ApplicationServiceClient.getPrefsPath()` 问守护进程要 prefs 目录，再自己解析 XML
  两条都失败才回落到老链路，老链路行为不变（纯增量）

- **ClassFinder 扫描后全空** — 原实现用 `scannedClassLoaders` 标记「这个 ClassLoader 已扫过」，
  导致第一次扫完某个包之后所有后续包扫描都直接返回空。改为按 ClassLoader 缓存完整类名列表。
- **全量 Class.forName 拖垮宿主** — 原实现对整个包做 `Class.forName`，
  `com.ss.android.ugc.aweme` 是十万级类。改为类名过滤 + 懒加载 + 上限（默认 500）。
- **字符串特征搜索从来没生效** — 原 `findByStringConstants` 只读注解和 String 静态字段，
  方法体里的 `auto_play_key` 这类常量一个也搜不到。改为优先走已集成的 DexKit，
  拿不到时退回新增的 `DexStringIndex`（自己解析 dex 字符串池和 const-string 指令，纯 Kotlin）。
- **缓存只比版本号** — 抖音同版本号重打包很常见，现在缓存带 APK 指纹（长度+修改时间），不匹配就重扫。

### 适配（全部经 40.7.0 全量 55 dex / 437MB 扫描验证）

- **混淆包动态发现** — README 里「应用类都在 yyds 包下」这条在 40.7.0 已不成立（`Lyyds/` 0 命中），
  实际被打散进 `X` / `Y` / `O` / `m` / `ms` / `j6` 等单双字母根。改为按根名长度动态识别，保留 yyds 兼容旧版。
- **AutoPlay 锚点修正** — `auto_play_key` 归属 `...contentconsumption.autoplay.AutoPlayComponent` /
  `AutoPlayViewModel`；`AutoPlayController` 实际在 `com.ss.android.ugc.aweme.follow_shoot_fra.play_control`。
  移除 0 命中的 `AutoPlayConfig`。
- **分享面板锚点更换** — `share_bottom_sheet` / `share_hsv` / `shareAddTool` 在 40.7.0 全部 0 命中，
  换成 `share_panel`（66 命中）/ `share_bottom` / `SharePanel`，并补上 presenter 类锚点。
- **Cronet 走 ttnet 分支** — `org/chromium/net/` 0 命中，`com/ttnet/` 14 个 dex 在，候选表已调整。
- **播放器** — ExoPlayer 与 Media3 在 40.7.0 都不存在，补自研播放器路径，探测不再恒为「未找到」。
- **热更新** — Tinker / Sophix / mira 均 0 命中，实际是 `com/ss/android/ugc/aweme/ies`，候选表已前置。
- **广告 SDK** — 仍是全量包扫描改成类名过滤；40.7.0 只有穿山甲在。

### 已知待修

- **商城入口（首页顶部「商城」tab）不做隐藏** — `MallHomeTab` / `BottomMallHomeTab` / `BaseHomeTab`
  的方法名在 40.7.0 被 R8 全量打散（`LJJLIIIJILLIZJL` 之类），没有稳定锚点，按方法名挂等于瞎猜
- **商城页内内容（banner / 商品卡 / 楼层）无法用 native 类名隐藏** — `com.bytedance.android.shopping.*`
  下 `mall/feed/jsb`、`hybrid/bridges` 一大堆，但 native 视图类 0 个，页面主体是 Lynx/H5；
  硬要藏只能在 JS 层动刀，风险大于收益，有意不做
- 参考插件 `0920131537` 的电商锚点（`getLiveCommercePlaceholderBind` / `C0526.m1395` 注册式）
  在 40.7.0 全部 0 命中，无法照搬其做法，本项目商城锚点为独立摸排结果
- 商城引流弹窗目前按类名拦截已装好，但还没在真实会话里抓到一次弹窗触发日志证明拦下
- 跨进程设置读取的新链路（XSharedPreferences / 守护进程 prefs 路径）已写好并编译通过，
  但设备掉线，**尚未上机验证**；要看着日志确认出现
  `DouSettings: XSharedPreferences 初始化成功` 或 `直接读 LSPosed 的 prefs 目录…成功`

- `AutoPlayControllerHook` 内部仍引用 `yyds.C1714` / `yyds.InterfaceC1354` / `yyds.C2429`（抖音 39.x 类名），
  40.7.0 上 `yyds` 包已不存在，该 Hook 当前不生效，需要按新锚点重写。
- `UrlParser` 的 `playwm`→`play` 策略在 40.7.0 0 命中（水印地址结构已变），需改用 `watermark` 字段判定。

## v3.1.0 (2026-09-20)

### 适配

- **适配抖音 40.5.0**（versionCode 400500）：
  - compileSdk/targetSdk 升级至 35（Android 15）
  - libxposed API 升级至 102.0.0
  - 新增 v40.5.0 类候选列表适配（RegionUnlock/AutoSignIn/AntiRevoke/LuckyBag）
  - AdaptationManager 动态扫描新增 4 组特征类

### 新增

- **地区限制解锁**：Hook 地区检查方法，解除抖音地区限制
- **自动签到**：Hook 每日签到方法，自动完成签到任务
- **消息防撤回**：拦截消息撤回方法，阻止消息被撤回
- **福袋自动领取**：Hook 直播间福袋方法，自动领取奖励
- 所有新功能均支持独立开关控制（设置页 → 扩展功能）

## v3.0.0 (2026-08-17)

### 适配

- **适配抖音 40.1.0**（versionCode 400100）：
  - 反编译对比确认 40.1.0 与 40.0.0 结构完全一致，无需改动 Hook 代码
  - Video 72 个 `@SerializedName` 字段映射与 40.0.0 完全相同（`play_addr -> a`、`play_addr_h264 -> d`、`download_addr -> v`、`bit_rate -> y`、`real_duration -> J` 等）
  - Aweme：`aweme_id`/`aid`、`public Video video`、`getVideo()`、`getAid()` 保留
  - UrlModel：`public List<String> urlList` 保留；BitRate：`bitRate`/`playAddr`/`playAddr265`/`playAddrBytevc1` 保留
  - AutoPlayViewModel 结构兼容（static boolean / 非 static boolean / LiveData getter），`auto_play_key` 字符串仍存在，DexKit 可动态定位
  - AutoPlayComponent：`triggerAutoPlayTask(String, String)` 双参数保留
  - Comment：`CommentViewHolderOldStyle`、`vh/holders/CommentViewHolder` 均在
  - 分享面板：`LongVideoShareCommandDialog`/`ShareCommonCommandDialog`/`VideoShareCommandDialog` 均在
  - `FlippableViewPager` 仍在 `base/ui` 路径
  - 版本检测补充 40.1.0 实际 versionCode 400100

### 新增

- **适配抖音 40.0.0**（versionCode 400001）：

### 新增

- **适配抖音 40.0.0**（versionCode 400001）：
  - 40.0.0 全字段混淆（Video 字段名变为单字母且 getter 被移除），新增 **@SerializedName 注解反查机制**
  - `HookUtils.getFieldDeep` / `MediaCache.objByNames` 在字段名匹配失败时，通过 Gson `@SerializedName` 注解按原始 JSON 名（如 `play_addr_h264`、`download_addr`、`bit_rate`）反查字段
  - 兼容 `aweme_id -> aid`、`bit_rate -> y` 等混淆映射，无水印下载 / 视频时长 / 过滤判断恢复正常
- 分享面板支持 40.0.0 新 `CommandDialog` 系列（`ShareCommandDialog` / `VideoShareCommandDialog` 等）
- 评论图片保存候选类补充 40.0.0 实际存在的 `CommentViewHolderOldStyle`
- VerticalViewPager 候选补充 40.0.0 新位置 `base.ui.FlippableViewPager`

### 修复

- `FeedHook` / `VideoFilterHook` 的 Aweme ID 识别补充 `getAid()` / `aid`（40.0.0 Aweme 主键）
- 版本检测补充 40.0.0 实际 versionCode 400001

## v2.0.2 (2026-08-09)

### 修复

- **自动播放设置失效**：
  - `DouSettings.isAutoPlayEnabled()` 改为优先读取本地 SharedPreferences，不再依赖跨进程 ContentProvider
  - `DouSettings.syncFromModuleFile()` 支持多路径查找模块 prefs 文件
  - `DouSettings.setLocalContext()` 初始化时主动触发文件同步
- **视频过滤误跳过**：
  - `VideoFilterHook.triggerFilterSwipe()` 移除 `triggerMoveToNextForce()` 调用
  - 视频过滤不再干扰官方自动播放逻辑
- **无水印下载误触发**：
  - `ShareHook` 仅拦截 `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 分享 Intent
  - 不再拦截抖音内部导航（打开评论、个人页等）触发的 `startActivity` 调用
  - 移除 `Instrumentation.execStartActivity` 的底层拦截
- **实况照片误触发**：
  - `DownloadDialogHook` 中 `Dialog.onClick` 不再调用 `tryHandleLivePhotoSave()`
  - 防止误触发下载

## v2.0.1 (2026-08-09)

### 修复

- 适配抖音 39.8 版本
- 修复跨进程设置同步问题
- 修复双击功能异常
- 修复自动播放功能
- 修复视频误杀问题
- 关闭自动播放后彻底停止自动切换（过滤跳过也受开关控制）
- Hook `AutoPlayComponent.triggerAutoPlayTask` 彻底拦截官方连播

### 新增

- 评论收藏(Bookmark)功能
- 视频收藏(Bookmark)功能
- 主页收藏(Bookmark)功能
- 沉浸式播放模式悬浮下载按钮
- 自动播放悬浮按钮（可隐藏）

## v2.0.0

### 新增

- DYPP 品牌重命名
- 实现 save_comment_media 功能

### 修复

- 自动播放设置页增加总开关
- 移除每次写设置的 `share()` 调用
- 视频进度 70% 检查防止提前自动切换
- 只 Hook `hN1()` 方法，移除构造函数 Hook（`postValue` 触发提前切换）
- 只 Hook `auto_play_key` 方法，不影响其他 `()Z` 方法
- 保留 `jN1`（服务器开关）不 Hook，保留时间控制
- 定时 `tryInject` + 启动 `KeepAliveService` 同步跨进程隐藏状态
- 移除每次偏好写入的 `share()/chmod` 调用，解决 UI 卡顿
- 使用 `PreferenceFragmentCompat` 重写设置页修复 MIUI CheckBox 点击问题
- 使用 `total displacement` 从 down→up 检测拖拽；在 MOVE 时始终移动
- 在 `onTouchEvent` 中完全消费触摸替代 `performClick`；增加拖拽阈值
- 设置跨进程同步、会话级按钮隐藏、长按隐藏
- 移除 2 秒周期性检查，`enabled()` 添加 200ms 缓存减少反射/配置读取开销
- 自动播放按钮隐藏设置动态生效（每 3 秒周期性检查）
- 自动播放按钮隐藏设置改用 `tryInject` 周期性检查（每 2 秒）
- 自动播放开关同步到 native Keva 存储 (C1714)
