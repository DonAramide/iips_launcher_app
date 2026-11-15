# PDS Stage 2.5D.3 — First Controlled MP45P Enrollment

**Status:** COMPLETED WITH FAILURE — stop for review (no retry)  
**Date:** 2026-11-05  
**Android:** `C:\dev\Dotroid-phase-1d` / `feat/phase-1d-android-enrollment` / HEAD `c4fed4e` (+ local 2.5B–2.5D.3 deltas)  
**Device:** MP45P `HE8558H000567` / Android 8.1.0 / API 27 / package `com.iips.dotroid` `1.0.1` (v2)  
**Backend:** `127.0.0.1:4010` (loopback-only) / DB `pds_phase1d_integration`  
**Tenant:** `830cb9cf-5717-4b68-8e29-dfb28ce755f3` / `pds-phase1d-int` / **active**

---

## Verdict

Exactly **one network enrollment CONTINUE** was executed on MP45P.

| Hop | Outcome |
|---|---|
| 1. PDS `enroll/challenge` | **Succeeded** (challenge row + audit issued) |
| 2. PDS `enroll/verify` | **Failed — HTTP 403** |
| 3. Legacy `device/register` | **Not attempted** (fail-closed after verify) |

No device identity, device key, or MDM registration was created. Enrollment token remains unrevoked. **No automatic retry** was performed.

---

## 1. Device identity and APK

| Item | Value |
|---|---|
| ADB serial | `HE8558H000567` |
| Model | MP45P |
| Android / SDK | 8.1.0 / 27 |
| Hardware SN (UI) | `P332600114704` |
| Package | `com.iips.dotroid` |
| versionName / versionCode | `1.0.1` / `2` |
| Flags | DEBUGGABLE, TEST_ONLY |
| K11/D60 used | **No** |

### UI automation note (pre-network)

Immersive bottom-edge taps initially did not reach `btn_setup_continue`. CONTINUE was raised (`marginBottom=72dp`) and a non-secret click log was added. Prior no-op taps produced **zero** enroll traffic. One CONTINUE engaged validation with incomplete passwords (aborted locally, no network). The **authorized network attempt** is the CONTINUE that reached `DeviceEnrollmentManager` / verify.

Supporting glue (already in worktree for 2.5D.2 import): empty Enrollment Token field falls back to EncryptedSharedPreferences so the token is not shown on screen.

---

## 2. Preflight evidence

| Check | Result |
|---|---|
| Serial / model | MP45P `HE8558H000567` |
| APK package/version | `com.iips.dotroid` `1.0.1` (v2) |
| `adb reverse` | `(reverse) tcp:4010 tcp:4010` |
| Host `/health` | **200** Quasar OK |
| Bind | `TCP 127.0.0.1:4010 LISTENING` (PID 27652) — loopback only |
| Database | `current_database()=pds_phase1d_integration` |
| Tenant | `830cb9cf-…` / `pds-phase1d-int` / `status=active` |
| Valid tokens for tenant | **1** unrevoked (`expires_at` null) |
| Other-tenant tokens | **0** |
| Import status (presence-only) | `token_present=true`, `tenant_uuid=830cb9cf-5717-4b68-8e29-dfb28ce755f3` |
| Token on-screen | **No** (field left on hint) |
| OkHttp debug logging | **NONE** (no BODY; challenge/verify URLs absent from logcat) |

### Baseline counts (before CONTINUE)

| Table | Count |
|---|---|
| `devices` | 0 |
| `pds_device_keys` | 0 |
| `pds_enrollment_challenges` | 0 |
| `pds_identity_audit` | 0 |
| `do_mdm_devices` | 0 |

---

## 3. Onboarding fields (operator)

| Field | Value used |
|---|---|
| Business Name | `PDS Phase1D Integration` (local only; **not** used as tenant) |
| Admin password | Local app password entered (not recorded here) |
| Institutional / Agent Code | Left blank |
| Enrollment Token UI | Empty — used SecurePreferences import |
| PDS tenant UUID | Explicit `830cb9cf-5717-4b68-8e29-dfb28ce755f3` (not business-name / not `default`) |

