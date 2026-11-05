# 低配 Android TV 流畅度优化方案

文档版本：2026-11-04 v1.4  
适用范围：`frontend-tv` 独立 TV 工程  
目标设备：S621 投影仪及同类低内存、低算力 Android TV 设备

> 文档性质：低配机优化方案与当前验证记录。  
> 交付边界：本文严格区分“代码已实现”“构建/单元测试通过”和“S621 实机验收通过”，
> 未完成的实机项目不会被写成已通过。

## 结论摘要

- 低配策略由运行时 `TvPerformanceProfile` 自动启用，用户无需手动设置。
- 优化重点是遥控焦点、页面首开、海报解码、播放器侧栏和厂商无障碍扫描器五类高开销路径。
- 低配机关闭整页淡入淡出、卡片放大、颜色动画、图片淡入和非必要阴影，但不关闭任何业务功能。
- 2026-11-04 低配流畅度专项把切栏从固定 180 ms 改为“方向键松开后 24 ms 切页”，
  并让普通卡片走原生焦点快速路径；详细机制见第 7 节。
- 焦点跨行不再单帧瞬移，改为 120 ms 短滚动；明确回顶仍直接落到页面顶部。
- 正常设备继续保留原有动效与更高图片规格，不做全局降级。
- 最终候选包 `37DC8E21B8FAD52354045F02ACB2B04D429AC4088359793F4A9B9F01505B60A5`
  已完成构建、lint、117 个测试套件 / 610 项单元测试及 S621 重点实机回归；
  七个顶栏入口、搜索/“我的”返回、海报行与跨行移动、回顶、直接续播和播放器侧栏均已覆盖。
- 方向键长按期间不提交切页：必须收到对应方向键的 `ACTION_UP`，再等待 24 ms 落定；
  1.5 秒无新事件只用于兜底丢失的 UP，连续按住达到 3 秒等待上限时直接放弃本次自动切换。
- 最终包原始 gfx：冷切 5 帧 P50 46 / P90 121 ms；热切两组均 3 帧 P50 48 / P90 57 ms；
  首页海报行 8 次移动 131 帧 P50 25 / P90 34 ms。
- 播放器暂停后侧栏连续 `DOWN × 8` 三轮为 35/37/40 帧，P50 34–36 ms、P90 38–44 ms；
  侧栏首次打开三次均为 3 帧、P90 150 ms。打开耗时达到 250 ms 验收线，单项移动仍高于 33.3 ms。
- 冷切 P90 121 ms 仍明显超过 33.3 ms/帧；本轮先解决感知延迟与无效焦点/组合成本，
  真机长播、完整音频、全部播放器控制项和完整遥控器矩阵仍未验收，当前不能标记为
  “全量性能验收通过”。

## 1. 目标

在不改变正常硬件体验、不删减业务功能的前提下，降低低配设备上的以下成本：

- 遥控器焦点移动时的重组、重测量和重绘制
- 页面切换时同时组合两页产生的峰值开销
- 海报解码、淡入和内存占用
- 播放器侧栏打开与逐项移动时的主线程阻塞
- 厂商常驻无障碍扫描器对 Compose 语义树的重复遍历
- 非必要动画、模糊、阴影和逐帧绘制

优化只影响视觉过渡和运行时策略，不改变账号、片库、播放、片源、收藏和历史数据。

## 2. 设备分档

运行时由 `TvPerformanceProfile` 自动判定，不需要用户手动选择。

| 条件 | 档位 |
| --- | --- |
| `ActivityManager.isLowRamDevice == true` | `CONSTRAINED` |
| 应用最大堆 `<= 192 MB` | `CONSTRAINED` |
| CPU 核数 `<= 2` | `CONSTRAINED` |
| 以上条件均不满足 | `BALANCED` |

S621 实际判定结果：

```text
performance tier=CONSTRAINED maxHeapMb=160 cpuCount=4 lowRamDevice=false
```

因此 S621 使用低配策略；正常内存和 CPU 的电视、盒子保持原有动效与更高图片规格。

## 3. 设计原则

1. **焦点必须立即可见**：低配机以高对比描边和聚焦底色为主要反馈；海报卡片另加
   1.03×、90 ms 的轻微缩放，其他控件不依赖缩放、颜色渐变或阴影动画表达焦点。
2. **页面换行不能瞬移**：焦点跨行时使用 120 ms 的短滚动补齐可视区域；回顶等明确页面跳转仍直接落位。
3. **一次移动只做必要工作**：不因焦点变化触发整页重组、图片淡入或大型语义树导出。
4. **启动只等首屏必需内容**：每个首页标签先加载第一栏，其他栏目在进入 app 后后台继续。
5. **低配关动画，不关功能**：所有入口、按钮、选集、片源、设置仍然可聚焦、可点击。
6. **正常设备不降级**：卡片以 1.08× 缩放，页面淡入和颜色过渡仍在 `BALANCED` 设备启用；
   海报/hero 请求本身仍显式关闭 crossfade，避免图片淡入与页面切换叠加。
7. **先减少导航路径**：低配普通卡片直接使用 Compose 原生焦点；只有需要方向覆盖、外部落焦、
   自动选中或浮层分组的节点才进入完整 `OtvNav` 注册/几何派发。
8. **切页跟松手，不跟固定计时**：顶栏移动时只更新焦点，松开方向键后只提交最终目标的页面切换。

