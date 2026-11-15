# PDS Stage 2.5D.3S — MP45P Attestation Hard-Reject Investigation

**Status:** COMPLETE — investigation only (no remediation / no recovery executed)  
**Date:** 2026-11-05  
**Prior:** Stage 2.5D.3 (verify HTTP 403), Stage 2.5D.3-R (forensic)  
**Android:** `C:\dev\Dotroid-phase-1d`  
**Backend:** `C:\dev\quasar-phase-1d-2`  
**Device:** MP45P `HE8558H000567` (API 27)  
**DB:** `pds_phase1d_integration`  
**Tenant:** `830cb9cf-5717-4b68-8e29-dfb28ce755f3`  
**Challenge:** `chl-128cd484-e882-40de-a121-bfe3ef929c2d`

**Restrictions honored:** no key delete/regen; no challenge/verify/CONTINUE; no token rotation; no DB writes; no policy relaxation; no deploy/merge; diagnostic improvements proposed only (not implemented).

---

## Verdict

The verify **HTTP 403** maps to durable  
`PdsEnrollmentHttpError.forbidden('attestation_failed: <reject reasons>')`  
after ECDSA proof verification. Soft/incomplete attestation under default **`hardware_relaxed`** returns **`valid: true`**, so this failure requires a **`PdsAttestationService.reject()`** branch.

Production Nest wires a bare `PdsAttestationService` with **`applyPolicy({})` → empty trust roots**. That makes full-chain hardware verification unreachable and **eliminates** the post-anchor `verified_boot_state` hard-reject for this runtime. The closed set of reachable hard-rejects for this attempt is therefore narrow (see §1.3).

The **exact reason string was not retained** (server does not WARN on reject; Android discards non-409 `error_code`). Live Keystore chain inspection this stage was blocked when MP45P disconnected mid-probe; prior bind/pending evidence remains valid.

**Recovery (not executed):** existing key **cannot** be rebound to a new attestation challenge; expired pending must be discarded under an **authorized key replacement**; a **fresh challenge** is required. Backend policy changes are **not** required for recovery; sanitized diagnostics are recommended before the next attempt.

---

## 1. Backend rejection analysis

### 1.1 Call path

```
POST /api/v1/device/pds/enroll/verify
  → DotoidDeviceController.verifyPdsEnrollment
  → PdsDeviceIdentityService.completeEnrollmentVerification
  → PdsDurableEnrollmentService.completeVerification
       validateToken / validateHardware
       load challenge (FOR UPDATE)
       token/tenant hash check
       expiry / pubkey parse / hardware fingerprint
       verifyDeviceEcdsaSignature          ← failure would be 401
       PdsAttestationService.evaluateAttestation
       if (!valid) throw PdsEnrollmentHttpError.forbidden('attestation_failed: …')
  → QuasarApiExceptionFilter → HTTP 403, responseCode 03, data.error_code preserved
```

### 1.2 `hardware_relaxed` / soft vs hard

| Path | `valid` | Effect on verify |
|---|---|---|
| `security_level === 'unsupported'` / missing evidence | **true** (`softResult`) | Accept (unverified tier) |
| Empty certificate chain | **true** (`downgradeIncomplete`) | Accept |
| Untrusted / empty trust store / missing KeyDescription / incomplete chain | **true** (`downgradeIncomplete`) | Accept |
| Unrecognized POS model after full verify | **true** (`softResult`) | Accept |
| Any `reject(reason)` | **false** | **403** `attestation_failed: …` |

Tenant does not send `tenant_minimum_attestation`; durable defaults to **`hardware_relaxed`**. Tenant `metadata` is empty. Soft acceptance still yields limited capabilities / `REGISTERED` lifecycle when not `software` policy — it does **not** 403.

Topwise catalog models (`T1`, `MP35P`, `T3`, `M3`) **exclude `MP45P`**. That only blocks `hardware_relaxed` **tier upgrade**, not enrollment validity under soft paths.

### 1.3 Trust roots (this runtime)

| Item | Finding |
|---|---|
| Constructor | `new PdsAttestationService()` → `applyPolicy({})` |
| Nest module | Provides `PdsAttestationService` with **no** `trustedRootPems` / `trustStore` load |
| Effect | `trustedRoots.length === 0` → `attestation_trust_store_empty_fail_closed` reason; `chainAnchoredToTrustedRoot` false; **`chainVerified` never true** |
| Consequence | Code after the “full chain + KD + challengeMatched” gate (app-id, root-of-trust, **verified_boot hard-reject**) is **unreachable** in this deployment |

Google roots exist in `pds-attestation-google-roots.ts` / `PdsAttestationTrustStore` for tests/operators but are **not** applied to the running Phase 1D integration API process inspected here.

### 1.4 Complete hard-reject catalog (`reject()`)

From `pds-attestation.service.ts` `evaluateAttestation`:

