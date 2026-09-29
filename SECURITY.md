# Security Notes

## Data and API keys

- API keys are stored locally in the app-private directory (`files/profiles.json`)
  and in app-private SharedPreferences. The app disables cloud backup
  (`allowBackup=false`), so keys are not copied by normal Android backup.
- API keys are sent only to the Base URL you configure. Prefer `https://`.
  If you configure an `http://` Base URL, the app shows a warning because keys
  and chat content can be intercepted in transit.
- The app does not include any built-in API key.

## Powerful permissions

The app can request the following sensitive capabilities:

- **Accessibility Service**: read screen content and perform gestures/clicks.
  This is required for the phone-control feature. Only enable it if you trust
  the app and the model/API you configured.
- **Python / Linux execution**: the model can run arbitrary Python and shell
  commands inside the app's private Linux/PRoot environment. Do not use this
  feature with an untrusted model or API endpoint.
- **File/URL tools**: the model can read/write files under the app workspace,
  fetch URLs, and make HTTP requests. A malicious prompt or a compromised model
  could attempt to exfiltrate data through these tools.
- **Exact alarms / foreground service**: used for the optional active-mode
  heartbeat. Declining exact-alarm access degrades to inexact alarms.

## Network security

- TLS certificate verification uses the Android system defaults; the app does
  not disable certificate or hostname verification.
- `usesCleartextTraffic=true` is required to support user-configured local
  APIs (for example Ollama on `http://...`). Use HTTPS for remote providers.

## Reporting

If you find a security issue, please open a private security advisory or
contact the repository owner instead of filing a public issue with exploit
details.
