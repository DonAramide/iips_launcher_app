# PDS Stage 2.5D.1 — Enrollment Token Flow Inspection

**Status:** INSPECTION COMPLETE — awaiting review  
**Date:** 2026-11-05  
**Android worktree:** `C:\dev\Dotroid-phase-1d`  
**Branch:** `feat/phase-1d-android-enrollment` (git HEAD `c4fed4e`; local uncommitted Stage 2.5B/2.5C UI/connectivity deltas present)  
**Device (reference only; not exercised for CONTINUE):** MP45P `HE8558H000567` / Android 8.1 / API 27  
**Backend (reference only; no challenge/verify calls made):** Quasar `127.0.0.1:4010`  
**Tenant (non-secret):** `830cb9cf-5717-4b68-8e29-dfb28ce755f3` / `PDS Phase1D Integration` / slug `pds-phase1d-int`  
**Token custody:** host DPAPI `credential-handoff\pds.enrollment-token.dpapi` — **not decrypted, not retrieved, not injected**

---

## 1. Onboarding UI — Device & Business Setup

### Screen ownership

| Item | Finding |
|---|---|
| Activity | `com.iips.launcher.ui.OnboardingActivity` (`app/src/launcher/.../OnboardingActivity.kt`) |
| Layout | `app/src/main/res/layout/activity_onboarding.xml` — `ViewFlipper` step index 1 |
| Fragment / Compose | **None.** Classic View system; no Fragment, no Compose, **no ViewModel** |
| State | Activity-local `findViewById` + `lifecycleScope`; Hilt-injected `DeviceEnrollmentManager` |

Entry path observed in prior stages: `LauncherActivity` → `OnboardingActivity` when device is not registered.

### Fields on Device & Business Setup

| UI control | Resource ID | Role |
|---|---|---|
| Enrollment Token | `input_enrollment_token` / `enrollment_token_layout` | Required string; QR end-icon launches `QrScannerActivity` |
| Business Name | `input_business_name` | Required locally; persisted before network |
| Institutional Code (label) | `input_agent_code` / `agent_code_layout` | Optional UI; still API field `agent_code` |
| Admin / Confirm Password | `input_admin_password` / `input_confirm_password` | Local admin unlock only (SHA-256 into SecurePreferences) |
| CONTINUE | `btn_setup_continue` | Starts enrollment coroutine |

There is **no tenant UUID field** on this screen.

### What CONTINUE does (local → network)

```
OnboardingActivity.setupBusinessStep
  ├─ validate: token non-empty, business name non-empty, password ≥4 + match
  ├─ SecurePreferences.clearRegistrationDetails()
  ├─ setBusinessName / setAdminPassword(sha256) / setEnrollmentToken
  └─ startRegistrationProcess(token, btn, agentCode)
       └─ DeviceEnrollmentManager.enrollIfNeeded(token, agentCode)
            └─ PdsOnboardingEnrollmentFlow.execute(...)
                 ├─ PDS: challenge → Keystore key → sign transcript → verify
                 └─ Legacy MDM: POST device/register (same token + known_device_id)
```

- **Local validation only** for emptiness / password rules. There is **no** offline cryptographic validation of the PDS token format before network use.
- On success: `STATE_REGISTERED`, flipper → finish step (index 3).
- On failure: button re-enabled; Toast with exception message (may include HTTP/status text — token itself is not intentionally toasted).

### Is the Enrollment Token field PDS or legacy?

**Both — shared capability string.**

- The same value is stored under SecurePreferences key `enrollment_token`.
- It is passed into `PdsEnrollmentCoordinator.enroll` (PDS challenge + verify).
- The **same** value is then sent on legacy `POST device/register` (`EnrollmentRequest.enrollment_token`) together with `known_device_id` = PDS device UUID.
- Quasar `DeviceEnrollmentService.register` accepts tokens from **`device_enrollment_tokens`** first, then falls back to `mdm_provisioning_profiles`. Stage 2.4B.2 issued a PDS token into the durable token table (meta: `pds.enrollment-token.dpapi`), so this dual hop is the designed onboarding path — **not** a PDS-only field and **not** a legacy-only field.

Do **not** assume typing a token into the field alone is sufficient for PDS: **tenant UUID must also be present** (see §5).

