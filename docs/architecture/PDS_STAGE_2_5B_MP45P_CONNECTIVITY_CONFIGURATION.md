# PDS Stage 2.5B — MP45P Physical POS Connectivity Configuration

**Status:** SUCCESS (configuration + reverse + build/verify). APK **not** installed.  
**Date:** 2026-11-05  
**Android:** `C:\dev\Dotroid-phase-1d` / `feat/phase-1d-android-enrollment` / HEAD `c4fed4e`  
**Backend:** `C:\dev\quasar-phase-1d-2` — **unchanged**; API `127.0.0.1:4010`; DB `pds_phase1d_integration`  
**Primary device:** Topwise **MP45P** `HE8558H000567` (Android 8.1 / API 27, USB)  
**Excluded:** K11 (API 25); no `minSdk` change; no reverse for K11/D60

---

## 1. Preflight

| Check | Result |
|---|---|
| Branch | `feat/phase-1d-android-enrollment` |
| HEAD | `c4fed4e` |
| Working tree | Not empty: prior Stage 2.5B debug URL/NSC + readiness docs (expected continuation). No unexpected source churn. |
| MP45P `get-state` | `device` |
| Model / Android / SDK | **MP45P** / **8.1.0** / **API 27** |
| Quasar `/health` | **200** |
| Quasar bind | **`127.0.0.1:4010` only** (not `0.0.0.0`) |
| DB `current_database()` | **`pds_phase1d_integration`** |
| Reverse on 4010 (before) | **None** on MP45P, K11, or D60 |
| `minSdk` | **26** (unchanged) |

---

## 2. Files changed (Android worktree)

| Path | Role |
|---|---|
| `app/build.gradle` | Debug `BASE_URL` → `http://127.0.0.1:4010/api/v1/`; release keeps property-driven default |
| `app/src/debug/AndroidManifest.xml` | Debug-only `networkSecurityConfig` |
| `app/src/debug/res/xml/network_security_config.xml` | Cleartext **only** for `127.0.0.1` / `localhost` |
| `docs/architecture/PDS_STAGE_2_5B_MP45P_CONNECTIVITY_CONFIGURATION.md` | This report |

Backend files: **none**.

---

## 3. Debug API URL configuration

| Variant | `BuildConfig.BASE_URL` | `DEFAULT_SERVER_URL` |
|---|---|---|
| `launcherDebug` | `http://127.0.0.1:4010/api/v1/` | `http://127.0.0.1:4010/` |
| `launcherRelease` | `http://192.168.1.134:4000/api/v1/` | `http://192.168.1.134:4000/` |

- Trailing slash on base URL: **yes** (Retrofit-compatible).
- Production/default release URL preserved via `LOCAL_IP`/`LOCAL_PORT`.
- No credentials or enrollment tokens in BuildConfig.

Retrofit continues to use `BuildConfig.BASE_URL` (`NetworkModule`).

---

## 4. Merged manifest verification

| Variant | `networkSecurityConfig` | `usesCleartextTraffic="true"` |
|---|---|---|
| `launcherDebug` | **Present** (`@xml/network_security_config`) | **Absent** (domain-scoped NSC only) |
| `launcherRelease` | **Absent** | **Absent** |

Certificate validation not weakened (no custom trust anchors).

---

## 5. ADB reverse (MP45P only)

```text
adb -s HE8558H000567 reverse tcp:4010 tcp:4010
```

| Check | Result |
|---|---|
| `adb -s HE8558H000567 reverse --list` | `(reverse) tcp:4010 tcp:4010` |
| K11 reverse list | Empty |
| D60 reverse list | Empty |
| Host bind after reverse | Still **`127.0.0.1:4010` only** |
| Device netstat | `127.0.0.1:4010 LISTEN` (reverse proxy) + client `TIME_WAIT` to `:4010` |
| Device → health via reverse | **PASS** — temporary busybox `wget` returned `{"status":"ok","service":"QUASAR",...}` (tool removed from device afterward) |
| Host `/health` | Still **200** |

No LAN / `0.0.0.0` exposure introduced.

---

## 6. Build and tests

| Task | Result |
|---|---|
| `:app:assembleLauncherDebug` | **SUCCESS** → `app/build/outputs/apk/launcher/debug/app-launcher-debug.apk` |
| `:app:compileLauncherDebugKotlin` | **SUCCESS** (UP-TO-DATE) |
| `:app:testLauncherDebugUnitTest --tests 'com.iips.launcher.security.pds.*'` | **55 tests, 0 failures, 0 errors** |
| APK install | **Not performed** |
| Packaged secret scan (text resources) | **0** hits for enrollment-token / bcrypt material |

---

## 7. Git status / HEAD

```
HEAD: c4fed4e (no commit created this stage)
Branch: feat/phase-1d-android-enrollment

 M app/build.gradle
?? app/src/debug/
?? docs/architecture/PDS_STAGE_2_5A_*.md
?? docs/architecture/PDS_STAGE_2_5B_CONNECTIVITY_CONFIGURATION.md
?? docs/architecture/PDS_STAGE_2_5B_MP45P_CONNECTIVITY_CONFIGURATION.md
```

---

## 8. Remaining blockers (next stage)

| ID | Item |
|---|---|
| N-1 | APK not yet installed on MP45P |
| N-2 | Enrollment token not injected (DPAPI handoff retained on host) |
| N-3 | OkHttp BODY logging still enabled (reduce before live enrollment) |
| N-4 | Challenge/verify enrollment not authorized yet |

---

## Explicit non-actions

- No APK install / Dotroid launch for enrollment  
- No enrollment-token decrypt/inject  
- No challenge/verify HTTP  
- No DB writes / backend changes / minSdk / K11 work  
- No Phase 1C / main-repo / push / merge  

**Stop — awaiting approval for Stage 2.5C installation on MP45P.**
