# Global Voice Helper

独立安装包 `com.dongpiandi.voicehelper`，不依赖 Video Pro、Tigris 或其他业务 App。

## Behaviour

- A focused editor plus a visible input method uses the installed
  `com.jmgo.ai.voice` iFlytek AIUI engine for dictation. The only result is committed to the
  current input connection; no assistant, LLM, or TTS is opened.
- Without a focused editor, the factory `com.jmgo.hippo` key service stays enabled and shows the
  stock voice window. The helper collects the factory ASR text, suppresses the factory spoken
  reply, and requests the configured OpenAI-compatible endpoint.
- Model order is fixed:
  1. `group/free`
  2. `auto/deepseek-v4.1-flash`
- Network, timeout, retryable HTTP, empty-content, and malformed-response failures fall through to
  the next model. `401`, `403`, invalid local configuration, and cancellation stop immediately.

The API key is entered at runtime and stored with `EncryptedSharedPreferences`. It is never placed
in Gradle files, source, APK resources, or logs.

## Device package

- `minSdk 33`, `targetSdk 34`
- 32-bit process required by the factory AIUI runtime
- Device ABI: `armeabi-v7a`
- Emulator QA ABI: `x86`
- A small JNI stub is included because Android otherwise keeps a pure-Java APK in a 64-bit
  process even when `android:use32bitAbi="true"`.

## Setup

1. Install the APK and open `全局语音助手`.
2. Grant microphone permission.
3. Enter the user's OpenAI-compatible base URL and API key, then save.
4. Enable the helper accessibility service. On S621 the setup page can request this through the
   factory accessibility controller; other devices open Android accessibility settings.
5. Enable only one system dictation service. If Video Pro's old
   `SystemDictationService` is enabled, disable it first.

The voice-window node collector is the primary transcript source. For firmware that exposes ASR
only in logcat, QA can optionally grant the development permission:

```powershell
adb shell pm grant com.dongpiandi.voicehelper android.permission.READ_LOGS
```

## Closed-source vendor limits

- The factory listening window and factory ASR are used as-is.
- The factory package has no API for injecting a custom answer. The LLM answer is therefore shown
  in a separate accessibility overlay styled to match the stock voice UI.
- The factory key release also starts its own NLP path. The helper immediately cancels factory TTS
  and dismisses its dialog, but no public firmware API fully isolates factory ASR from factory NLP.
- Window-node text extraction still requires S621 hardware verification. If the firmware does not
  expose the recognition text, the optional logcat fallback is used after explicit ADB grant.

## Verification

```powershell
.\gradlew.bat :voice-core:testDebugUnitTest :voice-helper:testDebugUnitTest `
  :voice-core:lintDebug :voice-helper:lintDebug :voice-helper:assembleDebug
```

Debug builds expose a shell-only QA receiver guarded by `android.permission.DUMP`. It can inject a
transcript and configure an emulator mock endpoint without changing the release attack surface.
