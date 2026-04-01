# Kiosk Camera

A kiosk-mode camera app for Android (targeting Pixel 7), designed to work with [FreeKiosk](https://freekiosk.app) as an external app. It provides a full-featured camera experience locked down for unattended or supervised use, with automatic upload of captured media to a server.

## Features

- **Photo capture** with tap-to-focus, pinch-to-zoom, and exposure compensation
- **Video recording** with long-press shutter and recording timer
- **Gallery** with Google Photos-style selection, multi-select with drag, and thumbnail caching
- **Media viewer** with carousel swipe, pinch-to-zoom photos, and in-app video playback
- **Flash** (off / on / auto)
- **Timer** (off / 3s / 10s countdown)
- **Night / HDR mode** via CameraX extensions
- **Camera flip** (front / back)
- **Grid overlay** for composition
- **Volume buttons** as shutter trigger (tap for photo, hold for video)
- **Landscape-aware** photo and video orientation handling
- **Server upload** via SCP (primary) or HTTPS with certificate pinning

## Build instructions

Requirements:
- JDK 21
- Android SDK (API 34)

```bash
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Installation

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## FreeKiosk integration

In FreeKiosk, add Kiosk Camera as an external app with the package name:

```
com.kioskcamera
```

The app runs in portrait mode and hides the system UI, so it fits naturally into a locked-down kiosk setup.

## TODOs: server upload testing

- Upload is currently configured for **SCP** to a configurable server (toggle `USE_SCP` in build config to switch modes).
- **HTTPS upload** is also supported -- a Python receiver is included in `receiver/` (run `receiver/server.py`).
- Need to resolve **cross-subnet connectivity** between the phone and the upload server.
- Need to test **SCP key auth end-to-end** (the app auto-generates an RSA keypair on first launch; the public key must be added to the server's `authorized_keys`).
- Upload URL/host is **baked in at build time** via `BuildConfig` fields in `app/build.gradle.kts`.
- **Certificate pinning** is configured for HTTPS mode (`CERT_PIN` in build config); regenerate with `receiver/generate_certs.sh` if the server cert changes.
