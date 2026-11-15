# PDS Stage 2.5D.2 — Pre-enrollment Hardening and Secure Token Injection

**Status:** COMPLETE (including Stage **2.5D.2-R** MP45P import verification)  
**Date:** 2026-11-05  
**Android:** `C:\dev\Dotroid-phase-1d` / `feat/phase-1d-android-enrollment` / HEAD `c4fed4e` (+ local Stage 2.5B–2.5D.2 changes)  
**Target device:** MP45P `HE8558H000567` / Android 8.1.0 / API 27  
**Backend (unchanged):** `http://127.0.0.1:4010/api/v1/` / DB `pds_phase1d_integration`  
**Tenant (non-secret):** `830cb9cf-5717-4b68-8e29-dfb28ce755f3`  
**DPAPI source:** `credential-handoff\pds.enrollment-token.dpapi` — **unchanged** (SHA-256 `A1B1F17D939E5BD76139B4FEE575DF4BDFB31E0ADC1BD7A46BEADA23B5F2E07E`, length 620)

---

## Summary

| Area | Result |
|---|---|
| A. OkHttp BODY logging removed | **Done** — debug `NONE`, release `BASIC` |
| B. PDS tenant UUID fail-closed | **Done** — no business-name / `"default"` |
| C. Debug-only secure file import | **Done** — imported on MP45P (2.5D.2-R) |
| D. Dual-hop safeguards | **Documented + covered by tests** — PDS failure skips legacy |
| E. MP45P verification | **Done** — see §2.5D.2-R below |
| F. Tests + `launcherDebug` APK | **Done** |
| G. Worktree / no commit | **Preserved**; no commit |

**No** challenge, verify, or `device/register` requests were sent. **CONTINUE was not pressed.**

---

## A. HTTP logging protection

### Change
- Added `OkHttpLoggingPolicy` (`app/src/main/java/.../OkHttpLoggingPolicy.kt`).
- `NetworkModule.provideOkHttpClient` now sets:
  - **Debug:** `HttpLoggingInterceptor.Level.NONE`
  - **Release:** `HttpLoggingInterceptor.Level.BASIC` (method + URL only)
- **Never** `BODY` or `HEADERS` (avoids token / signature / Authorization leakage).
- Secondary OkHttp client in `RemoteCommandExecutionEngine` (APK download) had **no** logging interceptor — unchanged.
- TLS / NSC cleartext policy untouched (debug loopback NSC from 2.5B preserved).

### Tests
`OkHttpLoggingPolicyTest` — asserts debug=`NONE`, release=`BASIC`, and `forbidsBodyLogging` rejects BODY/HEADERS.

---

## B. Tenant identity (fail-closed)

### Change
- `PdsTenantIdentity.requireExplicitTenantUuid` — hex UUID shape only; rejects blank, `"default"`, business names, agent codes.
- `SecurePreferences.getExplicitTenantId` / `hasExplicitTenantId` / `hasEnrollmentToken` — raw prefs, **no** business-name fallback.
- Legacy `getTenantId()` (business-name fallback) **preserved** for non-PDS telemetry callers.
- `DeviceEnrollmentManager.enrollIfNeeded` and `PdsOnboardingEnrollmentFlow.execute` both require explicit tenant UUID **before** any `pdsEnroll` call (no challenge on failure).
- Debug BuildConfig (non-secret):
  - `PDS_PHASE1D_TENANT_ID = "830cb9cf-5717-4b68-8e29-dfb28ce755f3"`
  - `PDS_DEBUG_CREDENTIAL_IMPORT = true`
- Release BuildConfig:
  - `PDS_PHASE1D_TENANT_ID = ""`
  - `PDS_DEBUG_CREDENTIAL_IMPORT = false`
  - `BASE_URL` remains `http://192.168.1.134:4000/api/v1/` (unchanged integration default)

### Tests
`PdsTenantIdentityTest` + onboarding test `business name tenant is rejected before pds or legacy network hops`.

---

## C. Secure token handoff design

### Device (debug source set only)
- `app/src/debug/java/.../PdsDebugCredentialImport.kt`
  - Reads `files/pds_import/enrollment_token.pending`
  - Writes token into EncryptedSharedPreferences via `SecurePreferences.setEnrollmentToken`
  - Seeds tenant UUID from `BuildConfig.PDS_PHASE1D_TENANT_ID`
  - Pins debug `BASE_URL` into provisioning/backend prefs
  - Overwrites + deletes pending file
  - Writes presence-only `files/pds_import/import_status.txt` (`token_present=…`, `tenant_uuid_present=…`, **never** the token)
- Invoked from `LauncherApplication.onCreate` only when `PDS_DEBUG_CREDENTIAL_IMPORT` (Class.forName; absent/no-op in release).
- **No** exported Activity, BroadcastReceiver, or ContentProvider for import.

