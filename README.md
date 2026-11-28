# Karaoke YouTube — standalone APK for Android TV Box

**Version:** 1.0.26-pinggy (versionCode 27) · **APK:** `KaraokeYouTube.apk` — **2,769,372 bytes**
**Package:** `vn.karaoke.youtube` · **minSdk 21 / targetSdk 30** · **Signed** (new per-project keystore `keystore.jks`, alias `karaoke`)
**Build:** `./build.sh` — manual aapt2 → javac (Java 8) → d8 → zipalign → apksigner, no Gradle.
**Note:** `libs/` (binary jars/AARs) and `keystore.jks` are NOT in this repo — run `./fetch-libs.sh` first to download the libraries, and `build.sh` generates a fresh local keystore on first run; neither is ever committed.
No decompilation/repack of tvhome: clean rewrite from the behaviour spec in
`~/workspace/karaoke-split-inventory.md`.

## Internet exposure: Pinggy ONLY — no Cloudflare, no Tailscale, no ngrok/zrok, no Termux
- `PinggyTunnel` uses pure-Java SSH (**JSch 0.2.9, mwiede fork**, in-process, no binary,
  no extra process) to `free.pinggy.io:443`, remote forwarding `-R0:localhost:8792`,
  parses the returned `https://<random>.pinggy.link` URL from the shell output.
  **Why 0.2.9:** JSch 0.1.55 (2018) only offers ssh-rsa (SHA-1) host-key algorithms
  and Pinggy's modern SSH server refuses them — every connect failed with
  "Algorithm negotiation fail" (seen on the FPT Play 650, 2026-11-27). The mwiede
  fork offers rsa-sha2-256/512, ssh-ed25519, ecdsa, curve25519 KEX. **Do NOT
  upgrade to 0.2.16+ / 0.2.21:** d8 8.2.2 (build-tools 34.0.0) crashes with an
  internal NPE on `Channel$1.class` in those jars; 0.2.9 dexes cleanly.
- Free Pinggy dies after ~60 min with a random URL each time → auto-reconnect
  (re-dial at ~55 min + reconnect on drop, ServerAliveInterval 30 s) and the
  current URL + QR are always shown on the phone panel, in the notification,
  and as the floating QR (Pinggy URL only).
- Optional basic auth (`b:user:pass` in the SSH username, free on Pinggy) in
  `/api/pinggy?action=auth&user=..&pass=..`, default off. No account/token needed.
- `libcloudflared.so` (28 MB) is NOT bundled; no `com.gathin.*` references anywhere
  (verified: 0 matches in the APK).

## What is inside
- `KaraokeActivity` — WebView (no layout XML) loading `http://127.0.0.1:8792/stage`,
  JS bridges `PX` (player) / `MR` (resolver) / `KB`; SurfaceView behind the
  transparent WebView for ExoPlayer video; D-pad: centre play/pause, ←/→ prev/next.
- `PlayerService` — foreground service (`mediaPlayback`, START_STICKY, ongoing
  notification), owns `MesuPlayer`, `LibraryState`, `RemoteServer`, `PinggyTunnel`.
- `MesuPlayer` — ExoPlayer **2.19.1** (core/hls/dash/extractor/… AARs from Google Maven).
- `YoutubeApi` + `MesuResolver` — InnerTube `youtubei/v1/search` + `/player`,
  client ANDROID, UA `com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip`,
  oEmbed + Invidious fallbacks (`invidious.ducks.party`, `invidious.materialio.us`,
  `yt.chocolatemoo53.com`); stage tries direct URL then `/stream?u=` proxy for
  manifests/.m3u8, 1 s stall watchdog.
- `RemoteServer` — port **8792**, ServerSocket + HttpURLConnection + org.json only,
  HTML pages as string constants: `/stage`, `/panel`, `/api/state|search|control|
  enqueue|playnow|paste|channel|playlists|playlist|playlist-add|create|delete|
  enqueue|play|remove|pinggy|overlay|qr|resolve`, `/stream` proxy (Range passthrough).
  Queue + playlists persisted in SharedPreferences (`LibraryState`).
- `QrMatrix` + `QrCode` — **hand-rolled QR encoder** (byte mode, EC level M,
  versions 1–6, Reed-Solomon, mask penalty scoring), no QR library bundled.
  Verified on the JVM: 7/7 test strings round-trip through ZXing's decoder.
