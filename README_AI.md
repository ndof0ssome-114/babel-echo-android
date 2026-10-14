# Babel Echo Android — AI implementation guide

Last reviewed: 2026-10-15

This document is the authoritative Android implementation guide. This standalone repository is synchronized from `apps/android` in the Babel Echo monorepo. Start with `README.md`, `PRIVACY.md`, and `SECURITY.md`. Paths here are relative to this repository root.

## 1. Product scope

Babel Echo Android is a native Android 10+ meeting recorder and AI meeting workspace. It can capture:

- microphone audio;
- media/game playback that Android allows third parties to capture;
- a live mix of both sources.

It stores a local PCM WAV recording and can use user-configured OpenAI-compatible endpoints for live transcription, translation, incremental summaries, structured minutes, and meeting Q&A.

Current version: `0.2.0-alpha.7` (`versionCode 7`).

Application ID: `app.babelecho.android`.

Minimum SDK: 29. Target and compile SDK: 36.

This is an alpha. A successful Gradle build does not prove microphone, playback capture, background behavior, or vendor-specific MediaProjection behavior on every device.

## 2. Mandatory constraints

1. Never commit API keys, recordings, transcripts, meeting JSON, signing keys, billing exports, `local.properties`, APKs, or AABs.
2. Obtain fresh MediaProjection consent whenever Android requires it; do not cache or reuse a projection token across sessions.
3. Preserve foreground service types for microphone and media projection.
4. Keep recordings in app-private storage by default.
5. Do not silently send audio or text to a provider. Starting a configured live transcription session and invoking AI actions must remain understandable in the UI and privacy notice.
6. Require HTTPS unless the user explicitly enables insecure HTTP for a trusted local network.
7. Do not imply that calls, DRM media, or every application can be captured.
8. Keep old meeting JSON readable when adding fields.
9. Keep key material encrypted with Android Keystore; never log decrypted keys.
10. Preserve local recording if ASR or LLM calls fail.

## 3. File map

```text
app/src/main/
├─ AndroidManifest.xml
├─ java/app/babelecho/android/
│  ├─ MainActivity.kt
│  ├─ MainViewModel.kt
│  ├─ cloud/
│  │  ├─ CloudClient.kt
│  │  └─ WavChunker.kt
│  ├─ data/
│  │  ├─ AppSettings.kt
│  │  ├─ Meeting.kt
│  │  ├─ MeetingStore.kt
│  │  └─ SecureSettings.kt
│  └─ recording/
│     ├─ RecordingService.kt
│     └─ WavFileWriter.kt
└─ res/
   ├─ drawable/
   ├─ mipmap-*/
   ├─ values/
   └─ values-en/
```

Other important files:

- `app/build.gradle.kts`: application ID, SDK levels, version, build types, dependencies.
- `gradle/libs.versions.toml`: dependency/plugin versions.
- `.github/workflows/android.yml`: known-good CI environment and commands.
- `artwork/`: Android copies of the desktop/master brand artwork.
- `PRIVACY.md`: user-facing data flow; update whenever network behavior changes.
- `SECURITY.md`: credential and vulnerability guidance.

## 4. Build environment

Required:

- JDK 17
- Android SDK platform 36
- Android Build Tools 36.0.0
- network access for first dependency resolution

Build and lint:

