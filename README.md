# SwiftDrop — Android

A single-screen Android app for fast LAN file transfer, symmetric with the Mac
app: it both **serves** (receives) and **sends**. Discovers Macs/phones on the
network via mDNS, streams files over raw HTTP for max speed, and integrates
with the system **share sheet**. Received files land in **Downloads/SwiftDrop**.

No Android Studio required — build and install entirely from the terminal.

## Features

- **AES-256-GCM encryption** — all transfers between paired devices are encrypted end-to-end
- **Device pairing** — PIN-based and QR code pairing; paired keys are persisted across restarts
- **QR scanner with autofocus** — continuous autofocus and metering for fast QR scanning
- **Bilateral unpairing** — unpairing on one device notifies the other
- **Auto-close pairing dialog** — when the remote device confirms the PIN, the local dialog closes automatically
- **SHA-256 integrity verification** — sender hashes the file, receiver verifies after write; corrupted files are rejected and deleted
- **Smart notifications** — idle notification shows device name and IP; active transfers show progress (percentage, data transferred, speed); completion notification fires when done
- **Live transfer progress** — real-time progress bars with transferred data (MB/GB), percentage, and speed
- **Retry failed transfers** — retry button on failed/canceled outbound sends
- **Open folder** — tap the folder icon next to a completed transfer to open Downloads
- **Cancel transfers** — cancel an in-flight send (including encrypted); connection is disconnected immediately
- **Share sheet integration** — share files into SwiftDrop from any app
- **Wake + WiFi locks** — CPU and WiFi radio stay active during transfers, even with screen off
- **Stall detection** — 30s read timeout detects dead peers
- **No file size cap** — transfers of any size; disk space checked before writing
- **Automatic version bumping** — CI sets versionName and versionCode from git tag on release

## What it does

- **Receive:** runs a small HTTP server in a foreground service; files pushed by
  peers are streamed straight to `Downloads/SwiftDrop` via MediaStore (no
  storage permission needed). A notification fires on receipt.
- **Send:** pick files (or **Share → SwiftDrop** from any app), choose a device,
  hit Send. The app streams the file with a fixed-length HTTP body — no
  buffering — so transfers run at full LAN speed.
- **Discovery:** registers + browses `_swiftdrop._tcp` with the system
  NsdManager, so devices find each other automatically.

The UI is a WebView served by the in-app server (same origin, no CORS); file
picking uses the native SAF picker, bridged to the page.

## One-time toolchain setup (terminal only)

```bash
brew install --cask temurin@17          # or: brew install openjdk@17
brew install --cask android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

`local.properties` already points `sdk.dir` at the cmdline-tools location; edit
it if your SDK is elsewhere.

## Build

```bash
./build.sh
# → app/build/outputs/apk/debug/app-debug.apk  (~7 MB)
```

`build.sh` pins `JAVA_HOME` to JDK 17 (AGP 8.5 requires it) and uses the Gradle
8.7 wrapper.

## Install over Wi-Fi adb

On the phone: **Settings → Developer options → Wireless debugging → ON**.

```bash
# First time only — pair (tap "Pair device with pairing code" for IP:PORT + code)
./install.sh pair 192.168.1.42:37123      # then type the 6-digit code

# Then connect to the "IP & address" shown under Wireless debugging, and install
./install.sh 192.168.1.42:39000
```

Re-running `./install.sh <ip:port>` reinstalls after a rebuild. (adb lives at
`$ANDROID_HOME/platform-tools/adb`.)

## Using it

1. Launch SwiftDrop. Allow the notification prompt (so it can run in the
   background and alert you on receipt).
2. Devices on your Wi-Fi appear under **Devices**. Tap one to select.
3. Tap **Choose files** (or share files into SwiftDrop from another app), then
   **Send**.
4. To receive: just leave it running — pushed files arrive in
   `Downloads/SwiftDrop` with a notification.

Tap the device name in the header to rename this device.

> **Same network required.** Phone and the other device must be on the same
> Wi-Fi (or the phone's hotspot). Hotspot is often the fastest path.

## Project layout

| File | Role |
|------|------|
| `MainActivity.kt` | WebView UI, JS bridge, SAF picker, share-intent handling |
| `SwiftDropService.kt` | foreground service: hosts server + discovery, multicast lock |
| `HttpServer.kt` | NanoHTTPD: `/inbox`, `/api/me`, `/api/devices`, `/api/transfers`, `/api/send-path`, UI |
| `Discovery.kt` | mDNS register + browse via NsdManager |
| `Sender.kt` | streams a content URI to a peer's `/inbox` with progress |
| `State.kt` | identity, peer registry, transfer tracker |
| `assets/web/index.html` | the mobile UI |

Shares the exact HTTP contract with the Mac app, so Mac ↔ Android works both
directions.

## Shared Core

The transfer engine, discovery, encryption, and HTTP API contract are shared with
the macOS and Windows apps via
[swiftdrop-core](https://github.com/dilip1232/swiftdrop-core). The Android app
re-implements the same HTTP contract in Kotlin (NanoHTTPD) for native Android
integration (MediaStore, foreground service, SAF).

## Roadmap

- Battery optimization exemption prompt on first launch
- Optional self-signed TLS with cached fingerprint
- Resume interrupted transfers via HTTP range
