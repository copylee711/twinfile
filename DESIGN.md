# TwinFile UI

Android 原生文件管理工具，界面使用简体中文和 Jetpack Compose Material3。保留现有双栏、紧凑文件列表、主题色和系统字体；本轮不改变整体视觉布局。

## 运行时所有者

- 色彩、字体、浅色/深色：`ui/Theme.kt` 的 TwinTheme 与 MaterialTheme。
- 通用输入和确认：`ui/Dialogs.kt` 的 InputDialog、CheckRow 和 Material3 AlertDialog。
- 压缩格式和密码：`ui/ArchiveDialogs.kt`，复用同一密码字段，默认遮挡，提供显示/隐藏。
- 云盘：`ui/CloudScreen.kt` 负责账号与授权状态；未配置的提供方禁用登录并说明原因。Google/微软的同意界面由原生授权库管理。账号标识与文件路径使用稳定 ID，显示名称独立，支持重名。移除账号有应用内确认，云端删除提示回收站语义。
- 网络配置：`ui/NetworkScreen.kt` 的 Field 和内联连接状态；保存 SMB 前实际读取指定共享目录。
- 操作进度、取消和错误：`ops/Ops.kt` 与现有任务面板；浏览消息由 MainViewModel.say 提供。

密码不使用 rememberSaveable，也不持久化。密码错误保留在密码弹窗中，取消结束等待，不创建半成品。ZIP 创建时明确提示 AES-256 只加密内容、文件名可见；其他格式不提供密码开关。TAR/7z 点击执行解压，ZIP 继续支持目录浏览。Android 原生控件拥有焦点、返回键和触摸语义；Web 的 CSS/滚动条规则不适用。

## 验证

执行 JVM 回归测试、debug/release 构建及 Android lint。用 Android 模拟器检查压缩对话框、密码输入/显示/重试/取消和 SMB 缺少共享名的错误引导；真实 NAS、手机及 OAuth 账号联调另行记录，不以静态审计代替。
