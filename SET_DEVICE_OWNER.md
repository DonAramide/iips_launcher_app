# Set Device Owner via ADB - Quick Guide

## ⚠️ IMPORTANT Requirements

**The device MUST be factory reset (unprovisioned) or have NO existing Device Owner before proceeding!**

You CANNOT set a Device Owner if:
- The device setup wizard has been completed
- Another app is already the Device Owner
- The device has any user accounts set up

## Step-by-Step Instructions

### Step 1: Factory Reset Your Device

**IMPORTANT:** Device Owner can ONLY be set on a factory-reset device BEFORE completing the setup wizard!

**Option A: Factory Reset via Settings (if device is already set up)**
1. Open **Settings** → **System** → **Reset options** (or **Backup & reset**)
2. Tap **Erase all data (factory reset)**
3. Confirm and wait for device to reset
4. Device will reboot to setup wizard

**Option B: Factory Reset via Recovery Mode**
1. Power off the device
2. Press and hold **Volume Down + Power** (varies by device, may be **Volume Up + Power**)
3. Use volume keys to navigate to **Recovery Mode**, press Power to select
4. Select **Wipe data/factory reset**
5. Confirm and reboot
6. Device will boot to setup wizard

**Option C: Factory Reset via ADB (if USB Debugging is already enabled)**
```bash
adb reboot recovery
# Then follow recovery menu to factory reset
```

### Step 2: Enable ADB BEFORE Completing Setup

**⚠️ CRITICAL:** ADB MUST be accessible during the setup wizard!

**Method 1: Enable ADB via Setup Wizard (Android 6.0+)**

**⚠️ If tapping 7-10 times shows QR code:**
- That's for WiFi/device transfer - NOT what we need
- Try these alternatives instead:

**Option A: Skip WiFi and Continue**
- Look for a **"Skip"** button on the QR code screen
- Or tap **"Skip"** or **"Set up later"** if available
- Continue setup WITHOUT connecting to WiFi/network
- You can enable ADB later in Settings

**Option B: Connect to WiFi First (Recommended)**
- Scan QR code or manually connect to WiFi
- This allows you to continue setup
- Once WiFi is connected, look for ways to access Settings
- Some devices allow accessing Settings during setup

**Option C: Emergency Call Trick**
- Look for "Emergency call" or "Call emergency services" button
- On some devices, emergency dialer allows accessing Settings
- Type `*#*#4636#*#*` or similar codes (varies by device)

**Option D: Tap on Text/Version Info**
- Look for small text at bottom: "Android version", "Build number", or copyright
- Tap rapidly on version/build info (not just anywhere on screen)
- Some devices reveal "Skip" or access to Developer Options this way

**Method 2: Enable ADB Before Setup (If Device Allows)**
- If you can access any settings menu during setup:
  1. Navigate to **Settings** → **About phone**
  2. Tap **Build number** 7 times to enable Developer Options
  3. Go back to **Settings** → **Developer options**
  4. Enable **USB debugging**

**Method 3: Enable ADB via Recovery (Advanced)**
- Boot into Recovery Mode
- Some custom recoveries allow enabling ADB directly
- Or use ADB sideload mode if available

**Method 4: Use Wireless ADB (if WiFi is set up)**
- During setup wizard, connect to WiFi
- Some devices allow ADB over network without full setup completion

### Step 3: Verify ADB Connection

**⚠️ DO THIS BEFORE completing setup wizard!**

```bash
# Check if device is connected
adb devices
```

**Expected output:**
```
List of devices attached
XXXXXXXX    device
```

**If you see "unauthorized":**
- Device will show a popup: **"Allow USB debugging?"**
- Check **"Always allow from this computer"**
- Tap **OK**
- Run `adb devices` again

**If ADB is NOT accessible:**
- You may need to complete setup partially to enable ADB
- **BUT:** This might prevent setting Device Owner!
- Try accessing Developer Options from Emergency Call or by tapping version numbers
- As a last resort, complete minimal setup, enable ADB, then factory reset again

### Step 4: Install the App

