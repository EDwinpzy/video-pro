# Global Voice Helper

独立安装包 `com.dongpiandi.voicehelper`，不依赖 Video Pro、Tigris 或其他业务 App。

## 当前行为

- 有焦点输入框时（即使软键盘暂时隐藏），使用已安装的 `com.jmgo.ai.voice`
  iFlytek AIUI 识别，松键后把文字直接提交到当前输入连接；不启动大模型、
  不显示助手界面、不播放 TTS。
- 没有输入框时，helper 自己接管遥控器语音键，显示自有聆听/思考/回答覆盖层；
  识别仍由原厂 AIUI 完成，大模型回答由原厂 TTS 播报。
- helper 会关闭 `com.jmgo.hippo` 的原厂按键服务，避免同一按键再打开原厂语音助手窗口；
  收到意外的原厂 `AI_VOICE` 广播时，仍会立即取消原厂会话并关闭残留窗口。
- helper 不修改其他 App 的无障碍设置。如果 Video Pro 的旧听写无障碍服务仍启用，
  设置页会拒绝继续并提示先手动关闭；即使绕过设置页在系统中同时启用两个服务，
  helper 也会自行停用，避免两个服务同时消费语音键。
- 原厂语音包只作为识别和 TTS 引擎保留。完全不卸载它，因为 S621 上的麦克风路由、
  AIUI 和 TTS 都在该系统中。

## 大模型

模型顺序固定：

1. `group/free`
2. `auto/deepseek-v4.1-flash`

网络、超时、可重试 HTTP、空内容和格式错误会自动切换下一个模型。首个模型最多等待
10 秒，给网络搜索留出时间但避免 `group/free` 长时间占用整个回答；第二个模型最多等待
30 秒。`401`、`403`、本地配置无效和任务取消不会重试。API 配置在运行时输入，使用
`EncryptedSharedPreferences` 保存，不写入 Gradle、源码、资源或日志。

请求采用 Codex/Magpie 当前使用的 Responses API 协议：

- `POST /v1/responses`
- 结构化 `input` 与 `instructions`
- `tools: [{"type":"web_search","external_web_access":true,"search_context_size":"low"}]`
- `tool_choice: "auto"`、`max_tool_calls: 1`、`parallel_tool_calls: false`、`store: false`

新闻、天气、价格、版本等可能变化的问题会由模型调用一次 `web_search` 获取最新资料。
限制为单次搜索可以避免 `group/free` 连续搜索多次、耗尽输出额度后没有最终回答。
如果 Base URL 填的是 `/v1/chat/completions`，helper 会自动规范化到 `/v1/responses`。

## 安装

1. 安装并打开 `全局语音助手`。
2. 允许麦克风权限。
3. 输入 OpenAI-compatible Base URL 和 API Key，保存配置。
4. 开启 helper 的无障碍服务；在 S621 上可通过设置页请求原厂无障碍控制器打开。
5. 如果 Video Pro 的旧听写无障碍服务仍启用，先在系统无障碍设置中手动关闭，
   否则 helper 设置页会提示冲突。

不再需要 `READ_LOGS`，也不再抓取原厂窗口节点或 logcat 中的 ASR 文本。

## 设备要求

- S621 / Android 13
- 32 位 `armeabi-v7a` 进程
- 原厂 `com.jmgo.ai.voice` 已安装并可加载 AIUI 与 TTS

## 验证

```powershell
.\gradlew.bat :voice-core:testDebugUnitTest :voice-helper:testDebugUnitTest `
  :voice-core:lintDebug :voice-helper:lintDebug :voice-helper:assembleDebug
```

Debug 构建仍提供 shell-only QA receiver，可通过 `DUMP` 权限注入文本/PCM 或触发一次
语音键流程；这些入口不会进入 release。

## 边界

- S621 上的真实输入设备事件、输入框提交、原厂 AIUI 识别、模型降级、原厂 TTS 和
  自有覆盖层可自动验收；注入 PCM 不能代替“人对着实体遥控器麦克风说话”的声学验收。
- 原厂语音包是闭源系统组件。helper 不启动其 UI/NLP，但不能在没有 root 的情况下
  物理删除该包；这样做会使原厂识别、TTS 和按键音频路由失去依赖。
