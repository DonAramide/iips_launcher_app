# Quick Start Guide - IIPS Launcher

## Fast Setup (5 minutes)

### Step 1: Build the Project
1. Open project in Android Studio
2. Wait for Gradle sync to complete
3. Build → Make Project (or Ctrl+F9 / Cmd+F9)

### Step 2: Prepare Device
1. **Factory reset** your Android device (Settings → System → Reset → Factory Reset)
2. **Do NOT complete** the setup wizard
3. Enable USB Debugging:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - Back to Settings → Developer Options
   - Enable "USB Debugging"

### Step 3: Install & Provision
1. Connect device to computer via USB
2. Run the setup script:
   ```bash
   ./setup_device_owner.sh
   ```
   Or manually:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   adb shell dpm set-device-owner com.iips.launcher/.device.DeviceAdminReceiver
   ```

3. Complete device setup wizard

### Step 4: Configure Launcher
1. Launch "IIPS Launcher" app
2. Set as default home when prompted
3. **Access Admin Panel**: Long-press the gear icon
4. **Enter password**: `admin123` (change it immediately!)
5. Tap "Add App" to select allowed apps
6. Enable "Lockdown Mode" toggle
7. **Factory Reset Protection** is enabled by default - prevents users from factory resetting

### Step 5: Change Password
1. In Admin Panel, tap menu (three dots) → Settings
2. Tap "Change Password"
3. Enter new secure password

## That's It! 🎉

Your device is now locked to the launcher. Users can only access apps you've added.

## Troubleshooting

**"Device Owner not set" error?**
- Run: `adb shell dpm list-owners` to check
- Must factory reset and run setup during initial wizard

**Can't exit lockdown?**
- Long-press gear icon → Enter password → Toggle lockdown OFF
- Or: `adb shell am force-stop com.iips.launcher`

**Apps not showing?**
- Admin Panel → Add App → Select apps → Save

For detailed documentation, see [README.md](README.md)