```bash
# Navigate to your project directory
cd /Users/mac/Documents/iips_launcher_app

# Install the app
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Expected output:** `Success` or `Performing Streamed Install` followed by `Success`

### Step 5: Set Device Owner (CRITICAL - Must be BEFORE setup completion)

**⚠️ TIMING IS CRITICAL:** Run this command while the device is STILL in the setup wizard, before you tap "Finish" or "Get Started"!

```bash
adb shell dpm set-device-owner "com.iips.launcher/.policy.DeviceAdminReceiver"
```

**Expected output:** `Success: Device owner set to package ComponentInfo{...}`

### Step 6: Verify Device Owner

**Run this immediately after setting Device Owner:**

```bash
adb shell dumpsys device_policy | grep -i "device.*owner\|admin"
```

Or try:
```bash
adb shell dpm get-owner
```

**If the command doesn't exist, verify by checking if uninstall is blocked:**
```bash
adb shell pm list packages | grep iips.launcher
adb shell pm uninstall com.iips.launcher
# Should FAIL with "Device Owner cannot be uninstalled" or similar
```

**You should see:**
```
Device owner: ComponentInfo{com.iips.launcher/com.iips.launcher.policy.DeviceAdminReceiver}
```

### Step 7: Complete Device Setup

**NOW** you can complete the Android setup wizard on your device.

Now you can complete the Android setup wizard on your device.

### Step 8: Set as Default Launcher

**After setup completion:**

```bash
adb shell cmd package set-home-activity "com.iips.launcher/.ui.LauncherActivity"
```

Or manually: Press Home button → Select "IIPS Launcher" → Choose "Always"

## 🔍 ADB Availability During Setup - FAQ

**Q: Can I use ADB during the setup wizard?**  
A: **YES, but it depends on your device and Android version:**
- ✅ **Modern devices (Android 8.0+)**: Usually allow ADB if USB debugging was enabled before reset
- ✅ **Some devices**: Allow accessing Developer Options during setup by tapping version/build numbers
- ⚠️ **Some devices**: Require completing partial setup to enable ADB (this may prevent Device Owner setup)

**Q: What if ADB isn't accessible during setup?**  
A: Try these options:
1. Connect USB before starting setup wizard
2. During setup, look for "Emergency call" - some allow settings access
3. Tap "Build number" or version info multiple times during setup
4. Use Recovery Mode ADB if your device supports it
5. **Last resort**: Complete minimal setup, enable ADB, factory reset again, then set Device Owner

**Q: When exactly should I run the Device Owner command?**  
A: **IMMEDIATELY after installing the app, while still in setup wizard, before tapping "Finish/Get Started"**

**Q: How do I know if ADB is working during setup?**  
A: Run `adb devices` - if device shows as "device" (not "unauthorized" or "offline"), ADB is working!

**Q: Can I set Device Owner after completing setup?**  
A: **NO** - Device Owner can ONLY be set on unprovisioned devices (before setup completion)

## Troubleshooting

### Error: "Not allowed to set device owner because there are already some users on the device"

**Solution:** Factory reset the device and try again BEFORE completing setup.

### Error: "Not allowed to set device owner because the target user is not initialized"

**Solution:** This means setup was completed. Factory reset and try BEFORE setup completion.

### Error: "Not allowed to set device owner because the package is not installed"

**Solution:** Install the app first with `adb install`

### Error: "Device or resource busy" or "ADB not responding"

**Solution:** 
- Disconnect and reconnect USB
- Run `adb kill-server && adb start-server`
- Check USB cable and try different USB port
- Ensure USB debugging is enabled

### Error: "ADB device not found" during setup

**Solution:**
- Device may require partial setup to enable ADB
- Try connecting USB before starting setup
- Enable USB debugging in Developer Options if accessible
- Some devices need WiFi connection first

### Check Current Device Owner

```bash
# Method 1 (may not work on all devices):
adb shell dumpsys device_policy | grep -i "device.*owner"

# Method 2 (verify by trying to uninstall):
adb shell pm uninstall com.iips.launcher
# Should FAIL if Device Owner is set

# Method 3 (check in app):
# Open app → Long press admin button → Check if "Device Owner: YES" appears
```

If you see another package listed or uninstall succeeds, Device Owner is NOT set. Factory reset required.

## Quick One-Liner (After Factory Reset)

```bash
adb install app/build/outputs/apk/debug/app-debug.apk && \
adb shell dpm set-device-owner "com.iips.launcher/.policy.DeviceAdminReceiver" && \
adb shell dpm list-owners && \
adb shell cmd package set-home-activity "com.iips.launcher/.ui.LauncherActivity"
```

## Using the Setup Script

Alternatively, use the provided script:

```bash
chmod +x setup_device_owner.sh
./setup_device_owner.sh
```

The script will guide you through the process.

## After Device Owner is Set

Once Device Owner is set:
- ✅ App cannot be uninstalled
- ✅ Settings access is blocked
- ✅ Force stop is prevented
- ✅ Factory reset protection is enabled
- ✅ Full kiosk mode capabilities

**Default Admin Password:** `admin123` (change it immediately!)



