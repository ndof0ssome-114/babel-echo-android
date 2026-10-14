# Security

## Reporting

Please report security issues privately to the repository owner instead of opening a public issue containing credentials or personal meeting data.

## Supported version

Only the latest prerelease receives fixes during the alpha period.

## Design notes

- No credentials are included in the source tree or build workflow.
- API keys are encrypted at rest with AES-GCM and an Android Keystore key.
- Android backup is disabled; Android 12+ extraction rules also exclude app data from cloud backup and device transfer.
- HTTPS is required by application logic unless the user explicitly enables local HTTP.
- HTTP redirects are not followed, preventing audio/text or credentials being forwarded to another host or a downgraded transport.
- Network responses shown as errors are length-limited; authorization headers are never included.
- Provider profile keys use independent encrypted preference entries; profile JSON contains only non-secret configuration.
- Meeting writes and read/modify/write operations share a process-wide lock to prevent concurrent service/UI updates from losing fields.
- Imports use the system document picker. Export destinations are explicitly selected by the user. No broad storage permission is required.

Debug APKs use the standard Android debug certificate and are intended only for testing.

- No-auth ASR/text/model-list requests omit saved credentials. Explicit model discovery uses only the draft endpoint/authentication; changing those drafts invalidates late results.
- Thinking controls are scoped to the exact official `api.deepseek.com` host. Empty or output-truncated text responses preserve existing documents, report usage first, and are not retried automatically.