---

## 2. PDS enrollment implementation status

### Components (present and wired)

| Component | Path | Role |
|---|---|---|
| Transport | `PdsHttpEnrollmentTransport` | Retrofit challenge/verify; Quasar envelope unwrap; 409 recovery via `errorBody` |
| Models | `PdsEnrollmentModels.kt` | Wire JSON with snake_case SerializedName |
| Coordinator | `PdsEnrollmentCoordinator` | Challenge resume, Keystore bind, transcript sign, verify, recovery |
| Crypto | `PdsEnrollmentCrypto` | Hardware fingerprint, attestation challenge decode, enroll/recover transcripts |
| Keys | `PdsDeviceKeyManager` / `PdsProductionEnrollmentWiring` | EC P-256 Android Keystore; SPKI = Base64(`PublicKey.encoded`) |
| State | `PdsEnrollmentStateStore`, `PdsPendingEnrollmentStore` | Durable PDS + pending challenge files under app `filesDir` |
| Orchestration | `PdsOnboardingEnrollmentFlow` | PDS enroll **then** legacy register; refuse non-UUID identity |
| Launcher glue | `DeviceEnrollmentManager.enrollIfNeeded` | Builds hardware attrs, wires transport, calls flow |
| Lock | `PdsEnrollmentProcessLock` | Exclusive enroll |
| Errors | `PdsEnrollmentHttpErrors` | `enrollment_already_completed` recovery parser |

### Does onboarding invoke PDS?

**Yes.** `OnboardingActivity` → `DeviceEnrollmentManager.enrollIfNeeded` → `PdsOnboardingEnrollmentFlow` → `PdsEnrollmentCoordinator.enroll` → `PdsHttpEnrollmentTransport` → `ConfigService.pdsEnrollmentChallenge` / `pdsEnrollmentVerify`.

Prior Stage 2.5C runtime: no challenge/verify in logcat (CONTINUE not pressed) — consistent with “wired but not exercised.”

### Challenge signing / transcript / SPKI (summary)

1. Challenge requested **without** device public key (`devicePublicKeySpkiB64 = null`).
2. Server returns `attestation_challenge_b64`; Keystore key generated bound to those bytes.
3. Transcript (`pds-enroll-challenge-v1|…`) hashes the **enrollment token** (SHA-256 hex of UTF-8), does not embed raw token:
   `challengeId|challengeNonce|tokenHash|tenantId|pubKeyFp|hardwareFp`.
4. Signature: Keystore `signPayload` → `challenge_signature_b64url`.
5. Verify body includes `device_public_key_spki_b64` from certificate public key encoding (X.509/SPKI DER → standard Base64).

### Error / envelope handling

- Success: `QuasarApiEnvelope` requires `responseCode` empty or `"00"` and non-null `data`.
- Verify HTTP 409 + `error_code=enrollment_already_completed` without recovery → `PdsEnrollmentNeedsRecoveryException` → recovery transcript `pds-enroll-recover-v1` + second verify with recovery fields.
- Recovery identity parsed only from `errorBody()` (R-01 remediation).

---

## 3. Backend contract alignment

Compared against Quasar `dotoid-device.controller` + `InitiatePdsEnrollmentChallengeInput` / `CompletePdsEnrollmentInput` (**no live requests**).

### `POST /api/v1/device/pds/enroll/challenge`

| Field | Android sends | Backend expects | Align |
|---|---|---|---|
| `enrollment_token` | yes | required | Yes |
| `tenant_id` | yes | required | Yes |
| `device_public_key_spki_b64` | null on first challenge | optional | Yes |
| `hardware` object | manufacturer, model, hardware_id, serial_number, android_version, sdk_int, build_fingerprint, pds_version, vendor, capability_profile | required hardware attrs | Yes |
| Response under Quasar `data` | challenge_id, challenge_nonce, attestation_challenge_b64, tenant_id, public_key_fingerprint_sha256, hardware_fingerprint_sha256, issued_at, expires_at | same | Yes |

### `POST /api/v1/device/pds/enroll/verify`