- `QrOverlayService` — floating QR over any app / home screen (WindowManager
  `TYPE_APPLICATION_OVERLAY` / `TYPE_PHONE`, `FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL`,
  draggable, long-press hides until the next URL change). **QR rule: Pinggy URL ONLY — never the LAN URL.
  Tunnel down/reconnecting → QR hidden completely until the new URL is ready.**
  Toggle: `/api/overlay?action=enable|disable` or the stage page.
  **Gathin ratio (measured from tvhome-new.apk, classes5.dex, MesuHome.build()):**
  white card, corner radius 12 dp, padding 4 dp (sides/top) / 3 dp (bottom)
  since v1.0.17 (Gathin used 9/6 — the user asked for the white background
  to be tighter and the label pulled close under the QR),
  QR image **64 dp** square (v1.0.26, was 96 dp; Gathin used 132 dp),
  label "Karaoke" 16 sp colour #FF14213A, position **top-LEFT, margins
  16 dp**. While a video is playing the QR shrinks to **48 dp** (label
  12 sp). Tap toggles small (64 dp <-> 48 dp); long-press hides it until
  the next Pinggy URL change. The home-screen 3x enlargement is GONE
  (removed v1.0.26 — the user hated the 288 dp card on his TV photo
  2026-11-28 11:09; home detection and the usage-access prompt are deleted
  too, and PACKAGE_USAGE_STATS is gone from the manifest). (The other
  Gathin QR, 190 dp centred in the MesuSettings activity, is a
  settings-screen QR, not the floating one.)
- `BootReceiver` — `BOOT_COMPLETED` → starts `PlayerService` (→ RemoteServer 8792
  + Pinggy tunnel + overlay). No launcher coupling.

## Auto-log (telemetry) — the app sends its log home by itself (v1.0.5+)
- `LogUploader` POSTs to a webhook.site token (see `LogUploader.ENDPOINT`,
  created 2026-11-27, **expires ~2026-12-04** — mint a fresh token + rebuild if
  logs stop arriving). Every upload carries the full in-memory log (last 120
  lines) + device info. No credentials are ever sent.
- Tags: `STARTUP` (~6 s after launch), `CRASH` (with full stack trace + thread,
  sent synchronously, 4 s cap), `STAGE-ERROR` / `STAGE-TIMEOUT`,
  `PINGGY` (tunnel online + any tunnel error, e.g. "Algorithm negotiation fail"),
  `SEARCH-ERR` (search failed on all sources), `RESOLVE-ERR` (no playable stream),
  `PLAY-ERR` (ExoPlayer error / resolve / prepare failure). Also logged (no
  upload): each search result count + source, each resolve, play start, track
  end → next, sleep-timer set, boot intent, QR first shown with a URL.
- The first-run permission screen was REMOVED in v1.0.5: the app opens straight
  to the stage. Battery/overlay/usage grants are no longer prompted at first run.

## Crash history on the real box (FPT Play 650, 2026-11-27)
- v1.0.0–v1.0.5 crashed on launch with `RemoteServiceException: startForegroundService()
  did not then call startForeground(): QrOverlayService` — the overlay service
  checked overlay permission first and called `stopSelf()` without `startForeground()`.
  **Fix in v1.0.7:** `startForeground()` is called first in `onCreate()`, and the
  service is only started when overlay permission is already granted.
- v1.0.7 confirmed working on the box: stage loads (`onPageFinished`), no crash.
  The floating QR stays off until overlay permission is granted.
- v1.0.10 (video fix): v1.0.9 played audio but no video — the stage page painted
  an opaque `#09090d` body background over the SurfaceView, and a dead empty
  `<video>` element sat on top of it. Fix: stage adds `html,body{background:transparent}`
  after COMMON_CSS (phone panel keeps its opaque background), the dead
  `<div id='videoZone'>` + `<video id='p'>` + unused `loadVideo()` are removed
  (video comes from ExoPlayer on the SurfaceView only), `attachSurface()` now
  retries every 500 ms up to ~10 s when the service isn't up yet, and
  `onVideoSizeChanged` logs `Video: <w>x<h>` so the webhook log proves a video
  track exists.