```powershell
cd D:\Codex\babelecho\apps\android
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

CI additionally runs `apksigner verify --verbose --print-certs`.

If the local machine only has Java 11, do not modify Gradle to make it work. Install/select JDK 17 or use the documented CI environment.

## 5. UI architecture

`MainActivity.kt` is a single-activity Jetpack Compose application.

### 5.1 Root state

`BabelEchoApp` collects `MainViewModel.state` with lifecycle awareness and manages transient UI choices such as:

- current bottom navigation page;
- microphone source toggle;
- playback source toggle;
- pending capture choices while permissions are requested.

### 5.2 Permissions

The activity requests:

- `RECORD_AUDIO`;
- `POST_NOTIFICATIONS` on Android 13+;
- MediaProjection consent through the platform screen-capture intent when playback capture is selected.

Do not start playback capture without `RESULT_OK` and non-null result data.

Notification permission denial must not be confused with microphone denial. When multiple permissions are requested, re-check the actual microphone permission after the callback.

### 5.3 Pages

- Workspace: capture controls and meeting tabs.
- History: saved meetings with open/delete actions.
- Settings: UI language, endpoints, model IDs, keys, no-auth switches, chunking, overlap, summary interval, translation target, and insecure HTTP.

### 5.4 Meeting panels

- Transcript
- Summary
- Minutes
- Ask
- Stats/share

The UI uses a light blue/purple translucent-card design. Avoid hard-coded implementation details in user-facing copy.

### 5.5 Localization

The Compose UI currently uses a small `tr(lang, zh, ja, en)` helper for most runtime strings, while Android system labels/notifications use resources. If localization expands, migrate deliberately to resource-backed strings rather than mixing more ad hoc mechanisms.

## 6. View model

`MainViewModel.kt` owns `MainUiState`:

- meeting list;
- current settings;
- recording/paused state;
- audio meter level;
- selected meeting;
- current background operation;
- transient message.

It registers a non-exported broadcast receiver for service state and new transcript segments.

Important actions:

- `startRecording`
- `stopRecording`
- `togglePause`
- `transcribe`
- `summarize`
- `createMinutes`
- `ask`
- `translate`
- `renameMeeting`
- `deleteMeeting`

### 6.1 Stop finalization

The recording service owns stop finalization, including notification stops. It closes and saves the WAV, then optionally creates minutes from existing transcript text only. The view model observes service processing broadcasts. Never perform automatic whole-recording retranscription after stop. AI errors leave the audio and saved text intact; explicit manual retranscription commits only after every chunk succeeds. All file/network work runs off the UI thread and only one manual/import/export operation runs at a time.

### 6.2 Broadcast safety

Broadcasts are package-scoped and receiver registration is not exported. Keep service extras stable or migrate both sender and receiver together.

## 7. Recording service state machine

`RecordingService.kt` is the most sensitive Android file.

Actions:

- `ACTION_START`
- `ACTION_PAUSE`
- `ACTION_RESUME`
- `ACTION_STOP`

Broadcasts:

- `ACTION_STATE`
- `ACTION_SEGMENT`

Core atomics:

- `running`
- `stopping`
- `paused`

Expected state flow:

```text
idle
  -> start foreground
  -> validate permission/projection
  -> create meeting directory and WAV writer
  -> create AudioRecord sources
  -> start readers
  -> optionally start live ASR consumer
  -> start mixer
  -> recording
       <-> paused
  -> stopping (only once)
  -> drain/join bounded work
  -> close WAV header
  -> release AudioRecord and MediaProjection
  -> persist final duration/state
  -> remove foreground notification
  -> stopped
