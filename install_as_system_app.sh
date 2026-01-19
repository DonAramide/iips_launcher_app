#!/bin/bash

# IIPS Launcher - System App Installation Script
# This script helps install the IIPS Launcher as a system app on rooted Android devices

set -e

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.iips.launcher"
SYSTEM_DIR="/system/priv-app"
APP_DIR_NAME="IIPSLauncher"

echo "========================================="
echo "IIPS Launcher - System App Installer"
echo "========================================="
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ Error: No Android device connected or device not authorized"
    echo "Please connect your device via USB and enable USB debugging"
    exit 1
fi

echo "✅ Device connected"

# Check if device is rooted
echo "Checking root access..."
if ! adb shell "su -c 'id'" | grep -q "uid=0"; then
    echo "⚠️  Warning: Device may not be rooted or root access not granted"
    echo "This script requires root access to install as system app"
    read -p "Continue anyway? (y/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo "❌ Error: APK not found at $APK_PATH"
    echo "Please build the project first: ./gradlew assembleDebug"
    exit 1
fi

echo "✅ APK found: $APK_PATH"

# Uninstall existing app (if installed as regular app)
echo ""
echo "Removing existing installation (if any)..."
adb uninstall $PACKAGE_NAME 2>/dev/null || echo "   (No existing installation found)"

# Install APK to device temp location
TEMP_PATH="/sdcard/temp_launcher.apk"
echo ""
echo "Uploading APK to device..."
adb push "$APK_PATH" "$TEMP_PATH"

# Create system app directory
echo ""
echo "Creating system app directory..."
adb shell "su -c 'mkdir -p $SYSTEM_DIR/$APP_DIR_NAME'"

# Copy APK to system partition
echo ""
echo "Installing to system partition..."
adb shell "su -c 'cp $TEMP_PATH $SYSTEM_DIR/$APP_DIR_NAME/$PACKAGE_NAME.apk'"

# Set proper permissions
echo ""
echo "Setting permissions..."
adb shell "su -c 'chmod 644 $SYSTEM_DIR/$APP_DIR_NAME/$PACKAGE_NAME.apk'"
adb shell "su -c 'chown root:root $SYSTEM_DIR/$APP_DIR_NAME/$PACKAGE_NAME.apk'"
adb shell "su -c 'chmod 755 $SYSTEM_DIR/$APP_DIR_NAME'"

# Clean up temp file
echo ""
echo "Cleaning up..."
adb shell "su -c 'rm -f $TEMP_PATH'"

echo ""
echo "========================================="
echo "✅ System app installation complete!"
echo "========================================="
echo ""
echo "Next steps:"
echo "1. Reboot your device: adb reboot"
echo "2. After reboot, the app will be installed as a system app"
echo "3. Set as Device Owner using: adb shell dpm set-device-owner $PACKAGE_NAME/.device.DeviceAdminReceiver"
echo ""
echo "⚠️  Note: If the app doesn't appear after reboot, check:"
echo "   - /system is mounted as read-write"
echo "   - APK was signed correctly (may need platform keys for production)"
echo "   - Device is running Android 6.0+"
echo ""




