# signage-tv-kiosk — spec for the implementing session

Status: **R1–R14 implemented, not yet field-verified** (2026-10-22). Pure logic covered by JUnit
(`./gradlew :app:testDebugUnitTest`). Deviations decided while implementing: R6 — `low_mem_mb = 0` only disables the
MB threshold, the system `lowMemory` flag always triggers a restart after ≥20 min uptime (Nam); R3 — after an
escalation restart, another escalation within 10 min of uptime becomes a 60 s recreate (no restart loop);
`app_trim` — `TRIM_MEMORY_RUNNING_*` are not delivered since API 34, so every level except `UI_HIDDEN` is logged.
Written by the `signage_nodejs_server` session that diagnosed the problem; that project's vault holds the full history:
`projects-vault/YoungMin/Signage-Nodejs-Server/` — `Fix-History/2026-10-22-tv11-heap-viewer-media-release.md`,
`Plan/tv-client-remote-log.md`, `Architecture/Current-State.md`.

## 1. Why this app exists

The signage server (`signage_nodejs_server`, Next.js + Express + Socket.io, prod `http://192.168.0.254:13000`) serves
`/#checkin`: a slideshow of PDF pages with per-page mp4/mp3 and "realtime" link pages, played full-screen on ~11
factory TVs (Xiaomi MiTV, e.g. MiTV-MZTU0, Android 14, Android System WebView Chrome/154). Each TV logs in once as a
client account (user Id > 3) and must run unattended — no login prompts, no configuration on site.

The TVs currently run a third-party WebView-based browser app ("Trình duyệt INTERNET"). Measured facts:
- Some TVs (tivi 11 worst) crash with the browser's "Tab bị sập" page ~35-45 min after each load, and stay on that
  page until a person presses "Tải lại". Root cause NOT proven (best hypothesis: WebView keeps mid-load media
  buffers; second: a heavy realtime report page).
- Memory is NOT released by `location.reload()` or `location.replace()` inside that browser — only an app/process
  restart frees it. On some TVs `location.reload()` never even commits.
- `performance.memory.usedJSHeapSize` on this WebView is bucketized, refreshes only every ~20 min, and survives a
  same-renderer reload — useless as a precise metric.

A browser we control fixes the symptom regardless of root cause: auto-recover from renderer death, and periodically
restart the whole process so memory really goes back to the OS.

## 2. Requirements

Native Kotlin, no Flutter (decided by Nam 2026-10-22: Flutter's engine costs 50-100 MB RAM on memory-starved TVs and
the critical parts are Android APIs anyway). Keep dependencies at zero beyond the Android framework unless justified.