## 4. 全局调整

### 4.1 启动门闸

- `HomeStartupGate` 在进入主页前加载电影、电视剧、国漫、短剧、综艺五个标签各自的第一栏。
- 每个标签预载第一栏最多 12 张卡片图和最多 5 张最近观看 hero 预览图。
- 最近观看 hero 图不阻塞门闸；第一栏卡片图必须完成成功加载。
- 其他首页栏目、片源预热、直播数据和大目录刷新在进入主页后继续。
- 请求失败自动重试两次；门闸失败时显示可用遥控器聚焦的“重试”，不会带病进入半空首页。
- 低配机在首次主页焦点落定后，再延迟 1.5 秒启动高成本后台预热，避免和首帧争主线程与网络。

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/HomeStartupGate.kt`
- `app/src/main/java/com/qiubo/optimaltv/HomeStartupPreload.kt`
- `app/src/main/java/com/qiubo/optimaltv/App.kt`

### 4.2 页面切换

- `NavHost` 在低配机关闭全屏 `fadeIn` / `fadeOut`，页面直接切换。
- 避免导航过程中同时组合、测量和绘制旧页与新页。
- 正常设备继续保留短淡入淡出。

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/MainActivity.kt`

### 4.3 焦点滚入视口

- 低配机向 Compose 注入 `TvShortBringIntoViewSpec`，页面换行补滚使用 120 ms 的 `tween`，避免 0 帧瞬移造成“整页跳一下”。
- 自定义焦点引擎的 `ensureVisible` 走同一套短滚动，不再直接调用瞬时 `scrollBy`。
- 从下方进入“继续播放”或“立即观看”时，页面仍直接回到顶部并落焦；该路径是明确回顶，不使用缓动。
- 首页、直播页等显式回顶逻辑统一使用 `scrollToItem(0)`，避免 `animateScrollToItem`。

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/MainActivity.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/home/HomeScreen.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/live/LiveScreen.kt`

### 4.4 焦点反馈

- 低配机默认不为可聚焦控件创建休眠 `graphicsLayer`；只有显式启用的海报卡片例外。
- 焦点、选中态和颜色在绘制阶段读取，直接画最终状态。
- 卡片保留双层描边和聚焦底色，保证深色、浅色海报上都能看清。
- 海报卡片固定 90 ms 缩放：`BALANCED` 为 1.08×，`CONSTRAINED` 上限为 1.03×。
  缩放只改 `graphicsLayer`，不改布局、不缩放相邻卡片，也不叠加阴影。
- 搜索、我的、全部片库和首页推荐行使用同一卡片策略；按钮、标签和其他控件在
  `CONSTRAINED` 下仍不创建缩放状态或图层。
- 低配且不需要旧导航能力的普通卡片走原生焦点快速路径，不再创建 `OtvNav.Node`、
  不写入 `OtvNav` 注册表，也不安装 `TvDirectionKeyHandler`/几何派发；详见 7.2。

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/TvPerformanceProfile.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/components/Focus.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/components/TvInteractionMotion.kt`

### 4.5 顶部标签栏与浮层

- 低配机关闭标签栏透明度和位移动画，直接切换状态。
- 低配 `TabBar` 传入 `autoSelectDelayMs=24L`，但实际切页仍由“方向键松开”控制：
  `dpadFocusable` 先等待当前方向键释放，再按该值等待 24 ms 提交；`BALANCED` 为 80 ms。
  详见 7.1。
- 每个 `TabBar` 实例用独立 owner 持有顶部 chrome 状态。新页进入时会重新取得所有权，
  旧页销毁只能释放自己持有的状态，不能把新页刚设置的阈值和显隐状态清空。
- 公告进入、退出和 20 秒进度条动画关闭；自动收起计时仍保留。
- 会员页、激活页的入场、淡入、二维码过渡和光标脉冲关闭。

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/ui/components/TabBar.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/components/AnnouncementBanner.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/paywall/PaywallScreen.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/paywall/ActivateScreen.kt`

### 4.6 图片与网络

- `RemotePoster` 请求和首页 hero 请求都显式设置 `.crossfade(false)`，海报/hero 可用后直接显示；
  低配全局 `ImageLoader` 也使用 `.crossfade(false)`，`BALANCED` 的全局默认虽开启，但这两类请求仍显式关闭。
- 海报按显示宽度 1x 解码；正常设备使用 2x。1920 宽屏上低配海报请求为 232 × 352 px，
  hero 请求为 1920 × 500 px。
- 海报请求固定 `.allowHardware(true)`；hero 请求使用
  `.allowHardware(TvPerformanceProfile.isConstrained)`，即 S621 低配档允许硬件位图，
  `BALANCED` 档为 `false`。图片请求的旧“hero 软件兼容”注释不再代表当前实际参数，本文以请求代码为准。
- 启动封面预热上限为 8 张、并发 2；正常设备上限 64、并发 8。
- Coil 内存缓存低配为最大堆的 25%，正常设备为 20%；磁盘缓存统一 256 MB。
- 低配图片解码并发限制为 2；正常设备不额外限制解码并发。
- OkHttp 并发限制为 24 个总请求、10 个同主机请求；正常设备为 128 / 32。
- 复用 Coil 内存缓存和磁盘缓存，回页、滚动回看优先命中缓存。
- `RemotePoster` 仍保留候选 URL 失败回退、空图检测和渐变占位，避免单张封面失败把卡片留空。

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/TvPerformanceProfile.kt`
- `app/src/main/java/com/qiubo/optimaltv/App.kt`

