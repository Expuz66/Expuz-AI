# Expuz AI (Android)

A small native Android shell that runs a self-hosted **Open WebUI** frontend in a
hardened `WebView`. The chat UI is **not bundled** in the APK: it is downloaded
from the server you configure, so frontend updates on the server show up after a
reload without rebuilding the app.

## Project Overview

Expuz AI Android is a WebView-based mobile interface for an existing self-hosted
Open WebUI server. The APK provides the native shell, secure navigation, settings,
connection handling, and update delivery; the Open WebUI frontend remains hosted
by the configured server.

- Package: `com.expuz.ai`
- Kotlin + AndroidX + Material 3, XML layouts / ViewBinding (no Compose)
- Min SDK 26, target/compile SDK 36
- No API keys, tokens or backend credentials are compiled into the APK
- No changes are made to the Open WebUI server or its configuration

## Release Information

- Version: **1.0.1**
- Application ID: `com.expuz.ai`
- Version code: `1`
- Minimum Android SDK: `26`
- Target Android SDK: `36`
- Compile SDK: `36`

---

## Installation

Official APK distributions are provided through the [GitHub Releases page](https://github.com/Expuz66/Expuz-AI/releases).
When v1.0.1 is published there, download `Expuz-AI-v1.0.1.apk`, enable
installation from this source if Android requests it, and confirm installation
in the Android system package installer.

## Building from Source

Requirements: JDK 17+ (built with JDK 21), Android SDK with API 36 platform and
build-tools, and an Android device or emulator running API 26+.

```powershell
# Debug build (cleartext HTTP allowed only for private LAN hosts)
.\gradlew.bat :app:assembleDebug

# Production release build
.\gradlew.bat :app:assembleRelease

# Unit tests
.\gradlew.bat :app:testDebugUnitTest
```

The release build uses the existing release keystore through an untracked
`keystore.properties` file. Do not commit passwords or keystores. Configure it
locally with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` values.

Release APK output:

```
app/build/outputs/apk/release/app-release.apk
```

Install on a connected device/emulator:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The launcher entry point is `com.expuz.ai/.ui.MainActivity`; on first launch it
redirects to the onboarding screen.

---

## Connecting Open WebUI

Start Open WebUI as usual (default: `http://localhost:8080`). Open WebUI must be
reached from the device, not from the developer PC, so use the right address:

| Where the app runs | Address to enter |
| --- | --- |
| Android emulator on the same PC | `http://10.0.2.2:8080` |
| Physical phone on the same Wi-Fi/LAN | `http://<PC-LAN-IP>:8080` (e.g. `http://192.168.1.20:8080`) |
| Remote / internet deployment | `https://ai.example.com` (HTTPS required) |

Notes:

- `localhost` inside the app means the phone/emulator itself, not your PC.
- For a phone, make Open WebUI listen on `0.0.0.0` (not only `127.0.0.1`) and
  allow the port through the PC firewall. Example:
  `uvicorn open_webui.main:app --host 0.0.0.0 --port 8080`
  (use whatever launch command your Open WebUI version documents).
- **Plain HTTP is accepted for private/LAN addresses in release and debug
  builds** so self-hosted Open WebUI servers can run on a local network. HTTP
  traffic is unencrypted; use HTTPS on untrusted networks. Any remote address
  must be HTTPS; certificate errors are never ignored or bypassed.
- A different port is a different origin.
- If Open WebUI is behind a reverse proxy, use the public HTTPS URL.

Onboarding flow: enter the address -> **Test connection** -> **Save and
continue**. If the server is temporarily unreachable, the app offers to save
anyway so the address is not lost. The URL can be changed later from the
toolbar settings button.

---

## Features

- Native splash, onboarding, loading, error and settings screens.
- Server URL validation (scheme, host, no credentials/query/fragment, LAN vs
  remote rules) with a real HTTP reachability probe before saving.
- Connection modes:
  - **Single server** – one address.
  - **Automatic (local -> remote)** – probes the local server first; if it is
    down, offers a *Switch to REMOTE server* button with an explicit
    confirmation dialog. Origins are never switched silently.
  - **Local only**, **Remote only**.
- Compact floating glass toolbar with refresh and settings icon buttons.
- Persistent login: WebView cookies, DOM storage and HTTP cache are kept between
  launches and are flushed on pause. Clearing the HTTP cache keeps cookies and
  local storage, so the session survives.
- Glass toolbar refresh button, and Settings actions *Reload frontend* and
  *Clear HTTP cache and reload*. Pull-to-refresh is intentionally not used:
  Open WebUI scrolls conversations inside its own `#messages-container`, so a
  native refresh wrapper could steal those gestures.
- Edge-to-edge WebView with a translucent/blurred native toolbar, cutout and
  system-bar insets, and keyboard-aware bottom padding.
- Friendly error mapping for DNS failure, connection refused, timeout, no
  network, HTTP 4xx/5xx, TLS/certificate errors, blocked navigation and WebView
  renderer crashes, each with Retry and Server settings.
- Page loading progress, offline banner, Android back navigation inside the
  WebView, external links opened in the browser after confirmation, `mailto:`
  and `tel:` support.
- File uploads through the Storage Access Framework; downloads through
  `DownloadManager` into the app-specific Downloads folder.
- AMOLED-friendly black theme with a replaceable accent colour.
- Automatic GitHub Releases update checking at application startup.
- Manual update checking from Settings with release status and timestamps.
- Glass-styled update notification dialog showing release changelogs.
- HTTPS APK download with progress and SHA-256 verification when a release digest
  is supplied.
- Android `FileProvider` and system package-installer integration with package,
  version, and signing-certificate compatibility checks.
- Release-build support for user-configured private-LAN HTTP servers, including
  RFC1918 IPv4 ranges, while remote HTTP remains rejected.
---

## Architecture

```
app/src/main/kotlin/com/expuz/ai/
  ExpuzApp.kt              Application; WebView debug instrumentation in debug builds
  AppContainer.kt          Manual dependency container (store, policy, probe, manager)
  data/
    ServerConfig.kt        Persisted configuration model and connection modes
    ServerStore.kt         Store interface
    DataStoreServerStore.kt DataStore (Preferences) implementation
  domain/
    UrlPolicy.kt           URL normalization/validation, LAN detection, origin rules
    ServerProbe.kt         OkHttp reachability probe and failure classification
    ServerRouting.kt       Candidates, active URL and LOCAL/REMOTE classification
    ServerManager.kt       Validate/save/prepare/persist, connection tests, fallback
    WebFailure.kt          WebView failure model
  web/
    WebViewManager.kt      WebView creation, security settings, clients, uploads,
                           downloads, crash recovery, cookie flushing
  ui/
    MainActivity.kt        Shell, status, WebView lifecycle, loading/error/fallback UI
    UiText.kt              Friendly message mapping
    onboarding/OnboardingActivity.kt
    settings/SettingsActivity.kt
  util/
    NetworkMonitor.kt      Default-network availability for the offline banner
  update/
    UpdateManager.kt        GitHub release lookup and semantic-version comparison
    ApkDownloader.kt        APK download, progress, and digest verification
    ApkInstaller.kt         APK validation and Android package installation
    UpdateDialog.kt         Release changelog and update actions
```

Key configuration locations:

- `app/src/main/AndroidManifest.xml` – release manifest, cleartext disabled.
- `app/src/debug/AndroidManifest.xml` – debug overlay enabling cleartext for
  explicit LAN development servers.
- `app/src/main/res/xml/network_security_config.xml` – HTTPS-only, system CAs.
- `app/src/debug/res/xml/network_security_config.xml` – cleartext permitted at
  platform level; runtime `UrlPolicy` still restricts it to private hosts.
- `app/src/main/res/values/colors.xml`, `themes.xml` – AMOLED palette/accent.
- `app/src/main/res/drawable/ic_launcher_foreground.xml`,
  `ic_splash_logo.xml` – placeholder brand assets (replaceable).

The configured URL is the only origin loaded inside the app. Same-host HTTPS
upgrades, external HTTP(S) links, `mailto:` and `tel:` are handled outside the
WebView; unknown schemes are blocked. File access and content access are
disabled, JavaScript can open no windows, mixed content is never allowed, and
there is no JavaScript bridge.

---

## Testing performed

- `:app:testDebugUnitTest` – **31 tests, 0 failures**
  (`UrlPolicyTest` 16, `ServerManagerTest` 10, `ServerProbeTest` 5).
- `:app:assembleDebug` and `:app:lintDebug` completed successfully.
- `:app:assembleRelease` completed successfully with the existing release key.
- The release APK was verified with Android SDK Build Tools `apksigner` using APK
  Signature Scheme v2. The application ID, version name, and version code were
  verified as `com.expuz.ai`, `1.0.1`, and `2`.
- Installed with `adb install -r` on an authorized Pixel 4 XL (Android 13 / API
  33) using the existing app data and active Open WebUI session. Verified the
  glass toolbar/status/buttons, Settings navigation, refresh button, and
  conversation scrolling. Upward and downward swipes changed the internal
  `#messages-container` scroll position without changing the URL or losing a
  JavaScript marker; no runtime errors were emitted by `ExpuzMain` or
  `AndroidRuntime`.
- Built and installed the debug APK on an Android emulator (API 37, x86_64).
  Verified the following manually end-to-end:
  - Onboarding validation, connection test and save against
    `http://10.0.2.2:8080`.
  - Open WebUI login page loaded from the server and inspected through the
    WebView DevTools protocol (`http://10.0.2.2:8080/auth?redirect=%2F`,
    title `Open WebUI`).
  - Cookies (with expiry) and `localStorage` survived a process restart after
    backgrounding, and survived *Clear HTTP cache and reload*.
  - Settings mode dropdown, automatic/local/remote field visibility, and
    HTTPS-required validation for remote HTTP addresses.
  - Origin-change confirmation when switching servers.
  - Automatic fallback: unreachable local server -> *Switch to REMOTE server* ->
    confirmation -> remote origin loaded and status showed `REMOTE`.
  - Failure handling with a closed port: friendly error screen, server detail,
    Retry and Server settings; retry reproduced the error.
  - External `https://` navigation prompted, then opened in Chrome while the
    app stayed on its configured origin.
- `probeStillWorksAfterServerClosesIdleConnection` reproduces and prevents an
  intermittent onboarding "could not reach" failure caused by reusing an idle
  keep-alive socket; the probe now retries on a fresh connection.

## APK Signature Verification

Official release APKs are signed with the project's existing release keystore.
Future updates must use the same signing certificate or Android will reject the
update over an installed copy. Verify an APK with:

```powershell
apksigner verify --verbose --print-certs .\Expuz-AI-v1.0.1.apk
```

The certificate fingerprint and the APK file checksum are different values.

## SHA-256 Verification

From the directory containing the downloaded APK, calculate its checksum with:

```powershell
Get-FileHash .\Expuz-AI-v1.0.1.apk -Algorithm SHA256
```

The calculated value must match the published `Expuz-AI-v1.0.1.apk.sha256` file.
Recalculate the checksum whenever the APK changes; a rebuilt or modified APK has
a different checksum.

## Changelog

See [`CHANGELOG.md`](CHANGELOG.md) for the release history.

### Expuz AI v1.0.1 — Settings UI & Glassmorphism Improvements

Fixed:

- Fixed Settings panel positioning on newer Android versions.
- Fixed Settings header overlap with the system status bar.
- Fixed the Settings navigation button being hidden by inset padding.
- Fixed Settings content clipping near the navigation bar.

Improved:

- Improved Settings panel transparency and background blur.
- Improved Android 17 visual compatibility.
- Improved Settings scrolling and navigation while preserving Android 13 support.

## Automatic Updates

The app queries the official GitHub Releases API when the application process
starts and does not interrupt WebView initialization when no update is available.
Users can also run the same check from **Settings**. When a newer stable release
is found, the dialog displays its release name and changelog. The user explicitly
starts the HTTPS APK download, and Android displays the final package-installer
confirmation. Installation requires a compatible package name, newer version,
and the same signing certificate. Draft and prerelease releases are not treated
as stable updates.

## License

This project is distributed under the MIT License. See [`LICENSE`](LICENSE).

---

## Known limitations

- A different origin means different WebView cookies/`localStorage`, so
  switching between local and remote servers can require signing in again. The
  app warns before switching and does not delete the old session.
- Blob/`data:` downloads generated in JavaScript are not supported; normal
  server downloads use `DownloadManager`.
- Camera/microphone and geolocation permissions requested by the page are
  denied (not needed for chat). Microphone-based voice input is therefore not
  available.
- OAuth/social sign-in providers that open a separate browser flow may not
  return a session to the WebView; username/password login works inside the
  app.
- The launch icon, splash logo and accent colour are placeholders.
- HTTP is supported for private/LAN server addresses in both build variants,
  but traffic is unencrypted. Remote servers must use HTTPS.

---

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `Connection refused` | Open WebUI is running, listens on `0.0.0.0`, and the port is reachable from the device. |
| `Host not found` | Typo in host, or the device has no DNS/network. |
| `Remote HTTP blocked` | Use `https://` for anything that is not a private LAN address. |
| Login page reloads / signed out | Confirm the address did not change origin; cookies are per origin. |
| Frontend looks stale | Settings -> *Clear HTTP cache and reload*. |
| TLS error | Fix the server certificate/chain; the app never bypasses validation. |
