# 巴别回声 Android / Babel Echo Android

> 将散落的声音，整理成可检索、可追问的会议记录。
> Turn scattered voices into searchable meeting records you can ask about.

巴别回声 Android 是原生 Android 会议录音与整理工具。它沿用桌面版的会议工作台结构，同时适配手机单手操作：录音过程中可查看实时转写与摘要，结束后可生成结构化纪要、翻译内容、追问会议信息并导出 Markdown。

Babel Echo Android is a native meeting recorder and assistant based on the desktop workspace. It combines live capture, transcription, summaries, structured minutes, translation, meeting Q&A, and Markdown sharing in a mobile interface.

**当前测试版 / Current alpha: `0.2.0-alpha.4`**

## 主要功能 / Features

- 麦克风、Android 允许捕获的系统媒体声音，或两者实时混音
- 录音暂停与继续、实时音量显示、本机 PCM WAV 存档
- 可调转写分段与重叠时间，并以前一段尾部提示下一段，减少句子切断
- 增量摘要只发送上次摘要和新增片段，降低长会议重复上下文消耗
- 转写、摘要、结构化纪要、翻译、带时间戳的会议问答与统计
- 工作区、历史记录、设置三段式导航，以及接近桌面版的半透明卡片视觉
- 中文、日文和英文界面与识别语言选择
- 分别指定摘要、纪要、翻译和问答模型
- 兼容 OpenAI 风格的云端接口与局域网本地服务；本地服务可设为无需 API Key
- API Key 使用 Android Keystore 生成的 AES-GCM 密钥加密保存

---

- Capture microphone audio, Android-permitted media playback, or a live mix of both
- Pause and resume, live level meter, and local PCM WAV archives
- Configurable ASR chunk and overlap windows with previous-text prompting across boundaries
- Incremental summaries send only the previous summary and new transcript segments
- Transcript, summary, structured minutes, translation, timestamp-aware Q&A, and statistics
- Mobile workspace, history, and settings navigation with a translucent card interface
- Chinese, Japanese, and English UI and recognition language choices
- Separate model IDs for summary, minutes, translation, and Q&A
- OpenAI-compatible cloud endpoints and LAN-hosted local services, including no-key local endpoints
- API keys encrypted with an AES-GCM key managed by Android Keystore

## 使用 / Getting started

1. 在“设置”填写 ASR 与文本模型地址、模型 ID 和密钥；本地无鉴权服务可开启“无需密钥”。
2. 选择麦克风、系统声音或两者，点击“开始录音”。
3. 使用系统声音时，按 Android 系统页面授权屏幕/媒体捕获。
4. 在工作区切换转写、摘要、纪要、问答和统计；结束后可从历史记录重新打开。
5. 使用分享按钮导出 Markdown 文本。

1. Configure ASR and text endpoints, model IDs, and keys in Settings. Enable no-key mode for compatible local services.
2. Select microphone, device audio, or both, then start recording.
3. Grant Android's media capture consent when device audio is enabled.
4. Use the Transcript, Summary, Minutes, Ask, and Stats tabs during or after the meeting.
5. Share the meeting as Markdown text.

局域网模型可填写 OpenAI 兼容地址。Android 模拟器访问宿主机时通常使用 `http://10.0.2.2:端口/v1`，并需要开启“允许 HTTP”。请只连接可信局域网。

For a LAN-hosted model, enter its OpenAI-compatible base URL. An Android emulator commonly reaches the host at `http://10.0.2.2:PORT/v1`; enable HTTP only for a trusted local network.

## 系统限制 / Platform limits

- 需要 Android 10（API 29）或更高版本。
- 系统声音使用 `AudioPlaybackCapture`。通话、DRM 内容以及禁止第三方捕获的应用可能没有声音。
- Android 14 及以上每次录制均要求新的媒体投影同意。
- 当前 APK 使用测试签名，仅用于设备测试，不适合应用商店分发。

---

- Android 10 (API 29) or newer is required.
- Device audio uses `AudioPlaybackCapture`. Calls, DRM media, and apps that block capture may be silent.
- Android 14+ requires fresh MediaProjection consent for every recording session.
- Current APKs use a test signature and are intended for device testing rather than store distribution.

## 构建 / Build

需要 JDK 17 与 Android SDK 36：

```bash
./gradlew :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 会对每次提交执行编译、Lint 和 APK 签名验证。

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds, lints, and verifies the APK signature on every push.

## 隐私、合规与许可 / Privacy, compliance, and license

开始录音前请取得参与者同意，并遵守所在地法律、雇主政策与会议平台条款。仓库不包含 API Key、录音或会议数据。数据发送范围与保存方式见 [PRIVACY.md](PRIVACY.md)，安全问题见 [SECURITY.md](SECURITY.md)。

Obtain participant consent before recording and follow applicable laws, workplace policies, and meeting platform terms. The repository contains no API keys, recordings, or meeting data. See [PRIVACY.md](PRIVACY.md) for data handling and [SECURITY.md](SECURITY.md) for security reporting.

Source code is licensed under Apache License 2.0. The Babel Echo name and artwork are not granted for unrelated commercial branding by the software license.