### 4.7 语义树裁剪

低配机检测到厂商常驻按键无障碍服务（`com.jmgo.*`）且未启用 TalkBack 时：

- 保留 Compose 原生焦点、方向键、确认键和点击。
- 对整页或闭合播放器面板使用 `clearAndSetSemantics {}`，停止为焦点变化反复构建大型语义树。
- 检测到真实屏幕阅读器时自动关闭该裁剪，保留无障碍导航。

覆盖页面：

- 首页、搜索、我的、详情、直播、电视直播
- 播放器及播放器侧栏
- 登录门、会员页、激活页、片单/全部页

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/TvPerformanceProfile.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/components/TvInteractionMotion.kt`

## 5. 逐页策略

| 页面 | 低配策略 | 保留能力 |
| --- | --- | --- |
| 首页海报 | 关闭左右切换 Crossfade，删除标题模糊阴影；栏目头与海报行拆成独立 Lazy item | 左右切换、最近观看轮播、进入详情 |
| 首页卡片 | 卡片 1.03×/90 ms 轻缩放，图片淡入关闭，焦点描边直接绘制；普通卡片走原生焦点快速路径 | 全部卡片可聚焦、可点击、可滚动 |
| 顶部标签栏 | 关闭透明度和位移动画；低配方向键松开后 24 ms 切页 | 七个标签全部可移动和切换 |
| 搜索 | 关闭页面动效和语义导出；结果卡保留 1.03×/90 ms 缩放，键盘滚动瞬时落位 | 字母/数字输入、候选词、分类、结果卡和返回落焦 |
| 我的 | 关闭语义导出；收藏/历史卡保留 1.03×/90 ms 缩放，映射使用缓存 | 账号、收藏、历史、设置入口全部保留 |
| 详情 | 关闭页面动效与语义导出，按钮和选集使用直接焦点反馈 | 收藏、续播、从头看、选集和分页 |
| 播放器 | 关闭 scrim 淡入淡出、标题模糊阴影和侧栏阴影 | 播放、暂停、快进、倍速、弹幕、音轨、字幕 |
| 播放器侧栏 | 8 组颜色动画改直接切色；低配进度刷新量化为 10 秒；固定头部高度 | 选集、播放设置、片源三个标签，全部条目可达 |
| 直播 | 关闭 hero 内容切换动画；进入立即观看按钮直接回顶 | 比赛切换、筛选、进入直播 |
| 会员/激活 | 关闭入场、二维码淡入和光标脉冲 | 激活码输入、二维码和遥控器操作 |
| 登录门 | 关闭焦点缩放并裁剪语义树 | 账号输入、登录、退出 |

## 6. 播放器侧栏专项

副栏卡顿来自多个成本叠加：

- 面板打开时同时绘制海报、渐变、阴影和大量焦点节点
- 方向键移动触发颜色动画和重新测量
- 厂商无障碍服务扫描整棵侧栏语义树
- 播放器进度每秒刷新导致面板一起重组

低配处理：

1. 关闭面板 28 dp 阴影和海报 10 dp 阴影。
2. 关闭标签、选集、设置、片源卡片的颜色动画。
3. 焦点底色和描边在 draw 阶段读取状态，不触发重组。
4. 面板头部固定 126 dp，减少动态测量。
5. 侧栏打开时将进度 UI 更新量化为 10 秒，播放本身仍按原时钟推进。
6. 低配 + 厂商扫描器场景下，整块侧栏不导出语义树。
7. 方向键滚入使用低配 120 ms 短滚动策略，避免侧栏内容单帧瞬跳。
8. 影片介绍区不再单独显示“电影”行；标题与右侧时间码同排，简介允许自动换行，
   最多 3 行，整体高度不超过左侧 `84 × 126 dp` 海报。

相关文件：

- `app/src/main/java/com/qiubo/optimaltv/ui/player/PlayerClassicSidebar.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/player/PlayerScreen.kt`
- `app/src/main/java/com/qiubo/optimaltv/ui/player/PlayerViewModel.kt`

## 7. 2026-11-04 低配流畅度专项

本轮针对 S621（Android 13 / 1920×1080 / `maxHeapMb=160` / `CONSTRAINED`）处理三类路径：
切栏防抖、普通卡片焦点注册、首页栏目组合与图片首开。结论先说清楚：本轮主要改善的是
“按下后多久看到页面切换”的感知延迟，不是把所有帧耗时都压到 33.3 ms 以内。

### 7.1 切栏：固定 180 ms 改为“松手即切”（24 ms）

旧实现中，低配 `TabBar` 传入 `autoSelectDelayMs=180L`，`dpadFocusable` 在标签获得焦点后
固定等待 180 ms 再调用切页。单次按键还叠加焦点到达和组合调度时间，主观上会出现
“按完以后页面还要顿一下”的感觉。

现实现：

- 新增的 `TvDirectionKeyHandler` 在每次方向键事件中把 `keyCode + action` 交给
  `OtvNav.onDirectionKeyAction`；`DirectionKeyHoldState` 按 keyCode 维护当前按住的键，
  `ACTION_UP` 只清除对应键，避免不同方向键的交错事件把长按误判为已松开。
- 低配 `autoSelect` 分支先调用 `OtvNav.awaitDirectionIdle()`；只有当前仍持有焦点且
  `isFocusCurrent(bandId, focusEpoch)` 时，才执行 `delay(autoSelectDelayMs)`（低配 24 ms）
  并调用 `onClick()`。
- `awaitDirectionIdle()` 每 16 ms 检查一次，正常收到 `ACTION_UP` 即返回。系统持续发送
  按键重复事件时不会进入空闲；若 UP 丢失，则以 1.5 秒无新方向键作为兜底。
  整体最多等待 3 秒，超过上限直接返回失败，不在用户仍按住方向键时提交切页。
- 提交前还会检查 `focused` 和 `OtvNav.isFocusCurrent(bandId, focusEpoch)`。只有当前仍持有焦点、
  焦点事务没有被后续事件替换的节点才能切页。
- `TabBar.kt` 低配传 `autoSelectDelayMs=24L`，正常设备传 80 ms；低配的实际提交时机仍是
  “方向键松手 + 24 ms”，不是 24 ms 后无条件切页。

为什么连续跨标签不会反复重组：

- 按住方向键横扫标签时，`awaitDirectionIdle()` 尚未返回，`onClick()` 不会执行；这段过程只改变焦点、
  焦点底色/描边，不会提交新的 `NavHost` 目标，也不会组合或销毁整页内容。
- 每个曾获得焦点的标签可能留下一个等待协程，但失焦后 `stillWaiting()` 立即为 false，
  旧协程会在下一次 16 ms 检查时退出；松手后也只有最终焦点节点能通过
  `focused && isFocusCurrent(bandId, focusEpoch)`，不会把页面切回旧标签。
- 因此一次连续横扫最多提交一次目标页切换；不是每跨一个标签就重开一页。24 ms 是为了给焦点落定和
  当前组合留出约一帧量级的余量，不是新的固定防抖窗口。

这里说的“不会反复重组”指不会重复组合/销毁目标页或提交多次整页路由切换；标签自身的焦点态仍会
更新，但不触发页面切换事务。

顶部 chrome 的隐藏阈值和显隐状态是 `OtvNav` 上的全局状态。页面切换期间新旧 `TabBar`
可能短暂共存；`ChromeOwnership` 只允许当前 owner 执行 `releaseChrome`，避免旧页面的
`onDispose` 把新页刚取得的阈值清零，或把已隐藏状态改回显示。

### 7.2 低配焦点快速路径

`Focus.kt` 的 `dpadFocusable` 先计算是否需要完整导航注册：

```text
navOverride != null ||
LocalNavOverride.current != null ||
externalFocusRequester != null ||
onFocusedChange != null ||
autoSelect ||
floating ||
navGroup != null
```

当 `TvPerformanceProfile.isConstrained` 为真、且上述条件全部不满足时，普通卡片走原生焦点快速路径：

- 不创建 `OtvNav.Node`，不调用 `registerCard` / `unregisterCard`，不写入 `OtvNav` 几何注册表。
- 不安装 `TvDirectionKeyHandler`，不执行 `OtvNav` 的方向几何搜索，直接使用 Compose 原生焦点导航。
- 除显式启用的海报卡片外，不创建 `animateFloatAsState` 缩放动画，也不创建休眠
  `graphicsLayer`；普通控件只通过 draw 阶段的描边和底色表达焦点。
- 仍保留 `onGloballyPositioned` 记录 top 位置，以及 `onFocusEvent` 调用
  `OtvNav.onNativeFocusEvent`，仅用于顶部 chrome 显隐和当前分组等轻量状态，不再参与几何候选。

需要 `navOverride`（例如首页首行“向上回 hero”）、外部落焦、自动选中、浮层分组或
`onFocusedChange` 的节点仍走完整 `OtvNav`。顶栏同时具备 `floating`、`onFocusedChange` 和
`autoSelect`，所以仍走完整路径；这也是“低配普通卡片快速路径”与“顶栏松手切页”可以同时成立的原因。

### 7.3 首页拆分、目录索引与图片路径

- `HomeScreen.kt` 把每个栏目的标题和海报行拆成两个独立的 `LazyColumn` item，分别使用
  `recommendations-header:$index` 和 `recommendations-row:$index` 的稳定 key 与 contentType。
  这样下方标题进入视口时，不会顺带把尚未进入视口的大段海报行全部组合；此前标题和海报行合在一个
  item 内，切页时会出现多组离屏海报组合树。
- `catalogById` 由 `remember(catalog.catalog)` 缓存，只在该目录对象变化时重新构建；
  最近观看 hero 计算复用该 Map，避免每次重组都 flatten 并 `associateBy` 全目录。
- `HomeRecommendationRow` 的 `recommendationColumns` 按 `entries` 与 `rows` 记忆；低配首个推荐栏目
  只排 1 行，正常设备首个栏目可排 2 行。
- 海报规格按显示宽度计算：设计基准 232 × 352，低配 `posterDecodeScale=1f`，正常设备为 `2f`；
  hero 请求按 1920 设计宽和 500 设计高裁到可见条带，不按整张超宽原图解码。
- `RemotePoster` 的请求保持 `.crossfade(false)`，并保留候选 URL 回退、空图检测和渐变占位；
  首页 hero 请求也显式 `.crossfade(false)`。低配 `ImageLoader` 同样关闭全局 crossfade。
- `MainActivity.kt` 在低配向 `LocalBringIntoViewSpec` 注入 `TvShortBringIntoViewSpec`，
  其 `focusRevealScrollSpec` 为 120 ms；正常设备使用默认行为。`OtvNav.ensureVisible`
  的自定义补滚走同一个 `TvInteractionMotion.focusRevealScrollSpec`，同样为低配 120 ms。

### 7.4 原始性能记录

以下均来自 S621 实机 `dumpsys gfxinfo` 原始文件，帧耗时单位均为 ms。不同构建、不同场景不能混用。

| 场景 | 原始文件 | 帧数 | 卡顿帧 | P50 | P90 |
| --- | --- | ---: | ---: | ---: | ---: |
| 冷切：电影 → 短剧 | `D:\Codex\2026-11-04\video-pro-fluency9\110-fluency15-cold-short-framestats.txt` | 5 | 5（100%） | 46 | 121 |
| 热切：短剧 → 国漫 | `D:\Codex\2026-11-04\video-pro-fluency9\111-fluency15-warm-anime-framestats.txt` | 3 | 3（100%） | 48 | 57 |
| 热切：国漫 → 短剧 | `D:\Codex\2026-11-04\video-pro-fluency9\112-fluency15-warm-short-framestats.txt` | 3 | 3（100%） | 48 | 57 |
| 首页海报行连续 8 次右移 | `D:\Codex\2026-11-04\video-pro-fluency9\115-row-right8-gfx.txt` | 131 | 78（59.54%） | 25 | 34 |

`fluency15` 的冷切比旧记录少 1 帧，P50 从 121 ms 降到 46 ms；热切两组都稳定在 3 帧、
P50 48 / P90 57 ms。感知上的固定 180 ms 防抖已经移除，但整页首开、组合和销毁仍存在：
冷切最大帧 121 ms、热切 P90 57 ms 都超过 33.3 ms 预算，不能把“松手即切”写成“页面切换已达标”。

首页海报行这组 8 次移动为 P90 34 ms，P95 36 ms，但仍只有 53/131 帧在 33.3 ms 以内，
不能外推为所有页面、所有滚动路径都满足帧预算。

### 7.5 尚未完全达标

- 冷切仍会明显超过 33 ms/帧：`fluency15` 为 5 帧、P50 46 ms、P90 121 ms；
  这不是“已经解决冷启动/首开成本”。
- 热切减少到 3 帧，但 P90 仍为 57 ms；不能当作完整帧预算验收。
- 首页海报行 P90 34 ms、卡顿帧 78/131（59.54%），仍高于单项移动 33.3 ms 目标。
- 播放器侧栏 `DOWN × 8` 三轮 P90 为 38/42/44 ms；首次打开 P90 150 ms。
  前者未达 33.3 ms，后者达到 250 ms 验收线。
- 本轮优先解决操作到切页的感知延迟、减少无效焦点注册与离屏海报组合，不等于真机长播、
  音频、后台/唤醒、全部播放器控制项和完整遥控器矩阵已经验收。
- 最终候选包的重点实机验证结果见 10.3；10.2 的实机结果属于历史验证包，不能直接套用。

## 8. 已采集的性能对照（历史记录）

以下为 2026-11-04 低配流畅度专项之前的 S621 实机记录，保留用于对照；
不同构建之间不能混用。

### 8.1 播放器侧栏

| 指标 | 优化前 | 优化后（页面滚动策略定稿前） |
| --- | --- | --- |
| 首次打开 P50 | 约 400 ms | 约 300-350 ms |
| 首次打开卡顿帧 | 100% | 100% |
| 单次 DOWN | 57-105 ms | 65-81 ms；语义裁剪后 30-38 ms |
| 首帧 | - | 335 ms |
| measure/layout | - | 162 ms |
| recompose | - | 82.8 ms |
| applyChanges | - | 44.5 ms |

此前验证包（`4A2A5111B977F3E176D7E9FBC7CDE2E60CFB0A025DC897E876E71EA09CA1715D`）
的 S621 复测数据（历史记录）：

| 场景 | 帧数 | 卡顿帧 | P50 | P90 | P95 | P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 侧栏“片源”连续 8 次 DOWN | 47 | 85.11% | 38 ms | 44 ms | 44 ms | 57 ms |
| 顶栏连续切换 | 31 | 100% | 133 ms | 300 ms | 350 ms | 400 ms |
| 电视剧/国漫来回切换（热页） | 44 | 97.73% | 125 ms | 350 ms | 350 ms | 400 ms |

侧栏单项移动较旧版本明显下降，但顶栏切换仍存在整页首开、测量和销毁成本，
未达到“单次 D-pad 移动 P90 <= 33.3 ms”的验收线。

### 8.2 标签页切换

| 指标 | 优化前 |
| --- | --- |
| 多标签快切帧数 | 24 帧 |
| 卡顿帧 | 100% |
| P50 | 150 ms |
| P90 | 400 ms |

“我的 → 搜索”单次 trace：

- 首帧 245.55 ms
- measure/layout 107.56 ms
- 第二帧 112.58 ms

上述标签页 gfx 数据属于此前验证包；本轮低配流畅度专项的原始数据见第 7 节，
页面切换仍是最主要的未达标项。

## 9. 验收标准

### 流畅度

- 单次 D-pad 移动 P90 `<= 33.3 ms`
- 侧栏首次打开 P90 `<= 250 ms`
- 连续 100 次 D-pad 操作无丢焦、无越级跳焦、无意外点击
- 页面切换不在首帧后出现超过 100 ms 的主线程长任务
- 连续打开/关闭侧栏 20 次无 ANR、崩溃或内存持续增长

### 功能

- 每个页面所有可见按钮、卡片和列表项均可到达并确认。
- Back 层级正确，退出详情/播放器后焦点回到原入口。
- 卡片缩放：`BALANCED` 为 1.08×，`CONSTRAINED` 上限为 1.03×；其他控件不缩放。
- 从下方进入首页“继续播放/立即播放”时页面立即回到顶部。
- 每个首页标签在入口前已有第一栏内容，其他栏目进入后继续加载。

### 无障碍

- 未启用厂商扫描器或检测到 TalkBack 时，语义树正常导出。
- 低配语义裁剪不能影响真实屏幕阅读器。
- 焦点描边对比度满足电视远距离阅读，深色和浅色海报都可见。

## 10. 当前验证状态

当前最终候选包（`37DC8E21B8FAD52354045F02ACB2B04D429AC4088359793F4A9B9F01505B60A5`）
已完成以下核验：

- `:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均成功；
  仪器测试 APK 为 2,768,795 bytes，SHA-256 为
  `48EB436EB447F1C29E3BF81FE528B343A7AA80A2BFFDD2C773726754DA215B13`。