| # | Reason string | Reachable with empty trust store + valid EC key + prior ECDSA OK? |
|---|---|---|
| R1 | `Device public key must be EC P-256` | No (signature already verified) |
| R2 | `Malformed device_public_key_spki_b64` | No (same) |
| R3 | `Attestation challenge does not match issued enrollment challenge` | Possible if wire `attestation_challenge_b64` ≠ DB bytes |
| R4 | `Invalid base64 in attestation_challenge_b64` | Possible if corrupted encoding |
| R5 | `Attestation certificate chain parsing failed: …` | **Yes** — malformed OEM DER |
| R6 | `Attestation leaf certificate public key does not match registered device public key` | **Yes** — leaf SPKI ≠ submitted SPKI |
| R7 | `attestation_certificate_revoked:<serial>` | Unlikely (empty local revoke set; no remote list) |
| R8 | `attestation_certificate_revoked\|suspended:<serial>` (remote) | No remote list configured |
| R9 | `attestation_certificate_expired_or_not_yet_valid` | **Yes** — clock skew / cert window |
| R10 | `KeyDescription attestationChallenge does not match issued enrollment challenge` | **Yes** — KD present but challenge bytes ≠ server challenge (**leading candidate on API 27 OEM**) |
| R11 | `Device verified_boot_state is unverified/unlocked` | **No** in this runtime (requires `chainVerified`) |

### 1.5 Ranked causes for the MP45P attempt

Given: ECDSA passed (otherwise 401); pending attestation challenge **equals** DB bytea (2.5D.3-R); Android echoes server challenge into evidence; production trust store empty.

| Rank | Branch | Why |
|---|---|---|
| 1 | **R10 KeyDescription challenge mismatch** | Only fires when attestation extension parses **and** embedded challenge ≠ expected; does **not** soft-downgrade |
| 2 | **R6 Leaf SPKI mismatch** | OEM attestation leaf not binding the enrolled public key |
| 3 | **R5 Chain parse failure** | Non-X.509 / corrupt chain entries |
| 4 | **R9 Cert not yet valid / expired** | Device clock vs cert validity |
| 5 | **R3/R4 Evidence challenge field** | Weakly excluded by pending↔DB equality if the same string was sent |

### 1.6 Exception mapping & why the reason was not retained

| Layer | Behavior |
|---|---|
| Throw | `PdsEnrollmentHttpError.forbidden(\`attestation_failed: ${reasons.join(', ')}\`)` — full reason **is** in `error_code` |
| Filter | `QuasarApiExceptionFilter` copies `error_code` into Quasar `data` and `responseMessage` |
| API logs | Durable service logs challenge issue only; **no WARN/ERROR on attestation reject** |
| Android | `PdsHttpEnrollmentTransport` parses `error_code` but on non-409 only throws `HTTP ${code}` — **drops** the code |
| OkHttp | Debug logging **NONE** — bodies not in logcat |

The classification **was generated and returned**; it was **not persisted** on either side.

---

## 2. Android attestation evidence

### 2.1 Binding / pending (read-only; confirmed 2.5D.3-R + rechecked files while device was online)

| Item | Result |
|---|---|
| Alias (expected) | `pds_device_key_v1` |
| `files/pds_key_attestation.bind` | Present; 64-hex = SHA-256(server attestation challenge) |
| `files/pds_enrollment.pending` | Present; challenge `chl-128cd484-…`; expired |
| Pending ↔ DB attestation challenge | **Byte-identical** |
| Enroll signature | Present in pending (local ECDSA completed) |
| Pubkey fingerprint on pending | Empty (SPKI-less challenge) |

### 2.2 Live Keystore metadata probe (this stage)

An ephemeral, non-mutating public-metadata probe (KeyInfo + cert fingerprints + KeyDescription OID presence + challenge-byte containment) was prepared and pushed, but **MP45P disconnected before execution completed**. Probe artifacts were intended for `/data/local/tmp` + app `files/` only; **no Keystore mutation** was performed. Device was offline at end of stage (only K11 attached).

| Desired measurement | Status |
|---|---|
| Hardware-backed (`KeyInfo.isInsideSecureHardware`) | **Not re-measured** this stage |
| Certificate chain length | **Not re-measured** |
| KeyDescription OID `1.3.6.1.4.1.11129.2.1.17` present | **Not re-measured** |
| KD contains server challenge bytes | **Not re-measured** |
| Chain vs backend trust roots | Backend trust roots **empty** → no chain can anchor regardless of device certs |

**Inference from generation path:** `PdsDeviceKeyManager.generateKeyPairInternal` always calls `setAttestationChallenge`. On API 27, successful generation typically yields at least a leaf cert; collector would classify `vendor_hardware` (hw + chain len 1) or `tee` (hw + chain len > 1) or `software` / `unsupported`. Soft paths for unsupported/empty chain would **not** 403 — consistent with a **non-empty chain that entered a hard-reject**.

### 2.3 Protocol notes (no private material)