```

### 7.1 Start ordering

The live ASR consumer must exist before the mixer checks whether to enqueue frames, otherwise initial frames can be omitted. Audio records must be initialized before starting. The meeting file is created early so a later failure can be represented or cleaned up.

### 7.2 Microphone capture

Uses `MediaRecorder.AudioSource.VOICE_RECOGNITION` with mono PCM 16-bit at 48 kHz. Do not change source or format without testing multiple phones and the WAV/chunk pipeline.

### 7.3 Playback capture

Uses `AudioPlaybackCaptureConfiguration` with media, game, and unknown usage. Requires MediaProjection.

Playback capture limitations are platform-enforced. Silent playback capture can be correct behavior when the source app disallows capture.

### 7.4 Reader threads

Each `AudioRecord` reader copies frames into a bounded queue. If full, the oldest frame is dropped before inserting the newest. This keeps memory bounded but may create discontinuities under sustained overload; any queue-size change needs long-session testing.

### 7.5 Mixer

The current mixer aligns available microphone/playback frames and adds sample values with saturation to signed 16-bit range.

Risks:

- two loud sources can clip;
- independently polled sources can drift;
- missing frames from one source become silence for that frame;
- a better mixer may need gain control and timestamp-aware alignment.

During pause, the service continues draining capture sources but does not write mixed PCM or send it to live ASR.

### 7.6 Audio meter

The meter is based on average absolute sample magnitude and is broadcast at a reduced cadence. Do not broadcast every PCM frame.

### 7.7 Stop behavior

Stop is guarded by `stopping.compareAndSet(false, true)`. All stop paths, including MediaProjection revocation, converge on `stopCapture`.

Join durations are bounded. Avoid infinite waits on network calls inside service shutdown.

## 8. WAV writer

`WavFileWriter.kt` writes PCM data with a placeholder RIFF/WAV header and patches lengths on close.

Invariants:

- 16-bit little-endian PCM;
- mono;
- declared sample rate matches capture;
- file size greater than 44 bytes indicates at least some data, not necessarily audible data;
- close must occur even on stop/error;
- partial files should not be presented as successful recordings.

When debugging audio, inspect duration, RMS/peak, and listen to the WAV. File existence alone is insufficient.

## 9. Live transcription pipeline

The mixer copies mixed frames into `liveQueue` only when live transcription was explicitly enabled and the selected ASR route is configured. Keys alone do not enable upload.

`PcmAccumulator` collects samples until `chunkSeconds * SAMPLE_RATE` is reached. After processing, it retains `overlapSeconds * SAMPLE_RATE` at the boundary.

For every chunk:

1. Write a temporary WAV in cache.
2. Call `CloudClient.transcribeChunk`.
3. Pass up to the last 240 characters of the previous result as ASR prompt.
4. Create a timestamped `TranscriptSegment`.
5. Optionally translate it.
6. Append it to the meeting.
7. Update ASR call and character statistics.
8. Broadcast `ACTION_SEGMENT`.
9. Delete the temporary WAV.

### 9.1 Chunk constraints

- `chunkSeconds`: 10–120.
- `overlapSeconds`: 0–10 and less than chunk duration.
- very short chunks increase RPM/cost and reduce context;
- very long chunks increase latency and can hit request limits;
- overlap improves continuity but increases repeated audio and potential duplicate text.

### 9.2 Error behavior

An ASR failure must not stop local recording. It may broadcast a user-readable error. Avoid flooding the UI with identical failures; future work should add backoff/deduplication.

### 9.3 Queue behavior

The live queue is bounded. In alpha.5 a full queue stops live uploading instead of dropping frames with misleading timestamps; local WAV remains intact. Before increasing queue size, measure memory and shutdown latency.

## 10. Cloud client

`CloudClient.kt` uses OkHttp.

### 10.1 ASR

Endpoint:

```text
{asrBaseUrl}/audio/transcriptions
```

Multipart fields include model, response format, temperature, file, optional language, and optional prompt.

Authorization is included only when the configured key is nonblank. `asrNoAuth` permits a blank key for local services.

### 10.2 Chat

Endpoint:

```text
{llmBaseUrl}/chat/completions
```

The client sends system/user messages and selects the task model. `llmNoAuth` permits no-key local services.

Task model fallback:

- use task-specific model when nonblank;
- otherwise use `llmModel`.

### 10.3 URL validation

HTTPS is required unless `allowInsecureHttp` is true. Keep this validation in the network layer even if the UI also warns.

### 10.4 Error parsing

For non-2xx responses, extract the provider error message when possible, cap raw response exposure, and never include credentials.

### 10.5 Provider compatibility

“OpenAI-compatible” is not a perfect standard. Providers may differ in multipart fields, accepted language codes, JSON structure, maximum file size, or chat response schema. Add provider adapters only when the generic path is insufficient and tested.

## 11. AI operations

### 11.1 Full summary

`summarize` chunks very long text, summarizes each part, then synthesizes the partial summaries. Preserve names, decisions, numbers, action items, and unknowns.

### 11.2 Incremental summary

`updateSummary(previous, newSegments)` sends only:

- previous summary;
- transcript segments added since the previous update.

Never change this to previous summary plus the full transcript. That recreates the token growth problem the product already addressed.

The service tracks `summarizedSegments` and `lastSummaryAt`.

### 11.3 Minutes

Minutes use meeting title, current summary, and transcript. Output should contain overview, participants, key points, decisions, action items, risks/disagreements, unresolved questions, and timestamped chapters. Never invent owner/deadline values.

### 11.4 Ask

Answers are constrained to the supplied meeting record and should cite timestamps when possible. Only the latest few Q&A pairs are included as history to bound context.

### 11.5 Translation

Segments are sent with stable IDs. The model must return JSON mapping IDs to text. The parser extracts the first complete JSON object from surrounding model text. Invalid JSON produces a clear error rather than silently misaligning translations.

## 12. Settings and key storage

`AppSettings.kt` contains defaults. `SecureSettings.kt` persists values.

Important fields:

- `uiLanguage`
- ASR base/model/language/key/no-auth
- LLM base/default model/key/no-auth
- summary/minutes/translate/ask model IDs
- chunk seconds
- overlap seconds
- automatic summary seconds
- translation target
- insecure HTTP opt-in

Keys are encrypted with AES/GCM/NoPadding. The AES key is generated/stored in Android Keystore under a stable alias.

### 12.1 Save validation

Validate:

- UI language is supported;
- chunk duration range;
- overlap range and relation to chunk;
- auto summary is zero or in supported range;
- URLs/models are trimmed before storage.

### 12.2 Clearing keys

Saving a blank key must persist a blank encrypted setting, not leave the previous encrypted value. Test by restarting the app and confirming the key remains cleared.

### 12.3 Keystore failure

Decryption failures currently fall back to blank. Do not crash the app or expose ciphertext. A future UX can explain that credentials must be re-entered after device/security changes.

## 13. Meeting storage

`MeetingStore.kt` creates one app-private directory per meeting and stores `meeting.json` beside `recording.wav`.

### 13.1 Meeting schema

`Meeting.kt` defines:

- `Meeting`
- `TranscriptSegment`
- `QaEntry`
- `MeetingStats`

### 13.2 Atomic writes

Meeting JSON is first written to `meeting.json.tmp`, then moved with `ATOMIC_MOVE` and `REPLACE_EXISTING` when supported. A replacement fallback is used when the filesystem does not support atomic move.

Do not return to `File.renameTo` without checking the result; replacing an existing file can fail on Windows-like filesystems and silently leave stale metadata.

### 13.3 Backward compatibility

JSON reads use optional values/defaults for fields introduced after the initial alpha. Add new fields the same way.

### 13.4 Delete

Deleting a meeting removes its entire private directory, including WAV and metadata. UI requires confirmation. If export/share files are introduced later, define whether they are owned by the meeting directory.

## 14. Branding resources

Android branding must match desktop/macOS.

Authoritative source in monorepo:

```text
../desktop/desktop/build/icon-source.png
```

Android copies:

```text
artwork/babel-echo-mascot-source.png
artwork/babel-echo-android-store-icon.png
```

Adaptive resources:

- `mipmap-anydpi-v26/ic_launcher.xml`
- `mipmap-anydpi-v26/ic_launcher_round.xml`
- density-specific `ic_launcher_foreground.png`
- density-specific legacy/round PNGs

When changing branding:

1. Edit or replace the desktop master.
2. Regenerate ICO/ICNS/desktop PNG as needed.
3. Copy the exact master and 512 PNG to Android artwork.
4. Generate all Android density resources.
5. Confirm adaptive icon safe-area behavior.
6. Compare hashes of copied masters.
7. Increment Android versionCode/versionName.
8. Build and inspect on square and circular launchers.

## 15. Manifest and platform policy

Manifest permissions include:

- `RECORD_AUDIO`
- `INTERNET`
- `POST_NOTIFICATIONS`
- foreground service permissions
- microphone and mediaProjection foreground service permissions

The recording service declares both foreground service types.

`allowBackup=false` protects app-private meeting and credential state from standard Android backup. Do not enable backup without designing explicit exclusions and updating privacy documentation.

The manifest currently permits cleartext traffic because the app offers an explicit local HTTP option. Application code still gates HTTP with `allowInsecureHttp`; do not remove the runtime gate.

## 16. Performance and resource use

Potential hotspots:

- two AudioRecord threads;
- mixer allocations (`ShortArray` copies);
- live ASR queue;
- temporary WAV creation;
- repeated MeetingStore list/read/write;
- Compose list rendering for long transcripts;
- model/network timeouts during stop.

Optimization rules:

- measure before changing audio buffer size;
- keep queues bounded;
- avoid reading every meeting file for every new segment if introducing a direct-by-ID lookup;
- batch UI refreshes when segments arrive quickly;
- avoid full transcript recomposition on every meter update;
- move expensive file/network work off the main thread;
- preserve correctness and recoverability over tiny latency gains.

## 17. Testing

### 17.1 Build gate

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
```