- `:app:testDebugUnitTest`：117 个测试套件、610 项测试，0 失败、0 错误、0 跳过。
- `:app:lintDebug`：0 error、32 warning。
- 最终 APK 已覆盖安装到 S621；`versionName=0.2.0`、`versionCode=2`，
  `lastUpdateTime=2026-11-04 18:53:32`，未卸载、未清数据。
- 方向键长按 350 ms 时焦点已移到“国漫”，页面仍停留在“电影”；松手后才切换。
- 七个顶栏入口、搜索/“我的”返回、海报行横移、跨行滚动、返回顶部和直接续播均完成实机操作。
- 顶部 chrome 在 `113/114` 内容区无栏，`116` 返回顶部后恢复，旧页销毁未覆盖新页状态。
- 播放器真实出画并持续解码；暂停后侧栏从第 10 集连续下移 8 次到第 34 集，
  三轮 `gfxinfo` 为 P50 34–36 ms、P90 38–44 ms。

以上结果仅属于 `37DC.../fluency15`。10.2 中的 `4A2A...` 结果保留为历史包记录，
不能与最终包混用；完整音频、长播、后台/唤醒和全部播放器控制项仍未验收。

尚未完成：

- 全部页面逐位置遥控器矩阵
- 搜索键盘完整按键矩阵、详情收藏往返与全部分页
- 原 APP 同账号完整功能对照
- 音频、长播、后台/唤醒和播放器全部控制项
- 页面切换 P90 <= 33.3 ms 的流畅度目标

