# Karaoke YouTube — standalone APK for Android TV Box

**Version:** 1.0.10-pinggy (versionCode 11) · **APK:** `KaraokeYouTube.apk` — **2,765,276 bytes**
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
  current URL + QR are always shown on the TV UI, stage page and notification.
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
