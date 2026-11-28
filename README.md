# Karaoke YouTube — standalone APK for Android TV Box

**Version:** 1.0.37-pinggy (versionCode 38) · **APK:** `KaraokeYouTube.apk`
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
- v1.0.39 (PINGGY PRO TUNNEL + TOKEN ENTRY, 2026-11-28 — user: wants a FIXED panel link + his custom domain karaoke.thiep-quan.online; everything must run ON THE BOX, no hosting on another machine):
  WHY: Pinggy free gives a random URL + 60-min timeout, so the panel link dies on every reconnect. Pinggy Pro ($3/month after a 7-day no-card trial) issues a persistent subdomain bound to a token in the dashboard, and a custom domain CNAMEs to it. The app already opens the tunnel in-process via JSch SSH, so Pro costs zero extra weight: same code path, authenticated.
  FIXES: (a) PRO TUNNEL (PinggyTunnel): a token stored in SharedPreferences ("karaoke_pinggy", key "proToken", empty = free tier) switches the tunnel to pro.pinggy.io:443 with the token as the SSH username, same -R0:localhost:8792 and same reconnect logic; the panel/QR shows whatever public URL the tunnel returns (the persistent one on Pro). Pro tunnels have no 60-min timeout, so the scheduled re-dial is 24 h instead of 55 min; the 30-s no-URL give-up is now measured from session start (mode-independent). URL regex also accepts a.pinggy.link explicitly. Log lines are "Pinggy: Pro" vs "Pinggy: free" only. (b) TOKEN ENTRY (RemoteServer): Settings tab "Pinggy Pro" section — masked password-type input + Lưu + Xóa token buttons (POST /api/pinggy/token {token}); on save it stores the token, restarts the tunnel, and toasts the new public URL (polled from /api/state after ~4 s); empty token clears it and falls back to free tier. GET /api/pinggy/token returns {hasToken} ONLY — never the value. POST works without the panel field too (curl). /api/state gains a "pinggyPro" boolean flag (never the token). (c) SECURITY RULES (hard): the Pro token is a credential — it must NEVER appear in any source file, in the APK as a constant, in any log line, in LogUploader output, in /api/state output, in README, or in any file pushed to GitHub. It is entered at runtime on the box only and stored in SharedPreferences (private mode) only. The panel field is masked (type=password) and cleared after save; the value is never rendered back. (d) Everything from v1.0.38 kept: log token 7c383cb4, quieter resolve (VISIONOS first), failure backoff, Settings "Sửa lỗi video" 5 buttons + /api/fix, v1.0.37 auto-recovery, no sign-in UI (YouTubeAuth dormant), STAGE byte-identical; caps unchanged (1920x1080 / 12 Mbps / no fps cap); version.json NOT touched (parent deploys to Vercel after review). NOT YET PROVEN (needs the box + the user's real Pro token): the Pro tunnel returning the persistent subdomain on real hardware, the custom-domain CNAME for karaoke.thiep-quan.online (DNS, not part of this build), and the token save/clear flow on the real panel.
- v1.0.38 (QUIETER RESOLVE + FAILURE BACKOFF + LOG TOKEN SWAP, 2026-11-28 — user: after v1.0.37 the box is blocked after only 3-4 songs; "phải có cách để ẩn khỏi google mới được k thì toàn bị chặn thôi"):
  WHY: v1.0.37's auto-recovery helps a stuck session, but each failure still triggers a storm of API calls (ANDROID + VISIONOS dance x2 + Invidious + proxy retry + re-resolve), and the stall watchdog cycled the queue every ~30 s during an outage — each skipped track fired a fresh storm, which flags the IP/session even faster. The box now gets blocked after 3-4 songs instead of ~2 h. The fix is to make the app QUIETER (fewer fingerprints per track) and to STOP hammering during an outage.
  FIXES: (a) LOG TOKEN SWAP: webhook.site token 887924a9… (minted 2026-11-28 15:49) is FULL — 50/50 stored requests, newest upload 20:16, HTTP 429 drops every new upload, so Mesu went blind during the 20:11 outage. New token 7c383cb4-bb2e-46fc-a0d5-81870e7d539a (minted 2026-11-28 21:05 UTC, expires ~2026-12-05) in LogUploader ENDPOINT + VIEW_URL; old token is ZERO in the dex. Same 50-request cap applies. (b) QUIET RESOLVE: YoutubeApi.resolve() now tries VISIONOS FIRST (new resolveVisionosPlayer() returns the full player response so title/channel/durationMs come from its videoDetails — no ANDROID call for metadata). When it returns a usable HLS manifest, the ANDROID player call AND the Invidious call are SKIPPED entirely for that track. A normal track costs ~1 player API call instead of ~4. When VISIONOS fails, the old ladder runs unchanged (ANDROID -> Invidious -> progressive, with the v1.0.37 rules: fresh-visitorData retry in the dance, Invidious progressive always appended last). The dance is attempted ONCE per resolve() — the ladder reuses that attempt instead of dancing again. resolve(videoId, boolean fullLadder): fullLadder=true runs the old v1.0.37 ladder (ANDROID first, then VISIONOS + Invidious) — used ONLY by the manual "Phát bản 360p" fix, which needs the full candidate list to find itag 18 (the quiet path returns a single VISIONOS HLS candidate). Trade-off, recorded honestly: a quiet-path track has a single candidate, so the v1.0.35 runtime fallback has nothing to fall back to and "Đổi nguồn phát" has no next source for it; a playback 403 still gets the v1.0.37 single re-resolve (fresh manifest URL) and then the backoff. (c) FAILURE BACKOFF (PlayerService): consecutive failed tracks are counted in endCheck (a track that plays, state 1, or ends normally, state 0, resets the counter). After 2 consecutive failures the next advance waits 60 s; after 3+ it waits 90 s (cap). Log: "Cho doi 60s sau 2 bai loi lien tiep" / "Cho doi 90s sau 3 bai loi lien tiep". The wait runs BEFORE advancing to the next track; while a wait is pending endCheck does not count stall polls (or the ~30 s clock would fire a second advance mid-wait). The wait is a single handler.postDelayed (scheduleBackoffAdvance) — it always fires, never stalls forever; the advance only runs when the failed track is still current. CANCELLABLE: any manual panel play (RemoteServer.playItem now calls service.userPlayNow, which cancels the wait first), any fix button (refresh via userPlayNow; force360/nextsource/restartplayer cancel inside PlayerService.playCandidates/nextCandidate/restartPlayer), pause and stop (cancelBackoff inside pausePlayer/stopPlayer — a stopped player must never be re-started by a pending advance; stop() leaves lastState=-1 so the still-current check alone would not have caught it). A track that starts playing after all also cancels the wait ("Huy cho doi (bai da phat duoc)"). The v1.0.37 single re-resolve per failure episode is unchanged — the backoff adds NO extra resolves. (d) Everything from v1.0.37 kept: the Settings "Sửa lỗi video" 5 buttons + /api/fix, the v1.0.37 auto-recovery, no sign-in UI (YouTubeAuth stays dormant), STAGE byte-identical; caps unchanged (1920x1080 / 12 Mbps / no fps cap); version.json NOT touched (parent deploys to Vercel after review).
- v1.0.37 (AUTO-RECOVERY + MANUAL VIDEO FIXES, SIGN-IN REMOVED, 2026-11-28 — user: after ~2 h of use the box stops playing EVERY track; remove sign-in from Settings, add manual video-fix functions like the current automatic ones):
  DIAGNOSIS (verified live 2026-11-28 ~20:11-20:13, logs + VM tests): after ~2 h of use every resolve returned exactly "1 ung vien [hls (invidious)]"; direct HLS failed "Source error | ERROR_CODE_IO_BAD_HTTP_STATUS | cause: InvalidResponseCodeException: Response code: 403"; the proxy retry also 403'd; the stall watchdog skipped the track after ~40 s and the next track failed the same way. The same videos had played fine at 1920x1080 at 19:34-19:56. VM tests at the same time: VISIONOS two-call dance OK + manifest fetch 200, Invidious hlsUrl manifest 200, ANDROID player OK with itag 18 — the source side is healthy from a different IP; the failure is box-side. ROOT CAUSE (most likely): the cached visitorData (YoutubeApi.java, in-memory) went stale/flagged after hours of use. BOTH InnerTube clients send it (ANDROID via clientContext, VISIONOS via the dance). YouTube now answers that visitor session with stream-less responses: ANDROID returns HTTP 200 with zero usable formats (no throw, so no "Resolve LOI" line), VISIONOS call 1 returns LOGIN_REQUIRED with the SAME visitorData, so the dance gate (!freshVd.equals(sentVd)) refuses call 2 and resolveVisionosHls returns null silently (NO "visionos LOI" line in the logs). The app never retried with a cleared visitorData — stuck until the process restarts. AMPLIFIER: the resolve() merge took only the first Invidious candidate when it was HLS and DROPPED the Invidious progressive formatStreams, and ANDROID cands were empty — leaving a single candidate, so the v1.0.35 candidate fallback had nothing to fall back to. UNPROVEN ALTERNATIVE: Google IP-throttling of the box's public IP (1.55.211.178) after hours of streaming (the 403 on googlevideo could be IP-based). The fixes cover the session half + the single-candidate half; NO IP-rotation/proxy feature was built.
  FIXES: (a) innertubePlayerVisionOS: if call 1 (sent WITH a visitorData) returns LOGIN_REQUIRED with the SAME visitorData and no manifest, clear visitorData and retry ONCE from scratch (call 1 with no visitorData, then call 2 with the fresh data if offered); logs "Resolve <id> visionos: visitorData cu, lay moi". (b) resolve() merge: the Invidious progressive candidates (formatStreams) are ALWAYS appended as LAST candidates even when HLS is first — hlsInvidious() now collects formatStreams after the hlsUrl too (it used to skip them whenever an hlsUrl existed). A resolve never leaves a single HLS candidate when progressive URLs exist. (c) MesuPlayer.onPlayerError, pre-first-frame HTTP 403 (ERROR_CODE_IO_BAD_HTTP_STATUS + "403" in the cause chain, checked by isHttp403): re-resolve the current track ONCE with a cleared session (retryResolveAfter403 — fresh visitorData + fresh manifest URLs + cleared directCache/urlCache entries for that id) and replay from the fresh candidate list; one attempt only (hls403Retried, per track, reset in play()/stop(), never in buildSourceForCandidate), log "Phat <id>: 403, lay link moi"; a second 403 falls through to the proxy/candidate paths; mid-play errors never take this path. (d) YoutubeApi.clearSession() (+ per-id overload) clears visitorData + both caches (urlCache is unused today but cleared too). (e) resolve() logs the ANDROID zero-candidates case in one line ("Resolve <id> android: 0 dinh dang, playability=..., formats=..., adaptive=...") so the next diagnosis has the evidence. (f) MANUAL FIXES — Settings tab "Sửa lỗi video" section (flat v1.0.32/33 style, 44px rows, hairlines, no nested cards) + /api/fix?action=... (answers {ok, message} for the panel toast; every action logs "Sua loi: <action> ..."): "Lấy link mới" (refresh: clearSession + re-resolve + replay current track), "Phát bản 360p" (force360: fresh resolve, play ONLY the progressive itag 18 candidate; "Không có bản 360p" when none — the player is never touched on failure), "Đổi nguồn phát" (nextsource: reuses the v1.0.35 advanceToNextCandidate path), "Khởi động lại trình phát" (restartplayer: release + recreate the player, replay current track; a failed replay never leaves the player permanently broken — every later play() rebuilds it via ensurePlayer()), "Khởi động lại ứng dụng" (restartapp: AlarmManager pending intent + process kill ~800 ms after the HTTP reply flushes; START_STICKY restarts the service; the queue + current index survive — LibraryState persists them in SharedPreferences on every mutation and reloads them in the constructor; the app does NOT auto-play after the restart, same as a boot restart; NOTE: the Pinggy tunnel mints a NEW public URL on reconnect, so the toast tells the user to re-scan the TV QR). (g) SIGN-IN REMOVED from Settings at the user's ask: the "Tài khoản YouTube" card and the "Kênh đăng ký" section are gone from the panel, and /api/auth/*, /api/subs*, /api/history are gone from RemoteServer. YouTubeAuth.java + the resolveSignedIn() call in resolve() are KEPT dormant (no-op when unsigned) so sign-in can be re-enabled later without a rebuild. STAGE byte-identical; caps unchanged (1920x1080 / 12 Mbps / no fps cap); log token 887924a9 unchanged; version.json NOT touched (parent deploys to Vercel after review).
- v1.0.36 (YOUTUBE SIGN-IN — OWN SESSION COOKIES, 2026-11-28 — user: redo per YouDash — direct YouTube data + sign in/out on the panel so his own Premium can be used; ad-blocker temporary, Premium is the replacement if it breaks):
  SCOPE: sign-in uses ONLY the user's own session — he signs into his own Google/YouTube account on Google's own sign-in page, inside a second WebView on the TV (KaraokeActivity sign-in mode, desktop Chrome UA so Google does not block the embedded WebView as insecure; Back goes back/closes it, D-pad keys never reach the player while it is open). The app never sees the password/2FA. After sign-in the youtube.com cookies are read from CookieManager (LOGIN_INFO = signed-in marker) and stored ENCRYPTED (AndroidKeyStore AES/GCM, key alias karaoke_yt_auth; API < 23 or broken keystore = memory-only for the process lifetime, never plaintext). HARD RULES: no other app's OAuth client_id/secret is used/scraped/hard-coded (incl. YouTube TV's / SmartTube's — the earlier v1.0.36 SmartTube attempt was blocked before writing any code and is NOT retried); no device-code flow; no ReVanced/YouMod; no new AARs. NEW MODULE YouTubeAuth (SessionSource interface = the pluggable seam; CookieSession is the only implementation — a future own-client device flow would plug in there ONLY if the user supplies his own client_id/secret, NOT implemented now): Cookie header + SAPISIDHASH on InnerTube calls. SAPISIDHASH verified against yt-dlp's public source (yt_dlp/extractor/youtube/_base.py::_generate_sapisidhash_header, jihulab mirror commits a5c56234 + cf87314d; corroborated by yt-dlp-rust README and MeeTube §6): header "SAPISIDHASH <unix_ts>_<sha1("<ts> <SAPISID> https://www.youtube.com")>", plus X-Origin + X-Goog-AuthUser: 0 + X-Youtube-Bootstrap-Logged-In: true; SAPISID falls back to __Secure-3PAPISID when absent (yt-dlp issue #393). No Bearer token is ever sent (ANDROID player rejects Bearer 400). RESOLVE: YoutubeApi.resolveSignedIn() runs FIRST inside resolve() (the choke point — MesuPlayer.play + MesuResolver both go through it): WEB player (client 1, version 2.20260708.00.00 — the version yt-dlp pinned 2026-09-16, c7fb478d) with Safari UA (WEB + Safari UA returns a pre-merged hlsManifestUrl, MeeTube §4), then TVHTML5 (client 7, 7.20260114.12.00). Only a plain-`url` adaptive format list or hlsManifestUrl counts as usable (ciphered formats skipped, no deciphering; SABR-only/serverAbrStreamingUrl-only and DRM rejected); usable candidates go in FRONT of the v1.0.35 ladder (VISIONOS HLS -> proxy -> Invidious HLS -> itag 18) UNCHANGED, so a signed-in failure (400 / SABR-only / DRM / stale client version) can never fail a song, and a signed-in candidate that errors at playback still falls through at runtime via the v1.0.35 candidate fallback; sign-out restores the exact v1.0.35 behaviour. PREMIUM PROBE (instrument only, NEVER gates playback): one log line per video ("Premium probe: <id> signed-in web ads=... hls=... sabr-only=... plainFormats=... playability=... => premium CO THE / chua thay dau hieu") — signed in + no ads + usable streams = "co the la Premium" (there is no official Premium flag; absence of adPlacements/playerAds is the observable signal, research §2); plus a manual "Tôi có Premium" toggle (persisted in karaoke_prefs). BROWSE (signed-in, WEB + cookies): FEsubscriptions / FEhistory / channel videos tab (params EgZ2aWRlb3M%3D) via a recursive renderer collector (videoRenderer/channelRenderer at any depth). ENDPOINTS: /api/auth/status (signedIn, accountName, premiumLikely, premiumSource manual|probe|none, premiumManual, sessionSource, probe), /api/auth/start (tells the TV to open the sign-in WebView; panel shows "Mở TV để đăng nhập" — cookies cannot transfer from the phone browser to the box), /api/auth/logout, /api/auth/premium (toggle/on/off), /api/subs, /api/subs/videos, /api/history — all guarded so a failure never breaks /api/state or playback. PANEL (flat v1.0.32/33 design kept): Settings tab gains a "Tài khoản YouTube" section (status + Đăng nhập/Đăng xuất + Premium indicator + toggle); Search tab gains a "Kênh đăng ký" section (visible only when signed in; tapping a channel loads its videos into the results list). VERSION: 37 / 1.0.36-pinggy, APK 2,802,206 bytes, md5 15428740669a662637f52c9af5d226bc. VERIFIED ON VM: build OK, apksigner verify OK, panel JS node --check OK, STAGE byte-identical to v1.0.35 (268 chars, compared against the pushed v1.0.35 source), dex markers (SAPISIDHASH, CookieManager, "Premium probe:", "thu ung vien" x1, "Cap nhat:" x17, "Dieu khien:", token 887924a9 only, no "o/oauth2", no old tokens, no SmartTube/ReVanced/YouMod/client_secret), headless jsdom test of the real panel against fake /api/auth/status + /api/subs: signed-in shows "Đã đăng nhập · Test User" + "Có thể là Premium (theo dò tìm)" + 2 channels, clicking a channel loads 2 video rows; signed-out shows "Chưa đăng nhập" + subs hidden. NOT YET PROVEN (needs the user's real Premium account on the box): the actual Google sign-in in the TV WebView (Google may still challenge the WebView), the signed-in WEB player returning a usable HLS manifest, the Premium probe saying "CO THE" for his Premium account, and subscription/history browse on his account. Log token unchanged (887924a9); version.json NOT touched (parent deploys to Vercel after review).
- v1.0.35 (RUNTIME CANDIDATE FALLBACK, 2026-11-28 — user: playback is unstable long-term; proven live ~15:54: tracks fail intermittently with "Source error" (player -1), the ~30 s stall watchdog then skips the track, the next track may play; the same failed video resolves perfectly from the VM):
  ROOT CAUSE: MesuPlayer.prepareFromCandidates() played the FIRST usable candidate and broke — there was NO runtime fallback to the next candidate when the first errored (the v1.0.30 notes already flagged this). onPlayerError only retried the SAME HLS URL once via the /stream proxy, then gave up with PLAY-ERR. FIX: the resolved candidate list + current index are kept as fields (trackCandidates/trackCandIndex, reset in play() for every new track, list cleared in stop()); the per-candidate source building is extracted into buildSourceForCandidate(i) with the EXACT old per-kind logic (HLS -> direct HlsMediaSource/Safari UA + hlsDirectUrl/hlsProxyRetried/hlsPlayedOnce; otherwise -> /stream proxy for manifest/.m3u8/googlevideo, Dash or Progressive; null for an unusable entry). On a pre-first-frame error (!hlsPlayedOnce): (a) the existing one-shot direct->proxy HLS retry (unchanged); (b) otherwise, if more candidates remain, advanceToNextCandidate() — posted to the main looper with a staleness guard (saved list must still be the track's list AND currentId must match, so play()/stop()/release() since the error drops a stale advance) — scans forward for the next usable candidate, logs "Phat <id>: ung vien <k> LOI (<msg> + errDetail), thu ung vien <k+1> (<kind>)", resets the stall watchdog clock (PlayerService.resetStallPolls(), new public method; also called from retryHlsViaProxy) so the ~30 s endCheck window cannot skip the track mid-fallback, and prepares the next source; (c) only when the list is exhausted does the PLAY-ERR path run ("het ung vien"). Mid-play errors (hlsPlayedOnce) keep the old behaviour exactly (no restart, no advance; the stall watchdog advances the queue). Net effect: HLS loi -> tu thu nguon tiep theo -> 360p thay vi bo bai; chi khi het nguon moi bao loi. v1.0.34's token (887924a9) + PLAY-ERR cause logging ride in this build; v1.0.34 itself was never deployed separately (no Vercel deploy, no version.json bump).
- v1.0.34 (DIAGNOSTICS: LOG TOKEN + PLAY-ERR CAUSE, 2026-11-28 — user: long videos no longer play; two "Unexpected runtime error" and a run of "Source error" PLAY-ERR uploads arrived with no usable cause):
  LOG TOKEN SWAP: webhook.site token 3978eae9… (minted 2026-11-28 11:40) died ~15:49 — test POST got HTTP 429 "Request limit exceeded" at exactly 50 stored requests, same cap that killed 0f050b91… and 136db121… before it. New token 887924a9-847e-4b74-bac2-04b408ca952d (minted 2026-11-28 15:49 UTC, expires ~2026-12-05) in LogUploader ENDPOINT + VIEW_URL, comment updated with the cap + expiry. PLAY-ERR CAUSE: the "Phat LOI" line in MesuPlayer.onPlayerError now appends errDetail(error) — ExoPlaybackException type (SOURCE/RENDERER/UNEXPECTED/REMOTE), renderer index + name, error-code name (getErrorCodeName(), e.g. ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) and the FULL cause chain (getCause() up to 4 levels, class simple name + message each, each message capped at 200 chars). Same detail appended to the direct-HLS retry line ("hls truc tiep LOI, thu qua proxy"). API verified by javap on the 2.19.1 AARs before writing: ExoPlaybackException has public final type/rendererIndex/rendererName + TYPE_SOURCE/RENDERER/UNEXPECTED/REMOTE (NO TYPE_TIMEOUT in 2.19.1 — timeouts surface via the error-code name; the switch defaults to the raw int). No playback-path change, no panel change, stage untouched. This is a diagnostics release: the next long-video PLAY-ERR will name the real failure.
- v1.0.33 (PANEL HARDENING + QR CLEANUP + SEEK, 2026-11-28 — user asked for 7 small things in one build):
  PANEL: anti-zoom (viewport maximum-scale=1,user-scalable=no + body touch-action:manipulation + gesturestart preventDefault — stage viewport untouched); anti-copy (global user-select:none + -webkit-touch-callout:none + tap-highlight transparent, inputs stay selectable; copy + contextmenu preventDefault); double-click blocked (dblclick preventDefault + button touch-action:manipulation — single-click behaviour unchanged). QUEUE: new "Dọn sạch" button in the queue tab header (red-outline flat style) with confirm('Dọn sạch toàn bộ hàng chờ?') → POST /api/control {action:'clear'} (server-side clear already existed: clearQueue + stop) → refresh(). SEEK: the 2px progress bar now has a 10px vertical touch zone (cursor:pointer, touch-action:none); pointerdown/move/up with setPointerCapture (same pattern as queue drag) scrubs — fill + time update live while dragging (seekDrag flag stops refresh() overwriting them, same pattern as volDrag), a single POST /api/control {action:'seek', pos: ms} fires on release (seconds→ms); tap also seeks; ignored when nothing is playing (duration<=0). QR: the "Karaoke" label is GONE from both QR cards (label view + size code deleted; overlay card height no longer adds the +24 label allowance — the card wraps the QR only; padding 4dp/3dp, radius 12dp, 64dp/48dp unchanged); both QRs moved to the TOP-RIGHT corner (16dp margins) — in-activity card uses Gravity.END|TOP + rightMargin (FrameLayout ignores leftMargin under END gravity), overlay uses Gravity.TOP|END with params.x as the right-edge offset; drag direction fixed for END gravity (params.x = downX - dx) and the clamp is unchanged (x stays in 0..screenW-width either way). Server unchanged except the panel PAGE string.
- v1.0.32 (FLAT PANEL REDESIGN + POSITION FIX, 2026-11-28 — user approved the demo panel-demo-flat.html, "Oke rồi"):
  PANEL: the phone control panel is rebuilt flat (bg #0b0b10, hairline dividers #26262e, accent #ff3b57, muted #a8a8b8, NO cards): slim header ("Karaoke" + live status Đang phát/Đã dừng/Chưa phát), now-playing bar with a 72px thumb + one-line ELLIPSIS title (no marquee — titles were getting clipped mid-scroll), 2px progress line + times, ONE row with prev/play/next + volume slider + %, slim 40px search (Enter to search, mic = SpeechRecognition vi-VN), flat rows (44px thumbs, ellipsis titles) separated only by hairlines, fixed 44px bottom tab bar (Tìm bài / Hàng chờ + count badge / Playlist / Cài đặt). The "Cài đặt" tab holds the old "Hệ thống" footer (self-update row + toggle, QR Hiện/Ẩn, Pinggy restart, LAN + public link rows). REMOVED from the panel: sleep-timer row/chips + moon button (PlayerService sleepCheck and /api/sleep untouched, now inert) and the "Bài hát / Kênh" mode toggle + channel view (channel search is cut server-side). KEPT: fetch shim (Pinggy Caution handshake + one retry), refresh() 2500 ms, updFoot() 15 s, search paging 20/page + infinite scroll, queue drag-reorder + swipe delete/add-to-playlist, playlist bottom sheet, paste row, all /api/* via post(). Verified in a headless browser against a mock /api server: 30/32 checks pass (the 2 "failures" are 404s for fake-ID thumbnails in the mock, not panel errors) — search, queue, playlist detail/back, settings tab, paste-row detection, progress fill, badge, all verified; panel JS passes node --check and the PAGE extracted from the built dex is byte-identical to the authored HTML.
  POSITION BUG — FIXED: /api/state always reported pos=0, dur=0 while playing (panel progress stuck at 0:00). ROOT CAUSE (proven by javap on exoplayer-core-2.19.1.aar): getCurrentPosition()/getDuration()/getVideoSize() all call verifyApplicationThread() first and throw IllegalStateException off the main thread; getState() runs on RemoteServer's pool thread, so the inner try/catches swallowed the throws and left 0,0,0,0 — the same thread check that broke pause()/stop() in v1.0.26. FIX: a 500 ms main-thread poller in MesuPlayer caches lastPosMs/lastDurMs/lastVideoW/lastVideoH (plus direct cache writes in onVideoSizeChanged); getState() now only reads the cache and never touches ExoPlayer off-thread. NOT yet verified on hardware (needs a real play session: /api/state should show position advancing and the panel progress bar moving).
- v1.0.31 (1080p VIA ORIGINAL GATHIN VISIONOS HLS, 2026-11-28 — user:
  "Bản Gathin cũ nó làm được nó phát được 1080p tại sao bản này lại không?"):
  ROOT CAUSE (decoded from tvhome-new.apk classes3.dex
  YoutubeApi.innertubePlayer/playerBody/postJson + classes4.dex
  MesuPlayer$1.run): the ORIGINAL never used progressive itag 18 as
  primary — its resolve chain is resolveCandidates -> resolvePiped ->
  resolveInnertube -> innertubePlayer(videoId, VISIONOS=true), which
  does TWO POSTs to youtubei/v1/player: call 1 without visitorData
  returns playability LOGIN_REQUIRED but its responseContext carries
  visitorData; call 2 WITH that visitorData returns playability OK +
  streamingData.hlsManifestUrl + ALL adaptiveFormats with direct URLs.
  Headers (verbatim): Safari UA, X-YouTube-Client-Name "101",
  X-YouTube-Client-Version "1.02", X-Goog-Visitor-Id <visitorData> on
  call 2. Body: VISIONOS/1.02, hl en, gl US, clientScreen WATCH,
  deviceMake Apple, deviceModel RealityDevice17,1, contentCheckOk +
  racyCheckOk true — with the QUIRK that osName/osVersion are
  "Android"/"11" (the original sets visionOS/26.5.23O471 first, then
  unconditionally overwrites with Android/11); verified live WITH the
  quirk. FIX: `YoutubeApi.resolveVisionosHls()` implements the dance
  (call 1 may reuse the cached instance visitorData; no forced call 2
  when call 1 already returns a manifest; call 2 only on a FRESH
  visitorData; never throws — failure falls through to Invidious);
  resolve() now orders candidates visionos HLS -> invidious HLS ->
  progressive itag 18 last (the ANDROID player call still runs for
  title/duration; the catch path also tries VISIONOS before
  Invidious). Playback mirrors the original: HLS plays DIRECT first
  (Safari-UA DefaultHttpDataSource.Factory, 15s/30s timeouts), and on
  a load error before first frame the player's error listener retries
  ONCE via the /stream proxy (v1.0.30 ProxyHttpDataSource) — at most
  once per track, stale retries dropped when a new track starts, and
  a mid-play error never restarts the track. Verified live 2026-11-28:
  jkVlEvuGjg8 call 2 -> OK, hlsManifestUrl on manifest.googlevideo.com,
  23/23 adaptiveFormats with URLs, 16-variant ladder incl. AVC
  1920x1080@24 (BANDWIDTH=4420108); 4s7OVBc6HBo -> 30/30 with URLs,
  AVC 1920x1080@60 (BANDWIDTH=6293353). This is true adaptive
  1080p/1080p60 — better than v1.0.30's Invidious single-variant
  manifest (1440p60-only for 60fps videos). HLS-first resolves stay
  uncached (manifest URLs expire). The resolve log now reads e.g.
  "[hls (visionos), hls (invidious), progressive itag18]". HONEST
  LIMITS: the Gathin APK on disk caps its own player at 1280x720 /
  4.5 Mbps (decoded from its MesuPlayer.ensurePlayer), so THAT build
  would render at most 720p despite the HLS ladder having 1080p — the
  user's memory of 1080p on Gathin likely predates that cap. Our build
  keeps the USER's cap (1920x1080, 12 Mbps, NO frame-rate cap — since
  v1.0.17, untouched), so v1.0.31 genuinely plays 1080p/1080p60.
  Needs YouTube to keep serving VISIONOS this way; VISIONOS-dead +
  Invidious-down -> 360p. The box's decoder died once on 1080p60
  (2026-11-28 07:04) — if it chokes again that is the decoder, not the
  stream. Channel search still cut.
- v1.0.30 (1080p VIA INVIDIOUS HLS, 2026-11-28 — user: "Nó không hề phát
  video ở 1080p"): ROOT CAUSE — InnerTube client ANDROID only ever returns
  itag 18 (640x360) with a playable URL (verified live: `formats` = [18]
  for jkVlEvuGjg8; itag 22 absent; all 25 adaptiveFormats — 137/299/136/
  140... — carry NEITHER `url` NOR `signatureCipher`, because YouTube
  withholds direct adaptive URLs without a poToken; `hlsManifestUrl` is
  absent for ANDROID; WEB returns UNPLAYABLE, TVHTML5 LOGIN_REQUIRED, IOS
  HTTP 400 from this VM — poToken generation deliberately NOT attempted).
  The code's prefer list {22,18,...} therefore always landed on 18, and
  `prepareFromCandidates` plays the first candidate — hence every "Video:"
  log read 640x360. FIX: when the best progressive is itag 18 (or there
  is none), `YoutubeApi.resolve()` also asks Invidious and puts its HLS
  manifest candidate FIRST, progressive after it as fallback;
  `hlsInvidious()` now prefers the `hlsUrl` master manifest over
  `formatStreams` (formatStreams tops out at 720p and was EMPTY for the
  user's videos). Verified live 2026-11-28: yt.chocolatemoo53.com
  /api/v1/videos/jkVlEvuGjg8 returns hlsUrl whose master manifest has
  RESOLUTION=1920x1080 (BANDWIDTH=4513312, VP9+AAC) with absolute
  youtube.com variant URIs; its variant playlist lists 902 absolute
  googlevideo segment URIs. The other two list hosts (ducks.party,
  materialio.us) returned HTTP 500. HLS loads (master manifest, variant
  playlists, segments) ALL go through the local /stream proxy via a small
  `DefaultHttpDataSource` subclass (`ProxyHttpDataSource` in MesuPlayer)
  that rewrites any URI it is asked to open to
  `http://127.0.0.1:8792/stream?u=...` — manifest contents are NOT
  rewritten, `getUri()` still returns the original URI, already-proxied
  URIs pass through unchanged; progressive/DASH keep the plain factory.
  The track-selector cap is UNTOUCHED (1920x1080, 12 Mbps, NO frame-rate
  cap — user demands 60fps). HLS-manifest resolves are NOT cached (the
  manifest URL carries an expire= timestamp); the resolve log now names
  the chosen path, e.g. "hls (invidious)". HONEST LIMITS: when ALL
  Invidious hosts are down the video falls back to 360p (same as today,
  no regression). The Invidious master manifest carries a SINGLE variant
  per video — for a 60fps source video that variant is the source's top
  quality, NOT 1080p60: 4s7OVBc6HBo's manifest is 2560x1440 @ 60fps
  (BANDWIDTH=14143231). ExoPlayer 2.19.1 defaults
  `exceedVideoConstraintsIfNecessary=true`, so the cap does not block it
  — it plays at 1440p60 if the decoder can, else the stall watchdog
  advances. The box's decoder died once on 1080p60 (2026-11-28 07:04
  MediaCodec error) — if it chokes again that is the decoder, not the
  stream. Channel search still cut.
- v1.0.29 (REAL GATHIN PANEL, 2026-11-28 — user: "Giao diện như vậy mày bảo
  giống gathintv mày có thật sự xem code code gathintv không thế"): the phone
  panel is now the REAL Gathin TV panel, ported verbatim from the original
  Gathin tvhome APK (`classes3.dex` string constant,
  `gathin-panel-original.html`) — same dark UI (#0c0c12, accent #ff3b57),
  now-playing card with scrolling marquee title, round play/prev/next
  buttons, volume slider, sleep-timer chips, sticky tabs (Tìm bài / Hàng
  chờ / Playlist), Bài hát-Kênh toggle, voice-search mic button, paste-link
  row, drag-to-reorder queue, swipe-right delete / swipe-left add-to-playlist,
  bottom-sheet playlist picker, toasts. Server compat layer added in
  `RemoteServer` so the original panel's calls work: POST form bodies were
  already merged into the params map (verified in `handle()`); `stateJson()`
  now also emits `playing`/`position`/`duration` (seconds, parsed from the
  "st,pos,dur,..." state string), `volume` (0-100, tracked in `MesuPlayer`
  — ExoPlayer has no volume getter), `sleepLeft` (alias of
  `sleepMinutesLeft`) and `index` (alias of `currentIndex`); items now
  carry `artist`/`thumb`/`duration` (seconds) aliases in both
  `LibraryState.Item` and `YoutubeApi.Video` toJson; `/api/search` returns
  `ok:true` and pages server-side, 20 per page (the panel stops only when a
  page returns < 20 — without paging the same results would loop forever),
  `type=channel` returns empty results; `/api/channel` returns `ok:false`
  (panel shows "Không lấy được video của kênh."); playlists carry a stable
  persisted `id` + `count` (old name-only playlists get an id on load);
  `/api/playlist`, `/api/playlist-add/remove/enqueue/play/delete` resolve by
  `playlist=<id>` (name param still works), `playlist-add` counts only
  actually-added items (0 = duplicate) and handles `playlist=new`;
  `/api/control` volume accepts `value` as well as `v`; `/api/paste`
  accepts mode `now` and returns `{ok, item}`. Two deliberate adaptations
  to the original HTML: (1) a `window.fetch` shim (Pinggy Caution handshake
  + one retry on JSON-parse failure — the v1.0.19 mechanism) wrapping every
  panel call; (2) a small "Hệ thống" footer card (self-update row + Bật/Tắt
  toggle, QR overlay Hiện/Ẩn, Pinggy restart) — the Gathin panel has none.
  CORRECTION (same day, still v1.0.29 — never deployed): the tail is now
  the TRUE original. The first extraction had sliced the dex string by
  BYTES instead of UTF-16 units and cut ~468 chars off the tail, so the
  first v1.0.29 build shipped a hand-reconstructed tail (bPlEnqAll toast
  without s.count, bPlDel confirming with the playlist name, 5000 ms
  poll) — replaced with the true tail (toast reads s.count, bPlDel
  confirms 'Xoá playlist này?', refresh() + 2500 ms poll).
  `/api/playlist-enqueue` now returns `{ok, count}` (the panel's toast
  reads s.count). The "6 U+FFFD removed" note was wrong: the paste toast
  genuinely contains the 🎶 emoji ('🎶 Đã thêm vào hàng chờ: '), stored
  in the dex as a CESU-8 surrogate pair — it is original and preserved.
  Channel search/view stays cut in v1 (UI-only, empty results).
- v1.0.28 (TOKEN SWAP ONLY, 2026-11-28): the webhook.site log token
  136db121… died ~11:38 (HTTP 429 "Request limit exceeded" — the free
  token's 50-stored-request cap, confirmed by a test POST), so the app's
  logs stopped arriving again while the app was still uploading. This
  build swaps in the fresh token 3978eae9-eb51-442f-917c-dff22b438894
  (minted 2026-11-28 11:40, expires ~2026-12-05) in `LogUploader.java`
  (ENDPOINT + VIEW_URL) and bumps the version — NOTHING else changes: no
  QR, panel, player, control or UpdateChecker change.
- v1.0.27 (SELF-UPDATE, user asked 2026-11-28 11:22 "Tao muon no tu update
  khi co phien ban moi. Chu cu phai cai thu cong met lol ghe"): the app now
  updates itself — as far as a stock, unrooted box allows. HONEST LIMIT
  (never claimed otherwise): a fully SILENT install is impossible for a
  normal app on a stock box (only system apps / root can install without
  the user's tap). What happens: `UpdateChecker` fetches
  `https://raw.githubusercontent.com/eocotekapp/karaoke-youtube-tv/main/version.json`
  (plain HTTPS, no tunnel) ~20 s after the service starts and every ~6 h
  while it runs (gated by the existing 10 s watchdog tick — no new timer
  service; a failed check retries in 30 min). If remote versionCode >
  this build's, it downloads the APK to the app cache, verifies it with
  `PackageManager.getPackageArchiveInfo` (package must be
  `vn.karaoke.youtube`, versionCode exactly the announced one — anything
  else is deleted, never installed), then posts a HIGH-PRIORITY
  notification (channel `karaoke_update`, id 8794) "Có bản Karaoke mới
  <name> — chạm để cài đặt" and, if the activity is in the foreground,
  fires the system installer directly. The APK is served to the installer
  via `androidx.core.content.FileProvider` (authority
  `vn.karaoke.youtube.fileprovider`, `res/xml/file_paths.xml` cache-path)
  with `FLAG_GRANT_READ_URI_PERMISSION`; the user still taps "Cài đặt"
  once. A finished download is never re-downloaded
  (`update_downloaded_version` in `karaoke_prefs`); a verified download
  re-prompts on the next check / app start, never nags in between. The
  UPDATE log tag uploads ONLY when a new version is found and when a
  download completes, once per version each (the webhook token caps at
  50 requests — routine no-update checks upload nothing). One-time
  unknown-sources grant: `REQUEST_INSTALL_PACKAGES` in the manifest plus a
  one-time dismissible dialog on launch (`install_prompt_asked`, modelled
  on the v1.0.14 overlay prompt) that opens
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package; declining
  leaves auto-update off until the grant is given, and returning from
  Settings re-runs the check at once (`recheckAfterGrant()` in
  `onResume`). The phone panel shows one update row ("Tự cập nhật: Bật •
  có bản 1.0.27 — đã tải xong, xem thông báo trên TV để cài đặt") plus a
  Bật/Tắt toggle (`/api/update?action=toggle`, default ON); `/api/state`
  carries `autoUpdate` / `updateAvailable` / `updateVersion` /
  `updateState` (none/checking/downloading/ready/error/grant). The parent
  updates version.json on every release — the app never hardcodes a
  version-specific Vercel URL. NOT yet verified on the real box: the whole
  install flow (notification tap, direct intent, grant dialog) can only be
  proven after the user installs v1.0.27 once — this build itself still
  goes in by Downloader code, like every version before it.
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