### 17.2 Static checks

- `git diff --check`
- no hard-coded keys/tokens
- versionName and workflow artifact names match
- Manifest resource references resolve
- old JSON fields still have defaults

### 17.3 Physical device

Test Android 10+ and preferably Android 14+:

1. Microphone only.
2. Playback only with a capturable media app.
3. Mixed audio.
4. Pause/resume.
5. Stop from notification.
6. Projection revoked by system.
7. Background and locked screen behavior.
8. Live ASR with 10/25/60 second chunks.
9. Overlap deduplication.
10. Japanese, Chinese, English.
11. Translation target changes.
12. Auto summary off/on.
13. 429, timeout, offline, invalid JSON.
14. Local HTTP no-auth endpoint.
15. App restart and saved meeting reload.
16. Rename/delete/share.
17. Upgrade from earlier alpha.

### 17.4 Audio quality

For recorded fixtures, inspect:

- RIFF header validity;
- sample rate/channels/bit depth;
- file duration;
- peak and RMS;
- clipping rate;
- audible dropouts at queue pressure;
- source balance in mixed recording.

## 18. Release process

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update version in README.
3. Update CI artifact name if the workflow remains version-specific.
4. Run assemble + lint.
5. Verify APK signature.
6. Scan for sensitive information.
7. Create a prerelease tag matching version name.
8. Attach APK and SHA-256.
9. State that debug/test signing is used.
10. Document device test coverage and limitations.

