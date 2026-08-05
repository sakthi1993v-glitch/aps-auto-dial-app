# APS Auto Dial

Native Android companion app for the APS CRM staff auto-dial flow. WebView shell around the
existing CRM website (`https://agent.adiparasakthicharitabletrust.in`) — every CRM feature
(login, My Queue, everything) stays exactly as the web CRM already is. The only thing this
native shell adds: `tel:` links are intercepted and dialed directly via `ACTION_CALL`
(0-tap, no dialer screen) instead of the browser default (which needs one tap).

## Build

No local Android Studio / SDK needed. Push to `main` (or run the workflow manually) and
GitHub Actions builds the debug APK in the cloud — download it from the workflow run's
**Artifacts** section, then sideload it onto a staff phone (Settings -> allow installs
from this source, once per phone).

## Scope (v1)

- WebView loading the CRM gateway URL.
- CALL_PHONE permission request on first launch.
- `tel:` link interception -> `ACTION_CALL` (falls back to `ACTION_DIAL`, one tap, if
  permission isn't granted yet).

## Planned next (not built yet)

- READ_CALL_LOG -> capture call duration, sync back to the CRM for the per-staff daily
  call report (calls attended / talk time / idle time between calls).