| Field | Android sends | Backend expects | Align |
|---|---|---|---|
| `challenge_id`, `enrollment_token`, `tenant_id` | yes | required | Yes |
| `device_public_key_spki_b64` | yes | required | Yes |
| `hardware` | yes | required | Yes |
| `challenge_signature_b64url` | yes | required | Yes |
| `attestation` map | optional local classification | optional | Yes |
| `recovery_nonce` / `recovery_signature_b64url` / `claimed_device_id` | recovery path only | optional | Yes |

Tenant association: Android refuses blank tenant before enroll; coordinator also checks challenge `tenant_id` equals requested tenant. Token↔tenant binding is enforced server-side.

### Follow-on legacy hop (same CONTINUE)

| Endpoint | Android | Backend |
|---|---|---|
| `POST /api/v1/device/register` | `EnrollmentRequest` including `enrollment_token`, `known_device_id` (PDS UUID), optional `agent_code`, `business_name`, location | `DeviceEnrollmentService.register` — prefers `device_enrollment_tokens`, else provisioning profile |

**Contract risk (operational, not field-name mismatch):** after PDS verify creates the device row, legacy register must still accept the **same** token from `device_enrollment_tokens` and locate the device via `known_device_id`. If token were MDM-profile-only or already revoked, PDS could succeed and MDM hop fail, leaving `pdsEnrollmentComplete=true` with onboarding Toast failure.

---

## 4. Secure token injection assessment

**Not performed.** Options ranked for a later approved stage.

### Constraints already in product

- Token must not live in source, `BuildConfig`, or APK resources.
- App persistence path today: AndroidX Security Crypto `EncryptedSharedPreferences` (`SecurePreferences.setEnrollmentToken`).
- Existing intake paths: QR extras, Device Owner / `PersistableBundle`, top-level Intent extras on **`LauncherActivity`** (`extractAndPersistProvisioningExtras`). `OnboardingActivity` is **not exported**.
- OkHttp interceptor is still `HttpLoggingInterceptor.Level.BODY` (`NetworkModule`) — **live enrollment would log request JSON including `enrollment_token`**.

### Options

| Method | Hardcode? | CLI/logcat/screenshot risk | Persistent plaintext? | Notes |
|---|---|---|---|---|
| A. `am start` LauncherActivity extras (`enrollment_token`, `tenant_id`, `backend_url`) | No | **Token in argv / shell history**; avoid `echo`; redacted scripts | Encrypted prefs only | Matches Stage 2.5A preferred path; works with existing code |
| B. QR → `QrScannerActivity` → field | No | Token **visible on screen**; UI dump risk | Prefs after CONTINUE/QR | Fine for lab; weak for “no screenshot” |
| C. Manual type into Enrollment Token | No | Visible on screen; soft keyboard | Prefs after CONTINUE | Same visibility issues; easy operator error |
| D. Debug `run-as` / instrumentation write to encrypted prefs | No | Lower argv exposure if carefully scripted | Encrypted prefs | No first-class helper today; higher build complexity |
| E. BuildConfig / resource / commit | Yes | N/A | N/A | **Disallowed** |

### Recommendation (for Stage 2.5D.2+, pending approval)

1. **Downgrade OkHttp logging** to `HEADERS` or `BASIC` before any injection or CONTINUE (mandatory gate).
2. Inject **non-secret** `tenant_id=830cb9cf-5717-4b68-8e29-dfb28ce755f3` and `backend_url=http://127.0.0.1:4010/api/v1/` via LauncherActivity extras (safe to script plainly).
3. Decrypt DPAPI **on host memory only**; inject token via Option **A** with redacted operator scripts (no chat/report echo), USB-only to MP45P; scrub host buffers/`PDS_*` env immediately.
4. Confirm prefs hold tenant UUID (**not** business-name fallback) before CONTINUE.
5. Prefer **not** relying on the on-screen token field for the first controlled run (reduces screenshot/UI-dump exposure), but the field remains a valid secondary path.
6. After success or abort: `clearProvisioningExtras` / clear enrollment token from prefs; retain host DPAPI only.
7. Ensure the value is never sent only to a legacy-only endpoint without the PDS hop — current CONTINUE always runs PDS first; do not invent alternate “register-only” shortcuts for this token.

Residual risk for A: Android debug `am` arguments are visible to local shell users on the device/host. Acceptable only for this isolated loopback lab POS under operator control.