- Attestation challenge on wire: standard Base64 (pending contains `+`).  
- Collector sends `security_level` lowercase enum names matching backend `AttestationSecurityLevel`.  
- Chain elements: standard Base64 of `Certificate.getEncoded()` (public certs only).

---

## 3. Safe diagnostic improvement (proposal only — not implemented)

### 3.1 Smallest backend change

In `PdsDurableEnrollmentService.completeVerification`, immediately before throwing on `!attestation.valid`, emit a **WARN** log with a fixed sanitized object:

```
pds_enrollment_attestation_rejected
  challenge_id
  tenant_id
  reasons[]          // already non-secret machine strings
  trust_tier
  security_level
  chain_verified
  challenge_matched
  cert_chain_sha256  // hash of b64 strings already computed — not raw certs
  certificate_count  // length only
  min_policy
```

**Do not log:** enrollment token, signatures, SPKI, raw `certificate_chain_b64`, vendor tokens, private keys.

Optional companion (still sanitized): insert `pds_identity_audit` event `pds.enrollment.attestation_rejected` with the same metadata JSON (requires an approved write-stage; not this investigation).

### 3.2 Smallest Android change

In `PdsHttpEnrollmentTransport.verifyEnrollment`, for non-success non-409 responses, throw:

`PDS enrollment verification failed (HTTP ${code}, error_code=${errorCode ?: "absent"})`

using the already-parsed `errorCode` from the Quasar envelope. Still no BODY logging of certs/tokens.

### 3.3 Guarantees

- Does **not** weaken validation or change tenant policy  
- Does **not** accept a rejected attestation  
- Does **not** put cert chains or secrets in HTTP responses (error_code already returned today)

---

## 4. Recovery assessment (not executed)

| Question | Answer |
|---|---|
| Can existing Keystore key be rebound to a fresh attestation challenge? | **No.** `PdsKeystoreBindingPolicy` refuses hash mismatch; requires authorized rotation. Coordinator also blocks expired pending when `keyExists()`. |
| Must old pending be discarded? | **Yes**, as part of authorized recovery (after or with key handling). Leaving expired pending + existing key causes `PdsAuthorizedRotationRequiredException` on next enroll. |
| Is a new key pair required? | **Yes**, for any new server attestation challenge (cannot REUSE across challenge hashes). |
| Is a fresh challenge required? | **Yes.** `chl-128cd484-…` is expired and must not be reused. |
| Backend changes required for recovery? | **Not for recovery itself.** Sanitized reject logging (§3) strongly recommended before the next controlled attempt. Trust-store loading is a **separate** security/ops decision — not required to unblock soft-accept paths, but required for true hardware trust. |

**Safe recovery sequence (approval required later):**

1. Capture diagnostics (§3) on backend/Android builds used for the next attempt.  
2. Authorized Keystore alias delete + regenerate against **new** challenge only (explicit operator-approved stage).  
3. Clear `pds_enrollment.pending` / bind file as part of that stage.  
4. Issue **one** new challenge + verify with error_code retention.  
5. Do **not** relax attestation policy to force success.

---

## 5. Database and device state (SELECT-only / read-only)

| Check | Result |
|---|---|
| Challenge expired | **Yes** (`expires_at` 2026-11-05 17:38:19Z) |
| Challenge consumed | **No** |
| `devices` | **0** |
| `pds_device_keys` | **0** |
| `do_mdm_devices` | **0** |
| Audit | Still only `pds.enrollment.challenge_issued` |
| Enrollment token | Unrevoked, no expiry (`59006ead-…`) |
| Legacy registration | None |
| Device pending identifiable | `files/pds_enrollment.pending` + `files/pds_key_attestation.bind` (last confirmed while MP45P was online) |
| MP45P at stage end | **Offline** (reconnect wait timed out; K11 only) |

---

## 6. Recommended remediation (for approval — not started)

1. **Implement §3 diagnostics** (backend WARN + Android `error_code` in exception) in a dedicated stage.  
2. **Re-run Keystore public-metadata inspection** when MP45P is online (chain length, KD OID, challenge containment, `isInsideSecureHardware`) — read-only.  
3. **Authorized recovery stage:** discard pending, replace Keystore key, one new challenge/verify with captured `attestation_failed` reason.  
4. **Separately review** loading `PdsAttestationTrustStore` / Google roots in Phase 1D integration (does not fix soft-path 403s; enables real hardware trust evaluation).  
5. **Do not** add MP45P to vendor allowlist or lower `min_attestation` solely to clear this 403.

---

## 7. Confirmation checklist

- [x] Hard-reject branches enumerated + empty-trust-store reachability  
- [x] Exception filter / retention gap explained  
- [x] Pending/bind evidence reviewed; live chain probe deferred (device offline)  
- [x] Diagnostic proposal only (not implemented)  
- [x] Recovery assessed (not executed)  
- [x] SELECT-only DB confirmation  
- [x] Stop for approval  

**Stop.**