- v1.0.11 (bare TV stage, like the original Gathin): the stage page is now a
  bare transparent page — `html,body{margin:0;padding:0;background:transparent;
  height:100%;overflow:hidden}`, no topbar, no search box, no queue, no links,
  no QR, no script at all (read from the real Gathin stage HTML,
  `gathin-stage-original.html`). Video shows fullscreen from the SurfaceView;
  when idle the screen is black (Activity root background). The Pinggy row
  (Bật/Tắt/Đổi link) and QR-nổi row (Hiện/Ẩn/Cấp quyền) moved from the stage to
  the phone panel (`/panel`), so nothing is lost. **Known gap:** track-end
  advance still works without stage JS (`PlayerService.endCheck` polls the
  player state every 3 s and plays the next queue item on code 0), but
  stall/error advance does NOT — it lived only in stage JS (v1.0.10
  `watchStall()` called `ctl('next')` on state -1/3; the original Gathin stage
  had its own `startWatch` for the same job). A stalled/errored track now sits
  until the user presses Next on the phone panel or remote. Not fixed here —
  needs a service-side stall watchdog, deliberately left for a later version.
- v1.0.12 (service-side stall watchdog — closes the v1.0.11 gap above):
  `PlayerService.endCheck` (3 s poll) now counts consecutive polls where a
  current queue item exists and the player state is 3 (buffering) or -1
  (error); at 10 polls (~30 s) it logs "Bai ket/loi, chuyen bai ke" and
  advances exactly like the ended-path (`library.next()` → `playNow()`,
  `player.stop()` if the queue is empty). Any other state (1 playing,
  0 ended, 2 paused) resets the counter — paused never counts as a stall.
- v1.0.13 (in-activity QR card on the TV screen — fixes the v1.0.11
  chicken-and-egg): v1.0.11 stripped the stage bare and left the floating
  system-overlay QR as the ONLY QR, but that QR needs the overlay-permission
  grant, which needs opening the phone panel, which needs scanning a QR.
  The original Gathin never had this problem — its QR was a card INSIDE the
  activity (qrCard), always visible, no permission needed. `KaraokeActivity`
  now builds that card again (same styling as `QrOverlayService`'s floating
  card: white, radius 12dp, padding 9dp sides/top + 6dp bottom, QR 132dp,
  label "Karaoke" 16sp 0xFF14213A, bottom-left, 16dp margins), added to the
  Activity's root FrameLayout after the WebView so it sits on top; it covers
  only its own small corner of the video. Pinggy URL only, never the LAN URL;
  hidden entirely while the tunnel is down/reconnecting; polled every 2 s via
  `stageHandler`, regenerates on URL change and logs "QR man hinh: hien link
  <url>". The floating `QrOverlayService` is untouched (still the QR over
  other apps / the home screen).
- v1.0.23 (fresh log token + full-screen-intent fallback for exit-play
  video, 2026-11-28 09:01): TWO fixes. (1) LOG CHANNEL: the webhook.site
  token from 2026-11-27 died — free tokens cap at 50 stored requests, then
  answer HTTP 429 "Request limit exceeded" and DROP every upload, so the app
  looked like it had stopped logging while it was still uploading (last
  upload seen 08:32:51; a test POST proved the 429). Token replaced with a
  fresh one minted 2026-11-28 (expires ~2026-12-05); same 50-request cap
  applies, so expect another swap around then. (2) EXIT-PLAY VIDEO: the
  v1.0.21 startActivity bring-to-front did NOT work on the FPT Play 650 —
  user installed v1.0.22 and still got audio-only when playing from the
  panel while exited. Audit (in `PlayerService.playNow()` comments): on API
  30 a blocked background activity start fails SILENTLY — startActivity()
  does not throw, so the try/catch could not see it; the
  SYSTEM_ALERT_WINDOW exemption evidently does not take effect on this
  operator box. The surface re-attach path was verified sound (no gap).
  Fix: keep the startActivity attempt as the FIRST try; ~1 s later, if
  `KaraokeActivity.isForeground` is still false, post a HIGH-PRIORITY
  notification with a FULL-SCREEN INTENT to KaraokeActivity (new channel
  `karaoke_fullscreen`, notification id 8793, `USE_FULL_SCREEN_INTENT`
  declared in the manifest) — the mechanism Android provides for showing
  UI from a background service, independent of the background-activity
  exemption. On an unlocked TV box it normally surfaces as a heads-up the
  user opens with one remote OK press (on many TV firmwares it opens
  straight away). Every step is logged ("Mo app: ..." lines) and the
  fallback can never break playback — audio keeps playing no matter what.