### Host (outside repo)
Operator script (does not live in the Android worktree):

`…\AgentStores\…\credential-handoff\pds-2.5d2-inject-token.ps1`

Flow: DPAPI decrypt in memory → restrictive ACL temp under `%TEMP%\pds-phase1d-2.5d2\` → ADB stdin pipe into `run-as com.iips.dotroid` pending file → wipe host temp → force-stop/start LauncherActivity → print **status marker only**.

Initially blocked (MP45P offline). **Executed in 2.5D.2-R** on MP45P only; K11 intentionally unused.

---

## D. Dual-hop onboarding safeguards

Order remains:

1. PDS challenge → verify (`PdsEnrollmentCoordinator`)
2. Legacy `POST device/register` with same token + `known_device_id`

| Condition | Behavior |
|---|---|
| Invalid/missing tenant UUID | Local `SecurityException`; **no** challenge |
| PDS enroll throws | Legacy register **not** called (fail-closed) |
| PDS succeeds, legacy fails | `pdsEnrollmentComplete=true`, `mdmRegistrationComplete=false`; UI Toast failure; retry can reuse PDS UUID / 409 recovery |
| Business name / agent code | Unchanged semantics; not mapped to tenant |

Token is not sent on a legacy-only shortcut from CONTINUE — PDS always runs first when enrollment proceeds.

---

## E. Device-side verification (MP45P)

Completed in **Stage 2.5D.2-R** (see that section). Summary: MP45P identity confirmed; hardened APK installed; token present in encrypted prefs; tenant UUID matches approved value; plaintext staging cleared; no challenge/verify/register in logcat.

---

## F. Build and tests

### Unit tests (focused) — **PASS**
- `OkHttpLoggingPolicyTest`
- `PdsTenantIdentityTest`
- `PdsPhase1C3OnboardingFlowTest` (incl. business-name rejection / PDS-fail skips legacy)
- `PdsHttpEnrollmentTransportEnvelopeTest`
- `PdsQuasarEnvelopeParsingTest`
- `PdsPhase1D4EnrollmentTest`
- `PdsPhase1D4R01RecoveryTest`

### Artifacts
- `assembleLauncherDebug` — **SUCCESS**
- APK: `app\build\outputs\apk\launcher\debug\app-launcher-debug.apk` (~34.9 MB)
- Package `com.iips.dotroid`, minSdk 26, debuggable/testOnly
- DEX scan: no `BEGIN RSA/EC PRIVATE`; non-secret tenant UUID present in debug; `PdsDebugCredentialImport` present in debug DEX
- `compileLauncherReleaseKotlin` — **SUCCESS** with import flag false / empty tenant
- Debug merged manifest retains loopback `networkSecurityConfig`; release merged manifest has **no** debug NSC
- No new exported import surface in manifests

---

## Files changed (this stage + preserved prior local work)

### New
- `app/src/main/java/com/iips/launcher/network/OkHttpLoggingPolicy.kt`
- `app/src/main/java/com/iips/launcher/security/pds/PdsTenantIdentity.kt`
- `app/src/debug/java/com/iips/launcher/security/pds/PdsDebugCredentialImport.kt`
- `app/src/test/java/com/iips/launcher/network/OkHttpLoggingPolicyTest.kt`
- `app/src/test/java/com/iips/launcher/security/pds/PdsTenantIdentityTest.kt`
- Host script (agent-store, outside repo): `pds-2.5d2-inject-token.ps1`

### Modified
- `app/build.gradle` — debug/release BuildConfig tenant + import flags
- `app/src/main/java/.../NetworkModule.kt`
- `app/src/main/java/.../SecurePreferences.kt`
- `app/src/main/java/.../PdsOnboardingEnrollmentFlow.kt`
- `app/src/launcher/java/.../DeviceEnrollmentManager.kt`
- `app/src/launcher/java/.../LauncherApplication.kt`
- `app/src/test/.../PdsPhase1C3OnboardingFlowTest.kt`

### Preserved local (not reverted)
- Stage 2.5B debug URL/NSC, onboarding layout/dimens, prior architecture docs

---

## Git status (no commit)

```
feat/phase-1d-android-enrollment @ c4fed4e
 M app/build.gradle
 M …/LauncherApplication.kt
 M …/DeviceEnrollmentManager.kt
 M …/NetworkModule.kt
 M …/PdsOnboardingEnrollmentFlow.kt
 M …/SecurePreferences.kt
 M …/activity_onboarding.xml
 M …/PdsPhase1C3OnboardingFlowTest.kt
