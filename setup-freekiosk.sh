#!/bin/bash
set -euo pipefail

###############################################
#  FreeKiosk Device Setup Script             #
#  Installs FreeKiosk, sets Device Owner,    #
#  applies config, installs managed apps     #
#  Run from dev machine with ADB access      #
###############################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="$SCRIPT_DIR/freekiosk-config.json"
FREEKIOSK_APK="${FREEKIOSK_APK:-$HOME/Downloads/freekiosk-v1.2.18-beta.1.apk}"
KIOSKCAMERA_APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
OPENVPN_APK="${OPENVPN_APK:-$HOME/Downloads/de.blinkt.openvpn_219.apk}"

FREEKIOSK_PKG="com.freekiosk"
FREEKIOSK_RECEIVER="com.freekiosk/.DeviceAdminReceiver"
FREEKIOSK_ACTIVITY="com.freekiosk/.MainActivity"

echo "============================================"
echo "  FreeKiosk Device Setup"
echo "============================================"
echo ""

###############################################
#  Pre-flight checks                         #
###############################################

# Check config file
if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: Config file not found: $CONFIG_FILE"
    exit 1
fi

# Check and build/download APKs
if [ ! -f "$FREEKIOSK_APK" ]; then
    echo "WARNING: FreeKiosk APK not found: $FREEKIOSK_APK"
    echo "  Set FREEKIOSK_APK env var to the correct path"
    echo "  Download from: https://github.com/RushB-fr/freekiosk/releases"
    echo ""
    read -p "Continue without FreeKiosk APK? (y/N) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

if [ ! -f "$KIOSKCAMERA_APK" ]; then
    echo "Kiosk Camera APK not found, building..."
    if [ -f "$SCRIPT_DIR/gradlew" ]; then
        export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
        export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
        (cd "$SCRIPT_DIR" && ./gradlew assembleDebug)
        if [ -f "$KIOSKCAMERA_APK" ]; then
            echo "  Built successfully."
        else
            echo "  Build failed. Check ANDROID_HOME and JAVA_HOME."
        fi
    else
        echo "  WARNING: Not in kiosk-camera repo, can't build."
    fi
fi

if [ ! -f "$OPENVPN_APK" ]; then
    echo "OpenVPN APK not found, downloading from F-Droid..."
    OPENVPN_FDROID_URL="https://f-droid.org/repo/de.blinkt.openvpn_219.apk"
    if wget -q --show-progress -O "$OPENVPN_APK" "$OPENVPN_FDROID_URL" 2>/dev/null || \
       curl -fSL -o "$OPENVPN_APK" "$OPENVPN_FDROID_URL" 2>/dev/null; then
        echo "  Downloaded: $OPENVPN_APK"
    else
        echo "  WARNING: Download failed. Install OpenVPN manually."
        rm -f "$OPENVPN_APK"
    fi
fi

# Final APK check
MISSING_APKS=false
for apk_name in "FreeKiosk:$FREEKIOSK_APK" "Kiosk Camera:$KIOSKCAMERA_APK" "OpenVPN:$OPENVPN_APK"; do
    name="${apk_name%%:*}"
    path="${apk_name#*:}"
    if [ -f "$path" ]; then
        echo "  ✓ $name: $(basename "$path")"
    else
        echo "  ✗ $name: missing"
        MISSING_APKS=true
    fi
done

if [ "$MISSING_APKS" = true ]; then
    echo ""
    read -p "Continue without missing APKs? (y/N) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo ""
echo "Before continuing, ensure the Android device:"
echo ""
echo "  1. Is connected via USB"
echo "  2. Has Developer Options enabled"
echo "     (Settings > About phone > tap Build number 7 times)"
echo "  3. Has USB Debugging enabled"
echo "     (Settings > System > Developer options > USB debugging)"
echo "  4. Has trusted this computer"
echo "     (approved the 'Allow USB debugging?' prompt)"
echo "  5. Has ALL user accounts removed"
echo "     (Settings > Passwords & accounts > remove all)"
echo "     This is REQUIRED for Device Owner setup."
echo ""
read -p "Press Enter when ready, or Ctrl+C to abort... "
echo ""

