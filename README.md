# TwinFile

开源、免费的 Android 双栏文件管理器。操作习惯参照 MT 管理器，但不包含反编译、APK 编辑这类功能；MT 里需要会员的网络位置（FTPS / SFTP / SMB / WebDAV），这里全部免费。配合 [SyncBoard](https://github.com/copylee711/syncboard) 的电脑端，还可以在双栏里直接管理电脑上的文件。

MIT 许可。

## 功能

**双栏浏览**
- 左右两栏各自独立浏览，点哪栏就操作哪栏；也可以切成单栏
- 每栏有自己的后退、前进历史，回到上一级时自动定位到刚才的文件夹
- 点标题栏可以直接输入路径跳转
- 排序：名称（自然排序，file2 排在 file10 前）、时间、大小、类型；可以只对某个文件夹生效
- 过滤，显示或隐藏隐藏文件

**选择**
- 在文件上左右滑一下就能选中；按住不放上下拖动，可以连续选中多个
- 选择工具：全选、反选、选中区间、选同一天/周/月/年的文件、按关键词选

**文件操作**
- 复制或移动到另一栏，同名冲突可以选跳过、覆盖（文件夹会合并）或保留两者
- 新建、重命名、创建副本、交换两个文件的名字、复制路径
- 删除先进回收站，可以恢复，也可以设置几天后自动清理
- 属性：大小（文件夹递归统计）、权限、时间；计算 MD5、SHA-1、SHA-256、CRC32，可以粘贴校验值比对
- ZIP：像文件夹一样直接进入浏览；解压（文件名支持 UTF-8 和 GBK）、压缩
- 长时间的任务在后台运行，显示进度，可以取消

**查看与打开**
- 内置图片查看器：左右滑动切换，双指缩放
- 内置文本查看器：自动识别 UTF-8 / GBK，大文件只加载前 1 MB
- 其他文件用系统的「打开方式」，也可以分享

**书签与最近访问**：从底栏往上拉，面板会跟着手指出来，可以停在半屏或全屏；书签可以改名、调整顺序，抽屉里也会显示

**已安装应用**：用户应用和系统应用分开列出，支持搜索；可以查看包名、版本、SDK、签名证书 SHA-256；提取安装包，分包应用会打成 `.apks`

**网络位置**（全部免费）
- 支持 FTP、FTPS、SFTP、SMB 2/3、WebDAV
- 打开后和本地文件夹一样操作，两栏之间可以互相复制
- 密码用 Android Keystore 加密后保存
- SFTP 第一次连接时确认主机指纹，以后指纹变化会拒绝连接
- 空闲 2 分钟自动断开

**电脑**（需要 SyncBoard 电脑端）
- 扫描电脑上 SyncBoard「添加手机」的二维码完成配对；也可以局域网搜索或输入 IP
- 电脑的磁盘，以及桌面、下载等常用文件夹，会直接出现在两栏里
- 手机和电脑之间直接复制、移动；电脑上的文件也能改名、删除（删除进电脑回收站），图片和视频显示缩略图
- 传输全程加密，每个文件都做 SHA-256 校验

**外观**：进出文件夹时有滑入动画（可以关）；7 种主题色，包括 Android 12+ 的壁纸取色；浅色、深色或跟随系统；可以选紧凑列表

## 让手机管理电脑上的文件

1. 电脑上安装 SyncBoard 桌面端（需要包含「允许已配对设备浏览这台电脑的文件」这个开关的版本）。
2. 在 SyncBoard 设置里打开「允许已配对设备浏览这台电脑的文件」。默认是关闭的。
3. 在 TwinFile 的抽屉里点「电脑 → 扫码配对」，扫描电脑上「添加手机」显示的二维码。
4. 配对完成后，点电脑名称就会在当前栏打开电脑的磁盘。

TwinFile 和 SyncBoard 手机端可以同时与同一台电脑配对，互不影响。TwinFile 不参与剪贴板同步。

## 性能

**内存**：在模拟器（Android 15，x86_64）上运行 release 版，用 `dumpsys meminfo` 测得的 TOTAL PSS：

| 场景 | PSS |
|---|---|
| 启动后双栏空闲 | 约 40 MB |
| 快速滚动一个有 30 张图片的文件夹（加载缩略图） | 约 52 MB |
| 切到后台 | 约 40 MB |

真机上的数字以后补上。

**列表与缩略图**
- 列表是虚拟化的，只渲染屏幕上能看到的行
- 缩略图最多同时解码 4 个，内存缓存不超过堆的 1/8
- 滑出屏幕的缩略图会取消加载

## 体积与依赖

release APK 约 5.2 MB，已用 R8 压缩和混淆。下面是各部分在 dex 中的大小（未压缩，用 apkanalyzer 配合 mapping 统计）：

| 部分 | dex 大小 | 用途和选择理由 |
|---|---|---|
| Jetpack Compose（UI、Material3、图标） | 1460 KB | 界面 |
| BouncyCastle | 1737 KB | sshj 和 smbj 共用。Android 自带的 BC 是裁剪版，缺 SSH、NTLM 需要的算法 |
| CameraX | 563 KB | 扫描配对二维码，和 SyncBoard 手机端用的是同一套 |
| Kotlin 标准库 + 协程 | 296 KB | |
| 其他 AndroidX | 258 KB | activity、lifecycle、core |
| sshj | 170 KB | SFTP。API 比 JSch 清楚，维护活跃，支持现代算法（ed25519、chacha20） |
| smbj（含 mbassador、asn-one） | 170 KB | SMB 2/3。目前唯一成熟的纯 Java SMB2/3 实现；jcifs-ng 更大，而且 SMB1 已经过时 |
| OkHttp + Okio | 158 KB | WebDAV。只用到 OkHttp，PROPFIND 等方法是自己实现的；sardine 等现成库会带进 Apache HttpClient |
| ZXing core | 115 KB | 解码二维码 |
| commons-net | 34 KB | FTP / FTPS。事实标准，没有其他依赖 |
| slf4j-api | 32 KB | sshj、smbj 的日志接口（不输出） |
| TwinFile 自身 | 199 KB | |

后续可以做的：BouncyCastle 目前按 provider 整体保留，确认 SSH 和 SMB 实际用到哪些算法后，可以只保留那些，预计能减少 1 MB 以上。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 37）。

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

单元测试会在本机启动 FTP（Apache FtpServer）、SFTP（Apache MINA SSHD）和 WebDAV 测试服务器，用同一套操作检查每种网络位置。另外有一个可选的实机联调测试，连接真实的 SyncBoard 电脑端，用法见 `app/src/test/java/app/twinfile/pc/PcLiveTest.kt`。

**release 签名**：签名信息从 `~/.twinfile/keystore.properties`（或环境变量 `TWINFILE_SIGNING` 指定的文件）读取，需要的字段是 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。

```bash
./gradlew assembleRelease
```

**发版**：
1. 修改 `app/build.gradle.kts` 里的 `versionName` 和 `versionCode`。
2. 把发布说明写到 `docs/releases/vX.Y.Z.md`。
3. 推送 `vX.Y.Z` 标签。

GitHub Actions（`.github/workflows/release.yml`）会构建签名过的 APK，跑一遍测试，生成 `SHA256SUMS.txt`，然后发布 Release。签名密钥存在仓库的 Actions secrets 里。

## 权限说明

| 权限 | 用途 |
|---|---|
| 所有文件访问（Android 11+）/ 存储读写（Android 8–10） | 管理文件 |
| 读取已安装应用列表 | 「已安装应用」页面 |
| 网络 | 网络位置、连接电脑 |
| 相机 | 扫描电脑上的配对二维码 |
| 通知、前台服务 | 显示长时间复制、移动的进度 |
| 安装应用 | 点 APK 时交给系统安装器 |

TwinFile 不包含统计、广告和联网检查更新，除了你自己配置的网络位置和电脑，不连接其他服务器。

## 计划

- Shizuku 模式：访问 Android/data、obb
- 文本编辑器（大文件分段加载）
- 7z / rar 解压
- 按文件名和内容搜索
- 文件夹大小分析
- 批量重命名