- v1.0.24 (overlay video surface for exit-play + PLAY-BG log upload +
  usage-access prompt, 2026-11-28): THREE fixes. (1) ROOT CAUSE of the
  exit-play audio-only bug, found by reading `RemoteServer.playItem()`:
  it called `player.play()` DIRECTLY, bypassing `PlayerService.playNow()`
  entirely — so the v1.0.21 bring-to-front AND the v1.0.23 full-screen
  intent never fired for ANY panel play (the user's exact test). The
  v1.0.23 audit's "/api/playnow -> playItem -> playNow" trace was wrong.
  playItem now routes through `playNow()`, the true single choke point
  for every play start (panel, paste, next/prev, queue advance, stall
  watchdog, D-pad). (2) OVERLAY VIDEO: `playNow()` while the activity is
  not in the foreground now also attaches the player to a service-owned
  fullscreen `WindowManager` overlay (`TYPE_APPLICATION_OVERLAY`,
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, MATCH_PARENT) holding a
  `SurfaceView` — the video renders over the home screen / other apps,
  a mechanism background-activity-start restrictions cannot block (the
  app holds SYSTEM_ALERT_WINDOW and already runs an overlay service).
  The activity surface takes over on `onResume`/`attachSurface()` (the
  overlay window is then removed); exiting while playing re-attaches the
  overlay in `onPause()`; `endCheck` (3 s poll) shows it when playing in
  the background (covers resume-after-pause, which never goes through
  playNow) and removes it on pause/stop/end/error so no black overlay
  sits on screen. All switching goes through the one `ensureSurface()`
  path; the overlay's `surfaceDestroyed` never clears a live overlay
  surface, and the activity's `surfaceDestroyed` never clears one
  either. The floating QR is re-added on top of the video window
  (`QrOverlayService.bringToFront()`) and shrinks to 64dp while the
  overlay video is showing. The v1.0.23 full-screen intent stays as a
  secondary path. (3) PLAY-BG: `playNow()` while not in the foreground
  now triggers a log upload (new tag `PLAY-BG`, delayed 2 s so the
  "Video overlay:" / "Mo app:" lines land first) — a normal panel play
  used to fire NO upload tag, so the exit-play evidence never left the
  box. Only in the background case, so the token's 50-request cap is not
  burned by normal playback. (4) HOME 3x: the home-screen 3x rule could
  NEVER work — usage access was never requested anywhere, and the
  `ActivityManager` fallback only returns this app's own processes on
  API 26+, so the launcher was never detected (the QR stayed 96dp on the
  home screen, user's TV photo 2026-11-28 09:18). One-time
  usage-access prompt added (`maybePromptUsageAccess()`, modelled on the
  overlay prompt; flag `usage_prompt_asked`). Sizes unchanged
  (96/64/288dp).
- v1.0.25 (Back dismisses the overlay video + PLAY-BG upload on overlay
  show + usage-prompt once-ever check, 2026-11-28): (1) BACK-TO-DISMISS.
  User report 2026-11-28 09:47 ("tao bam Khong thoat duoc"): the v1.0.24
  overlay window was `FLAG_NOT_FOCUSABLE`, so no remote key ever reached
  it and there was no dismiss path at all — Home switches the foreground
  app but the overlay stays on top while the video plays. The window is
  focusable now (`FLAG_NOT_FOCUSABLE` removed, `FLAG_NOT_TOUCH_MODAL`
  kept) and the overlay `SurfaceView` takes focus (also re-asserted in
  `surfaceCreated`) with an `OnKeyListener`: Back hides the overlay AND
  pauses the player (user correction 2026-11-28 09:49 — "Thoat thi khong
  can tieng nua"), using the exact same `MesuPlayer.pause()` the phone
  panel's pause button uses, so the player state stays consistent. A
  sticky flag (`videoOverlayDismissedByUser`) suppresses `endCheck` /
  `onPause` re-show until the next `playNow()` (new track), panel resume
  (`/api/control` toggle-to-play / resume / play clears it), or app-open
  (`onResume` clears it); pressing play again from the panel resumes and
  the overlay shows again within ~3 s (endCheck poll). Other remote keys
  are dropped (the video is display-only; the panel controls playback).
  Everything try/catch — audio never breaks on a Back press. (2) PLAY-BG
  upload on overlay show: `showVideoOverlay()` now schedules the delayed
  PLAY-BG upload itself on a real show transition — the GTA session proved
  the onPause / endCheck path had none (track started in-foreground, the
  overlay only appeared later via `onPause`, no upload ever fired). Same
  reusable Runnable as `playNow` (re-posting replaces the pending one, so
  at most one upload); suppressed shows (dismissed flag / activity
  foreground) upload nothing. (3) USAGE PROMPT CHECK: `maybePromptUsageAccess()`
  was already once-ever — `usage_prompt_asked` is set BEFORE `show()`, so
  declining ("Để sau"), Back, or tap-outside all set the flag; no change
  was needed. The v1.0.23 full-screen intent and the `startActivity`
  attempt stay as secondary paths.
- v1.0.26 (panel pause/stop main-thread fix, 2026-11-28): the panel's
  pause/stop buttons were BROKEN — proven live 2026-11-28 11:01:
  `/api/control?action=toggle` and `?action=stop` both answered
  `{"error":"Player is accessed on the wrong thread. Current thread:
  'pool-2-thread-10'. Expected thread: 'main'..."}`, state stayed
  "1,0,0,0,0" — the video kept playing and the overlay kept covering the
  screen, so the user could not even close the old app to install the new
  one (the earlier "press Pause on the phone" workaround was dead on
  arrival). Root cause, same class as the v1.0.15 stateJson bug but in the
  CONTROL path: RemoteServer answers HTTP on a cached pool thread and its
  control actions called `MesuPlayer.pause()/resume()/stop()/setVolume()/
  seekTo()` directly on that thread; ExoPlayer 2.19.1 enforces
  application-thread access. WHY playNow worked while toggle/stop threw
  (traced end to end): `MesuPlayer.play()` never touches ExoPlayer
  synchronously — it spawns a resolver thread and that thread posts
  `prepareFromCandidates()` (setMediaSource/prepare/setPlayWhenReady) to
  a main-looper Handler, so every real ExoPlayer call in the play path
  lands on the main thread; the control methods call ExoPlayer
  synchronously on the calling thread. FIX: `PlayerService.playerAction
  (name, action)` is now THE single choke point for every player mutation
  — off-main it logs `Dieu khien: <name> len main thread` (the proof
  line), posts to the main handler and waits up to 3 s; on-main it runs
  inline. Wrappers `pausePlayer()/resumePlayer()/stopPlayer()/
  setPlayerVolume()/seekPlayer()` are the only way the HTTP layer
  (`/api/control` toggle/pause/resume/stop/volume/seek, plus next/prev/
  jump via `playItem` → `playNow`), the service internals (endCheck,
  sleepCheck, onStartCommand STOP, overlay Back handler) and the
  activity (D-pad, JS bridge — the bridge runs on a WebView thread) mutate
  the player; even `playNow`'s `player.play()` goes through it. Direct
  mutations remaining: `MesuPlayer.play()` itself (safe by construction),
  the service-null defensive fallback in `playItem()`, `ensureSurface()`
  (all callers main-thread), `getState()/getLastError()` (read-only,
  v1.0.15), `release()` in `onDestroy` (teardown). The control endpoint
  now also waits 300 ms after a mutation before answering, so the reply's
  state reflects the listener-updated code (setPlayWhenReady notifies
  asynchronously). GOOD NEWS, proven live in the same session: the v1.0.24
  overlay fix works — "Video overlay: tao/hien" at 09:46:10 and 10:34:40,
  PLAY-BG uploads arrived; not regressed by this build. (5) QR SMALLER +
  HOME 3x REMOVED (user corrections 2026-11-28 11:09–11:10, same build):
  "May dua a cai QR to the" — his TV photo showed the floating card at
  ~0.32 of the screen width (288dp, the home 3x live for the first time
  after the v1.0.24 usage-access prompt got granted); and "Cai tv t co
  300dp thoi" — even the 96dp base would be a third of his screen in his
  terms. So: the home-screen 3x enlargement is REMOVED entirely
  (`HOME_SCALE` / `targetScale()` / home detection deleted, `QrOverlayService`
  no longer touches UsageStatsManager at all), the one-time usage-access
  prompt (`maybePromptUsageAccess()`) is deleted, and PACKAGE_USAGE_STATS
  is removed from the manifest. Base QR is now **64dp** (was 96dp) on BOTH
  QRs (floating overlay + in-activity card), playing **48dp** (was 64dp),
  label 16sp / 12sp while playing, padding 4dp/3dp, radius 12dp, top-left
  16dp, tap toggles small (64dp <-> 48dp), long-press hides, /api/overlay
  on/off, Pinggy-only "/panel" content, hide-while-reconnecting — all
  unchanged. Rationale: 64dp = 128 physical px on this 320dpi box, ~2.8px
  per module for the v5 panel URL — still scannable from the couch, and
  half the Gathin original's 132dp.
- v1.0.22 (QR smaller while a video is playing, user asked 2026-11-28
  08:33 "Tôi cần QR bé hơn khi đang trong trình phát video"): while a video
  is playing (player state st==1 in "st,pos,dur,w,h,f") both QRs shrink to
  64dp with a 12sp label, and grow back to 96dp / 16sp when paused or idle.
  In-activity card: `refreshQrCard()` (2 s poll) evaluates the player state
  on every tick and only re-applies the ImageView size + label size on
  change — the 512px bitmap + FIT_XY stay, so no bitmap regeneration.
  Floating overlay: shrinks ONLY when the video is actually visible, i.e.
  `KaraokeActivity.isForeground` (v1.0.21 flag) AND playing; on the home
  screen / in other apps the 96dp base and the home-screen 3x rule (288dp)
  are unchanged. In the overlay the playing size wins over the scale (a
  manual 3x tap cannot blow the card up over the video). Both state reads
  are null-safe / try-catch and fall back to 96dp on any error, so a bad
  read can never hide the QR. Corner (top-left), padding, radius, Pinggy-
  only "/panel" content and hide-while-reconnecting all unchanged.
- v1.0.21 (video now brings the app to the front, user report 2026-11-28
  08:19 "qr ở ngoài màn hình chính hoặc trên bất kì app khác khi chọn phát
  video thì nó không mở. Mặc dù vẫn có tiếng"): the player lives in the
  foreground service, but its video goes to the SurfaceView that only exists
  inside KaraokeActivity — so playing from the phone panel while the user was
  on the home screen / another app played audio-only, with no surface at all.
  `playNow()` (the single choke point: /playnow, queue advance, stall
  watchdog, D-pad next/prev) now starts KaraokeActivity with
  FLAG_ACTIVITY_NEW_TASK | SINGLE_TOP | REORDER_TO_FRONT whenever
  `KaraokeActivity.isForeground` (new public static volatile flag, true in
  onResume, false in onPause/onDestroy) is false, BEFORE starting playback.
  The activity is singleTask, so the existing instance is reused and
  onResume re-attaches the surface; if it was finished, a fresh instance is
  created and loads the stage as usual. onResume also reloads the stage via
  the existing wait-for-server-then-loadUrl path if it never loaded or an
  earlier load errored (otherwise the dead status overlay would cover the
  video). Background activity start is allowed because the app holds
  SYSTEM_ALERT_WINDOW (overlay permission, already granted — the floating QR
  proves it), which exempts it from the Android 10+ background-activity-start
  restriction; the bring-to-front is wrapped in try/catch and can never break
  playback — audio keeps playing even if the start fails.
- v1.0.20 (smaller QR, top-left corner, user asked 2026-11-28 08:12 "T cần
  cái qr bé hơn nữa và để góc trên bên trái màn hình chứ k phải bên dưới"):
  both QRs (in-activity card and floating overlay) are now 96dp (was the
  Gathin 132dp) and anchored TOP-LEFT with 16dp left/top margins (was
  bottom-left). This also settles the long-open corner question: the Gathin
  original was bottom-left, but the user explicitly wants top-left. Home-
  screen 3x rule unchanged — the multiplier still applies, so on the home
  screen the card is now 288dp (was 396dp). Padding 4dp/3dp, radius 12dp,
  label 16sp 0xFF14213A, Pinggy-only "/panel" QR content, hide-while-
  reconnecting, drag, tap-to-toggle, long-press hide all unchanged.
- v1.0.19 (panel self-heals through Pinggy's "Caution" screen, 2026-11-28
  07:48): Pinggy free serves a "Caution — you are about to visit a website
  that is served for free through pinggy.io" interstitial to browser UAs on
  EVERY request, incl. /api/state (verified live: curl default UA = real
  content, iPhone Safari/Chrome UA = 15,492-byte Caution HTML). A real-browser
  check of /panel showed the static UI rendering after "Enter site" but ALL
  dynamic data stuck empty ("Đang tải…" forever) — fetch('/api/state') got
  Caution HTML, r.json() threw, refresh() died. Bypass (from the Caution
  page's own access() script): an XHR GET to the page URL with header
  "X-Pinggy-No-Screen-Cookie: true" + withCredentials sets the bypass
  (response body empty by design); browser-UA requests from the same client
  after it return real JSON. Fix (panel JS only): the panel fires that
  handshake itself on load (before the first refresh, ~800 ms), and all API
  calls go through apiSafe() — fetch + JSON.parse, on failure re-fire the
  handshake and retry once; refresh() is wrapped in try/catch and shows
  "Đang kết nối lại…" instead of stuck "Đang tải…", with the 5 s interval
  still retrying. Retry is safe: a parse failure means Pinggy intercepted
  the request, so it never reached the server (no double-execution of
  control actions). Panel JS syntax-checked with node --check.
- v1.0.18 (white control-panel page, user scanned the QR a dozen times and
  his iPhone showed a pure white page, 2026-11-28 07:40): root cause — the QR
  encoded the bare Pinggy root URL, and "/" served the bare transparent TV
  stage (zero UI since v1.0.11), which on a phone browser renders as a white
  blank page; the panel itself was fine at "/panel" (verified live: HTTP 200,
  full HTML, /api/state full JSON). Fix: both QRs (in-activity card and
  floating overlay) now encode publicUrl + "/panel"; "/" now serves the panel
  PAGE too, so any old QR/link to the root also opens the panel; the panel's
  links row now shows both URLs with the "/panel" suffix. "/stage" still
  serves STAGE_HTML (the TV WebView loads stageUrl() = /stage explicitly).
  QR capacity verified on the JVM: the longest real panel URL (80 chars,
  IPv6-suffixed run.pinggy-free.link + /panel) encodes as QR v5 (37x37),
  inside the v1-6 encoder — no capacity fix needed.
- v1.0.17 (1080p60 + tighter QR card, user asked 2026-11-28 07:24 "Tao cần
  1080p60fps và làm gọn cái QR lại nền trắng đang bị rộng quá. Và chữ
  karaoke kéo sát vào QR đi cho tiết kiệm không gian"): (1) the frame-rate
  cap is REMOVED (`setMaxVideoFrameRate` gone) and the bitrate cap raised to
  12 Mbps (was 8) — 1080p60 YouTube variants run ~8-12 Mbps, so 1080p60
  stays selectable; max size stays 1920x1080. Honest note: the box decoder
  DID die once on a 1080p60 segment (log 2026-11-28 07:04:48), but the user
  explicitly demands 60fps, so no cap is re-imposed. (2) Both QR cards
  tightened the same way — card padding 9dp sides/top + 6dp bottom is now
  4dp + 3dp, and the "Karaoke" label sits tight under the QR
  (`setIncludeFontPadding(false)`, line spacing 0 / 1.0x). QR stays 132dp,
  label 16sp 0xFF14213A, radius 12dp, bottom-left 16dp margins; the
  floating card's home-screen 3x scaling multiplies the new smaller
  padding as before. `QrCode.bitmap()` already uses a 2-module quiet zone
  (same as the Gathin original), so it was left alone.
- v1.0.16 (default video quality 1080p, user asked 2026-11-28 07:19 "T cần
  mặc định 1080"): the `DefaultTrackSelector` cap is now max 1920x1080,
  max 30 fps (`setMaxVideoFrameRate(30)`), max 8 Mbps. v1.0.14's 720p cap
  was too blunt — the FPT Play 650 played 1920x1080 fine for ~11 minutes
  and only died on a 1080p60 segment (avc1.4D402A, 5.5 Mbps), so 1080p
  itself is fine and 60 fps is what kills the box. 8 Mbps keeps 1080p30
  selectable (YouTube 1080p30 variants run ~5-6 Mbps) while still
  guarding the weak box.
- v1.0.15 (phone control panel dead, verified live against the user's box
  through its Pinggy tunnel, 2026-11-28 07:10): `GET /api/state` returned
  `{}` on every call and `/api/control?action=toggle` closed the connection
  with no reply — so the panel showed "Chưa phát bài nào", "Hàng đợi trống",
  "LAN: undefined", "Internet (Pinggy): undefined" and every control tap
  looked dead (search and playlists still worked). Root cause:
  `stateJson()` put `player.getState()` first and its catch returned an
  empty JSONObject on ANY exception; `MesuPlayer.getState()` called
  `player.getPlaybackState()` / `player.isPlaying()` without try/catch, and
  ExoPlayer 2.19.1 throws `IllegalStateException` from those when called off
  the application thread — RemoteServer answers from a pool thread. Fixes:
  (1) `getState()` now uses only the listener-maintained `lastState` and is
  wrapped in a whole-method try/catch — it can NEVER throw, from any thread.
  (2) `stateJson()` guards EACH field in its own try/catch with a safe
  default, so one bad field can never blank the whole state again.
  (3) `RemoteServer.handle()`'s catch now tries to answer HTTP 500 with a
  small JSON `{"error": "..."}` before closing the socket, so a failing
  request never dies silently again.
- v1.0.14 (three fixes from real-device logs, FPT Play 650, 2026-11-28
  07:06): (1) video capped at 1280x720 / 4.5 Mbps in the ExoPlayer
  `DefaultTrackSelector`, exactly like the original Gathin — the box decoder
  played 1280x720 then 1920x1080, then died on a 1080p60 segment
  (avc1.4D402A, 5.5 Mbps, advertises support but chokes); our rewrite had no
  cap. (2) `surfaceDestroyed()` now clears the player's video surface
  (`ensureSurface(null)`, try/catch) — after the user exited the app,
  playback failed again even on 640x360 because the player kept a dead
  surface; `surfaceCreated`/`onResume` re-attach the fresh surface.
  (3) ONE-TIME overlay-permission prompt on launch: a small dismissible
  native dialog ("QR nổi trên màn hình?") shown only if the grant is missing
  and never asked before (`karaoke_prefs` / `overlay_prompt_asked`); "Cho
  phép" opens the system overlay-permission screen, "Để sau" dismisses —
  either choice never shows it again (the phone panel's "Cấp quyền" button
  stays as the manual path). `onResume()` also starts `QrOverlayService`
  when enabled + permitted, so the floating QR appears right after the user
  returns from granting.
- v1.0.9: Pinggy's REAL free URL is `https://<random>.run.pinggy-free.link`
  (and `https://<random>.free.pinggy.net`) — the old regex only matched
  `*.pinggy.link`, so the tunnel sat in "waiting-url" forever and no QR ever
  appeared. The stage now has a **"Cấp quyền"** button (`/api/overlay?action=grant`)
  that opens the system overlay-permission screen, because without that grant
  the floating QR can never show and there is no in-app prompt for it.

## Autostart + anti-Doze (chống ngủ đông)
- `PlayerService` holds a `PARTIAL_WAKE_LOCK` ("karaoke:playback") and a
  `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`, "karaoke:wifi") while the server/tunnel
  runs; `onTaskRemoved()` restarts the service; a 10 s watchdog revives
  RemoteServer and the Pinggy session if they die.
- **Honest limits:** targetSdk 30 / Android 8–11 has no Android 12+ auto-hibernation,
  but some vendor ROMs still kill background apps — the user may also need to lock
  the app in recents / enable autostart in the vendor settings, which no APK can
  force. On some TV Box ROMs the BOOT_COMPLETED autostart also needs the vendor's
  autostart toggle.

## Verification done on this VM (2026-11-27)
- `apksigner verify` OK; `aapt2 dump badging`: package `vn.karaoke.youtube` v1
  (1.0.0-pinggy), sdk 21 / target 30, 9 permissions
  (INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, FOREGROUND_SERVICE,
  RECEIVE_BOOT_COMPLETED, WAKE_LOCK, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
  SYSTEM_ALERT_WINDOW, PACKAGE_USAGE_STATS), launchable + leanback-launchable activity, services
  `PlayerService` + `QrOverlayService`, receiver `BootReceiver`.
- `unzip -l`: no `.so` files at all, no `libcloudflared.so`.
- Dex: 43 `vn.karaoke.youtube.*` classes; 0 matches for `gathin|cloudflare` in the
  whole APK; JSch `setPortForwardingR`, `createWifiLock`, `startForeground`,
  `onTaskRemoved` all present in the dex.

## Verified on the real box (FPT Play 650, 2026-11-27)
- Install + launch + stage load (`onPageFinished`) confirmed on v1.0.7.
- Crash root cause found from the auto-log itself (RemoteServiceException,
  QrOverlayService) and fixed in v1.0.7.

## NOT yet verified on the real box
- ExoPlayer playback of InnerTube streams (itag/cipher handling, HLS via
  `/stream` proxy); **JSch 0.2.9 → Pinggy handshake + public URL** (the 1.0.8
  fix — needs one open of v1.0.8 on the box, the PINGGY log will confirm it);
  60-min re-dial timing; overlay permission grant flow on the vendor ROM; boot
  autostart on the vendor ROM; battery-whitelist behaviour overnight.
- Known fragility (same as the original): InnerTube can break when YouTube
  changes it; signature-ciphered formats are skipped (no deciphering); no
  in-app updater — a break needs a rebuild.
