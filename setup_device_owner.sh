#!/bin/bash

# IIPS Launcher - Device Owner Setup Script
# This script helps set up the launcher as Device Owner via ADB
# 
# Usage: ./setup_device_owner.sh

echo "=========================================="
echo "IIPS Launcher - Device Owner Setup"
echo "=========================================="
echo ""

# Check if ADB is available
if ! command -v adb &> /dev/null; then
    echo "❌ ERROR: ADB not found!"
    echo "Please install Android SDK Platform Tools and add 'adb' to your PATH"
    exit 1
fi

echo "Checking for connected devices..."
DEVICE_COUNT=$(adb devices | grep -v "List" | grep -c "device$")

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌ ERROR: No devices connected!"
    echo "Please:"
    echo "  1. Connect your Android device via USB"
    echo "  2. Enable USB Debugging in Developer Options"
    echo "  3. Accept the USB debugging prompt on your device"
    exit 1
fi

echo "✅ Found $DEVICE_COUNT device(s)"
echo ""

PACKAGE_NAME="com.iips.launcher"
RECEIVER_PATH="com.iips.launcher/.policy.DeviceAdminReceiver"

echo "⚠️  IMPORTANT: Device must be unprovisioned (factory reset) or have no existing Device Owner"
echo ""
read -p "Have you factory reset the device? (y/N): " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "❌ Setup cancelled. Please factory reset the device first."
    exit 1
fi

echo ""
echo "Installing app (if not already installed)..."
adb install -r app/build/outputs/apk/debug/app-debug.apk 2>/dev/null || echo "App already installed or APK not found. Continuing..."

echo ""
echo "Setting Device Owner..."
RESULT=$(adb shell dpm set-device-owner "$RECEIVER_PATH" 2>&1)

if echo "$RESULT" | grep -q "Success"; then
    echo "✅ SUCCESS: Device Owner set!"
    echo ""
    echo "Next steps:"
    echo "  1. Complete device setup"
    echo "  2. Launch IIPS Launcher"
    echo "  3. Set it as default home app when prompted"
    echo "  4. Access Admin Panel (long-press gear icon)"
    echo "  5. Default password: admin123 (change it!)"
    echo ""
else
    echo "❌ ERROR: Failed to set Device Owner"
    echo "Output: $RESULT"
    echo ""
    echo "Common issues:"
    echo "  - Device already has a Device Owner (factory reset required)"
    echo "  - Device setup wizard was completed (factory reset required)"
    echo "  - App not installed"
    echo ""
    echo "To check existing device owners:"
    echo "  adb shell dpm list-owners"
    exit 1
fi

echo "Verifying Device Owner status..."
adb shell dpm list-owners





