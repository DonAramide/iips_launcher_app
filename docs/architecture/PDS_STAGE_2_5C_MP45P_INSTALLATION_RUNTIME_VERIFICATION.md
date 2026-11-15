# PDS Stage 2.5C — MP45P Installation and Runtime Verification

**Status:** SUCCESS  
**Date:** 2026-11-05  
**Android:** `C:\dev\Dotroid-phase-1d` / `feat/phase-1d-android-enrollment` / HEAD `c4fed4e`  
**Device:** Topwise **MP45P** `HE8558H000567` (Android **8.1.0** / API **27**, USB)  
**Backend:** Quasar `127.0.0.1:4010`; DB `pds_phase1d_integration` (unchanged)

---

## 1. Preflight

| Check | Result |
|---|---|
| Branch / HEAD | `feat/phase-1d-android-enrollment` / `c4fed4e` |
| Working tree | Dirty with Stage 2.5B debug URL/NSC + architecture docs (expected) |
| Device state / model / SDK | `device` / **MP45P** / **27** |
| ADB reverse | `(reverse) tcp:4010 tcp:4010` |
| Host `/health` | **200** |
| Bind | **`127.0.0.1:4010` only** |
| DB | **`pds_phase1d_integration`** |
| APK | `app\build\outputs\apk\launcher\debug\app-launcher-debug.apk` (~34.5 MB) |

---

## 2. APK inspection (before install)

| Attribute | Value |
|---|---|
| Package | `com.iips.dotroid` |
| versionCode / versionName | `2` / `1.0.1` |
| minSdk / targetSdk | `26` / `34` |
| Debuggable | **Yes** |
| `android:testOnly` | **Yes** (requires `adb install -t`) |
| `BuildConfig.BASE_URL` | `http://127.0.0.1:4010/api/v1/` |
| Release APK installed? | **No** |
| Packaged bcrypt / PEM private key scan | **0 hits** |

---

## 3. Installation

| Step | Result |
|---|---|
| First `adb install -r` | `INSTALL_FAILED_TEST_ONLY` |
| Retry `adb install -r -t` | **Success** |
| Installed path | `/data/app/com.iips.dotroid-…/base.apk` |
| Installed on K11 | **No** |
| Installed on D60 | **No** |
| App data cleared? | **No** |

---

## 4. Runtime verification

### Launch

1. System **permission review** UI (`ReviewPermissionsActivity`) — confirmed dangerous permissions, tapped **CONFIRM**.
2. **Home app chooser** appeared (Dotroid declares `HOME`) — selected **Dotroid** / **JUST ONCE**.
3. App process started: **PID 11972**.
4. Cold start via `am start -W` → **Status: ok**; displayed `OnboardingActivity` (~1.8s).

| Check | Result |
|---|---|
| Fatal exception | **None** |
| ANR | **None** |
| Process alive after launch | **Yes** (`pidof` = 11972) |
| Foreground activity | `com.iips.dotroid/...OnboardingActivity` |

Non-fatal notes (not blockers for this stage):

- `ResourcesCompat` attribute resolve warning in `OnboardingActivity.onCreate`
- Google Play Store missing on device (`GooglePlayServicesUtil` warning)

### Integration API URL

| Evidence | Result |
|---|---|
| Build `BuildConfig` for this APK | `http://127.0.0.1:4010/api/v1/` |
| Installed APK DEX string scan | Contains **`127.0.0.1:4010`**; does **not** contain legacy `192.168.1.134:4000` |
| Startup logcat URL print | Not emitted (no OkHttp call on onboarding startup) |

### Quasar health via ADB reverse

| Check | Result |
|---|---|
| Reverse still listed | `(reverse) tcp:4010 tcp:4010` |
| Device `wget` `http://127.0.0.1:4010/health` | **`status=ok`, `service=QUASAR`** |
| Host health | **200** |
| Host bind | Still loopback-only |

### Enrollment not auto-triggered

| Indicator | Observed |
|---|---|
| `pds/enroll/challenge` in logcat | **Absent** |
| `pds/enroll/verify` in logcat | **Absent** |
| Onboarding / provisioning extras parse | Ran (`extractAndPersistProvisioningExtras`) with **no** enrollment token injected |

---

## 5. API / database identity

| Check | Result |
|---|---|
| Health | 200 / `status=ok` |
| Bind | `127.0.0.1:4010` |
| Database | `pds_phase1d_integration` |
| Backend code changes | None |

---

## 6. Git

```
HEAD: c4fed4e (no commit this stage)
Branch: feat/phase-1d-android-enrollment
 M app/build.gradle
?? app/src/debug/
?? docs/architecture/PDS_STAGE_2_5*.md
```

---

## Remaining blockers (Stage 2.5D+)

| ID | Item |
|---|---|
| N-1 | Enrollment token not yet injected |
| N-2 | Challenge/verify not yet authorized |
| N-3 | OkHttp BODY logging still on for later live enrollment |
| N-4 | ImmersiveModeConfirmation / HOME-app UX may need operator awareness on relaunch |

---

## Explicit non-actions

- No enrollment-token decrypt/inject  
- No challenge/verify enrollment HTTP  
- No DB writes / backend changes / minSdk changes  
- No install on K11/D60  
- No push/merge  

**Stop — awaiting approval for Stage 2.5D.**