### 10.1 隔离低配模拟器回归（2026-11-04）

为在不触碰 S621 的前提下验证低配路径，使用
`video_pro_native_qa_20261103`（Android 13 / API 30 x86_64，软件 GPU）并以
`Runtime.maxMemory = 192 MB` 启动。启动日志确认：

```text
performance tier=CONSTRAINED maxHeapMb=192 cpuCount=4 lowRamDevice=false focusScale=false
```

评测包 SHA-256 为 `D831FE014A803DD756DD3D490BE92585AF88F13156BF355E9C76CD7503D40C9B`，
使用 QA 证书覆盖安装，未卸载、未清数据。以下结果均为模拟器结果，不能替代 S621 实机：

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 冷启动进入首页 | 通过；首屏内容可用，无崩溃或 OOM | `lowtier-001-launch.png` |
| 七个顶部标签 | 电影、电视剧、国漫、短剧、综艺、搜索、我的均可到达并实际切换 | `170`–`180` 系列截图与 UI 树 |
| 从下方进入“继续播放” | 连续向下滚动后向上，页面完整回到顶部并落在“继续播放” | `010`–`011-up-4.png` |
| 首页第一行进入第二行 | 通过；逐帧对比确认存在连续中间位置，不再单帧瞬移 | `315-home-jump-fixed.mp4`、`355-final-jump.mp4` |
| 观看记录样式 | 纯文字，无背景框/环形符号/中点；整行同一字号、白色、斜体、下划线 | `lowtier-001-launch.png`、`lowtier-153-back-home.png` |
| 搜索输入 | 方向键键入 `love`，候选词、分类标签和结果卡逐级可达 | `040`–`050` 系列 |
| 搜索结果打开详情并返回 | 通过；返回后仍落在原结果卡 | `050`–`053` 系列 |
| 详情收藏往返 | `☆ 收藏` → `★ 已收藏` → `☆ 收藏` 往返通过 | `059`–`061` 系列 |
| 剧集分页与选集 | `1-20` / `21-40` / `41-55` 与剧集网格可达；确认第 7 集后实际起播 | `194`–`199` 系列 |
| 实际出画与暂停/恢复 | 真实影片出画；暂停 4 秒画面完全一致，恢复后画面持续变化 | `083`、`120`–`123` |
| 快进 | 播放器收起态连续右方向键，进度由 17:10 推进到 17:40 | `130` 系列 |
| 播放器侧栏 | MENU 打开“播放设置”，DOWN 扫过全部设置项 | `090`–`094` 系列 |
| 侧栏片源 | 切换到“片源”，12 个片源可达；切换线路后进度继续推进 | `095`–`113` 系列 |
| 侧栏连续 D-pad | 8 次 DOWN：7 帧，2 帧卡顿，P50 12 ms，P90 27 ms | `091-sidebar-down8`、`092-sidebar-gfx.txt` |
| 侧栏首次打开 | 2 帧，P50 28 ms（热进程、软件 GPU） | `140-sidebar-open-gfx.txt` |
| 播放错误兜底 | HTTP 403 时显示重试/换源/返回，换源面板可打开 | `185`–`188` 系列 |
| 稳定性 | 全程无 App FATAL、ANR 或 OOM 记录 | logcat |

