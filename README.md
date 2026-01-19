# IIPS Launcher - Device Owner Kiosk Launcher App

A comprehensive Android Device Owner / Kiosk-style launcher application designed to lock down Android devices and provide a controlled app environment. This launcher is ideal for kiosks, staff devices, or any scenario where you need to restrict device access to specific applications.

## Features

### 🔒 Kiosk Mode & Device Control
- **Device Owner Privileges**: Full device control using Android Device Owner API
- **Lock Task Mode**: Prevents users from exiting the launcher or accessing other apps
- **Immersive Mode**: Hides navigation and status bars for full-screen experience
- **System Button Blocking**: Blocks back, home, and recent apps buttons in lockdown mode
- **Notification Blocking**: Prevents access to notifications when locked down

### 📱 Dashboard & App Management
- **App Grid Display**: Clean grid layout showing all allowed applications
- **Customizable App List**: Admin can add or remove apps from the dashboard
- **App Launch Tracking**: Logs which apps are opened and when (for monitoring)
- **Modern UI**: Material Design 3 with light/dark theme support

### 👨‍💼 Admin Panel
- **Password Protection**: Secure admin access with encrypted password storage
- **App Management**: Add/remove apps from the allowed list
- **Lockdown Controls**: Enable/disable device lockdown mode
- **Settings Management**: Change admin password and configure launcher behavior

### 🔐 Security Features
- **Encrypted Storage**: Admin passwords stored using Android Security Crypto library
- **Device Owner Enforcement**: Uses DevicePolicyManager for maximum security
- **Factory Reset Protection**: Prevents users from factory resetting the device from Settings
- **Settings App Blocking**: Hides/restricts access to Settings app to prevent tampering
- **App Usage Logging**: Room database tracks app launches for audit purposes
- **Prevents Uninstallation**: Device Owner mode prevents app removal without admin access

## Technical Requirements

- **Minimum Android Version**: Android 10 (API 29)
- **Target Android Version**: Android 14 (API 34)
- **Language**: Kotlin
- **Architecture**: MVVM with Room database
- **Dependencies**: AndroidX, Material Design, Room, Security Crypto

## Installation & Setup

### Prerequisites

1. **Android Studio**: Arctic Fox or later recommended
2. **ADB (Android Debug Bridge)**: Required for Device Owner provisioning
3. **Android Device**: Must be unprovisioned (factory reset) or have no existing Device Owner
4. **USB Debugging**: Enabled on the target device

### Build Instructions

1. **Clone or Extract** the project to your local machine
2. **Open** the project in Android Studio
3. **Sync** Gradle files (File → Sync Project with Gradle Files)
4. **Build** the project (Build → Make Project)
5. **Install** on your device via USB or generate an APK

### Setting Up Device Owner (Critical Step)

**⚠️ IMPORTANT**: The app MUST be provisioned as Device Owner for full functionality. This can ONLY be done on an unprovisioned device or one without an existing Device Owner.

#### Method 1: ADB Command (Recommended)

1. **Factory reset** your Android device (or use a fresh device)
2. **Do NOT complete** the initial setup wizard
3. **Connect** device to computer via USB
4. **Enable USB Debugging**:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times to enable Developer Options
   - Go to Developer Options → Enable "USB Debugging"
5. **Run** the following ADB command:

```bash
adb shell dpm set-device-owner com.iips.launcher/.device.DeviceAdminReceiver
```

6. If successful, you should see: `Success: Device owner set to package com.iips.launcher`

**Note**: If you see "Error: Can't set package X as device owner because there are already some accounts on the device", you need to factory reset the device first.

#### Method 2: Production Deployment (Rooted Devices)

For production deployments on rooted devices, use the automated production deployment script. This method is recommended for enterprise/kiosk scenarios where you have root access and cannot access settings during setup.

See [PRODUCTION_DEPLOYMENT.md](PRODUCTION_DEPLOYMENT.md) for complete production deployment instructions.

### Installing as System App (Optional)

For enhanced persistence and system-level privileges, you can install the app as a **system app**. This is particularly useful for enterprise deployments and kiosk scenarios.

**⚠️ Warning**: System app installation requires a rooted device and modifying the system partition. This can brick your device if done incorrectly.

#### Quick Install (Automated)

```bash
# Build the APK first
./gradlew assembleDebug

# Run the system app installation script
./install_as_system_app.sh

# Reboot device
adb reboot
```

#### Benefits of System App Installation

- ✅ **Survives Factory Reset**: App remains after factory reset (when installed to /system)
- ✅ **Cannot be Uninstalled**: Only removable via root/system partition modification
- ✅ **System-Level Privileges**: Enhanced permissions and capabilities
- ✅ **Boot Priority**: Starts earlier in boot process

**📖 For detailed instructions, see [SYSTEM_APP_INSTALLATION.md](SYSTEM_APP_INSTALLATION.md)**

**Note**: Even as a system app, you must still set Device Owner using the ADB command shown above.

### Post-Installation Configuration

1. **Launch** the IIPS Launcher app
2. **Access Admin Panel**: Long-press the admin button (gear icon) or tap it and enter password
   - **Default Password**: `admin123` (change this immediately!)
3. **Add Apps**: In Admin Panel, tap "Add App" and select apps to allow
4. **Enable Lockdown**: Toggle "Enable Lockdown" switch in Admin Panel
5. **Enable Factory Reset Protection**: Toggle "Factory Reset Protection" ON (enabled by default) - prevents users from factory resetting
6. **Set as Default Home**: When prompted, select "IIPS Launcher" as default home app

