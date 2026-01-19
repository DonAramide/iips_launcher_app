# Production Deployment Guide - IIPS Launcher

This guide is specifically designed for **production deployments on rooted devices** where you cannot access app settings during device setup. This method is ideal for enterprise kiosk deployments, mass device provisioning, and scenarios where manual setup is not feasible.

## Overview

The production deployment method combines:
- **System App Installation**: App is installed to `/system/priv-app/` for persistence
- **Automated Device Owner Setup**: Attempts to set Device Owner automatically (if possible)
- **Boot-Time Auto-Start**: Launcher automatically starts on device boot
- **Enhanced Security**: System-level privileges for maximum protection

## Prerequisites

### Required
- ✅ **Rooted Android Device** - Root access is required for system app installation
- ✅ **ADB (Android Debug Bridge)** - Installed and configured
- ✅ **USB Debugging** - Enabled on target device (can be enabled via root)
- ✅ **Built APK** - Application must be built first

### Optional
- **Platform Signing Keys** - For production deployments (test keys work for development)
- **Custom ROM Integration** - For OEM-level deployments

## Quick Deployment

### Automated Production Deployment (Recommended)

```bash
# 1. Build the APK
./gradlew assembleDebug

# 2. Run production deployment script
./deploy_production.sh

# Script will:
# - Install app as system app to /system/priv-app/
# - Reboot device
# - Attempt to set Device Owner automatically
# - Verify installation
```

The script handles everything automatically including:
- System partition remount
- APK installation to `/system/priv-app/`
- Permission setting
- SELinux context (if applicable)
- Device reboot
- Device Owner setup attempt
- Verification

## Detailed Deployment Steps

### Step 1: Prepare Device

#### Option A: Already Rooted Device

```bash
# Verify root access
adb shell su -c "id"
# Should show: uid=0(root)

# Enable USB debugging (if not already enabled)
adb shell su -c "setprop service.adb.tcp.port 5555"
adb shell su -c "start adbd"
```

#### Option B: Root Device After Setup

If device is not rooted:
1. Root the device using your preferred method (Magisk, custom recovery, etc.)
2. Ensure ADB has root access: `adb shell su -c "id"`

### Step 2: Build APK

```bash
# Build debug APK (for development/testing)
./gradlew assembleDebug

# Or build release APK (for production)
./gradlew assembleRelease

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
# or: app/build/outputs/apk/release/app-release.apk
```

### Step 3: Deploy to Device

#### Automated (Recommended)

```bash
./deploy_production.sh
```

#### Manual Deployment

```bash
# 1. Remount /system as read-write
adb shell su -c "mount -o remount,rw /system"

# 2. Create system app directory
adb shell su -c "mkdir -p /system/priv-app/IIPSLauncher"

# 3. Copy APK to device
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/temp_launcher.apk

# 4. Install to system partition
adb shell su -c "cp /sdcard/temp_launcher.apk /system/priv-app/IIPSLauncher/com.iips.launcher.apk"

# 5. Set permissions
adb shell su -c "chmod 644 /system/priv-app/IIPSLauncher/com.iips.launcher.apk"
adb shell su -c "chown root:root /system/priv-app/IIPSLauncher/com.iips.launcher.apk"
adb shell su -c "chmod 755 /system/priv-app/IIPSLauncher"

# 6. Set SELinux context (if applicable)
adb shell su -c "chcon u:object_r:system_file:s0 /system/priv-app/IIPSLauncher/com.iips.launcher.apk"

# 7. Remount /system as read-only
adb shell su -c "mount -o remount,ro /system"

# 8. Clean up
adb shell su -c "rm /sdcard/temp_launcher.apk"

# 9. Reboot device
adb reboot
```

### Step 4: Set Device Owner (After Reboot)

After device reboots, the app will be installed as a system app. To set Device Owner:

#### Option A: Device Already Provisioned

If device setup was already completed:
1. **Factory reset device** (Settings → System → Reset → Factory Reset)
2. **Do NOT complete setup wizard**
3. **Run**: `adb shell dpm set-device-owner com.iips.launcher/.device.DeviceAdminReceiver`

#### Option B: Device Not Yet Provisioned

If device is still in setup wizard:
```bash
# Set Device Owner immediately (before completing setup)
adb shell dpm set-device-owner com.iips.launcher/.device.DeviceAdminReceiver
```

**Note**: Device Owner can only be set on unprovisioned devices (before setup completion).

### Step 5: Verify Installation

```bash
# Check if app is system app
adb shell pm path com.iips.launcher
# Should show: package:/system/priv-app/IIPSLauncher/com.iips.launcher.apk

# Verify Device Owner
adb shell dpm list-owners
# Should show: com.iips.launcher/.device.DeviceAdminReceiver

# Check app status
adb shell dumpsys package com.iips.launcher | grep -i "system\|uid"
```

### Step 6: Set as Default Launcher

