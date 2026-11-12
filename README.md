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

**Releasing a new version — publish the APK before advertising it to phones:**
1. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`.
2. Build and validate the signed APK. A push to `main` automatically creates a release
   tag from the verified APK version; a branch workflow produces an artifact for testing.
3. Verify the new release asset exists, its package/version, and the existing shared
   signing certificate. Never replace an existing release tag with different APK bytes.
4. Only then update `version.json` with the new `versionCode`/`versionName`/`apkUrl`
   and push it to `main`. Android still requires the user to accept installation.

## Loading recovery (v1.11)

The WebView restores its history and reloads the current page after activity recreation.
Network/main-page HTTP or TLS failures show Retry; Retry makes the WebView visible again.
Loading has a 30-second deadline, and stale navigation callbacks cannot cancel a newer
page's deadline. A committed visible page stops the native spinner; this does not certify
that every CRM API request has completed. Normal launches honour server cache freshness;
the existing one-time version-upgrade migration remains in place.

The blue Office SIM confirmation auto-hides after five seconds. Red SIM/permission warnings
remain visible until their underlying issue is resolved. SIM selection does not gate CRM
page loading.

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


## Staff Phone / SIM setup (1.13)

The trusted staff CRM can call `window.APSLiveCalls.openSetup()` to open the native Phone / SIM setup screen. The read-only `getSetupStatus()` returns safe checklist metadata (one-based selected slot/carrier and permission booleans); it does not request permissions or change a SIM. Requests use independent IDs so status and logout replies do not interfere. Bridge actions are limited to `prepareLogout`, `openSetup` and `getSetupStatus`, on the approved HTTPS CRM main frame. `aps-live-bridge-ready` is dispatched after injection and on resume.

The scrollable screen groups Phone/call-log access, explicit actual Office SIM selection/change, reporting consent, effective notification permission/global/channel settings, default Phone role, Android14 full-screen access and App settings. State refreshes on resume and permission-result callbacks. Permanent-denial/restricted-setting cases offer explicit Android App settings rather than repeating ineffective grants. Only slot/carrier labels are shown; subscription IDs, ICCIDs, SIM phone numbers and cookies are not exposed through the bridge.

Direct CRM auto-calling on a multi-SIM phone now requires a positively mapped selected Office phone account. If mapping is missing or Office choice is held, no automatic call is placed and a setup action is offered; it does not silently use the personal/default SIM. Existing single-SIM default behaviour remains. Explicit Office SIM changes clear cached live scope and re-evaluate the current call. Ordinary manual Phone keypad calls still use Android's normal account selection.

Validation: `testDebugUnitTest assembleDebug` and `node tests/live-call-bridge.test.cjs`. Real-device calls, permissions/default-role grants and SIM changes are not exercised by the automated build tests.

## Update recovery (1.14)

The updater now polls persisted download status, shows progress and actionable retry/browser errors, resumes after the unknown-source permission screen, and offers an explicit retry after installer cancellation. Completed APKs must match this package, the expected newer version and the installed signing certificate before installation. APK navigation opens the external browser instead of a binary document inside the CRM WebView. Blank CRM loads retain a bounded recovery screen with Retry and Open CRM in browser.

These changes address defects found in the previous implementation; the reported handset failure was not reproduced on a physical device. Existing installations whose updater cannot finish can download the signed APK directly in Chrome and choose Update. Do not uninstall or clear app data. Release publication and version.json promotion are separate from building this artifact.

Validation: 19 native unit tests, existing JavaScript bridge tests, signed debug APK build and package/version/signature verification. Real-device download, Android installer/permission transitions and CRM recovery remain to be checked by the owner.
