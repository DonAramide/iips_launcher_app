# PDS Stage 2.5B — Physical Android POS Connectivity Configuration

**Status:** Partial success — debug/integration URL + cleartext configuration built and verified; **ADB reverse blocked** (intended SM-T397U not connected).  
**Date:** 2026-11-05  
**Android worktree:** `C:\dev\Dotroid-phase-1d`  
**Branch:** `feat/phase-1d-android-enrollment`  
**Starting HEAD:** `c4fed4e` (unchanged; no commit in this stage)  
**Backend:** `C:\dev\quasar-phase-1d-2` — **not modified**

---

## A. Preflight

| Check | Result |
|---|---|
| Branch | `feat/phase-1d-android-enrollment` |
| HEAD | `c4fed4e` |
| Working tree at start | Clean except untracked Stage 2.5A readiness doc |
| Flavors | `launcher` / `guard` |
| Build types | `debug` / `release` |
| Retrofit base | `BuildConfig.BASE_URL` via `NetworkModule` + optional `DynamicBaseUrlInterceptor` prefs rewrite |
| Phase 1C | Untouched |
| Backend bind | Remains `127.0.0.1:4010` (health 200); no Quasar code/bind changes |

### Device discovery (for Section D)

Intended device: **Samsung SM-T397U**.

`adb devices -l` during this stage showed **only**:

| Serial | Model |
|---|---|
| `0123456789ABCDEF` | Horizonpay K11 |
| `HE8558H000567` | Topwise MP45P |
| `192.168.1.145:45161` | Dspread D60 |

**SM-T397U was not present.** Per stage rules, `adb reverse` was **not** applied to any other device.

---

## B. Debug-only API configuration

### Change

`app/build.gradle`:

- **debug:** `BASE_URL` / `DEFAULT_SERVER_URL` → `http://127.0.0.1:4010/api/v1/` and `http://127.0.0.1:4010/`
- **release:** unchanged property-driven default (`LOCAL_IP`/`LOCAL_PORT` → currently `http://192.168.1.134:4000/api/v1/`)

Trailing slash: base ends with `/`; Retrofit paths are relative (`device/pds/...`) — correct.

### Generated BuildConfig verification

| Variant | `BASE_URL` |
|---|---|
| `launcherDebug` | `http://127.0.0.1:4010/api/v1/` |
| `launcherRelease` | `http://192.168.1.134:4000/api/v1/` |

No secrets, tokens, or credentials in BuildConfig.

---

## C. Debug-only cleartext permission

### Files added

| File | Purpose |
|---|---|
| `app/src/debug/res/xml/network_security_config.xml` | Cleartext **only** for `127.0.0.1` and `localhost` |
| `app/src/debug/AndroidManifest.xml` | Sets `android:networkSecurityConfig` on `<application>` |

### Explicit non-changes

- Did **not** set `android:usesCleartextTraffic="true"` on main/launcher manifests
- Release source sets have **no** network security config
- Certificate validation unchanged (no custom trust anchors)

### Merged manifest verification

**launcherDebug** (`app/build/intermediates/merged_manifests/launcherDebug/AndroidManifest.xml`):

- `android:networkSecurityConfig="@xml/network_security_config"` present
- No `android:usesCleartextTraffic="true"` attribute on `<application>`

**launcherRelease** (`…/launcherRelease/AndroidManifest.xml`):

- No `networkSecurityConfig`
- No `usesCleartextTraffic`

Packaged debug NSC confirms domain-scoped cleartext for loopback only.

---

## D. Physical-device connectivity — STOPPED

| Step | Result |
|---|---|
| Confirm SM-T397U via USB ADB | **FAIL — device not connected** |
| `adb reverse tcp:4010 tcp:4010` | **Not executed** |
| `adb reverse --list` | N/A |
| Device → `http://127.0.0.1:4010/health` via reverse | **Not verified** |
| Quasar bind unchanged | **PASS** — still `127.0.0.1:4010` only |

No LAN rebind / `0.0.0.0` workaround was attempted.

**Resume requirement:** Reconnect Samsung SM-T397U over USB, re-approve reverse verification, then continue.

---

## E. Build and verification

| Item | Result |
|---|---|
| `assembleLauncherDebug` | **SUCCESS** → `app-launcher-debug.apk` |
| `assembleLauncherRelease` | **SUCCESS** → `app-launcher-release.apk` |
| APK install | **Not performed** |
| PDS JVM tests | `:app:testLauncherDebugUnitTest --tests 'com.iips.launcher.security.pds.*'` → **55 tests, 0 failures, 0 errors** |
| Enrollment HTTP | **Not performed** |

---

## F. Security restrictions (observed)

- No enrollment-token retrieval/injection
- No credentials/tokens in source, BuildConfig, or resources
- No backend changes; no DB writes
- No push/merge
- Quasar remains loopback-only

---

## Files changed

| Path | Action |
|---|---|
| `app/build.gradle` | Modified — debug loopback URL |
| `app/src/debug/AndroidManifest.xml` | Added |
| `app/src/debug/res/xml/network_security_config.xml` | Added |
| `docs/architecture/PDS_STAGE_2_5B_CONNECTIVITY_CONFIGURATION.md` | Added (this report) |

Also present (prior stage, untracked): `docs/architecture/PDS_STAGE_2_5A_ANDROID_READINESS.md`

---

## Git status / HEAD

```
HEAD: c4fed4e (no new commit)
Branch: feat/phase-1d-android-enrollment
 M app/build.gradle
?? app/src/debug/
?? docs/architecture/PDS_STAGE_2_5A_ANDROID_READINESS.md
?? docs/architecture/PDS_STAGE_2_5B_CONNECTIVITY_CONFIGURATION.md
```

---

## Remaining blockers

| ID | Blocker | Severity |
|---|---|---|
| B-D1 | **SM-T397U not connected** — cannot complete `adb reverse` or on-device health check | **High** (blocks install/enrollment path for this stage’s intended device) |
| B-D2 | Other POS devices present (K11/MP45P/D60) were intentionally unused | Info |
| B-LOG | OkHttp BODY logging still enabled (Stage 2.5A finding; not changed here) | Medium (before live enrollment) |

---

## Recommended next steps (awaiting approval)

1. Connect Samsung **SM-T397U** via USB; confirm with `adb devices -l`.
2. Run `adb -s <serial> reverse tcp:4010 tcp:4010` and verify reverse list + device-side health fetch.
3. Approve APK install of `launcherDebug` onto that device only.
4. Later stage: token injection + enrollment (not authorized here).

**Stop.** No installation or enrollment performed.
