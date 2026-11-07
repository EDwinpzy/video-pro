# Video Pro 交接文档

> **2026-11-05 发布状态：** 正式 TV 应用是 [`frontend-tv`](frontend-tv)
>（`0.3.1` / `versionCode 7`）。下文关于根目录 KMP TV 迁移的记录属于历史
> 与实验资料，不再是发布路径。目录与版本边界见
> [`docs/repository-layout.md`](docs/repository-layout.md) 和
> [`docs/versioning.md`](docs/versioning.md)。

更新时间：2026-10-31

## 1. 项目目标

Video Pro 是懂片帝的新 Android TV 前端。播放器和 TV 交互壳来自
Jellystack，业务后端继续使用懂片帝官方服务，不另建媒体后端。

目标效果：

- 使用同一官方账号登录。
- 读取收藏、观看历史和云端续播位置。
- 使用同一个账号在不同设备间同步播放进度。
- 保留 Jellystack 的 TV 焦点、播放器 OSD、选集、换线、错误恢复能力。
- 全部用户界面、错误提示、空态、无障碍文案切换为简体中文。
- 删除 Seerr、Jellyfin 服务器管理、多用户 Profile 等不适用界面。

## 2. 当前仓库

- 本地目录：`D:\MyProjects\video-pro`
- GitHub：https://github.com/EDwinpzy/video-pro
- 可见性：私有
- 主分支：`main`
- 交接准备基线提交：`1b96aef838e9aa69cdd8aa50ab1277fd5f9e33be`
- `origin`：`git@github.com:EDwinpzy/video-pro.git`
- `upstream`：`git@github.com:Darkatek7/jellystack.git`
- Jellystack 上游基线：`c6eef175c09c4c413aeede37888f4f8ce978b9a2`
- 目标 applicationId：`com.dongpiandi.videopro`

当前仓库已导入 Jellystack 的完整源码和上游 Git 历史，并完成第一版
Video Pro TV 根界面与懂片帝数据层接入。新的启动入口不再经过 Jellyfin
ProfileHost；旧 Jellyfin/Seerr 深层代码仍保留在仓库中，等待后续清理。

## 3. 许可证和发布边界

Jellystack 使用 GPL-3.0。修改后再分发时，衍生应用必须遵守 GPL-3.0，
包括提供对应源代码和保留上游版权、许可证、第三方声明。

当前 GitHub 仓库是私有的。公开发布、商店上架或商业闭源分发前，必须先确认：

- 是否接受整个衍生应用按 GPL-3.0 发布。
- 是否已经从 Jellystack 作者取得其他授权。

不要在没有确认许可证方案前公开仓库或发布商店包。

## 4. 当前完成度

| 项目 | 状态 |
| --- | --- |
| Jellystack TV 源码和上游历史 | 已导入 |
| Video Pro 私有 GitHub 仓库 | 已创建并推送 |
| 中文迁移计划 | 已写入 `PROJECT_PLAN.md` |
| 懂片帝官方 API 逆向 | 已完成账号、片库、播放、历史、续播主链路 |
| 独立 Android 原型 | 已能在真实模拟器登录、播放和跨设备同步 |
| Video Pro 数据层替换 | 首版完成：签名、会话、首页、片库、搜索、详情、选集、片单、播放线路、历史/收藏/同步合同 |
| Video Pro 中文 UI | 主链路完成：登录、五路导航、首页、片库、搜索、片单、我的、详情、选集 |
| Seerr/Jellyfin 页面删除 | 启动入口和可见导航已切断；深层共享代码尚未删除 |
| Video Pro TV 构建和截图测试 | `assembleDebug`、静态检查、单测、真机 connected tests 通过；TV 参考截图尚未更新 |

## 5. 已有懂片帝逆向成果

这些成果当前位于旧项目：

`D:\MyProjects\懂片帝\dongpiandi_tv_rebuild`

主要文档：

- `D:\MyProjects\懂片帝\dongpiandi_tv_rebuild\reports\library-sync-api-2026-10-31.md`
- `D:\MyProjects\懂片帝\dongpiandi_tv_rebuild\reports\reverse-evidence.md`
- `D:\MyProjects\懂片帝\dongpiandi_tv_rebuild\reports\public-api-probe.json`
- `D:\MyProjects\懂片帝\dongpiandi_tv_rebuild\reports\api-reverse-20261031`

独立原型源码：

- `ApiTransport.java`
- `AccountClient.java`
- `AccountSession.java`
- `LibraryHistoryClient.java`
- `RemoteWatchHistory.java`
- `MainActivity.java`
- `NativeAccountView.java`
- `NativePlayerView.java`

路径后缀：

`D:\MyProjects\懂片帝\dongpiandi_tv_rebuild\app\src\main\java\com\dongpiandi\tvrebuild`

原型最终调试 APK：

- `D:\MyProjects\懂片帝\dongpiandi_tv_rebuild\app\build\outputs\apk\debug\app-debug.apk`
- SHA-256：`e2eca14b16c3cc45df2ad8fe3ac63fca0c2bf65c145aa5a663a1c524b45e52cb`
- 定位：验证用 debug 包，不是生产签名包。