| # | Requirement | Notes |
|---|---|---|
| R1 | Full-screen WebView on the configured URL; keep screen on; autoplay media without a gesture; black background; no default grey video poster. | Done in scaffold except the poster (WebChromeClient.getDefaultVideoPoster → 1x1 transparent bitmap). |
| R2 | **Renderer-death recovery**: `WebViewClient.onRenderProcessGone` → return `true`, remove + `destroy()` the dead WebView (never call anything else on it), recreate a new one after ~3 s and load the URL. | Log `did_crash` + `rendererPriorityAtExit`. |
| R3 | **Escalation**: ≥3 recoveries (render-gone or hang) within 15 min → full process restart instead of another recreate. | Pure, unit-testable policy class. |
| R4 | **Full process restart** (frees renderer memory for sure): ProcessPhoenix pattern — start a trampoline activity in a separate process (`android:process=":restart"`), `finish()` + `Runtime.exit(0)` the main process; the trampoline kills the old pid if still alive, starts `MainActivity` with `NEW_TASK|CLEAR_TASK`, then exits. All starts happen from a foreground activity, so Android 14 background-start limits do not apply. | Persist the restart reason in SharedPreferences (`commit()`, synchronous) and log it on the next start — the in-flight remote log dies with the process. |
| R5 | **Periodic restart**: every `restart_min` minutes of process uptime (default 240, 0 = off, else clamp 15..1440). Uptime from `Process.getStartElapsedRealtime()`. | tivi 11 may need ~30. |
| R6 | **Low-memory restart**: `ActivityManager.MemoryInfo.lowMemory` OR `availMem < low_mem_mb` (default 0 = off, else clamp 50..4096) → restart, only after ≥20 min uptime (avoid restart loops — lesson from the server-side heap watchdog). | Thresholds must be tuned from logged data. |
| R7 | **Hang watchdog**: every 60 s `evaluateJavascript("1")`; 2 consecutive unanswered probes → recreate WebView (counts as a recovery for R3). Reset the counter on recreate. | |
| R8 | **Main-frame load error** (`onReceivedError`, `request.isForMainFrame`) → log + retry load after 30 s (one pending retry at a time). | Server restarts / Wi-Fi drops. |
| R9 | **Start on boot**: `BOOT_COMPLETED` receiver → start `MainActivity`. Android 10+ blocks background activity starts unless the app holds `SYSTEM_ALERT_WINDOW` → declare it and grant via `adb shell appops set com.youngmin.signagekiosk SYSTEM_ALERT_WINDOW allow`. Alternative to evaluate: make the app the HOME launcher. | Must be measured on a real MiTV (PatchWall may behave differently; MiTV "standby" may not fire BOOT_COMPLETED). |
| R10 | **Configuration without touching code**: `url`, `restart_min`, `low_mem_mb` in SharedPreferences; settable via `adb shell am start -n com.youngmin.signagekiosk/.MainActivity --es url <url> --ei restart_min <n> --ei low_mem_mb <n>` (handle in `onCreate` and `onNewIntent`; `launchMode=singleTask`), and via an on-TV settings dialog. URL must be http/https with a host, else rejected. Since 2026-11: `update_channel` (`stable` default / `canary` / `off`, `--es update_channel canary`, radio buttons in the dialog). | |
| R11 | **Remote keys**: BACK never leaves the page (the checkin page is a hash SPA; going back breaks it). MENU/SETTINGS key or BACK pressed 3× within 2 s → settings dialog (URL, restart_min, low_mem_mb; buttons Save+reload / Exit app / Cancel). User-visible strings in Vietnamese with diacritics. | Mi remotes may lack a MENU key — hence the triple-BACK gesture. |
| R12 | **Logging — remote** (see §3) + **local** rotating file `getExternalFilesDir(null)/kiosk.log` (cap ~512 KB, rotate to `.1`), also logcat. Every caught exception is logged; never log cookies. | Local file is pullable: `adb shell cat /sdcard/Android/data/com.youngmin.signagekiosk/files/kiosk.log`. |
| R13 | Append ` SignageKiosk/<versionName>` to the WebView user agent so server `page_start` logs (field `ua`) show which TVs run the app. | |
| R14 | Flush `CookieManager` on pause and after config changes so the TV stays logged in across restarts. | |

Image sharpness is a hard customer constraint: do not cap resolution / scale the page down (keep WebView defaults
unless a measured reason says otherwise).

## 3. Remote log contract (server side already exists — no server change needed)

`POST <origin of url>/checkin/client-log`, `Content-Type: text/plain`, body = JSON
`{"event":"watchdog","data":{"kind":"app_<x>", "app":"<versionName>", ...}}`.
Server: `signage_nodejs_server/server/utils/clientLog.js` — `event` must be in its whitelist (`watchdog` is);
`data` max 20 fields, keys `^[A-Za-z_][A-Za-z0-9_]{0,31}$`, values string(≤300)/number/bool/null only (objects dropped);
body ≤ 8 KB (`MAX_BODY_CHARS`, `routes/checkin.js` `express.text({ limit: '8kb' })`);
rate limit 60 req / 5 min per IP (the page itself also logs from the same IP — stay frugal) plus a server-wide cap of
300 req / 5 min across all TVs. Send the WebView cookie
(`CookieManager.getCookie(endpoint)`, read on the main thread) so the server resolves `user_id`/`user_name`.
Logs land in `server/logs/client-YYYY-MM-DD.log` on the server (UTC days; VN = UTC+7). POST on a single background
executor, 5 s timeouts, failures → local log only.