---

## 4. Hop outcomes

### Hop 1 — Challenge

**Result:** Success (inferred from durable DB + subsequent verify call).

Evidence:

- `pds_enrollment_challenges`: **1** row  
  - `challenge_id=chl-128cd484-e882-40de-a121-bfe3ef929c2d`  
  - `tenant_id=830cb9cf-5717-4b68-8e29-dfb28ce755f3` (matches approved)  
  - `consumed=false`  
  - `issued_at=2026-11-05 17:33:19+00`  
  - `expires_at=2026-11-05 17:38:19+00`  
  - `public_key_fingerprint_sha256` null on challenge (expected for SPKI-less initiate)  
  - `consumed_by_device_id=null`
- `pds_identity_audit`: event `pds.enrollment.challenge_issued`, lifecycle `PENDING_ENROLLMENT`, same tenant

Device-side after attempt (presence only):

- `files/pds_enrollment.pending` present  
- `files/pds_key_attestation.bind` present  
- No `pds_enrollment.state` completion file observed

### Hop 2 — Verify

**Result:** **Failed**

```
SecurityException: PDS enrollment verification failed (HTTP 403)
  at PdsHttpEnrollmentTransport.verifyEnrollment (...:103)
```

| Item | Value |
|---|---|
| HTTP status | **403** |
| Machine `error_code` in app log | **Not surfaced** (transport maps non-409 failures to generic HTTP status) |
| Quasar success envelope | N/A |
| Device UUID returned | **None** |
| Key ID / fingerprint / lifecycle | **None persisted** |
| Public keys / signatures / token | **Not printed** (logging NONE; omitted from this report) |

**Stop condition honored:** no recovery/409 path; no second verify; no legacy register.

Likely next-review areas for 403 (not executed here): attestation acceptance on API 27 / MP45P, challenge signature transcript mismatch, or server-side forbidden classification. Challenge was **not** consumed, so a future approved retry may still use the same token after pending-state handling is reviewed.

### Hop 3 — Legacy MDM `device/register`

**Not started** — PDS verify failure fail-closed as designed.

---

## 5. After counts vs baseline

| Table | Before | After |
|---|---|---|
| `devices` | 0 | **0** |
| `pds_device_keys` | 0 | **0** |
| `pds_enrollment_challenges` | 0 | **1** (unconsumed) |
| `pds_identity_audit` | 0 | **1** (`challenge_issued` only) |
| `do_mdm_devices` | 0 | **0** |
| Valid enrollment tokens | 1 | **1** (unchanged) |

API database identity after attempt: still `pds_phase1d_integration`.

---

## 6. Security

| Control | Result |
|---|---|
| Debug OkHttp BODY | Still **NONE** — no request/response bodies in logcat |
| Token / JWT / private key / signatures in report | **Absent** |
| DPAPI source file | Unchanged (not rotated/revoked) |
| Token revoked | **No** |
| CONTINUE network attempts | **1** |

---

## 7. Partial local state (for review)

Left on MP45P after failed verify (not cleared by this stage):

- `files/pds_enrollment.pending`
- `files/pds_key_attestation.bind`

Server: one unconsumed challenge (expires ~5 minutes after issue; may already be expired by report time).

**Do not** delete/reset keystore or revoke token without a follow-up approved remediation stage.

---

## 8. Confirmation checklist

- [x] Exact MP45P only  
- [x] One network CONTINUE  
- [x] Challenge issued to approved tenant  
- [x] Verify failed HTTP 403 — stopped  
- [x] Legacy register not called  
- [x] No device/key rows created  
- [x] No auto-retry / no token rotation  
- [x] No push/merge/deploy  

---

## Recommended next stage (not started)

**Stage 2.5D.3-R / 2.5D.4 (approval required):** classify the verify **403** (`error_code` from Quasar error envelope), decide pending-challenge / Keystore bind handling on device, then either a single approved re-attempt or a new token issuance — only after review.

**Stop.**
