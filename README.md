# 巴别回声 Android

> 将散落的声音，整理成可检索的会议记录。

巴别回声 Android 是一款处于早期测试阶段的原生 Android 会议录音与整理工具。它可以同时采集麦克风和 Android 允许捕获的系统媒体声音，将录音保存在本机，并通过你自己配置的 OpenAI 兼容服务完成转写与摘要。

**当前版本：`0.1.0-alpha.1`**

## 已实现

- 麦克风录音、系统媒体声音录制，或两者实时混音
- 本地保存标准 PCM WAV，不会自动上传
- 每次采集系统声音前显示 Android 系统授权页
- 可编辑 ASR/LLM 服务地址、模型 ID、语言与 API Key
- 兼容 Groq、DeepSeek、OpenAI 兼容网关与局域网本地模型服务
- API Key 由 Android Keystore 生成的 AES-GCM 密钥加密保存
- 转写分段时使用可调重叠窗口，并把前文尾部传给下一段，减少句子被切断
- 摘要仅手动触发；长文本使用分层摘要，避免滚动摘要反复扩大上下文
- 简体中文界面，系统为其他语言时提供英文应用名与系统通知

## 系统要求与限制

- Android 10（API 29）或更高版本
- 系统声音使用 Android `AudioPlaybackCapture`。播放器必须允许第三方捕获，因此通话、受 DRM 保护的内容，以及主动禁止捕获的应用可能没有声音。
- Android 14 及以上每次录制都必须重新取得媒体投影同意，这是系统隐私要求。
- 当前 APK 是测试签名版本，不适合商店分发。正式发布前需要独立签名、隐私政策页面与更多设备测试。

## 使用

1. 在“设置”中填写语音识别服务和摘要模型。
2. 选择麦克风、系统声音或两者。
3. 点击“开始录音”，并按系统提示授权。
4. 停止后，录音仅保存在应用私有目录。
5. 点击“转写”或“生成摘要”时，相关内容才会发送到你配置的服务。

局域网本地模型可以使用 OpenAI 兼容地址，例如模拟器访问宿主机时常用的 `http://10.0.2.2:端口/v1`。只有在可信网络中才应启用“不安全 HTTP”。

## 构建

需要 JDK 17、Android SDK 37 和 Build Tools 36.0.0：

```bash
./gradlew :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 也会在每次推送后生成可下载的测试 APK artifact。

## 隐私与合规

请先取得会议参与者同意，并遵守所在地关于录音、个人信息和跨境传输的规定。项目不包含任何 API Key、用户录音或会议数据。详细说明见 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md)。

## English

Babel Echo Android is an early native Android meeting recorder. It can mix microphone input with capturable media playback, save PCM WAV recordings locally, and send content to user-configured OpenAI-compatible transcription and chat services only after an explicit action.

The app requires Android 10+. Playback capture is limited by Android: calls, DRM-protected media, and apps that disable capture may be silent. Android 14+ requires fresh MediaProjection consent for every recording session.

API keys are encrypted with an AES-GCM key held by Android Keystore. Recordings stay in app-private storage until the user explicitly requests transcription. This alpha uses a debug signature and is intended for device testing, not store distribution.

## License

Source code is available under the Apache License 2.0. The Babel Echo name and artwork are not granted for unrelated commercial branding by the software license.
