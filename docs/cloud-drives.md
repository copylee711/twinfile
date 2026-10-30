# Google Drive / OneDrive 接入可行性

调研日期：2026-10-30。本版本只完成调研，不包含云盘登录或文件管理入口。

## 结论

两者都可以通过 OAuth 接入，并复用 TwinFile 的双栏、浏览和传输界面。用户已确认 Google Drive 按完整云盘管理设计，接受申请受限的 `drive` 全盘权限；文件操作由用户在本机控制，不通过应用自有服务器中转。建议先实现 OneDrive，再接入 Google Drive。主要阻力不是文件 API，而是账号授权、Google 的全盘权限审核，以及 FileSource 当前以路径代替文件 ID 的设计。

## 授权与发布条件

| 项目 | Google Drive | OneDrive |
|---|---|---|
| 原生登录 | Android 授权客户端，或系统浏览器 OAuth；不要使用嵌入式 WebView | MSAL Android，系统浏览器，公共客户端 |
| 注册 | Google Cloud 项目、Drive API、OAuth 同意屏幕、Android 包名及签名证书 | Entra 应用注册，Android 包名、签名散列和重定向 URI |
| 建议权限 | 完整文件管理需 `drive`；`drive.file` 仅限应用创建或用户明确授权给应用的文件 | 委托权限 `Files.ReadWrite`；令牌续期交由 MSAL 管理 |
| 账号 | Google 账号；共享云端硬盘需额外支持 | 注册时选择个人和组织账号；组织策略可能禁止用户自行同意 |
| 发布条件 | `drive` 为受限权限，公开发行涉及 OAuth 审核；安全评估要求取决于数据是否经服务器存储或传输，不能承诺免审 | 需要真实应用注册和账号联调；组织租户可另有管理员授权要求 |

`drive.file` 不能提供与本地文件夹一样的全盘浏览：已有但未授权给应用的文件不会出现。这一点必须在产品上明确，避免再次出现“登录成功但看起来空目录”。不要为了避开审核而把此权限包装成全盘管理。

APK 是公共客户端，不能保存 client secret。Google 与 Microsoft 的 client ID 是应用配置，不是密码。测试签名和 release 签名需要各自配置正确的回调。账号令牌应由授权库缓存或用 Android Keystore 加密，禁止写入日志、路径或普通配置 JSON；退出登录清理令牌、目录映射与本地预览缓存。

## 与当前代码的衔接

当前 `fs/FileSource.kt` 提供 list/stat/openRead/openWrite/mkdir/rename/delete，`ops/Transfer.kt` 已支持来源之间的流式复制。可以增加独立的 CloudAccountStore、授权协调器、GoogleDriveSource 和 OneDriveSource。不要把 access token 塞进 NetConfig.password，也不要套用 NetSource 的用户名/密码连接池。

需要先解决以下接口差异：

1. **稳定 ID**：Google Drive 允许同一目录重名；重命名、移动后 ID 不变。现有按名称索引的路径接口不能无损表达这些对象。为 FEntry 加可选稳定 ID，再让目录导航与传输引用带 source/account/item ID；OneDrive 同样保存 drive ID 和 item ID。不要仅以文件名作为缓存键。
2. **分页与取消**：Drive 的 nextPageToken 和 Graph 的 @odata.nextLink 都要读完或在 UI 中提供显式分页。请求失败不能返回空列表；处理 401 后续期、403 权限、429/503 的 Retry-After 和撤权后重新登录。
3. **下载**：普通文件使用流式下载；Google Docs/Sheets/Slides 要通过 export 选择文件类型，不能当作普通二进制文件下载。Graph 下载可能重定向到预授权 URL，不能把 bearer token 转发给任意下载域名，也不要记录预授权 URL。
4. **上传**：小文件可直接上传，大文件用可恢复上传会话；用临时磁盘文件或有界管道衔接 openWrite，不能把整个文件放进内存。明确取消、失败清理、同名冲突和重试语义。
5. **能力**：按提供方声明服务端移动、复制、时间戳、缩略图能力。云端删除和本地回收站不同，应说明删除去向；共享盘、快捷方式、原生文档和跨账号移动需要独立规则。

实现建议：先做稳定 ID 与授权状态模型；再做 OneDrive 的登录/退出、完整分页浏览、下载；补齐上传和目录操作；最后添加 Google Drive，按已确定的“完整云盘”范围申请 `drive` 权限。两者均可沿用已有 OkHttp 做 REST 请求，授权使用官方原生库，避免引入完整 Graph/Google API SDK 增加体积。

## 上线前验证

使用真实的个人账号和工作/学校账号验证登录、回调、令牌过期、撤销授权、重装、多账号、分页、重名、大文件、网络中断和退出清理。Google 还应覆盖原生文档导出及选定权限的可见范围。当前没有注册这些 OAuth 应用，也未进行真实云盘账号联调。

## 官方资料

- [Drive 权限范围与审核](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Google 原生应用 OAuth](https://developers.google.com/identity/protocols/oauth2/native-app)
- [Drive 下载与导出](https://developers.google.com/workspace/drive/api/guides/manage-downloads)
- [MSAL Android 配置](https://learn.microsoft.com/en-us/entra/msal/android/msal-configuration)
- [Graph 权限参考](https://learn.microsoft.com/en-us/graph/permissions-reference#filesreadwrite)
- [Graph 目录分页](https://learn.microsoft.com/en-us/graph/api/driveitem-list-children?view=graph-rest-1.0)
- [Graph 上传会话](https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession?view=graph-rest-1.0)