Do not reuse a release tag for different APK bytes. Increment the alpha version for every public test package.

## 19. Common change recipes

### Add a provider preset

1. Decide whether generic OpenAI-compatible requests actually work.
2. Add UI preset without removing free-form URL/model entry.
3. Define language support.
4. Test key/no-auth behavior.
5. Test provider error JSON.
6. Update privacy/provider docs if endpoint defaults change.

### Add a meeting field

1. Add a defaulted property to `Meeting`.
2. Write it in `MeetingStore.save`.
3. Read it with optional/default semantics.
4. Test an old meeting file without the field.
5. Update this schema section.

### Change chunking

1. Validate settings ranges.
2. Confirm overlap retention math.
3. Confirm timestamps advance by non-overlap samples.
4. Confirm final remainder handling.
5. Test duplicate merge/prompt behavior.
6. Measure request rate and shutdown latency.

### Change capture source

1. Check Android permission/policy implications.
2. Preserve foreground service declarations.
3. Test initialization failures.
4. Test pause, projection revocation, and stop.
5. Inspect WAV and listen on multiple devices.

### Change model prompts

1. Keep “do not invent” constraints.
2. Keep primary-language behavior.
3. Preserve timestamps/IDs where parsing depends on them.
4. Measure token/input size.
5. Test malformed provider output.

## 20. Known limitations and next work

- No production signing pipeline.
- No Play Store AAB/release process.
- Speaker labels are placeholders; no true diarization.
- Mixer lacks automatic gain control and timestamp-based drift correction.
- Identical live errors are suppressed for 30 seconds; provider-specific retry/backoff still needs real-account validation.
- Meeting store lookup can be optimized for long histories.
- Transcript segments use lazy keyed rows; large individual model outputs may still require memory checks.
- Local endpoints may require provider-specific compatibility adapters.
- System playback availability remains app-dependent.
- Comprehensive automated audio instrumentation tests are not yet included.

## 21. Troubleshooting

### Build uses wrong Java

Check `java -version` and `JAVA_HOME`. Use JDK 17; do not downgrade AGP to accommodate Java 11.

### No launcher icon update

Increment versionCode, verify adaptive resource references, and uninstall the previous debug package to clear launcher cache.

### Microphone works, playback silent

Confirm MediaProjection consent and test a normal media player that allows capture. Calls/DRM/blocked apps may remain silent by design.

### Only one short transcript appears

Inspect WAV audibility, ASR responses, queue consumption, chunk duration, language route, rate limits, and whether the provider supports the selected language.

### 429 rate limit

Increase chunk duration, reduce unnecessary retries, respect provider retry timing, and preserve the WAV for later retranscription.

### Summary cost grows every interval

Verify only new segments after `summarizedSegments` are sent with the previous summary. Do not send the entire transcript each time.

