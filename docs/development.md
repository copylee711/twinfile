# 构建与发版

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

## 压缩与 SMB 回归

`ArchivePasswordTest` 覆盖 AES/ZipCrypto ZIP、外部生成的加密 7z、格式往返、中文文件名、取消、路径穿越与 TAR 链接。`SmbSourceTest` 覆盖缺少共享名和路径规范化。

可选 SMB2 实际服务测试：在隔离服务器创建名为 `test` 的共享，内有 `marker.txt`，允许本机匿名读写；设置 `TWINFILE_SMB_TEST_PORT` 后运行 `SmbSourceTest.liveServerContract`。测试会创建并删除该共享内自己的测试目录，切勿指向真实用户目录。