## 6. 已验证的官方服务

当前基础地址：

`https://ai.dongpian20.com`

当前客户端构建标识：

`dongpiandi-v2026.10.30.2-api-build-boundary-ee3385411179-web`

当前协议标识：

`2026-07-05.library-v2.playback-v1`

### 6.1 请求签名

签名密钥位于当前线上 `movie-card-runtime-*.js`，以 `Jo="<64 hex>"` 形式声明。
客户端应动态获取，不得写死。

签名原文：

```text
METHOD
/v1/path?query
timestamp
nonce
```

算法为 HMAC-SHA256，请求头：

- `x-ai-movie-timestamp`
- `x-ai-movie-nonce`
- `x-ai-movie-signature`
- `x-ai-movie-client-name`
- `x-ai-movie-client-version`
- `x-ai-movie-build-version`
- `x-ai-movie-protocol-version`

### 6.2 账号

- 登录：`POST /v1/users/password/login`
- 登录体：`{"account":"...","password":"..."}`
- 会话确认：`GET /v1/users/me`
- 会话载体：登录响应 Cookie
- 账号标识：`/v1/users/me` 返回的 `id`

### 6.3 内容

- 首页：`GET /v1/feed/home`
- 片库：`GET /v1/browse/catalog`
- 搜索：`GET /v1/suggest`
- 详情：`GET /v1/catalog/{id}/detail`
- 版本：`GET /v1/catalog/{id}/variants`
- 选集：`GET /v1/catalog/{id}/episodes`
- 播放解析：`GET /v1/playback/resolve/{episodeToken}`

播放解析返回 `line_options`。只使用 `resolved=true`、
`resolve_required=false` 且 URL 为 HTTPS 的线路。

### 6.4 历史、收藏和续播

- 历史列表：`GET /v1/users/me/history`
- 紧凑状态：`GET /v1/users/me/library/status?ids=<variantId>&view=compact`
- 收藏：`GET /v1/users/me/favorites`
- 批量同步：`POST /v1/users/me/library/sync`

历史读取参数：

- `limit`
- `cursor`
- `update_view=text`
- `q`
- `kind`
- `kind_state`

批量同步 operation：

- `history_upsert`
- `history_delete`
- `history_clear`
- `favorite_upsert`
- `favorite_delete`
- `favorite_clear`

进度 payload 关键字段：

- `variant_id`
- `title`
- `episode_id`
- `episode_label`
- `episode_number`
- `episode_token`
- `position_seconds`
- `duration_seconds`
- `watched_at`
- `poster_url`
- `backdrop_url`
- `content_kind`
- `area`
- `language`
- `genres`

同步成功必须检查响应中的 `accepted_op_ids`，HTTP 200 不能单独作为成功证据。

## 7. 跨设备验收结果

已使用真实测试账号在两台 Android TV 模拟器完成复测：

- 设备 A 将《嫁金钗》第 1 集写到 689 秒（11:29）。
- 设备 B 登录同一账号后，账号页显示“嫁金钗 · 第1集 · 11:29”。
- 设备 B 打开同一集后从云端位置继续，暂停画面显示 11:58。
- 设备 B 写回 739 秒（12:19），服务端状态接口确认落库。

复测期间修复了两个真实问题：

1. Android TV 不支持 `URLEncoder.encode(String, Charset)`，改为兼容版本的
   `URLEncoder.encode(value, "UTF-8")`。
2. 云端位置可能在播放器 READY 之前返回。现在保留 pending resume，
   在 `STATE_READY` 后再次校正位置。

## 8. Video Pro 页面范围

主导航保留 5 个：

1. 首页
2. 片库
3. 搜索
4. 片单
5. 我的

二级页面保留：

1. 影片/剧集详情
2. 播放器
3. 账号登录
4. 观看历史
5. 收藏
6. 片单详情
7. 追剧周表
8. 播放设置

播放器内部保留：

- OSD
- 快进/快退
- 选集
- 切换线路
- 音轨/字幕（官方线路支持时才显示）
- 自动连播
- 云端续播
- 播放错误重试
- 进度 checkpoint

## 9. 需要删除或改造的 Jellystack 页面

删除：

- Seerr Discover/Request/Request Detail
- Jellyfin 服务器地址输入和 Quick Connect
- Jellyfin 服务器管理
- 多用户 Profile、添加 Profile、Profile PIN
- SyncPlay
- 下载和离线媒体
- Cast
- Admin Dashboard
- 生产环境 Diagnostics
- 语音搜索
- Trailer 自动预览
- Jellyfin 专属且懂片帝没有对应字段的媒体能力

改造：

- `TvHomeScreen` -> 首页
- `TvLibraryScreen` -> 片库
- `TvSearchScreen` -> 搜索
- `TvDiscoverScreen` -> 片单
- `TvSettingsScreen` -> 我的
- `TvJellyfinDetailScreen` -> 通用影片详情
- `TvPlaybackScreen` -> 懂片帝播放器
- `TvConnectionScreen` -> 官方账号登录

