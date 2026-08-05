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

## Call-duration tracking (v1.2+)

`CallLogReporter.kt` listens for phone-state transitions (`PhoneStateListener`,
`READ_PHONE_STATE`) and detects a call ending (OFFHOOK -> IDLE). It then reads the just-
finished call's duration from `CallLog` (`READ_CALL_LOG`, ~1.5s delay for the log write to
land) and POSTs `{phone, durationSec, answered}` to
`https://agent.adiparasakthicharitabletrust.in/crm/api/leads/call-event` -- **note the
`/crm` prefix**: this is a raw native HTTP call, not a browser-rendered page, so it must hit
the CRM's real gateway path directly (nothing rewrites it the way `crmProxy.js`'s HTML
text-replace does for the WebView's own `/api/...` calls). The request reuses the WebView's
session cookie (`CookieManager.getInstance().getCookie(...)`) as a `Cookie` header, since
`auth.requireAuth` on the gateway rejects any `/crm/*` request without a valid session --
the native HTTP client has no cookie jar of its own.

The CRM already had a `callEvents[]` field reserved on every lead (unused placeholder from
an earlier phase) and the staff stats bubble / manager progress card already display
whatever's summed from it (an honest "Soon"/"0m" placeholder before this landed) -- this
version is the first one that actually produces that data.

## Planned next (not built yet)

- Idle-time-between-calls (gaps in the callEvents timeline) for the manager-side daily
  report -- computable from timestamps already being logged, no new tracking needed, just
  a report view.
