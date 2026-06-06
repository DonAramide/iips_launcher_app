# System App Installation Guide - IIPS Launcher

This guide explains how to install the IIPS Launcher as a **system app** on Android devices. Installing as a system app provides additional benefits and persistence.

## Benefits of System App Installation

- **Persistence**: App survives factory resets (when installed to /system)
- **Higher Privileges**: System-level permissions and capabilities
- **Cannot be Uninstalled**: Only removable via root/system partition modification
- **Boot Priority**: Starts earlier in boot process
- **Better Integration**: Deeper system integration capabilities

## Prerequisites

1. **Rooted Android Device** - System app installation requires root access
2. **ADB (Android Debug Bridge)** - Installed and configured
3. **USB Debugging** - Enabled on target device
4. **Built APK** - Application must be built first
5. **Writable /system Partition** - Some devices require system partition remount

⚠️ **Warning**: Modifying the system partition can brick your device if done incorrectly. Always backup your device before proceeding.

## Installation Methods

### Method 1: Automated Script (Recommended)

The easiest way to install as a system app:

```bash
# 1. Build the APK
./gradlew assembleDebug

# 2. Run the installation script
./install_as_system_app.sh

# 3. Reboot device
adb reboot
```

The script will:
- Check for connected device and root access
- Install APK to `/system/priv-app/IIPSLauncher/`
- Set proper permissions (644 for APK, 755 for directory)
- Clean up temporary files

### Method 2: Manual Installation

If the script doesn't work or you prefer manual control:

```bash
# 1. Build the APK
./gradlew assembleDebug

# 2. Remount /system as read-write
adb shell su -c "mount -o remount,rw /system"

# 3. Create system app directory
adb shell su -c "mkdir -p /system/priv-app/IIPSLauncher"

# 4. Copy APK to system partition
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/temp_launcher.apk
adb shell su -c "cp /sdcard/temp_launcher.apk /system/priv-app/IIPSLauncher/com.iips.launcher.apk"

# 5. Set permissions
adb shell su -c "chmod 644 /system/priv-app/IIPSLauncher/com.iips.launcher.apk"
adb shell su -c "chown root:root /system/priv-app/IIPSLauncher/com.iips.launcher.apk"
adb shell su -c "chmod 755 /system/priv-app/IIPSLauncher"

# 6. Remount /system as read-only
adb shell su -c "mount -o remount,ro /system"

# 7. Clean up
adb shell su -c "rm /sdcard/temp_launcher.apk"

# 8. Reboot device
adb reboot
```

### Method 3: Custom ROM Integration

For OEM/enterprise deployments, integrate the app into a custom ROM:

1. **Extract system image** or build custom ROM
2. **Add APK** to ROM at: `system/priv-app/IIPSLauncher/com.iips.launcher.apk`
3. **Set permissions** in ROM build system
4. **Flash ROM** to devices

This method is recommended for:
- Enterprise deployments
- Kiosk deployments
- OEM integrations
- Large-scale rollouts

## Post-Installation Setup

After installing as a system app and rebooting:

### 1. Set as Device Owner (Still Required)

Even as a system app, Device Owner status must be set:

```bash
adb shell dpm set-device-owner com.iips.launcher/.policy.DeviceAdminReceiver
```

**Note**: Device Owner can only be set on unprovisioned devices or during setup wizard.

### 2. Set as Default Launcher

After reboot, when prompted, select "IIPS Launcher" as the default home app.

### 3. Configure App

- Access Admin Panel (long-press gear icon)
- Enter default password: `admin123`
- Add allowed apps
- Enable lockdown mode
- Configure other settings

## System App Signing

### Development/Testing (Test Keys)

For development and testing, you can sign with test keys:

```bash
# Sign APK with test keys (development only)
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
  -keystore ~/.android/debug.keystore \
  app/build/outputs/apk/debug/app-debug.apk androiddebugkey

# Or use apksigner
apksigner sign --ks ~/.android/debug.keystore \
  --ks-key-alias androiddebugkey \
  app/build/outputs/apk/debug/app-debug.apk
```

### Production (Platform Keys)

For production deployments:

1. **Obtain platform keys** from device manufacturer or Android source
2. **Sign APK** with platform keys
3. **Install** to system partition

⚠️ **Important**: Using wrong signing keys will cause app installation to fail or app to crash.

## Verification

### Check if App is System App