###############################################
#  Step 1: Verify ADB connection             #
###############################################
echo "[1/8] Verifying ADB connection..."
if ! adb get-state >/dev/null 2>&1; then
    echo "ERROR: No device found. Check USB connection and debugging."
    exit 1
fi
DEVICE=$(adb get-serialno)
MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
echo "  Device:  $DEVICE"
echo "  Model:   $MODEL"
echo "  Android: $ANDROID"
echo ""

###############################################
#  Step 2: Install FreeKiosk APK             #
###############################################
echo "[2/8] Installing FreeKiosk..."
if adb shell pm list packages | grep -q "$FREEKIOSK_PKG"; then
    echo "  Already installed, reinstalling..."
    adb install -r "$FREEKIOSK_APK" || echo "  WARNING: Reinstall failed, continuing with existing install"
elif [ -f "$FREEKIOSK_APK" ]; then
    adb install "$FREEKIOSK_APK"
    echo "  Installed."
else
    echo "  SKIPPED: APK not found"
fi
echo ""

###############################################
#  Step 3: Install managed apps              #
###############################################
echo "[3/8] Installing managed apps..."

if [ -f "$OPENVPN_APK" ]; then
    echo "  Installing OpenVPN..."
    adb install -r "$OPENVPN_APK" 2>/dev/null || echo "  Already installed or failed"
else
    echo "  SKIPPED: OpenVPN APK not found"
fi

if [ -f "$KIOSKCAMERA_APK" ]; then
    echo "  Installing Kiosk Camera..."
    adb install -r "$KIOSKCAMERA_APK" 2>/dev/null || echo "  Already installed or failed"
else
    echo "  SKIPPED: Kiosk Camera APK not found"
fi
echo ""

###############################################
#  Step 4: Set Device Owner                  #
###############################################
echo "[4/8] Setting FreeKiosk as Device Owner..."
DPM_OUTPUT=$(adb shell dpm set-device-owner "$FREEKIOSK_RECEIVER" 2>&1 || true)

if echo "$DPM_OUTPUT" | grep -q "Success"; then
    echo "  Device Owner set successfully."
elif echo "$DPM_OUTPUT" | grep -q "already set"; then
    echo "  Device Owner already set."
elif echo "$DPM_OUTPUT" | grep -q "already several accounts\|already has an account"; then
    echo "  ERROR: Accounts still present on device."
    echo "  Remove ALL accounts in Settings > Passwords & accounts, then re-run."
    exit 1
else
    echo "  WARNING: $DPM_OUTPUT"
    read -p "  Continue anyway? (y/N) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi
echo ""

###############################################
#  Step 5: Grant permissions & system prefs  #
###############################################
echo "[5/8] Granting permissions and configuring system..."
adb shell appops set "$FREEKIOSK_PKG" android:get_usage_stats allow 2>/dev/null || true
echo "  Usage stats: granted"
adb shell pm grant "$FREEKIOSK_PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
echo "  Secure settings: granted"

# Set 3-button navigation (back, home, recents) instead of gesture nav
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton 2>/dev/null || true
echo "  Navigation: 3-button mode"

# Show battery percentage in status bar
adb shell settings put system status_bar_show_battery_percent 1 2>/dev/null || true
adb shell settings put global battery_percentage_enabled 1 2>/dev/null || true
echo "  Battery: percentage enabled"
echo ""

###############################################
#  Step 6: Apply configuration               #
###############################################
echo "[6/8] Applying FreeKiosk configuration..."

# Push config JSON to device
adb push "$CONFIG_FILE" "/sdcard/Download/freekiosk-config.json" >/dev/null

# Build am start command with key settings from the JSON
# FreeKiosk reads intent extras on launch
adb shell am force-stop "$FREEKIOSK_PKG"