补充边界：

- 该模拟器使用 SwiftShader 软件 GPU，绝对帧耗时明显高于真实硬件；侧栏数据用于证明
  “低配路径没有反复触发动画/重组”，不能直接当成 S621 的毫秒数。
- 标签切换仍会出现整页首开成本；模拟器三次切换为 19 帧、P50 48 ms、P90 200 ms。
  该数据只说明页面组合成本仍存在，最终是否达标必须在 S621 上复测。
- 本轮没有完成音频输出、长播、后台/唤醒、弹幕实际渲染、音轨字幕和原 APP 全量同条件对照。
- 播放器 HTTP 403 错误页出现英文按钮 `Change source`，与本轮低配改动无关，但属于当前可见的一致性遗留项。

### 10.2 S621 实机验收（2026-11-04，历史验证包）

设备与包：

```text
S621 / Android 13 / 1920 × 1080
performance tier=CONSTRAINED maxHeapMb=160 cpuCount=4 lowRamDevice=false
versionName=0.2.0 versionCode=2
APK SHA-256=4A2A5111B977F3E176D7E9FBC7CDE2E60CFB0A025DC897E876E71EA09CA1715D
```

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 冷启动与首栏门闸 | 通过；电影、电视剧、国漫、短剧、综艺首栏加载完成后进入首页 | `s621-001-launch.png`、启动日志 |
| 七个顶栏 | 通过；七个入口均实际切换并显示首屏内容 | `s621-024` 至 `s621-030` |
| 首页回顶 | 通过；从下方连续向上进入“继续播放”时页面完整回到顶部 | `s621-021`、`s621-022`、`s621-023` |
| 电影影片介绍区 | 通过；无单独“电影”行，简介 3 行且未超过海报底部 | `s621-013-header-corrected.png` |
| 电视剧影片介绍区 | 通过；`第 07 集`并入元信息行，简介仍限制在海报内 | `s621-036-series-sidebar-header.png` |
| 侧栏标签 | 通过；选集、播放设置、片源可移动并切换 | `s621-038` 至 `s621-040` |
| 片源列表 | 通过；连续 8 次 DOWN 可移动到“极速资源” | `s621-041-series-sources-down8.png` |
| 换源播放 | 通过；切换到“极速资源”后首帧渲染并继续播放 | `s621-042-series-source-switched.png`、播放器日志 |
| 播放器返回 | 通过；面板、播放器、详情、首页层级正确，详情焦点可恢复 | `s621-043`、`s621-044`、`s621-045` |
| 稳定性 | 通过；本轮无 App FATAL、ANR 或 OOM | logcat |

