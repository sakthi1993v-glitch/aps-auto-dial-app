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

## Data-accuracy fixes (v1.8)

Three things were reporting confidently wrong data to the CRM. All three are producer-side
fixes in this app -- no CRM/server change is needed, though the server can now *optionally*
read one new field (see the SMS item).

**1. Only calls this app dialed are reported.** `CallLogReporter` detected a call ending and
then read the *newest* `CallLog` row, without checking whether this app had dialed it. So a
staff member's own outgoing call, or any incoming call they took, was posted as that lead's
call-event -- talk time the manager's stats showed for leads nobody had called. `MainActivity.dial()`
now hands the number to `CallLogReporter.expectCall()`, and the call-end handler reports only
when the call-log row matches it (digits-only, last 9 compared -- the log returns
`+91 98765 43210` where the CRM's `tel:` link has `9876543210`). The expectation is consumed
once and expires after 4h, so a manual redial of the same lead the next day isn't attributed
to the app.

**2. Bulk SMS "sent" now means actually sent.** `sendTextMessage(..., null, null)` was called
with no `sentIntent`, and `ok=true` was reported whenever that call didn't throw -- but it only
means "handed to the framework". No signal, radio off, or a null PDU all return normally, so
the CRM counted messages that never left the phone. Each part now gets a `PendingIntent` and
the real framework result code decides `ok`, with the failure reason (`no_service`,
`radio_off`, `null_pdu`, `generic_failure`) sent along on failure. The result body carries a
new `confirmed` boolean: on a 45s confirmation timeout the send is reported as `ok=true,
confirmed=false` **on purpose** -- re-sending would text the lead twice and burn the staff
member's daily allowance, which is worse than one unconfirmed send. The server can surface
`confirmed=false` separately; unknown fields are otherwise ignored.

**3. No more forced re-login on every update.** The version-upgrade branch of `wipeStaleState()`
wiped cookies and `WebStorage` ("fresh install"-like). With auto-update firing often, staff
re-logged-in on every single version -- and worse, the lost cookie also broke the two native
HTTP workers (`auth.requireAuth` rejects any `/crm/*` request without a session), so
call-events silently piled up in the retry queue until someone logged back in. The actual
complaint was stale *content*, and that's already handled on every launch by `clearCache()` +
`LOAD_NO_CACHE`; killing the session was never needed for it. The upgrade branch now clears
history and form data only.

## Planned next (not built yet)

- **Foreground Service for the background workers.** `CallLogReporter` and `BulkSmsSender` are
  both started and stopped by `MainActivity` (`onDestroy` stops them), so they only live as long
  as the Activity does. Android will kill the Activity under memory pressure or after a long
  screen-off, which strands an unattended bulk-SMS run part-way through. Moving both into a
  foreground service (with its notification, channel, and `POST_NOTIFICATIONS` on Android 13+)
  is the real fix. Deliberately kept out of v1.8 -- it's an architectural change and shipping it
  alongside the data fixes above would make a staff-phone regression hard to attribute.
- Idle-time-between-calls (gaps in the callEvents timeline) for the manager-side daily
  report -- computable from timestamps already being logged, no new tracking needed, just
  a report view.
