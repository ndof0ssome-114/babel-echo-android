# Security

## Reporting

Please report security issues privately to the repository owner instead of opening a public issue containing credentials or personal meeting data.

## Supported version

Only the latest prerelease receives fixes during the alpha period.

## Design notes

- No credentials are included in the source tree or build workflow.
- API keys are encrypted at rest with AES-GCM and an Android Keystore key.
- Android backup is disabled for the application.
- HTTPS is required by application logic unless the user explicitly enables local HTTP.
- Network responses shown as errors are length-limited; authorization headers are never included.

Debug APKs use the standard Android debug certificate and are intended only for testing.