性能边界：

- 侧栏“片源”连续 8 次 DOWN 为 P50 38 ms、P90 44 ms，较旧版本下降，但仍高于 33.3 ms 目标。
- 顶栏切换为 P50 133 ms、P90 300 ms，卡顿帧 100%；整页首开和销毁仍是主要瓶颈。
- 以上数据来自 S621 实机，不能与隔离模拟器的 SwiftShader 数据混用。

### 10.3 当前最终候选包（2026-11-04 fluency15）

最终 APK：

```text
D:\Codex\video-pro-native-build-20261104-fluency15\app\outputs\apk\debug\app-debug.apk
SHA-256=37DC8E21B8FAD52354045F02ACB2B04D429AC4088359793F4A9B9F01505B60A5
size=170,898,330 bytes
versionName=0.2.0 versionCode=2
```

重点实机结果：

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 长按防误切 | 按住 350 ms 时焦点到“国漫”、页面仍是“电影”；松手后切到“国漫” | `101-longpress-hold-350ms.png`、`102-longpress-released.png` |
| 冷/热切栏 | 冷切 5 帧 P50 46 / P90 121 ms；热切两组 3 帧 P50 48 / P90 57 ms | `110`–`112` framestats |
| 海报行横移与跨行 | 8 次右移 131 帧 P50 25 / P90 34 ms；跨行滚动可达 | `115-row-right8-gfx.txt`、`043`–`044` 截图 |
| chrome 显隐 | 内容区无顶栏；返回顶部恢复顶栏；旧页销毁未清掉新页状态 | `113`、`114`、`116` 截图 |
| 播放器出画 | 直接续播成功；两帧截图内容变化，logcat 显示连续 30 fps 解码 | `123-player-playing-8s.png`、`124-player-playing-13s.png` |
| 播放器侧栏 | 暂停后 MENU 打开，焦点从第 10 集到第 34 集；三轮均保持面板 | `129`、`131` framestats |
| 侧栏连续 `DOWN × 8` | 35/37/40 帧；P50 36/34/34 ms，P90 44/42/38 ms | `129`、`131-2`、`131-3` framestats |
| 侧栏首次打开 | 三次均 3 帧；P50 105/150/38 ms，P90 均 150 ms | `130-fluency15-sidebar-open-1..3` |

