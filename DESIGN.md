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

## 产品网站

`website/` 是部署到 `https://copylee.cn/twinfile/` 的独立静态内容站，色彩、字体与间距的规范值来自 homepage/src/styles/tokens.css，原样镜像为 website/assets/site-tokens.css；website/style.css 仅消费变量并负责版式，不影响 Android 主题。使用应用原图标、蓝色强调色、Outfit 拉丁字体和中文系统回退，沿用主站的近白背景、淡蓝网格、黑色主按钮、细分隔线与宽松阅读行距；导航链接使用深灰和悬停下划线，正文链接以细灰底线标识，不用通篇亮蓝链接。主页展示真实演示截图、格式限制、当前 OAuth 开放状态和稳定 APK 下载地址；隐私与条款共用导航及阅读布局。所有内容无需 JavaScript、登录或外部字体服务；图像保留尺寸，导航可用键盘操作，窄屏顺序阅读，减少动效与高对比模式可用。网站维护源在本仓库，镜像部署目录为 homepage/public/twinfile。

## 网站验证

验证三个页面的站内链接、APK 与校验和入口，桌面与窄屏排版、键盘焦点、FAQ 展开和无需 JavaScript 的可读性。隐私内容以实际实现及 docs/permissions.md 为依据；公开验证未完成时不宣称已通过。

网站主标题使用 TwinFile。中文正文与标题使用站内 Noto Sans SC 可变字体（OFL 许可），与 Outfit 拉丁字体组合；Noto 字形由 TwinFile 的 scripts/subset-website-font.py 按页面文本生成子集，修改中文后须重新生成。首屏渐变在文档范围内渲染并向下淡出，避免内容容器边缘与分区截断。标题逐字入场、网格引导线与节点、截图浮动、滚动分区出现和按钮悬停遵循主站节奏；减少动态效果模式禁用动画，脚本不可用时正文仍可阅读。
