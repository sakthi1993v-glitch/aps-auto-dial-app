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
- Loading spinner + retry-able error screen (no more silent blank white on a failed load).

## Auto-update (v1.1+)

The app has no Play Store, so it self-checks `version.json` (raw file on `main`, this repo
must stay **public** for this to work without embedding a token) on every launch. If the
remote `versionCode` is higher than the running build, it downloads the new APK
(`DownloadManager`) and prompts the system installer (`FileProvider` + `ACTION_VIEW`).
Android still requires one final "Install" tap — no app can silently self-install without
root/device-owner privileges, this is the closest to fully-automatic that's possible.

**Releasing a new version — do all four, in order, or the update-check won't fire:**
1. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`.
2. Push to `main` -> GitHub Actions builds the APK (Actions tab -> Artifacts).
3. Create/update a GitHub Release tag (e.g. `v1.2-debug`) with that APK attached
   (`gh release create` or `gh release upload --clobber`).
4. Update `version.json` at the repo root with the new `versionCode`/`versionName`/`apkUrl`
   (the release asset URL from step 3) and push it to `main`.

## Planned next (not built yet)

- READ_CALL_LOG -> capture call duration, sync back to the CRM for the per-staff daily
  call report (calls attended / talk time / idle time between calls).
