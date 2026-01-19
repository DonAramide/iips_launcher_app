#!/bin/bash

# IIPS Launcher - Production Deployment Script for Rooted Devices
# This script automates complete deployment: System App Installation + Device Owner Setup

set -e

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.iips.launcher"
RECEIVER_PATH="com.iips.launcher/.device.DeviceAdminReceiver"
SYSTEM_DIR="/system/priv-app"
APP_DIR_NAME="IIPSLauncher"

echo "========================================="
echo "IIPS Launcher - Production Deployment"
echo "========================================="
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ Error: No Android device connected or device not authorized${NC}"
    echo "Please connect your device via USB and enable USB debugging"
    exit 1
fi

echo -e "${GREEN}✅ Device connected${NC}"

# Check root access
echo "Checking root access..."
if ! adb shell "su -c 'id'" 2>/dev/null | grep -q "uid=0"; then
    echo -e "${RED}❌ Error: Root access not available${NC}"
    echo "This script requires root access for production deployment"
    exit 1
fi

echo -e "${GREEN}✅ Root access confirmed${NC}"

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}❌ Error: APK not found at $APK_PATH${NC}"
    echo "Please build the project first: ./gradlew assembleDebug"
    exit 1
fi

echo -e "${GREEN}✅ APK found: $APK_PATH${NC}"

# Remount /system as read-write
echo ""
echo "Remounting /system partition as read-write..."
adb shell "su -c 'mount -o remount,rw /system'" || {
    echo -e "${YELLOW}⚠️  Warning: Could not remount /system${NC}"
    echo "Attempting alternative method..."
    adb shell "su -c 'mount -o remount,rw /system_root/system'" 2>/dev/null || {
        echo -e "${RED}❌ Error: Cannot remount /system partition${NC}"
        echo "Please ensure device is rooted and /system is not write-protected"
        exit 1
    }
    SYSTEM_DIR="/system_root/system/priv-app"
}

echo -e "${GREEN}✅ /system partition is writable${NC}"

# Uninstall existing app (if installed as regular app)
echo ""
echo "Removing existing installation (if any)..."
adb uninstall $PACKAGE_NAME 2>/dev/null || echo "   (No existing installation found)"

# Install APK to device temp location
TEMP_PATH="/sdcard/temp_launcher_$(date +%s).apk"
echo ""
echo "Uploading APK to device..."
adb push "$APK_PATH" "$TEMP_PATH"

# Create system app directory
echo ""
echo "Creating system app directory: $SYSTEM_DIR/$APP_DIR_NAME"
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

# Set SELinux context (if SELinux is enabled)
echo ""
echo "Setting SELinux context..."
adb shell "su -c 'chcon u:object_r:system_file:s0 $SYSTEM_DIR/$APP_DIR_NAME/$PACKAGE_NAME.apk'" 2>/dev/null || {
    echo "   (SELinux context setting skipped - may not be available)"
}

# Remount /system as read-only
echo ""
echo "Remounting /system partition as read-only..."
adb shell "su -c 'mount -o remount,ro /system'" 2>/dev/null || {
    adb shell "su -c 'mount -o remount,ro /system_root/system'" 2>/dev/null || true
}

# Clean up temp file
echo ""
echo "Cleaning up temporary files..."
adb shell "su -c 'rm -f $TEMP_PATH'"

# Reboot device
echo ""
echo -e "${YELLOW}⚠️  Device will reboot in 3 seconds...${NC}"
sleep 3
echo "Rebooting device..."
adb reboot

echo ""
echo "========================================="
echo -e "${GREEN}✅ System app installation complete!${NC}"
echo "========================================="
echo ""
echo -e "${YELLOW}Waiting for device to reboot...${NC}"
echo "This may take 30-60 seconds..."
echo ""

# Wait for device to come back online
sleep 10
timeout=60
elapsed=0
while [ $elapsed -lt $timeout ]; do
    if adb devices | grep -q "device$"; then
        echo -e "${GREEN}✅ Device is back online${NC}"
        break
    fi
    sleep 2
    elapsed=$((elapsed + 2))
    echo "   Waiting... ($elapsed/$timeout seconds)"
done

if [ $elapsed -ge $timeout ]; then
    echo -e "${RED}⚠️  Warning: Device did not come back online within $timeout seconds${NC}"
    echo "Please manually verify device is connected and proceed with Device Owner setup"
    exit 1
fi

# Wait a bit more for system to fully initialize
echo ""
echo "Waiting for system to initialize..."
sleep 5

# Try to set Device Owner (may work on unprovisioned devices)
echo ""
echo "Attempting to set Device Owner..."
DEVICE_OWNER_RESULT=$(adb shell dpm set-device-owner "$RECEIVER_PATH" 2>&1 || echo "FAILED")

if echo "$DEVICE_OWNER_RESULT" | grep -q "Success"; then
    echo -e "${GREEN}✅ Device Owner set successfully!${NC}"
    DEVICE_OWNER_SET=true
else
    echo -e "${YELLOW}⚠️  Device Owner could not be set automatically${NC}"
    echo "Reason: $DEVICE_OWNER_RESULT"
    echo ""
    echo "This is normal if:"
    echo "  - Device setup wizard was already completed"
    echo "  - Another app is already Device Owner"
    echo "  - Device has user accounts"
    echo ""
    DEVICE_OWNER_SET=false
fi

# Verify installation
echo ""
echo "Verifying installation..."
SYSTEM_APP_CHECK=$(adb shell pm path $PACKAGE_NAME 2>/dev/null || echo "NOT_FOUND")

if echo "$SYSTEM_APP_CHECK" | grep -q "/system/"; then
    echo -e "${GREEN}✅ App is installed as system app${NC}"
    echo "   Location: $SYSTEM_APP_CHECK"
else
    echo -e "${RED}⚠️  Warning: App may not be installed as system app${NC}"
fi

# Summary
echo ""
echo "========================================="
echo "Deployment Summary"
echo "========================================="
echo "✅ System app: Installed"
if [ "$DEVICE_OWNER_SET" = true ]; then
    echo -e "${GREEN}✅ Device Owner: Set${NC}"
else
    echo -e "${YELLOW}⚠️  Device Owner: Not set (may need manual setup)${NC}"
    echo ""
    echo "To set Device Owner manually:"
    echo "  1. Factory reset device (if needed)"
    echo "  2. Run: adb shell dpm set-device-owner $RECEIVER_PATH"
    echo "  3. Must be done BEFORE completing setup wizard"
fi
echo ""

# Next steps
echo "Next Steps:"
echo "1. Set IIPS Launcher as default home app:"
echo "   adb shell cmd package set-home-activity \"$PACKAGE_NAME/.ui.LauncherActivity\""
echo ""
echo "2. Access Admin Panel (long-press gear icon)"
echo "   Default password: admin123 (change immediately!)"
echo ""
echo "3. Configure allowed apps and enable lockdown mode"
echo ""
echo -e "${GREEN}✅ Production deployment complete!${NC}"