```bash
# List system apps (should include com.iips.launcher)
adb shell pm list packages -s | grep iips

# Check app location
adb shell pm path com.iips.launcher
# Should show: package:/system/priv-app/IIPSLauncher/com.iips.launcher.apk

# Verify permissions
adb shell ls -la /system/priv-app/IIPSLauncher/
# Should show: -rw-r--r-- root root ... com.iips.launcher.apk
```

### Verify System UID

After installation, check if app has system UID:

```bash
adb shell dumpsys package com.iips.launcher | grep userId
# Should show system UID (usually 1000)
```

## Troubleshooting

### App Not Appearing After Reboot

**Symptoms**: App doesn't appear in app drawer after reboot

**Solutions**:
- Check APK location: `adb shell ls -la /system/priv-app/IIPSLauncher/`
- Verify permissions: APK should be 644, directory should be 755
- Check SELinux context (if applicable): `adb shell ls -Z /system/priv-app/IIPSLauncher/`
- Verify APK is not corrupted: `adb shell pm path com.iips.launcher`
- Check logcat for errors: `adb logcat | grep -i iips`

### App Crashes on Launch

**Symptoms**: App crashes immediately when launched

**Solutions**:
- **Signing key mismatch**: Ensure APK is signed with correct keys (test keys for dev, platform keys for prod)
- **Permission issues**: Check if `android:sharedUserId="android.uid.system"` is present in manifest
- **Check logs**: `adb logcat | grep -i "iips\|fatal"`
- **Verify system app status**: `adb shell pm list packages -s | grep iips`

### Permission Denied Errors

**Symptoms**: Cannot write to /system partition

**Solutions**:
- Verify root access: `adb shell su -c "id"` (should show uid=0)
- Remount /system as writable: `adb shell su -c "mount -o remount,rw /system"`
- Some devices use `/system_root/system` instead of `/system`
- Check if device has system partition write protection (some OEMs lock this)

### Device Boot Loop

**Symptoms**: Device stuck in boot loop after installation

**Solutions**:
- **Boot to recovery mode** and use ADB sideload
- **Remove app** from recovery: `adb shell rm -rf /system/priv-app/IIPSLauncher`
- **Flash factory image** (last resort)
- **Always test** on a development device first

### SELinux Issues

**Symptoms**: App installed but not functioning properly

**Solutions**:
- Check SELinux mode: `adb shell getenforce` (should be Permissive for testing)
- Set SELinux to permissive: `adb shell su -c "setenforce 0"`
- Check SELinux contexts: `adb shell ls -Z /system/priv-app/IIPSLauncher/`
- May need SELinux policy modifications for production

## Best Practices

1. **Always Test First**: Test on a development device before production deployment
2. **Backup Device**: Always backup device before modifying system partition
3. **Use Platform Keys**: For production, use proper platform signing keys
4. **Custom ROM Integration**: For enterprise, consider integrating into custom ROM
5. **Document Signing Keys**: Keep track of which keys were used for signing
6. **Version Control**: Track APK versions and system locations

## Security Considerations

⚠️ **Important Security Notes**:

1. **System Apps Have Elevated Privileges**: System apps can access system-level APIs and data
2. **Platform Keys Are Sensitive**: Never share platform signing keys
3. **System Partition is Protected**: Modifying system partition requires root, which reduces security
4. **SELinux Policies**: May need to update SELinux policies for system app functionality
5. **Audit Trail**: Keep logs of system app installations and modifications

## Removing System App

To remove the system app:

```bash
# 1. Remount /system as writable
adb shell su -c "mount -o remount,rw /system"

# 2. Remove app directory
adb shell su -c "rm -rf /system/priv-app/IIPSLauncher"

# 3. Remount /system as read-only
adb shell su -c "mount -o remount,ro /system"

# 4. Reboot
adb reboot
```

## Additional Resources

- [Android System Apps Documentation](https://source.android.com/docs/core/permissions/perms-allowlist)
- [Platform Signing Keys](https://source.android.com/docs/security/features/apksigning)
- [SELinux for Android](https://source.android.com/docs/security/selinux)
- [Android System Partitions](https://source.android.com/docs/core/ota/ab/partitions)

## Support

For issues or questions:
1. Check device logs: `adb logcat | grep -i iips`
2. Verify system app installation: `adb shell pm path com.iips.launcher`
3. Check Device Owner status: `adb shell dpm list-owners`
4. Review this guide's troubleshooting section