## 10. 建议的工程结构

只保留 Android TV 需要的模块：

```text
app-tv
design-tv
players
shared-core
shared-network
shared-database
```

新增懂片帝数据适配层：

```text
dongpian-data
  DongpianRepository
  DongpianSessionRepository
  DongpianLibraryRepository
  DongpianHistoryRepository
  DongpianPlaybackResolver
```

第一版可以先把官方 JSON 映射到 Jellystack 现有领域模型，尽快跑通 UI；
之后再逐步去除 Jellyfin 命名和无关 DTO，避免一开始同时做 UI 和领域层大重构。

## 11. 中文和品牌改造

- 默认语言锁定简体中文，V1 不显示语言切换。
- 翻译所有页面标题、按钮、错误、空态、加载态、设置项、播放器控制。
- 翻译无障碍描述、遥控按键提示、日期和时长格式。
- 删除德语和英语界面入口。
- 替换 app 图标、TV banner、应用名和 applicationId。
- 新包名使用独立身份，避免和原懂片帝升级/签名链冲突。

## 12. 构建环境

Jellystack TV 构建要求：

- JDK 17
- Android SDK 36
- 在仓库根目录运行 Gradle

构建命令：

```powershell
.\gradlew.bat :app-tv:assembleDebug
```

当前机器已安装 Android platform 36、build-tools 36 和 JDK 21。
Video Pro 新仓库已完成 `:app-tv:assembleDebug`，并在真实 Android TV
设备上完成 1080p UI 验证。

如 Gradle 找不到 SDK，可临时设置：

```powershell
$env:ANDROID_HOME = 'C:\Users\63054\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

不要把 `local.properties`、keystore、APK、AAB、Cookie 或测试账号提交到仓库。

## 13. 下一步任务

按顺序执行：

1. 将 `app-tv` 品牌改为 Video Pro，applicationId 改为
   `com.dongpiandi.videopro`。
2. 新增简体中文 `TvStrings`，锁定中文默认。
3. 删除 Seerr、Profile、SyncPlay、Diagnostics、Trailer、
   Jellyfin 服务器管理相关路由和焦点项。
4. 将主导航改为首页、片库、搜索、片单、我的。
5. 新建 `dongpian-data`，先接入首页、片库、搜索、详情、选集和播放解析。
6. 接入账号登录、Cookie 会话、云端续播、历史和收藏。
7. 接入 `RemoteWatchHistory` 等价实现，支持 45 秒同步和
   pause/seek/line-switch/ended/close checkpoint。
8. 构建 `:app-tv:assembleDebug`，运行静态检查和 TV 截图测试。
9. 在 emulator-5556/emulator-5558 复测 D-pad、BACK、720p/1080p、
   真实播放、换线、选集、云端续播和双向进度同步。

## 14. 验收门槛

- 应用可以冷启动并进入中文首页。
- 所有可见文字和无障碍描述均为简体中文。
- 登录同一官方账号后能读到历史、收藏和续播位置。
- 两台设备可以双向同步播放进度。
- 选集、换线、暂停、快进快退、自然结束和 BACK 行为正确。
- 进程重启后不会把旧本地进度覆盖成更新的云端进度。
- 720p 和 1080p 下焦点框、标题、长列表、播放器控件无裁切。
- 真实影片连续播放至少 10 分钟，并完成 30 分钟稳定性测试。
- 生产包签名、包名、版本和 GPL 合规材料全部确认后才能发布。

## 15. 未决事项

- 是否接受 GPL-3.0 作为最终发布许可证。
- 是否只做私有/自用版本，还是准备公开发布。
- Video Pro 是否需要额外手机端；当前只规划 TV。
- 追剧周表是否进入 V1，当前计划保留。
- 官方接口后续变化时，是否继续动态解析网页签名密钥，还是改走授权 API。

## 16. 2026-10-31 本次续作结果

- 新包身份：`com.dongpiandi.videopro`，Video Pro `0.1.0`。
- 品牌资源：按用户提供的红色电影播放标更新启动图标、自适应/圆形图标、
  TV banner 和登录页品牌标识。
- 新启动入口：`VideoProTvRoot`，不再显示 Jellyfin 服务器、Quick Connect、
  Profile、Seerr、SyncPlay、Diagnostics 或自动 Trailer 预览入口。
- 新数据模块：`dongpian-data`，统一动态签名、Cookie 会话、SecureStore、
  请求头、401 密钥刷新、响应大小限制和官方接口 DTO 映射。
- 线上无账号 GET 验证：`feed/home`、`browse/catalog`、搜索、详情、选集、
  `playback/resolve` 和片单均返回 HTTP 200。
- 真机验证：使用用户提供的测试账号完成真实登录；首页、片库、搜索、片单、
  我的、详情、选集和冷启动会话恢复均正常。凭据未写入仓库或日志。
- 当前明确未完成：播放器接入、历史/收藏 UI、45 秒进度同步、换线播放、
  720p/1080p 截图矩阵、双向续播和 30 分钟稳定性测试。
