# Karaoke YouTube — standalone APK for Android TV Box

**Version:** 1.0.16-pinggy (versionCode 17) · **APK:** `KaraokeYouTube.apk` — **2,765,275 bytes**
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
  white card, corner radius 12 dp, padding 9 dp (sides/top) / 6 dp (bottom),
  QR image **132 dp** square, label "Karaoke" 16 sp colour #FF14213A, position
  **bottom-LEFT, margins 16 dp** — that is the base size. **Home-screen 3x rule:**
  only while the launcher/home screen is in the foreground the whole card grows
  to 3x (QR 396 dp); in any other app it returns to the 132 dp Gathin size.
  Foreground detection: UsageStatsManager first (usage-access grant, step 4 on
  the first-run screen), `ActivityManager.getRunningAppProcesses` as fallback.
  Tap toggles a manual size override (auto resumes when the foreground app
  changes). (The other Gathin QR, 190 dp centred in the MesuSettings activity,
  is a settings-screen QR, not the floating one.)
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
