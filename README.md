# Kiosk Camera

A kiosk-mode camera app for Android (targeting Pixel 7), designed to work with [FreeKiosk](https://freekiosk.app) as an external app. It provides a full-featured camera experience locked down for unattended or supervised use, with SCP upload of captured media to a remote server.

## FreeKiosk Compatibility

Tested with **FreeKiosk v1.2.18-beta.1 / v1.3.0** ([RushB-fr/freekiosk](https://github.com/RushB-fr/freekiosk)) on **Pixel 7** running Android 13+.

A reference FreeKiosk configuration is included in `freekiosk-config.json`. Key settings:
- **Multi-app mode** with OpenVPN and Kiosk Camera
- **Lock mode** enabled with Device Owner
- **Return button** at top-right (2 taps, 1500ms timeout)

> **Note:** FreeKiosk's overlay button can overlap the top-right corner of external apps. This app includes layout spacers to keep UI controls clear of the overlay area.

## Features

- **Photo capture** with tap-to-focus, pinch-to-zoom, and exposure compensation
- **Rapid burst capture** — in-memory pipeline with parallel disk saves, no dropped shots
- **Video recording** with long-press shutter (quick release takes photo instead)
- **Gallery** with Queue/Uploaded tabs, Google Photos-style multi-select with drag
- **Media viewer** with carousel swipe, pinch-to-zoom (Matrix-based, GrapheneOS style), and in-app video playback with scrub bar
- **Flash** (off / auto / on), **Timer** (off / 3s / 10s), **Night mode** (CameraX extension)
- **Camera flip** (front / back), **Grid overlay**, **Exposure compensation** (double-tap)
- **Volume buttons** as shutter trigger (tap for photo, hold for video)
- **Landscape-aware** photo and video orientation via OrientationEventListener
- **SCP upload** to remote server with auto-generated SSH keypair
- **HTTPS upload** alternative with certificate pinning
- **Upload confirmation dialogs** with one-way transfer warnings
- **Disk-backed thumbnail cache** for instant gallery loading

## Build

Requirements:
- JDK 21+
- Android SDK (API 34)

```bash
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
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
buildConfigField("String", "SCP_HOST", "\"10.99.88.108\"")
buildConfigField("int", "SCP_PORT", "22")
buildConfigField("String", "SCP_USER", "\"sysadm\"")
buildConfigField("String", "SCP_PATH", "\"/home/sysadm/uploads/\"")
buildConfigField("boolean", "USE_SCP", "true")
```

### SCP Setup

1. Launch the app once — it generates an RSA keypair and logs the public key
2. Extract the public key: `adb logcat -d | grep "Public key: ssh-rsa"`
3. Add it to the server: `echo "<key>" | ssh user@server "cat >> ~/.ssh/authorized_keys"`
4. Photos/videos upload via the gallery's upload button (floating blue FAB)

### HTTPS Setup (alternative)

Set `USE_SCP` to `false` and configure `UPLOAD_URL` and `CERT_PIN`. A Python receiver is included:

```bash
cd receiver && bash generate_certs.sh && python3 server.py
```

## License

MIT
