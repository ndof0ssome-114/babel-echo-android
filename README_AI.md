# AI contributor guide

Start with `README.md`, `PRIVACY.md`, and `SECURITY.md`.

## Architecture

- `recording/RecordingService.kt`: Android foreground service, microphone/playback `AudioRecord` instances, PCM mixing, WAV output.
- `cloud/WavChunker.kt`: overlap-aware PCM WAV chunking.
- `cloud/CloudClient.kt`: user-initiated OpenAI-compatible ASR and chat requests.
- `data/SecureSettings.kt`: Android Keystore backed API-key encryption.
- `data/MeetingStore.kt`: app-private JSON meeting metadata.
- `MainActivity.kt`: Jetpack Compose UI and permission/MediaProjection flow.

## Non-negotiable constraints

- Never add API keys, recordings, transcripts, local settings, signing stores, or billing exports to Git.
- Keep system playback capture behind fresh MediaProjection consent for every session.
- Preserve Android 14 foreground service types and permissions.
- Do not silently upload audio or text; network actions must remain explicit in the UI.
- Local HTTP must stay opt-in and carry a clear security warning.
- Treat captured audio as sensitive personal data.

## Verification

Run `./gradlew :app:assembleDebug lint` with JDK 17 and Android SDK 37. Test microphone-only, playback-only, and mixed capture on a physical Android 10+ device because emulators do not represent every vendor audio path.
