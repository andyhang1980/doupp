# DYPP 更新日志

## v3.0.0 (2026-08-16)

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
