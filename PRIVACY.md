# 隐私说明 / Privacy Notice

更新日期：2026-10-15

巴别回声 Android 本身不运营云端服务器，也不收集遥测数据。

## 本机数据

- 录音、转写和摘要保存在应用的私有目录中。
- API Key 加密后保存在 Android SharedPreferences；加密密钥由 Android Keystore 管理，各服务独立保存密钥。
- 意外中断的本地 WAV 在下一次启动时尝试修复文件头，保留已写入音频，不触发自动网络处理。
- 用户通过系统文件选择器导出 MD、TXT、VTT、双语 VTT、JSON 或 WAV。导出文件和分享文字由用户选择的文件位置或接收应用管理，删除会议不会删除已经导出的副本。JSON 导出不包含密钥或本机录音路径。
- 用量统计包含成功 ASR 的音频秒数、返回响应的文本请求（包括空或截断内容）及模型报告的 token，按任务记录。未报告完整用量会单独标示；思考 token 已包含在输出中，不重复加算。这些本机估算不是账单，也不发送遥测。
- 卸载应用通常会删除上述本机数据。备份已在应用清单中禁用。

## 外部服务

- 实时转写默认关闭。用户明确开启、配置识别服务并确认录音同意后，音频分段才会发送到所选 ASR；多服务模式按识别语言使用用户选择的服务，不会静默换到其他端点。
- 导入音频或视频默认只在本机转换、保存音轨。用户勾选“导入后转写 / 生成纪要”，或之后手动点击 AI 操作时，才会发送音频或文字。
- 停止录音不会自动上传完整音频。启用“结束后自动生成纪要”、存在转写且文本服务已配置时，结束后会发送已有转写文字生成纪要；通知栏结束同样遵循此设置。
- 启用自动摘要后，新增转写片段会按设置的间隔发送到用户配置的 LLM 地址。生成纪要、翻译和会议问答也会发送完成该操作所需的文本。
- 外部服务如何处理数据取决于其运营者的政策。使用前请自行确认保存期限、训练用途、传输地区与费用。
- 启用 HTTP 会使内容和密钥缺少传输加密，仅应连接可信局域网中的本地服务。

## 录音同意

用户负责在录音前取得参与者同意，并遵守适用法律、雇主政策和会议平台条款。

## 模型列表与复制

用户点击“获取模型列表”才会请求当前设置草稿服务的 `/models`，使用该草稿的密钥或无需密钥设置，不包含会议文本或音频。选择无需密钥时，ASR、文本和列表请求均省略已保存的密钥。列表不自动覆盖手动输入的模型。

复制本页内容或选择文字只在用户主动操作后写入系统剪贴板。可选静音断句以录音采样时间生成转写分段；完整音频仍在本机保存，实时转写开关仍默认关闭。

---

Babel Echo Android does not operate a cloud backend or collect telemetry. Recordings, transcripts, and summaries remain in app-private storage. Live transcription is off by default. Audio chunks are sent only after the user enables live transcription and confirms recording consent, or explicitly requests transcription. Media import is local-only unless the user opts into model processing. Stopping never uploads the full recording; enabled automatic minutes send existing transcript text to the configured task provider. Language routes and task roles select explicit user-configured endpoints. Exported copies are controlled by the selected destination or receiving application. Text is sent to the configured LLM endpoint when automatic summaries, minutes, translation, or meeting Q&A are used. API keys are encrypted using a key held by Android Keystore. Users are responsible for obtaining recording consent and reviewing the selected provider's data practices.
