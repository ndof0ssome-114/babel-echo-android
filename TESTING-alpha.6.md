# Android alpha.6 — MuMu 简单测试记录

2026-10-14（Asia/Tokyo），MuMu Android 15 / API 35。

alpha.6 修复深色模式下历史与设置页标题的文字颜色，其他功能和测试步骤参考 [alpha.5 测试说明](TESTING-alpha.5.md)。当前 APK 为本机 debug 测试签名，和上一版一致，可覆盖更新并保留会议。

已检查：录音同意与音频权限、本地录音、暂停/继续、短时间切后台、结束保存、回放与进度条跳转、应用重启后的历史记录。暂停时 WAV 大小不变；测试录音 98.796 秒，48 kHz 单声道 PCM16，ASR/LLM 请求均为零。

4 项 instrumentation 检查在 MuMu 通过：WAV 重采样与回放、AAC/MP4 音轨导入、密钥清空后重读、并发会议更新。

模拟器麦克风输入几乎静音（峰值仅为 PCM16 的 1/32768），本次不能证明麦克风映射、音质或语音识别有效。系统声音与真实模型服务仍待单独验证。

最终验证：alpha.6 assembleDebug 与 lintDebug 通过，APK 签名验证通过；覆盖更新后两条会议保留。深色历史/设置标题已重新目视确认，4 项 instrumentation 在更新后的 MuMu 上再次通过。