?? app/src/debug/
?? OkHttpLoggingPolicy.kt / PdsTenantIdentity.kt / tests / dimens / docs (2.5A–2.5D.2)
```

---

## Stage 2.5D.2-R — MP45P reconnection and secure credential import

### Device identity

| Check | Result |
|---|---|
| Serial | `HE8558H000567` |
| Model | `MP45P` |
| Android / SDK | 8.1.0 / 27 |
| USB state | `device` (authorized) |
| Package | `com.iips.dotroid` |
| K11 / D60 used? | **No** (K11 remained attached; no install/inject to it) |

### Connectivity / backend

| Check | Result |
|---|---|
| Host `GET http://127.0.0.1:4010/health` | **200** `{"status":"ok","service":"QUASAR",...}` |
| API process | PID `27652` alive |
| `adb reverse` | `(reverse) tcp:4010 tcp:4010` |
| Database identity | Unchanged target `pds_phase1d_integration` (tenant meta Stage 2.4B.2; no Quasar config edits this stage) |
| Device HTTP probe via `toybox nc` | Empty body on this POS image (no wget/curl); reverse + host health accepted as connectivity evidence |

### Installed APK

| Attribute | Value |
|---|---|
| Worktree APK | `app\build\outputs\apk\launcher\debug\app-launcher-debug.apk` |
| Install | `adb -s HE8558H000567 install -r -t` (**Success**, data preserved) |
| versionName / versionCode | `1.0.1` / `2` |
| Flags | `DEBUGGABLE`, `TEST_ONLY` |
| minSdk / targetSdk | 26 / 34 |

### Secure import result

Host script: agent-store `credential-handoff\pds-2.5d2-inject-token.ps1` (same Stage 2.5D.2 mechanism).

Transport note (API 27): stdin pipe into `adb shell`/`run-as` failed to create the pending file; script uses brief `adb push` → `/data/local/tmp/pds_enroll_stage.pending` → `run-as cat` into app-private `files/pds_import/enrollment_token.pending` → immediate `rm` of tmp hop. Token never on `am start` argv.

| Step | Result |
|---|---|
| DPAPI decrypt in host memory | Yes (not printed) |
| Host ACL temp under `%TEMP%\pds-phase1d-2.5d2\` | Created then wiped; dir empty after |
| App-private pending consumed | Yes |
| Encrypted prefs `token_present` | **true** |
| Status marker | `files/pds_import/import_status.txt` |
| DPAPI source SHA-256 | Unchanged `A1B1F17D…E07E` / len 620 |

Presence-only status after cold start (sanitized):

```
token_present=true
tenant_uuid_present=true
tenant_uuid=830cb9cf-5717-4b68-8e29-dfb28ce755f3
pending_existed=false
staging_cleared=true
```

### Tenant UUID verification

| Check | Result |
|---|---|
| Stored explicit tenant | `830cb9cf-5717-4b68-8e29-dfb28ce755f3` |
| Matches approved UUID | **Yes** |
| Equals `"default"` | **No** |
| Business-name shaped | **No** |
| Seed source | Debug `BuildConfig.PDS_PHASE1D_TENANT_ID` via `PdsDebugCredentialImport` (non-secret) |

### Plaintext cleanup evidence

| Location | Result |
|---|---|
| `files/pds_import/enrollment_token.pending` | **Absent** (only `import_status.txt` remains) |
| `/data/local/tmp/pds_enroll_stage.pending` | **Absent** |
| Host `%TEMP%\pds-phase1d-2.5d2\` token file | **Absent** (dir empty) |

### Sanitized log review

Logcat on MP45P after import/cold start (counts):

| Pattern | Hits |
|---|---|
| `pds/enroll/challenge` | 0 |
| `pds/enroll/verify` | 0 |
| `device/register` | 0 |
| `HttpLoggingInterceptor` / `--> END POST` / `Level.BODY` | 0 |
| `enrollment_token` / `challenge_signature` | 0 |

Debug OkHttp policy remains `NONE` (code + no BODY artifacts in logcat).

### Enrollment side-effects

| Check | Result |
|---|---|
| CONTINUE pressed | **No** |
| Challenge / verify / register requests | **None observed** |
| Device enrollment DB record created | **No** (no enroll traffic; API/DB config untouched) |

---

## Remaining before Stage 2.5D.3

1. Operator approval for a **single** controlled CONTINUE on MP45P only.
2. Keep `adb reverse tcp:4010 tcp:4010` and Quasar on `127.0.0.1:4010`.
3. Expect dual-hop: PDS challenge→verify then legacy `device/register`.
4. Do not rotate/revoke the DPAPI token unless a failure requires a new issuance.

---

## Explicit non-actions (2.5D.2 + 2.5D.2-R)

- Token contents / JWTs / private keys not printed in chat or this report
- No challenge / verify / `device/register`
- No CONTINUE
- No intentional DB writes / token rotation / revocation
- No Quasar or Phase 1C baseline edits
- No push / merge / commit
- No install or inject on K11 or D60

**Stop — device verification complete. Awaiting approval for Stage 2.5D.3.**
