# Kiosk Camera

A kiosk-mode camera app for Android (targeting Pixel 7), designed to work with [FreeKiosk](https://freekiosk.app) as an external app. It provides a full-featured camera experience locked down for unattended or supervised use, with SCP upload of captured media to a remote server.

## FreeKiosk Compatibility

Tested with **FreeKiosk v1.2.18-beta.1 / v1.3.0** ([RushB-fr/freekiosk](https://github.com/RushB-fr/freekiosk)) on **Pixel 7** running Android 13+.

A reference FreeKiosk configuration is included in `freekiosk-config.json`. Key settings:
- **Multi-app mode** with OpenVPN and Kiosk Camera
- **Lock mode** enabled with Device Owner
- **Return button** at top-right (2 taps, 1500ms timeout)

> **Note:** FreeKiosk's overlay button can overlap the top-right corner of external apps. This app includes layout spacers (64dp) in the gallery top bar to keep UI controls clear of the overlay area.

## Features

- **Photo capture** with tap-to-focus, pinch-to-zoom, and exposure compensation
- **Rapid burst capture** — in-memory pipeline with 4-thread parallel disk saves and MINIMIZE_LATENCY capture mode, no dropped shots
- **Video recording** with long-press shutter (quick release takes photo instead)
- **Project management** — create, remove, and switch between projects; filesystem-safe names (ASCII only, 64 char max, case-insensitive dedup); project picker with card UI and scrollable list
- **Gallery** with Queue/Uploaded tabs, Google Photos-style multi-select with drag, filtered by active project
- **Active project** shown in camera footer and gallery footer; project FAB in gallery to switch projects
- **Media viewer** with carousel swipe, pinch-to-zoom (Matrix-based, GrapheneOS style), in-app video playback with scrub bar, and batch/project info for uploaded files
- **Flash** (off / auto / on), **Timer** (off / 3s / 10s), **Night mode** (CameraX extension)
- **Camera flip** (front / back), **Grid overlay**, **Exposure compensation** (double-tap)
- **Volume buttons** as shutter trigger (tap for photo, hold for video)
- **Landscape-aware** photo and video orientation via OrientationEventListener
- **SCP upload** to remote server with auto-generated SSH keypair; upload folder structure: `<base>/<year>/<project>/<timestamp>/01_file.jpg` with sequential file numbering (auto-width for 100+ files)
- **HTTPS upload** alternative with certificate pinning
- **Upload confirmation dialogs** with one-way transfer warnings
- **Disk-backed thumbnail cache** for instant gallery loading
- **Upload error reporting** — SCP failures show the actual error message in a dialog (auth failure, connection refused, etc.)
- **Background threading** — RSA key generation, SSL setup, gallery file listing, thumbnail loading, and file moves all run off the UI thread

## Build

Requirements:
- JDK 21+
- Android SDK (API 34)

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install & Launch

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.kioskcamera -c android.intent.category.LAUNCHER 1
```

## FreeKiosk Setup

1. Set FreeKiosk as Device Owner (`adb shell dpm set-device-owner com.freekiosk/.DeviceAdminReceiver`)
2. Add Kiosk Camera as a managed app with package name `com.kioskcamera`
3. Enable Lock Mode in FreeKiosk settings
4. Import `freekiosk-config.json` or configure manually

## Upload Configuration

Upload settings are **build-time constants** in `app/build.gradle.kts` (not user-configurable):

```kotlin
buildConfigField("String", "SCP_HOST", "\"172.16.16.31\"")
buildConfigField("int", "SCP_PORT", "22")
buildConfigField("String", "SCP_USER", "\"cui-camera-01\"")
buildConfigField("String", "SCP_PATH", "\"/home/cui-camera-01/uploads/\"")
buildConfigField("boolean", "USE_SCP", "true")
```

### SCP Setup

1. Launch the app once — it generates an RSA keypair in the background
2. Extract the public key: `adb shell run-as com.kioskcamera cat files/ssh_keys/id_rsa.pub`
3. Add it to the server: `echo "<key>" | ssh user@server "cat >> ~/.ssh/authorized_keys"`
4. Create a project in the app, then upload via the gallery's upload button (floating blue FAB). Files are uploaded to `<SCP_PATH>/<year>/<project>/<timestamp>/` with sequential numbering.

### HTTPS Setup (alternative)

Set `USE_SCP` to `false` and configure `UPLOAD_URL` and `CERT_PIN`. A Python receiver is included:

```bash
cd receiver && bash generate_certs.sh && python3 server.py
```

## License

MIT