### Local model cannot connect

On an emulator, host loopback is commonly `10.0.2.2`, not `127.0.0.1`. Enable insecure HTTP only for a trusted LAN. On physical devices, use a reachable LAN address and check firewall binding.

## 22. Definition of done

An Android change is complete when:

- the requested behavior is implemented;
- persisted data remains compatible or has a documented migration;
- privacy UI/docs match network behavior;
- assembleDebug and lintDebug pass;
- sensitive scan is clean;
- relevant physical-device scenarios are tested or clearly listed as pending;
- version/release metadata are synchronized when publishing;
- this guide is updated when architecture or invariants change.

## 23. Alpha.5 mobile parity update (2026-10-14)

- Live ASR is now explicitly opt-in (`liveTranscription`, default false). Existing keys/endpoints remain intact; upgrading does not silently upload audio. `autoMinutes` defaults true but only sends existing transcript text when the configured minutes provider is ready. Stop never performs automatic full retranscription. Capture settings are snapshotted at recording consent/start and remain fixed throughout the session.
- Service stop, including the notification stop path, owns optional minutes generation. `EXTRA_MEETING_ID`, `EXTRA_DURATION` (sample-count duration excluding pause), and `EXTRA_PROCESSING` are included in state broadcasts. A process-local state snapshot restores the UI when its Activity/ViewModel is recreated. Stop guards prevent duplicate recordings/finalization.
- Shutdown waits up to eight seconds for live processing, then interrupts/cancels the request and joins up to two further seconds. Audio survives cancelled processing. The consumer waits until the mixer drains. A full live queue stops live uploading, preserving the WAV for manual retranscription instead of producing shifted timestamps. Interrupted WAV headers are repaired at next launch when their recording is no longer active; no AI action is triggered by recovery.
- `MediaImporter.kt` streams the first audio track selected by SAF through MediaExtractor/MediaCodec, downmixes PCM16 or float, and continuously resamples with `PcmResampler.kt` to mono 16 kHz WAV. Import defaults local-only; ASR/minutes are separate opt-ins. A failed AI stage preserves the imported local meeting. Decode failure removes only its newly-created private output, preserving the external original.
- `PlaybackCard.kt` prepares MediaPlayer asynchronously, supports seek/pause and timestamp seeking, pauses when the Activity is backgrounded and releases on disposal. Playback appears after stop with no cloud dependency.
- `MeetingExport.kt` generates MD/TXT/VTT/bilingual VTT/JSON; WAV is streamed to a user-selected SAF destination. JSON excludes the internal audio path; keys are never meeting fields. Exported copies outlive meeting deletion and are owned by the destination. Markdown text sharing remains available.
- `MeetingJson.kt` extracts the optional/defaulted codec from MeetingStore. New stats fields (`asrSeconds`, `inputTokens`, `outputTokens`) default to zero for old meetings. All MeetingStore instances share a lock around atomic writes and read/modify/write. IDs now use UUIDs; old timestamp IDs still load. Manual retranscription commits timestamped segments only after all requests succeed and then clears stale summary/minutes/Q&A. Translation and speaker assignments validate every stable ID before committing.
- ASR adapters support OpenAI verbose JSON, MiMo audio chat and Deepgram diarized utterances. MiMo is limited to explicit zh/en; auto is rejected to avoid unknown Japanese audio. OpenAI-compatible endpoints must accept `verbose_json`; provider compatibility still needs real accounts. Speaker labels from different chunks may not retain a globally stable identity. AI resplitting is a text-based inference and does not claim acoustic diarization.
- `ProviderSettings.kt` supplies opt-in ASR profiles (Groq/MiMo/Deepgram/local), explicit language routes, text profiles and per-task provider/model roles. Profiles are disabled by default; legacy settings remain active unless the user enables profiles. Missing/unsupported selected routes produce errors with no silent provider fallback. Profile keys are separately encrypted with the existing Keystore alias; non-secret JSON and routes are separate preferences. Cost estimates use editable shared rates/currency; mixed provider prices and missing usage cannot be reconstructed into a bill.
- HTTP redirects are disabled. Network-boundary HTTPS and URL credential checks apply after role/route resolution. UI follows system light/dark appearance, has horizontally scrollable tabs and searchable local history.
- JVM regression: `./gradlew --no-daemon :app:testDebugUnitTest`. Device regression: `./gradlew --no-daemon :app:connectedDebugAndroidTest` (synthetic WAV/AAC import, MediaPlayer prepare/seek, key clearing, concurrent persistence). Tests do not use provider accounts or real meeting audio.
- Remaining differences from iOS: Android retains fixed 10–120 second live chunks rather than iOS provisional utterance/VAD updates. No true cross-chunk diarization. System playback, microphone quality, screen lock/background behavior and actual provider interoperability require physical-device checks. See `TESTING-alpha.5.md` for exact evidence and handoff steps.