### Changing Default Password

1. Open Admin Panel (long-press admin button)
2. Tap the settings icon (three dots menu)
3. Select "Change Password"
4. Enter new password and confirm

## Usage

### For End Users

- The launcher displays only apps added by the admin
- Tap an app icon to launch it
- In lockdown mode, users cannot exit apps or access system settings
- Back button is disabled in lockdown mode

### For Admins

1. **Access Admin Panel**: Long-press the admin button (gear icon) in the launcher header
2. **Add Apps**: Tap "Add App" → Select apps → Tap save icon
3. **Manage Lockdown**: Toggle lockdown mode on/off as needed
4. **View Logs**: App usage is automatically logged to the database (can be extended with UI)

## Troubleshooting

### "Device Owner not set" Error

- Ensure you ran the ADB command during device setup
- Device must have no existing Device Owner
- Try factory resetting and repeating the ADB command

### App Not Showing in Launcher

- Ensure the app has been added via Admin Panel → "Add App"
- Some system apps may not appear; try adding manually by package name

### Cannot Exit Lockdown Mode

- Access Admin Panel (long-press admin button)
- Toggle "Enable Lockdown" switch OFF
- Or use ADB: `adb shell am force-stop com.iips.launcher`

### Lock Task Mode Not Working

- Verify Device Owner is properly set: `adb shell dpm list-owners`
- Ensure you're using Android 6.0 (Marshmallow) or later
- Check that the app is set as default home launcher

### Building/Compilation Errors

- Ensure all dependencies are synced (File → Sync Project with Gradle Files)
- Clean and rebuild project (Build → Clean Project, then Build → Rebuild Project)
- Check that your Android SDK is up to date

## Project Structure

```
app/
├── src/main/
│   ├── java/com/iips/launcher/
│   │   ├── data/              # Room database, entities, DAOs
│   │   ├── device/            # Device Owner receiver
│   │   ├── ui/                # Activities, adapters, fragments
│   │   ├── utils/             # Helper classes (DeviceController, SecurePreferences)
│   │   └── LauncherApplication.kt
│   ├── res/
│   │   ├── layout/            # XML layouts
│   │   ├── menu/              # Menu definitions
│   │   ├── values/            # Strings, colors, themes
│   │   └── xml/               # Device admin configuration
│   └── AndroidManifest.xml
└── build.gradle
```

## Customization

### Branding

1. **Colors**: Edit `app/src/main/res/values/colors.xml`
2. **Logo**: Replace `ic_launcher` in `res/mipmap-*` folders
3. **App Name**: Change `app_name` in `strings.xml`
4. **Theme**: Modify `themes.xml` for custom styling

### Adding Features

- **App Usage UI**: Extend `AppUsageLogDao` to display logs in Admin Panel
- **Remote Management**: Add Firebase/DMM integration for remote app management
- **Custom Restrictions**: Use `DeviceController.setUserRestriction()` for additional policies

## Security Considerations

⚠️ **Important Security Notes**:

1. **Change Default Password**: The default password is `admin123` - change it immediately!
2. **Device Owner Lock**: Once set as Device Owner, the app cannot be uninstalled without factory reset
3. **Factory Reset Protection**: Enabled by default, this prevents users from factory resetting via Settings. However, hardware recovery mode (button combinations) cannot be fully prevented without physical device modifications
4. **Backup Admin Password**: Store admin password securely - if forgotten, factory reset is required
5. **Testing**: Always test on a dedicated device, not your primary phone

### Factory Reset Protection Details

The Factory Reset Protection feature:
- ✅ **Prevents factory reset from Settings UI** - Users cannot access "Factory Reset" option in Settings
- ✅ **Hides Settings app** - Settings app is hidden from launcher on supported devices (Android 7.0+)
- ✅ **Blocks recovery settings** - Prevents access to recovery-related settings
- ⚠️ **Hardware recovery mode limitation** - Factory reset via hardware button combinations (e.g., Power + Volume Down) may still work on some devices. Physical security measures may be needed for complete protection.

**To manage Factory Reset Protection**: Admin Panel → Factory Reset Protection toggle

## Legal & Compliance

- This launcher uses Android Device Owner APIs which are intended for enterprise/MDM scenarios
- Ensure compliance with local privacy and monitoring laws when tracking app usage
- Device Owner privileges grant significant control - use responsibly

## License

This project is provided as-is for educational and enterprise use. Customize as needed for your requirements.

## Support

For issues or questions:
1. Check the Troubleshooting section above
2. Verify Device Owner is properly set: `adb shell dpm list-owners`
3. Review Android Device Owner documentation: [Android Developer Guide](https://developer.android.com/work/dpc/build-device-owner)

## Additional ADB Commands

```bash
# List current device owners
adb shell dpm list-owners

# Remove device owner (requires factory reset afterward)
adb shell dpm remove-active-admin com.iips.launcher/.device.DeviceAdminReceiver

# Force stop launcher (if locked)
adb shell am force-stop com.iips.launcher

# Clear app data
adb shell pm clear com.iips.launcher

# Uninstall (may fail if Device Owner)
adb uninstall com.iips.launcher
```

---

**Version**: 1.0.0  
**Last Updated**: 2024  
**Compatibility**: Android 10+ (API 29+)

