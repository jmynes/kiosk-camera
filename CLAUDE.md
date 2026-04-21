# CLAUDE.md

## Build, install, and launch

```bash
# Build
export ANDROID_HOME=$HOME/Android/Sdk && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk && ./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell monkey -p com.kioskcamera -c android.intent.category.LAUNCHER 1
```

## FreeKiosk context

This app is designed as an external app for [FreeKiosk](https://github.com/RushB-fr/freekiosk) v1.2.18-beta.1 / v1.3.0. Reference config in `freekiosk-config.json`. The app accounts for FreeKiosk's overlay button at the top-right with layout spacers (64dp) in the gallery top bar.

Target device: **Pixel 7** (Android 13+) with FreeKiosk as Device Owner.

## Project structure

```
kiosk-camera/
  app/
    build.gradle.kts          # Dependencies, BuildConfig fields (upload + SCP config)
    src/main/
      AndroidManifest.xml      # Permissions, activities, network security config
      java/com/kioskcamera/
        MainActivity.kt        # Camera: preview, capture, video, zoom, flash, timer, orientation
        GalleryActivity.kt     # Gallery: Queue/Uploaded tabs, multi-select, drag select, upload FAB
        PhotoViewerActivity.kt # Media viewer: carousel, zoom, video playback, controls toggle
        VideoPlayerActivity.kt # Standalone video player (kept but unused — viewer handles video)
        UploadManager.kt       # Upload orchestration: SCP or HTTPS, queue/uploaded dir management
        ProjectManager.kt     # Project CRUD: create, remove, active project (SharedPreferences-backed)
        ProjectPickerDialog.kt # Shared project picker dialog with card UI, manage, and create flows
        ScpUploader.kt         # SSHJ-based SCP: keypair generation, file upload over SSH
        ZoomableImageView.kt   # Matrix-based pinch-to-zoom (GrapheneOS Camera approach)
        FocusIndicatorView.kt  # Tap-to-focus circle animation
        GridOverlayView.kt     # Rule-of-thirds grid overlay
        BitmapUtils.kt         # EXIF-aware bitmap rotation
        ThumbnailCache.kt      # LRU memory cache + disk cache for gallery thumbnails
      res/
        layout/                # XML layouts for all activities
        drawable/              # Vector icons (Material Design), button backgrounds
        xml/                   # Network security config, file provider paths
        raw/                   # Bundled CA cert for HTTPS cert pinning
  receiver/
    server.py                  # Python HTTPS upload receiver (alternative to SCP)
    generate_certs.sh          # Self-signed TLS cert generation
    certs/                     # Generated certs (private keys gitignored)
  freekiosk-config.json        # Reference FreeKiosk configuration export
```

## Key files

- **`app/build.gradle.kts`** — Upload config as `buildConfigField`: `SCP_HOST`, `SCP_PORT`, `SCP_USER`, `SCP_PATH`, `USE_SCP`, `UPLOAD_URL`, `CERT_PIN`. These are compile-time constants, not user-configurable.
- **`MainActivity.kt`** — Camera activity. CameraX setup, in-memory capture pipeline with 4-thread save pool and MINIMIZE_LATENCY capture mode, video recording, zoom/flash/timer/night mode, orientation tracking, project picker and active project footer.
- **`GalleryActivity.kt`** — Queue/Uploaded tabs filtered by active project, thumbnail grid, multi-select with drag, upload FAB with confirmation, project FAB to switch projects, active project footer, FreeKiosk-safe spacers.
- **`ScpUploader.kt`** — Generates 4096-bit RSA keypair on first run (PKCS#8 PEM, background thread). Uploads via SSHJ with BouncyCastle provider. 60s connect timeout for intermittent networks. `lastError` property surfaces error details to UI.
- **`ProjectManager.kt`** — Singleton managing project list and active project. Stored in SharedPreferences (`projects` prefs file) as JSON array. Filesystem-safe names: ASCII-only (letters, numbers, dash, underscore, space, dot), 64 char max, case-insensitive dedup. Active project persisted across restarts.
- **`ProjectPickerDialog.kt`** — Shared dialog used in both camera and gallery. Card UI with scrollable list, active project highlighting (gold border/check), create dialog with input filter, manage dialog with per-project remove and remove-all.
- **`UploadManager.kt`** — Coordinates uploads. Upload folder structure: `<base>/<year>/<project>/<timestamp>/01_file.jpg` with sequential numbering (2-digit, auto-widens to 3 for 100+ files). Per-project queue dirs under `upload_queue/<project>/`. Copies files to `uploaded_cache/` before deleting from queue. Supports both SCP and HTTPS modes.
- **`ZoomableImageView.kt`** — Matrix-based zoom/pan with OverScroller fling momentum. Same approach as GrapheneOS Camera. Integrates with ViewPager2 via `canScrollHorizontally`. Disables ViewPager2 paging during multi-touch (pinch) via `onPagerInputChange` callback.
- **`BitmapUtils.kt`** — EXIF-aware bitmap rotation. `decodeBitmapWithRotation(path, sampleSize)` for subsampled decoding.

## Upload flow

1. Photos/videos save to `files/upload_queue/<project>/`
2. User opens gallery → Queue tab (filtered by active project) → taps upload FAB (or multi-selects + FAB)
3. `UploadManager` uploads via SCP to `<base>/<year>/<project>/<timestamp>/` with sequential file numbering (`01_`, `02_`, etc.), copies to `files/uploaded_cache/`, deletes from queue
4. Uploaded tab shows cached copies (local only, not a server browse)

## Project data

- Project list and active project stored in SharedPreferences (`projects` prefs file)
- Active project persists across app restarts
- Gallery queue is per-project (`upload_queue/<project>/`); uploaded cache is shared

## Threading model

- **App startup**: SSL cert parsing, HTTP client init, and SCP keypair generation run on background threads (not blocking `onCreate`)
- **Camera capture**: 4-thread `saveExecutor` pool for parallel disk saves; gallery thumbnail update also on this pool
- **Gallery**: File listing (`getItems()`) runs on a dedicated IO executor; adapter updated on UI thread when ready
- **Photo viewer**: Bitmap decoding is synchronous but subsampled to ~2x screen width (sampleSize=2 for Pixel 7). Video thumbnail extraction is async via IO executor.
- **Uploads**: Single-thread executor in `UploadManager`; SCP/HTTPS runs entirely off UI thread
- **Pending file moves**: `movePendingToProject()` runs on `saveExecutor`

## Known issues

- FreeKiosk overlay can hide top-right UI in external apps (filed as [RushB-fr/freekiosk#121](https://github.com/RushB-fr/freekiosk/issues/121))
- CameraX Night extension is the only vendor extension available on Pixel 7 (no HDR/Bokeh via public API)
- SCP connection can be intermittent on cross-subnet networks — 60s timeout handles this
