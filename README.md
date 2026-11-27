# Karaoke YouTube — standalone APK for Android TV Box

**Version:** 1.0.3-pinggy (versionCode 4) · **APK:** `KaraokeYouTube.apk` — **2,671,066 bytes**
**Package:** `vn.karaoke.youtube` · **minSdk 21 / targetSdk 30** · **Signed** (new per-project keystore `keystore.jks`, alias `karaoke`)
**Build:** `./build.sh` — manual aapt2 → javac (Java 8) → d8 → zipalign → apksigner, no Gradle.
**Note:** `libs/` (binary jars/AARs) and `keystore.jks` are NOT in this repo — run `./fetch-libs.sh` first to download the libraries, and `build.sh` generates a fresh local keystore on first run; neither is ever committed.
No decompilation/repack of tvhome: clean rewrite from the behaviour spec in
`~/workspace/karaoke-split-inventory.md`.

## Internet exposure: Pinggy ONLY — no Cloudflare, no Tailscale, no ngrok/zrok, no Termux
- `PinggyTunnel` uses pure-Java SSH (**JSch 0.1.55, ~300 KB**, in-process, no binary,
  no extra process) to `free.pinggy.io:443`, remote forwarding `-R0:localhost:8792`,
  parses the returned `https://<random>.pinggy.link` URL from the shell output.
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

## Autostart + anti-Doze (chống ngủ đông)
- First-run screen in `KaraokeActivity` (once) guides the user to: (a) ignore battery
  optimisation (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, checked via
  `PowerManager.isIgnoringBatteryOptimizations()`), (b) display over other apps
  (`ACTION_MANAGE_OVERLAY_PERMISSION`), (c) the box's autostart toggle via app
  settings (link only — the ROM may hide it elsewhere).
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

## NOT verified (no working TV Box available — K8 is dead, replacement not bought)
- Install + launch on a real box; ExoPlayer playback of InnerTube streams
  (itag/cipher handling, HLS via `/stream` proxy); JSch→Pinggy handshake from
  inside the APK (host-key acceptance, shell-channel URL parsing, 60-min
  re-dial timing); overlay permission grant flow on the vendor ROM; boot
  autostart on the vendor ROM; battery-whitelist behaviour overnight.
- Known fragility (same as the original): InnerTube can break when YouTube
  changes it; signature-ciphered formats are skipped (no deciphering); no
  in-app updater — a break needs a rebuild.
