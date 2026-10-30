# 性能与依赖

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

以下体积统计来自 v0.3.1（尚未加入 Zip4j、Commons Compress 和 XZ），仅作为历史基线；新版依赖会增加 APK 体积。release 已用 R8 压缩和混淆。下面是历史版本各部分在 dex 中的大小（未压缩，用 apkanalyzer 配合 mapping 统计）：

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

## 压缩格式依赖

- Zip4j 2.11.5：ZIP 流式读写、ZipCrypto/AES 解密、AES-256 加密。
- Apache Commons Compress 1.28.0：TAR 和 7z。
- XZ for Java 1.10：7z 的 LZMA/LZMA2 编解码。

新增库使用 Apache-2.0 或公共领域许可；具体条款以各项目发布包为准。