构建与检查：

```text
:app:assembleDebug                 BUILD SUCCESSFUL
:app:assembleDebugAndroidTest      BUILD SUCCESSFUL
:app:testDebugUnitTest             117 suites / 610 tests / 0 failed
:app:lintDebug                     0 error / 32 warning
```

设备内 `base.apk` 的 `sha256sum` 为
`37dc8e21b8fad52354045f02acb2b04d429ac4088359793f4a9b9f01505b60a5`，大小
`170898330` bytes，与构建产物一致。本轮播放器测试 logcat 未命中本应用
`FATAL EXCEPTION`、ANR 或 `OutOfMemoryError`。

边界：

- 冷切 P90 121 ms、热切 P90 57 ms、海报行 P90 34 ms、侧栏下移 P90 38–44 ms
  都仍高于 33.3 ms 的单帧目标；侧栏首次打开 P90 150 ms 达到 250 ms 验收线。
- 本轮只做侧栏快速复测和播放画面确认，没有重新执行 20 次开关、100 次连续 D-pad、
  长播、完整音频、后台/唤醒或原 APP 同条件全量对照。
- 播放器日志中仍可见厂商系统的 WLAN/事件追踪错误；没有出现本应用 FATAL、ANR 或 OOM。
- `fluency15` 是当前最终候选，不代表已经达到全部流畅度和业务验收标准。

## 11. 主要修改文件

| 文件 | 作用 |
| --- | --- |
| `TvPerformanceProfile.kt` | 设备分档、预载上限、语义裁剪判定 |
| `MainActivity.kt` | 页面过渡关闭、低配 `LocalBringIntoViewSpec` 注入 120 ms 焦点换行滚动 |
| `HomeStartupGate.kt` | 五标签第一栏启动门闸 |
| `HomeStartupPreload.kt` | 启动首栏重试与进度 |
| `App.kt` | 图片规格、硬件位图策略、双缓存、网络并发、无 crossfade 和后台预热 |
| `Focus.kt` | 低配原生焦点快速路径、松手后 24 ms 切页、卡片 1.03× opt-in 缩放和补滚动画 |
| `DirectionKeyHoldState.kt` | 按 keyCode 跟踪方向键按下/松开，处理丢失 UP 的兜底状态 |
| `ChromeOwnership.kt` | 防止旧页 `onDispose` 清除新页取得的顶栏阈值和显隐状态 |
| `TvInteractionMotion.kt` | 颜色动画、焦点滚动和语义树统一策略 |
| `TvDirectionKeyHandler.kt` | 方向键按下/松开跟踪，向 `OtvNav` 提供松手判定 |
| `TabBar.kt` | 低配关闭顶栏动效、24 ms 松手后 settle 和 chrome owner 接入 |
| `AnnouncementBanner.kt` | 公告动画关闭 |
| `RemotePoster.kt` | 海报请求尺寸、硬件位图、无淡入、候选回退和占位 |
| `HomeScreen.kt` | 首页海报、标题阴影、回顶、栏目头/海报行拆分和 `catalogById` 缓存 |
| `HomeRecommendationRow.kt` | 海报行组件、推荐列缓存和低配单行策略 |
| `HeroHistoryProgress.kt` | 观看记录纯文字样式 |
| `SearchScreen.kt` | 搜索页语义裁剪与瞬时滚动 |
| `FavoritesScreen.kt` | 我的页语义裁剪、收藏映射缓存 |
| `DetailScreen.kt` | 详情页语义裁剪与列表滚动 |
| `LiveScreen.kt` | 直播 hero 动画和回顶 |
| `TvScreen.kt` | 电视直播页语义裁剪 |
| `PlayerScreen.kt` | 播放器 scrim、标题阴影 |
| `PlayerClassicSidebar.kt` | 侧栏阴影/动画、绘制焦点、语义裁剪，以及影片介绍区与简介高度布局 |
| `PlayerViewModel.kt` | 侧栏打开时进度刷新量化 |
| `PaywallScreen.kt` / `ActivateScreen.kt` | 会员页和激活页动效关闭 |
| `DongpianLoginGate.kt` | 登录页语义裁剪 |

## 12. 回退与风险

- 所有优化由 `TvPerformanceProfile` 统一控制，不需要逐个界面手工恢复。
- 正常设备继续使用原动画和 2x 海报解码。
- 语义树裁剪只在“低配 + 厂商扫描器 + 无 TalkBack”三项同时满足时开启。
- 启动门闸等待五个标签第一栏是用户明确要求；若网络失败，会重试并显示可聚焦的错误页，不会静默进入空白首页。
- 进度 10 秒量化只影响播放器侧栏的显示刷新，不影响实际播放、保存进度或续播精度。
- 松手切页依赖方向键 UP；UP 丢失时用 1.5 秒无新按键兜底，整体最多等 3 秒，
  不应把 24 ms 理解为无条件固定提交时间。
- 最终候选包 `37DC...` 已完成重点实机回归，但尚未完成全量遥控器、播放器控制项和原 APP 对照；
  页面切换、海报行和侧栏移动性能仍未完全达标，不能将代码完成等同于最终业务或性能验收完成。