```bash
# Set as default home app via ADB
adb shell cmd package set-home-activity "com.iips.launcher/.ui.LauncherActivity"

# Or manually on device:
# Press Home → Select "IIPS Launcher" → Choose "Always"
```

### Step 7: Configure Launcher

1. **Access Admin Panel**: Long-press the gear icon in launcher
2. **Enter Password**: `admin123` (change immediately!)
3. **Add Apps**: Tap "Add App" → Select allowed apps
4. **Enable Lockdown**: Toggle "Enable Lockdown" switch
5. **Factory Reset Protection**: Already enabled by default

## Production Deployment Features

### System App Benefits

✅ **Persistence**: App survives factory reset (when installed to /system)  
✅ **Cannot be Uninstalled**: Only removable via root/system partition modification  
✅ **Boot Priority**: Starts earlier in boot process  
✅ **System-Level Privileges**: Enhanced permissions and capabilities  
✅ **Auto-Start on Boot**: Launcher automatically starts after device boot

### Boot-Time Auto-Start

The launcher includes a `BootReceiver` that automatically:
- Starts launcher on device boot
- Re-enables security features (lockdown, restrictions)
- Applies comprehensive security settings
- Ensures launcher is always running

### System-Level Security

When installed as system app:
- **Enhanced App Hiding**: Can hide more system apps
- **Stronger Restrictions**: System-level user restrictions
- **Boot-Time Enforcement**: Security enforced immediately on boot
- **Persistence**: Settings survive across reboots and factory resets

## Mass Deployment

For deploying to multiple devices:

### Script-Based Deployment

```bash
#!/bin/bash
# deploy_multiple.sh

DEVICES=$(adb devices | grep "device$" | cut -f1)

for device in $DEVICES; do
    echo "Deploying to device: $device"
    adb -s $device install -r app/build/outputs/apk/debug/app-debug.apk
    # ... rest of deployment steps
done
```

### Custom ROM Integration

For large-scale deployments:

1. **Build custom ROM** with IIPS Launcher included
2. **Pre-configure** allowed apps and settings
3. **Flash ROM** to all devices
4. **Device Owner** can be set during ROM initialization

## Troubleshooting

### App Not Appearing After Reboot

**Check**:
```bash
# Verify APK location
adb shell ls -la /system/priv-app/IIPSLauncher/

# Check permissions
adb shell ls -l /system/priv-app/IIPSLauncher/com.iips.launcher.apk
# Should be: -rw-r--r-- root root
```

**Solution**: Re-install with correct permissions

### Device Owner Not Set

**Check**:
```bash
adb shell dpm list-owners
```

**Solution**: 
- Factory reset device
- Set Device Owner before completing setup wizard
- Run: `adb shell dpm set-device-owner com.iips.launcher/.device.DeviceAdminReceiver`

### App Crashes on Launch

**Check**: APK signing keys

**Solution**: 
- For development: Use test keys (debug.keystore)
- For production: Sign with platform keys

### Boot Receiver Not Working

**Check**:
```bash
# Verify BootReceiver is registered
adb shell dumpsys package com.iips.launcher | grep -i boot
```

**Solution**: 
- Ensure `RECEIVE_BOOT_COMPLETED` permission is in manifest
- Verify app is installed as system app
- Check device boot logs: `adb logcat | grep -i boot`

## Security Considerations

⚠️ **Important for Production**:

1. **Change Default Password**: Immediately change from `admin123`
2. **Platform Signing Keys**: Use proper platform keys for production
3. **Secure Root Access**: Lock down root access after deployment
4. **SELinux Policies**: Ensure SELinux policies allow system app functionality
5. **Audit Trail**: Log all administrative actions

## Signing for Production

### Development (Test Keys)

```bash
# Sign with debug keystore (development only)
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
  -keystore ~/.android/debug.keystore \
  app/build/outputs/apk/debug/app-debug.apk androiddebugkey
```

### Production (Platform Keys)

```bash
# Sign with platform keys (production)
apksigner sign --ks platform.keystore \
  --ks-key-alias platform \
  app/build/outputs/apk/release/app-release.apk
```

**Note**: Platform keys are device/manufacturer-specific. Obtain from device OEM or Android source.

## Verification Checklist

After deployment, verify:

- [ ] App is installed to `/system/priv-app/IIPSLauncher/`
- [ ] App has system UID (UID < 10000)
- [ ] Device Owner is set: `adb shell dpm list-owners`
- [ ] Launcher is set as default home app
- [ ] Boot receiver is registered
- [ ] App starts automatically on boot
- [ ] Security features are active (lockdown, restrictions)
- [ ] Admin password has been changed from default

## Support

For issues:
1. Check logs: `adb logcat | grep -i iips`
2. Verify installation: `adb shell pm path com.iips.launcher`
3. Check Device Owner: `adb shell dpm list-owners`
4. Review troubleshooting section above

## Additional Resources

- [System App Installation Guide](SYSTEM_APP_INSTALLATION.md)
- [Device Owner Setup Guide](SET_DEVICE_OWNER.md)
- [Main README](README.md)