Suggested kinds: `app_start` (reason of previous restart, webview version, sdk, model), `app_status` every 5 min
(avail_mb, total_mb, low_mem, threshold_mb, own pss_mb, up_min, recoveries, config), `app_render_gone`,
`app_recreate` (reason), `app_restart` (reason), `app_load_error`, `app_trim` (onTrimMemory ≥ RUNNING_LOW),
`app_config`, `app_error`, `app_update` (self-update, `stage` = skip / download / verify_failed / download_failed /
fetch_failed / commit / install_failed / result, plus `reason`, `code`, `channel`; identical consecutive outcomes are sent
once per process), `app_refront` (`stage` = attempt / back: the activity was stopped within 8 s of a self-initiated
restart and started itself again — tivi 7, 2026-11-07, the R4 restart landed behind the launcher once; `attempt` carries
`finishing` = the system had finished the activity, which then comes back as a new instance logging `app_start
start=launch` instead of `back`; `back` carries `since_attempt_ms`, since a blocked start returns normally).

## 3b. Self-update (added 2026-11, plan `kiosk-boot-autoupdate` in the vault)

The app reads `<origin of url>/kiosk/<channel>/version.properties` (keys `versionCode`, `versionName`, `apk` = bare file
name in the same directory, `sha256` lowercase hex, `size` ≤ 20 MB) 2 min after start and every 30 min while in front
(`--ez update_check_now true` skips only that wait). A newer versionCode is downloaded to the cache, checked (size,
sha256, package name and versionCode inside the APK) and installed by a PackageInstaller session with
`USER_ACTION_NOT_REQUIRED` (API 31+, needs `REQUEST_INSTALL_PACKAGES` granted + `UPDATE_PACKAGES_WITHOUT_USER_ACTION`
declared). It never installs without the `SYSTEM_ALERT_WINDOW` appop (it could not reopen itself), while the settings
dialog is open or a restart runs. `MY_PACKAGE_REPLACED` (`UpdateReplacedReceiver`) reopens `MainActivity`; the next
`app_start` reports `start=update prev_reason=update_<code>`. Backoff: fetch errors none, install failure 6 h, verify
failure 24 h for that versionCode only, pending user action (abandoned, never shown) 24 h. The files are published by CI
(tag `vX.Y.Z` → GitHub prerelease) and mirrored onto the server by a cron script (`scripts/kiosk-pull.sh`).

## 4. Testing & verification

- Pure logic (recovery policy, config validation/clamping, log-endpoint derivation, triple-tap counter) → plain Kotlin
  classes with JUnit4 tests (`./gradlew testDebugUnitTest`).
- Build: `./gradlew assembleDebug assembleRelease`.
- Field verification on ONE TV first (likely tivi 9 or tivi 11, decided with Nam), overnight soak:
  `ua` contains `SignageKiosk/`; `app_render_gone` → page back within seconds; `app_restart reason=periodic` actually
  restarts and the TV keeps playing; memory trend in `app_status`; boot start after a real power cycle.
- Things to measure, not assume: whether process restart really frees the renderer (watch `avail_mb` before/after),
  BOOT_COMPLETED behaviour on MiTV, whether MiTV kills a long-running foreground app.

## 5. Open items

- Release signing: since 2026-11-06 a DEDICATED kiosk key (alias `signage-kiosk`, cert SHA-256
  `ef91044e059be45ba86c5051f6efa216940c990c8c0ba15e9c7360710b9c8715`, PKCS12) via the git-ignored
  `keystore.properties`; CI holds the same key in GitHub Secrets and refuses to publish an APK with another cert.
  Every update must use this key. (Before: Nam's general upload key `2f7590e2…7fbd`, used only by the 0.1.0 APK on
  tivi 7, which is reinstalled once with the new key.)
- Install/update channel for 11 TVs: ADB over network (enable Developer options + network debugging on MiTV) vs USB
  sideload for the FIRST install; later versions arrive by self-update (§3b, decided 2026-11-06).
- Harness: set up (stack `android`: android-reviewer / android-test-writer; vault `projects-vault/YoungMin/Signage-TV-Kiosk`).
