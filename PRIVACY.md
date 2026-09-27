# 隐私说明 / Privacy Notice

更新日期：2026-09-27

巴别回声 Android 本身不运营云端服务器，也不收集遥测数据。

## 本机数据

- 录音、转写和摘要保存在应用的私有目录中。
- API Key 加密后保存在 Android SharedPreferences；加密密钥由 Android Keystore 管理。
- 卸载应用通常会删除上述本机数据。备份已在应用清单中禁用。

## 外部服务

- 用户开始录音且已配置 ASR 后，应用会按设置的时间间隔将音频分段发送到用户配置的 ASR 地址，以生成实时转写；用户也可以对既有录音手动发起转写。
- 启用自动摘要后，新增转写片段会按设置的间隔发送到用户配置的 LLM 地址。生成纪要、翻译和会议问答也会发送完成该操作所需的文本。
- 外部服务如何处理数据取决于其运营者的政策。使用前请自行确认保存期限、训练用途、传输地区与费用。
- 启用 HTTP 会使内容和密钥缺少传输加密，仅应连接可信局域网中的本地服务。

## 录音同意

用户负责在录音前取得参与者同意，并遵守适用法律、雇主政策和会议平台条款。

---

Babel Echo Android does not operate a cloud backend or collect telemetry. Recordings, transcripts, and summaries remain in app-private storage. During a recording session with ASR configured, audio chunks are sent to the user-configured ASR endpoint for live transcription. Text is sent to the configured LLM endpoint when automatic summaries, minutes, translation, or meeting Q&A are used. API keys are encrypted using a key held by Android Keystore. Users are responsible for obtaining recording consent and reviewing the selected provider's data practices.
