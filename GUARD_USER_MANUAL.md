# Dotroid Guard - User Manual

This manual explains how to use and interpret the **Dotroid Guard** device monitoring dashboard. Dotroid Guard allows administrators to track paired device statuses, connectivity, security locks, and system health in real-time.

---

## 📱 Device Monitoring Dashboard

The main screen of the Dotroid Guard application displays the total count of devices by status and lists each managed device with its metadata, active locations, and status tags.

### Counter Cards
* **Total**: Cumulative count of all paired devices in the fleet.
* **Online / Offline**: Real-time count of connected and disconnected devices.
* **Locked**: Number of devices currently locked by MDM policies or admin command.
* **Pending**: Devices currently waiting to sync state changes or updates.

---

## 🏷️ Status Tags & Indicators

Each device card features three colored status tags to quickly diagnose device state. These correspond to the real-time telemetry gathered by the backend.

```
+-------------------------------------------------+
|  REV AUTOMOBILE                            Now  |
|  REV AUTOMOBILE • Quasar global inventory      |
|  [ONLINE] [NORMAL] [CRITICAL]       1493 Alerts |
+-------------------------------------------------+
```

### 1. Connectivity Status (First Tag)
* **ONLINE (Green)**: The device is currently connected to the server, actively executing tasks, and reporting heartbeats.
* **OFFLINE (Grey)**: The device is currently disconnected, turned off, or has missed its scheduled check-in window.

### 2. Security Status (Second Tag)
* **NORMAL (Green)**: The device is unlocked, compliant, and operating within allowed kiosk boundaries.
* **LOCKED (Red)**: The device has been locked down by an administrator command or automatically triggered due to a geofence/compliance violation.
* **PENDING (Orange)**: A lock or unlock command is currently queued and waiting to be received by the device.
* **SUSPENDED (Accent Blue)**: The device’s pairing or active permission state has been temporarily suspended.

### 3. Device Health Status (Third Tag)
* **HEALTHY (Green)**: The device has zero hardware issues, acceptable battery levels, and no warnings.
* **WARNING (Orange)**: Indicates mild issues such as a low battery status, weak network signals, or minor clock drift.
* **CRITICAL (Red)**: Indicates that the device has hit a high threshold of alert warnings (typically related to geofence breaches, hardware integrity checks, or multiple offline alerts). This matches high counter alert badges on the right.

---

## ⚠️ Telemetry & Alert Counts

The badge on the far right (e.g., `1493 Alerts`) indicates the number of unread alerts logged by that device. High alert counts will automatically trigger the third tag to go into **CRITICAL (Red)** status.

For details on resolving alerts, select the device card to open the **Device Details** page and review individual event logs.