---

## 5. Business onboarding vs PDS

| Question | Answer |
|---|---|
| Is Business Name required by legacy Dotroid onboarding UI? | **Yes** — CONTINUE refuses empty business name; stored hashed-admin + business name locally |
| Relationship of Business Name to PDS tenant? | **None invented.** Not a PDS challenge/verify field. Sent only on legacy `device/register` as `business_name` |
| Dangerous coupling | `SecurePreferences.getTenantId()` falls back to **business name** (or `"default"`) when `tenant_id` is missing/`default`. That string would be sent as PDS `tenant_id` and **will not** match UUID `830cb9cf-…` |
| Is Agent / Institutional Code required for PDS enrollment? | **No.** Not present on PDS wire models. Optional for legacy register; empty → Quasar may resolve `GLOBAL_AGENT_CODE` when using `device_enrollment_tokens` without bound `agent_id` |
| Can PDS enrollment complete independently of legacy business setup? | **Protocol:** PDS coordinator can enroll with token+tenant+hardware alone. **Product CONTINUE path:** cannot — UI requires business name + admin password, then `PdsOnboardingEnrollmentFlow` **always** performs legacy register after PDS. A PDS-only completion would need a different/approved code path (not present as a user action today) |

Do not map business name or agent/institutional code to the tenant UUID.

---

## 6. UI → service call flow (condensed)

```
[Welcome] GET STARTED
    → ViewFlipper Device & Business Setup
[CONTINUE]
    → local field validation
    → EncryptedSharedPreferences: business_name, admin_password_hash, enrollment_token
    → DeviceEnrollmentManager.enrollIfNeeded(token, agentCode?)
         → require tenant_id from SecurePreferences.getTenantId()
         → PdsEnrollmentCoordinator.enroll
              → POST device/pds/enroll/challenge
              → Keystore key + sign transcript
              → POST device/pds/enroll/verify
         → POST device/register (legacy MDM token exchange + access_token)
    → STATE_REGISTERED / Finish UI
```

Connectivity prerequisites already satisfied in 2.5B/2.5C for MP45P: debug `BASE_URL` `http://127.0.0.1:4010/api/v1/`, debug NSC cleartext for loopback, `adb reverse tcp:4010`.

---

## 7. Missing / incomplete for first controlled enrollment

| ID | Gap | Severity |
|---|---|---|
| G-1 | **Tenant UUID not on device UI** and not injected yet; business-name fallback will poison PDS `tenant_id` | **Blocker** |
| G-2 | Enrollment token still only in host DPAPI (by design) | Expected |
| G-3 | OkHttp **BODY** logging still enabled — token leakage on first call | **Blocker** before live enroll |
| G-4 | No ViewModel/single-flight UI state beyond button disable; errors surface as Toasts | Low |
| G-5 | Dual-hop CONTINUE: if legacy register fails after PDS verify, local PDS state may already be complete while UI reports failure | Medium (ops) |
| G-6 | Institutional Code default text `AAA000` in layout may be sent as `agent_code` if left unchanged | Low (legacy only) |

---

## 8. Recommended next execution stage

**Stage 2.5D.2 — Pre-enrollment hardening + secure injection (no challenge/verify yet)**

Suggested scope (approval required):

1. Downgrade debug OkHttp logging off BODY.
2. Decrypt DPAPI on host → inject `tenant_id` + token (+ confirm backend URL) into MP45P via LauncherActivity extras; scrub host memory.
3. Verify on-device: tenant UUID present, token present in encrypted prefs (presence only; never print), reverse still active, no challenge/verify traffic.
4. Stop for review.

**Stage 2.5D.3 — Single controlled CONTINUE / challenge→verify** only after 2.5D.2 review.

---

## Explicit non-actions (this stage)

- Did not decrypt or retrieve the DPAPI enrollment token  
- Did not inject or submit any token  
- Did not press CONTINUE on MP45P  
- Did not call `pds/enroll/challenge` or `pds/enroll/verify`  
- No database writes  
- No code modifications or commits for this stage  
- No backend configuration changes  
- Did not alter the working MP45P installation for enrollment  
- No staging or production access  

**Stop — awaiting review.**
