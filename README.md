# Dou+ — 抖音增强工具

基于 LSPosed/Xposed 框架的 Android 模块，为抖音提供无水印保存和增强功能。

## 功能

在抖音**分享面板最底部**注入功能入口：

- **无水印下载视频** — 自动去除水印，保存到相册
- **下载音乐 MP3** — 提取视频背景音乐并保存
- **下载图片** — 图集内容逐张下载无水印图片
- **复制文案** — 一键复制视频描述文案
- **设置** — 打开模块设置页面

设置页面支持：

- 去除广告（跳过开屏广告）
- 自动播放（视频播放完成后自动切换下一个）
- 自动保存视频/图集/实况照片
- 评论区媒体保存
- 保存目录、下载质量、提示等

## 适配

- 抖音 39.5.0 (versionCode 390501)
- Android 9.0+ (API 28)
- LSPosed 框架 (1.8+ 或 2.0.x)

## 技术架构

- Kotlin + Xposed API
- 跨进程配置: XSharedPreferences
- 加固延迟加载: Application.attach Hook + 双重安装策略
- 类查找: 多策略 ClassFinder (候选名/字段特征/方法签名)
- 媒体保存: MediaStore API (Scoped Storage 兼容)

## 构建

```bash
gradle assembleDebug
```

CI 自动构建: push 到 main 分支即触发 GitHub Actions。

## 安装

1. 下载 APK 文件
2. 安装 APK（需允许未知来源）
3. 打开 LSPosed 管理器 → 模块列表 → 启用 **Dou+**
4. 勾选作用域：**抖音**
5. 强制停止抖音或重启手机
6. 打开抖音，在视频分享面板底部即可看到功能按钮

## License

MIT
