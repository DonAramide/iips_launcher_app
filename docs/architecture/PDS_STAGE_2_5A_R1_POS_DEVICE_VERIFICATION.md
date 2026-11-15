# PDS Stage 2.5A-R1 — POS Device Verification

**Status:** Inspection only — complete.  
**Date:** 2026-11-05  
**Android worktree:** `C:\dev\Dotroid-phase-1d` @ `feat/phase-1d-android-enrollment` / `c4fed4e`  
**Excluded:** Samsung SM-T397U (not an approved PDS POS test device; not inspected)

Approved inventory verified against live ADB.

---

## 1. Individual device verification

### Horizonpay K11 — `0123456789ABCDEF`

| Property | Value |
|---|---|
| `get-state` | `device` (authorized) |
| Model | **K11** (matches inventory) |
| Manufacturer | Horizonpay |
| Android / SDK | **7.1.2 / API 25** |
| Fingerprint | `qcom/msm8909/msm8909:7.1.2/V3.6.7-gms-2607160636/V3.6.7-gms:user/dev-keys` |
| Hardware / ABI | qcom / `armeabi-v7a` |
| Characteristics | phone |
| Emulator | No |

### Topwise MP45P — `HE8558H000567`

| Property | Value |
|---|---|
| `get-state` | `device` (authorized) |
| Model | **MP45P** (matches inventory) |
| Manufacturer | Topwise |
| Android / SDK | **8.1.0 / API 27** |
| Fingerprint | `alps/full_tb8765ap1_bsp_1g/tb8765ap1_bsp_1g:8.1.0/O11019/1754908143:user/test-keys` |
| Hardware / ABI | mt6739 / `armeabi-v7a` |
| Characteristics | tablet |
| Emulator | No |

### Dspread D60 — `192.168.1.145:45161`

| Property | Value |
|---|---|
| `get-state` | `device` (authorized) |
| Model | **D60** (matches inventory) |
| Manufacturer | Dspread |
| Android / SDK | **11 / API 30** |
| Fingerprint | `Dspread/D60/D60:11/RP1A.200720.011/mp1V82432:1763091430/user/release-keys` |
| Hardware / ABI | mt6761 / `armeabi-v7a` |
| Characteristics | default |
| Emulator | No |

All three returned models match the approved inventory. SM-T397U was **not** present on `adb devices -l` and was not queried.

---

## 2. Connection verification

| Serial | Connection | Authorized | `adb reverse` |
|---|---|---|---|
| `0123456789ABCDEF` (K11) | **USB** | Yes (`device`) | Supported (`reverse --list` succeeds; **no mappings configured**) |
| `HE8558H000567` (MP45P) | **USB** | Yes | Supported; **no mappings configured** |
| `192.168.1.145:45161` (D60) | **TCP/IP** (Wi‑Fi ADB) | Yes | Supported over this TCP session; **no mappings configured** |

Reverse was probed read-only only. No `adb reverse tcp:4010` was applied.

---

## 3. Android application compatibility

### App SDK / variants (from `app/build.gradle`)

| Setting | Value |
|---|---|
| `minSdk` | **26** |
| `targetSdk` | 34 |
| `compileSdk` | 34 |
| Flavors | `launcher` (`com.iips.dotroid`), `guard` |
| Build types | `debug` / `release` |
| Debug BASE_URL (Stage 2.5B) | `http://127.0.0.1:4010/api/v1/` (not changed in this stage) |

### Per-device APK install compatibility

| Device | API | vs `minSdk 26` | Verdict |
|---|---|---|---|
| K11 | 25 | **Below** | **Incompatible** with current APK without approved minSdk change |
| MP45P | 27 | Meets | Compatible |
| D60 | 30 | Meets | Compatible |

### Permissions / hardware notes

Launcher manifest requests INTERNET, device-admin, camera, location, phone state, Bluetooth, etc. None of these were exercised. Camera is declared as `android.hardware.camera.any` (required feature) — all three POS units are expected to expose a camera for QR/provisioning flows; not re-validated here.

### PDS cryptography (EC P-256)

App path (`PdsDeviceKeyManager`):

- Android Keystore EC **`secp256r1`**, `PURPOSE_SIGN` / SHA-256
- StrongBox preferred on API ≥ 28 when `FEATURE_STRONGBOX_KEYSTORE` exists; otherwise **TEE Keystore fallback** (never software key outside Keystore)

| Device | API | StrongBox feature advertised | Expected crypto path |
|---|---|---|---|
| K11 | 25 | Not listed | N/A for current APK (minSdk block). If ever allowed: Keystore EC without StrongBox (API &lt; 28) |
| MP45P | 27 | Not listed | Android Keystore EC P-256, no StrongBox (API &lt; 28) — **supported by app fallback** |
| D60 | 30 | Not listed via `pm list features` | Android Keystore EC P-256; StrongBox unlikely — TEE fallback — **supported** |

No keys were generated on-device in this stage.

---

## 4. Test-device recommendation

### Primary (first enrollment)

**Topwise MP45P (`HE8558H000567`)**

Reasons:

1. USB ADB — stable for `adb reverse` to host `127.0.0.1:4010`
2. API **27 ≥ minSdk 26** — installable with current `launcherDebug`
3. Matches approved POS inventory
4. Avoids Wi‑Fi ADB fragility of the D60 path while Quasar remains loopback-bound

### Subsequent compatibility targets

| Order | Device | Role |
|---|---|---|
| 2 | **D60** | Newer API (30); TCP ADB — reverse still available, but USB-first preferred |
| 3 | **K11** | Blocked until minSdk / special build policy is approved |

---

## 5. Remaining blockers

| ID | Blocker | Applies to |
|---|---|---|
| B-1 | K11 API 25 &lt; `minSdk 26` | K11 |
| B-2 | `adb reverse` not yet configured (deferred by design) | MP45P / D60 for live API |
| B-3 | App not installed on any POS in this stage | All |
| B-4 | Enrollment token not injected | All |
| B-5 | OkHttp BODY logging still a live-test risk (prior finding) | All |
| B-6 | Single enrollment token — enroll **one** primary device first | Fleet |

---

## Explicit non-actions

- No SM-T397U inspection or use  
- No APK install / app launch  
- No API configuration changes  
- No enrollment HTTP  
- No backend / DB / Phase 1C / main-repo changes  
- No `adb reverse` mappings created  

**Stop — awaiting approval** to proceed with Stage 2.5B reverse on **MP45P** (or updated connectivity stage) and later install/enrollment.