# Apply core settings via intent extras
adb shell am start -n "$FREEKIOSK_ACTIVITY" \
    --es external_app_mode "multi" \
    --es back_button_mode "test" \
    --ez kiosk_enabled true \
    --ez auto_start false \
    --es auto_relaunch "true" \
    --es managed_apps "'[{\"packageName\":\"de.blinkt.openvpn\",\"displayName\":\"OpenVPN for Android\",\"showOnHomeScreen\":true,\"launchOnBoot\":true,\"keepAlive\":true,\"allowAccessibility\":false},{\"packageName\":\"com.kioskcamera\",\"displayName\":\"Kiosk Camera\",\"showOnHomeScreen\":true,\"launchOnBoot\":false,\"keepAlive\":false,\"allowAccessibility\":false}]'" \
    >/dev/null 2>&1 || true

echo "  Core settings applied via intent."
echo ""
echo "  The config JSON has also been pushed to the device at:"
echo "  /sdcard/Download/freekiosk-config.json"
echo ""
echo "  If FreeKiosk supports JSON import in its settings UI,"
echo "  import it from there for any settings not covered by"
echo "  intent extras."
echo ""

###############################################
#  Step 7: PIN setup instructions            #
###############################################
echo "[7/8] PIN setup required..."
echo ""
echo "  ┌─────────────────────────────────────────────────┐"
echo "  │                                                 │"
echo "  │  SET YOUR FREEKIOSK PIN NOW                     │"
echo "  │                                                 │"
echo "  │  On the device, open FreeKiosk settings and     │"
echo "  │  set a PIN code (4-6 digits).                   │"
echo "  │                                                 │"
echo "  │  IMPORTANT: Use a DIFFERENT PIN than your       │"
echo "  │  lock screen PIN. The FreeKiosk PIN is what     │"
echo "  │  you'll use to exit kiosk mode and access       │"
echo "  │  settings. If someone knows your lock screen    │"
echo "  │  PIN, they should NOT automatically know        │"
echo "  │  the kiosk admin PIN.                           │"
echo "  │                                                 │"
echo "  │  Default PIN is 0000 — CHANGE IT!               │"
echo "  │                                                 │"
echo "  │  To access FreeKiosk settings later:            │"
echo "  │  Tap 5 times in the top-left corner of the      │"
echo "  │  screen, then enter your admin PIN.             │"
echo "  │                                                 │"
echo "  └─────────────────────────────────────────────────┘"
echo ""
read -p "Press Enter after setting your FreeKiosk PIN... "
echo ""

###############################################
#  Step 8: Verify setup                      #
###############################################
echo "[8/8] Verifying setup..."

# Check Device Owner
if adb shell dpm list-owners 2>/dev/null | grep -q "$FREEKIOSK_PKG"; then
    echo "  ✓ Device Owner: $FREEKIOSK_PKG"
else
    echo "  ✗ Device Owner not set"
fi

# Check installed apps
for pkg in "$FREEKIOSK_PKG" "de.blinkt.openvpn" "com.kioskcamera"; do
    if adb shell pm list packages | grep -q "$pkg"; then
        echo "  ✓ Installed: $pkg"
    else
        echo "  ✗ Missing: $pkg"
    fi
done

# Check permissions
if adb shell appops get "$FREEKIOSK_PKG" android:get_usage_stats 2>/dev/null | grep -q "allow"; then
    echo "  ✓ Usage stats: allowed"
fi

echo ""
echo "============================================"
echo "  Setup complete!"
echo "============================================"
echo ""
echo "  Next steps:"
echo "  1. Verify FreeKiosk PIN is set (not 0000)"
echo "  2. Test kiosk mode — lock and unlock"
echo "  3. Verify managed apps appear on home screen"
echo "  4. Configure OpenVPN if needed"
echo "  5. Optionally disable USB debugging for"
echo "     production deployment"
echo ""
echo "  To remove Device Owner later:"
echo "  adb shell dpm remove-active-admin $FREEKIOSK_RECEIVER"
echo ""
echo "  ADB escape if locked out:"
echo "  adb shell am force-stop $FREEKIOSK_PKG && \\"
echo "  adb shell am start -S -n $FREEKIOSK_ACTIVITY"
echo ""
