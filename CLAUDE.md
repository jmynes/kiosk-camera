# CLAUDE.md

## Build, install, and launch

```bash
# Build
export ANDROID_HOME=$HOME/android-sdk && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && ./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell monkey -p com.kioskcamera -c android.intent.category.LAUNCHER 1
```

## Project structure

```
kiosk-camera/
  app/
    build.gradle.kts          # Dependencies, BuildConfig fields (upload config lives here)
    src/main/
      AndroidManifest.xml      # Permissions, activities, network security config
      java/com/kioskcamera/
        MainActivity.kt        # Camera preview, capture, video recording, zoom, flash, timer
        GalleryActivity.kt     # Thumbnail grid with multi-select and drag selection
        PhotoViewerActivity.kt # Full-screen photo carousel with pinch-to-zoom
        VideoPlayerActivity.kt # In-app video player with playback controls
        ScpUploader.kt         # SCP upload via SSHJ (generates RSA keypair, uploads files)
        ZoomableImageView.kt   # Matrix-based pinch-to-zoom and pan for photo viewer
        FocusIndicatorView.kt  # Tap-to-focus animation overlay
        GridOverlayView.kt     # Rule-of-thirds grid overlay on camera preview
        BitmapUtils.kt         # EXIF-aware bitmap rotation helpers
        ThumbnailCache.kt      # Disk-backed LRU thumbnail cache for gallery
      res/layout/              # XML layouts for all activities
      res/xml/                 # Network security config, file provider paths
  receiver/
    server.py                  # Python HTTPS upload receiver
    generate_certs.sh          # Generate self-signed TLS certs for the receiver
    certs/                     # Generated certificates (not checked in)
  build.gradle.kts             # Root Gradle build (plugin versions)
  settings.gradle.kts          # Project settings
```

## Key files

- **`app/build.gradle.kts`** -- Upload configuration is here as `buildConfigField` entries: `UPLOAD_URL`, `CERT_PIN`, `SCP_HOST`, `SCP_PORT`, `SCP_USER`, `SCP_PATH`, `USE_SCP`. These become compile-time constants in `BuildConfig`.
- **`MainActivity.kt`** -- The main camera activity. Handles CameraX setup, photo/video capture, zoom (pinch + volume), flash/timer/HDR toggles, exposure compensation, orientation tracking, and upload dispatch.
- **`ScpUploader.kt`** -- Generates an RSA keypair on first run and uploads files over SCP using SSHJ. The public key needs to be added to the server's `authorized_keys`.
- **`receiver/server.py`** -- Standalone Python HTTPS server that accepts multipart file uploads on port 8443 with TLS.