Android 12+ `dataExtractionRules` excludes all app storage from cloud backup and device transfer in addition to `allowBackup=false`. Transcript segment UI uses keyed lazy rows.

2026-10-14 alpha.6 MuMu Android 15 smoke test: set Scaffold contentColor explicitly to the theme onSurface color. Transparent containers otherwise inherited a black LocalContentColor for titles outside cards, making history/settings headings unreadable in dark mode. Recording pause/resume, brief background capture, stop, playback seeking and history reload were exercised; WAV was 48 kHz mono PCM16, 98.796 seconds. Input peak was only 1/32768, so microphone signal quality/host mapping remains unverified. Four instrumentation checks passed on MuMu API 35.

## 24. Alpha.7: independent iOS alpha.3 reference (2026-10-15)

Reference: https://github.com/ndof0ssome-114/babelecho-ios at `dd7c61e6b0984d307f47b09e2c7d9c0ca5871fe5`. Android changes remain native Kotlin/Compose; the reference checkout is read-only.

- Settings drafts live in MainViewModel, survive navigation and Activity recreation, and are persisted only by top/bottom Save. Saving is guarded against capture/work/concurrent saves. Newer edits during saving remain in the draft. Draft keys are never serialized into saved-instance state.
- Explicit GET `/models` uses the displayed draft endpoint/key/no-auth/HTTP choice. It sends no meeting data, deduplicates/sorts IDs and keeps the typed model unchanged. Each endpoint/auth snapshot owns its results; disposal or changes cancel its request and discard late results. Task provider changes reset model/thinking to a DeepSeek preset or blank custom-provider model.
- `TextRole.thinking` and legacy `textThinking` are optional. Old saved roles retain provider defaults when absent; fresh summary/translation disable thinking. Only the exact official DeepSeek host receives `thinking.type`. Output ceilings follow iOS: normal 4096, minutes 8192, official DeepSeek thinking/default 16384. No model IDs are silently migrated.
- No-auth requests omit saved keys in every ASR/text/list adapter. Text usage is saved before content validation; blank/null content and `finish_reason=length` preserve old documents without retries. `llmCalls` continues counting returned requests, including hierarchical and unusable responses. `textUsageByTask` stores calls, input/output, optional reasoning subset and incomplete-usage count; old meetings default to empty task usage. Never add reasoning to output totals or cost twice.
- `summaryUpTo` persists the captured segment count after successful summary; retranscription resets it. Old meetings default to zero. Existing guards serialize live/manual operations; updates send previous summary plus only segments beyond the cursor.
- Copy current panel includes timestamps, speakers and translations, or complete summary/minutes/Q&A. Translation and questions support text selection. Markdown sharing reuses MeetingExport including translations and Q&A.
- `UtteranceSegmenter` ports iOS energy-based segmentation: RMS .003, 0.3-second pre-roll, 1.2-second silence, 0.25-second minimum utterance, configured maximum chunk/overlap and bounded buffer. Timestamps include recorded silence and exclude pauses. Stop/pause flush new voice, never finalized overlap. Silence remains in WAV and is not sent in this mode. Fresh settings select utterance mode but live ASR stays off; existing `chunk_seconds` preferences retain fixed chunks until explicitly changed. No stored meetings are rewritten.
- Recording start is reserved before permission/projection UI to reject duplicate starts; cancellation releases it. Settings cannot be saved while reserved.
- Remaining differences: no provisional 3-second live text and no JSON minutes document (structured Markdown is supported). Global speaker identity, real providers and physical microphone/system-audio/lock-screen behavior still need validation. See [alpha.7 testing](TESTING-alpha.7.md).
