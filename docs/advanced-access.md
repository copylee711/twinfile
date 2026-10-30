# 高级访问

## Android/data 与 Root

Android 11 起，应用不能直接访问 `Android/data` 和 `Android/obb`。在「设置 → 高级访问」选择：

- **SyncBoard**：手机上装了 SyncBoard（0.4.1 及以上）并在运行时，借它的手机服务访问，第一次需要在 SyncBoard 里允许
- **Shizuku**（免 Root）：先安装 [Shizuku](https://github.com/RikkaApps/Shizuku) 并启动服务，TwinFile 第一次使用时会请求授权
- **Root**：通过 `su` 执行，还能浏览整个系统（抽屉里的「根目录 · Root」）

开启后进入这些文件夹会自动切换过去，离开后回到普通的本地访问。

## 让手机管理电脑上的文件

手机上已经装了 SyncBoard 的话，在「电脑」页点「在 SyncBoard 里允许」，就能直接用它连接的电脑，不用再配对。没装的话按下面的步骤让 TwinFile 自己配对：

1. 电脑上安装 SyncBoard 桌面端（需要包含「允许已配对设备浏览这台电脑的文件」这个开关的版本）。
2. 在 SyncBoard 设置里打开「允许已配对设备浏览这台电脑的文件」。默认是关闭的。
3. 在 TwinFile 的抽屉里点「电脑 → 扫码配对」，扫描电脑上「添加手机」显示的二维码。
4. 配对完成后，点电脑名称就会在当前栏打开电脑的磁盘。

TwinFile 和 SyncBoard 手机端可以同时与同一台电脑配对，互不影响。TwinFile 不参与剪贴板同步。
