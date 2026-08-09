# DYPP 更新日志

## v2.0.2 (2026-08-09)

### 修复

- **自动播放设置失效**：ContentProvider 跨进程不可达时，改用本地 SharedPreferences 兜底；`syncFromModuleFile` 支持多路径查找模块 prefs 文件；`setLocalContext` 时主动触发文件同步
- **视频过滤误跳过**：移除 `triggerFilterSwipe` 强制调用 `triggerMoveToNextForce()` 的逻辑，视频过滤不再干扰官方自动播放
- **无水印下载误触发**：`ShareHook` 仅拦截 `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 分享 Intent，不再拦截抖音内部导航（打开评论、个人页等）触发的 `startActivity` 调用；移除 `Instrumentation.execStartActivity` 的底层拦截
- **实况照片误触发**：移除 `DownloadDialogHook` 中 `Dialog.onClick` 对所有弹窗按钮点击调用 `tryHandleLivePhotoSave()` 的逻辑

## v2.0.1 (2026-08-09)

### 修复

- 适配抖音 39.8，修复跨进程设置同步、双击、自动播放、视频误杀
- 关闭自动播放后彻底停止自动切换（过滤跳过也受开关控制）
- Hook `AutoPlayComponent.triggerAutoPlayTask` 彻底拦截官方连播

### 新增

- 评论/视频/主页收藏(Bookmark)功能
- 沉浸式播放模式悬浮下载按钮
- 自动播放悬浮按钮（可隐藏）

## v2.0.0

### 修复

- 自动播放设置页增加总开关，移除每次写设置的 `share()` 调用
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
