# PDS Stage 2.5D.3-R — First MP45P Enrollment HTTP 403 Forensic Diagnosis

**Status:** COMPLETE — diagnosis only (no remediation executed)  
**Date:** 2026-11-05  
**Scope:** Read-only forensics of Stage 2.5D.3 verify failure  
**Android:** `C:\dev\Dotroid-phase-1d` / `feat/phase-1d-android-enrollment`  
**Device:** MP45P `HE8558H000567` / Android 8.1.0 / API 27 / `com.iips.dotroid` `1.0.1` (v2)  
**Backend:** `127.0.0.1:4010` (PID 27652) / DB `pds_phase1d_integration`  
**Tenant:** `830cb9cf-5717-4b68-8e29-dfb28ce755f3` (`pds-phase1d-int`, active)  
**Subject challenge:** `chl-128cd484-e882-40de-a121-bfe3ef929c2d`

**Restrictions honored:** no code changes; no DB writes; no challenge/verify/CONTINUE; no token rotation/revocation; no key deletion/regeneration; no attestation-policy weakening; no app-data clearing; no push/merge.

---

## Verdict

Verify failed with **HTTP 403** after a successful durable challenge issue. The machine-readable `error_code` was **not retained** in API INFO logs or Android logcat (OkHttp BODY logging is NONE; Android maps non-409 failures to a generic `HTTP ${code}` string).  

Using HTTP status, durable rejection-branch mapping, challenge/device correlation, and attestation accept-vs-reject semantics (not status alone):

| Classification | Result |
|---|---|
| **Primary root-cause class** | Backend **attestation hard-reject** (`PdsEnrollmentHttpError.forbidden('attestation_failed: …')`) inside durable verify |
| **Not the cause** | Signature/transcript mismatch (would be **401** `invalid_enrollment_proof`); challenge unknown/expired at verify time (401); CSRF (would also block challenge) |
| **Weakly excluded** | `hardware_fingerprint_mismatch` (client pre-check + pending↔DB fingerprint equality); `enrollment_token_or_tenant_mismatch` (same-session token/tenant as successful challenge) |
| **Side effects** | No device/key/MDM rows; challenge unconsumed; token unrevoked; no enrollment-time wallet/ledger creation |
| **Recovery** | Pending challenge is **expired**; Keystore key remains **bound** to that challenge → **cannot safely reuse**; a **new challenge + authorized key rebind** is required |

---

## 1. Exact sanitized 403 cause

### 1.1 What was observed

| Evidence | Value |
|---|---|
| Android exception | `SecurityException: PDS enrollment verification failed (HTTP 403)` at `PdsHttpEnrollmentTransport.verifyEnrollment` |
| HTTP status | **403** |
| Quasar business code (filter mapping) | **`03`** for HTTP 403 (`quasarResponseCodeFromHttpStatus`) |
| Surfaced `error_code` / `responseMessage` in app | **Not retained** — transport reads `errorBody` for `error_code` but only uses it for 409 recovery; other failures throw generic HTTP text |
| API process log around attempt | `pds_challenge_issued` at `2026-11-05 18:33:19` local / `17:33:19Z`; **no** verify success/failure LOG/WARN/ERROR line |

### 1.2 Exception-filter transformation

Yes. Failures thrown as Nest `HttpException` (including `PdsEnrollmentHttpError`) are handled by `QuasarApiExceptionFilter` (`iips-pay/src/common/http/quasar-api-exception.filter.ts`):

- HTTP status preserved (403)
- Envelope: `{ responseCode, responseMessage, data }`
- For `PdsEnrollmentHttpError`, `data.error_code` is preserved and `responseMessage` resolves to that `error_code`

So the client **did** receive a structured Quasar 403 envelope; Android simply did not persist/log the `error_code`.

### 1.3 Closed set of durable 403 branches for this attempt

From `PdsDurableEnrollmentService.completeVerification` (`pds-durable-enrollment.service.ts`), **403** can only come from:

| `error_code` / message | When |
|---|---|
| `enrollment_token_or_tenant_mismatch` | Token hash or tenant ≠ challenge row |
| `pds_device_revoked_or_retired` | Consumed-challenge recovery path (N/A — unconsumed) |
| `hardware_fingerprint_mismatch` | Recomputed HW fingerprint ≠ challenge binding |
| `attestation_failed: <reasons>` | `PdsAttestationService.evaluateAttestation` returned `valid: false` |

Also possible earlier: Nest `ForbiddenException('enrollment_token_tenant_mismatch')` from `validateEnrollmentTokenForTenant` (still HTTP 403; filter may omit `data.error_code` if not `PdsEnrollmentHttpError`).

**401** branches (ruled out by observed status): `unknown_enrollment_challenge`, `enrollment_challenge_expired`, `invalid_enrollment_proof`, `invalid_enrollment_token`, …

### 1.4 Ranked cause (evidence, not status-only inference)

1. **Most probable — `attestation_failed: …` (hard `reject()` path)**  
   - Verify ran after local keygen + signature (pending contains `enroll_signature_b64url`).  
   - Soft/incomplete attestation paths return **`valid: true`** under default `hardware_relaxed` (`softResult` / `downgradeIncomplete`).  
   - Therefore a 403 from attestation requires a **`reject()`** reason (not “unsupported” / empty-chain soft downgrade).  
   - Leading reject reasons for API 27 Keystore attestation chains:  
     - `KeyDescription attestationChallenge does not match issued enrollment challenge`  
     - `Attestation leaf certificate public key does not match registered device public key`  
     - chain parse / cert not-yet-valid/expired / challenge field mismatch in evidence  

2. **Less probable — `hardware_fingerprint_mismatch`**  
   - Client aborts locally if `computeCanonicalHardwareFingerprint(hardware) != challenge.hardware_fingerprint_sha256` before verify.  
   - Pending `hardware_fingerprint_sha256` **equals** DB challenge row (verified this stage).  

3. **Less probable — token/tenant mismatch**  
   - Challenge issued for tenant `830cb9cf-…` with token hash association moments earlier; same in-memory token/tenant used for verify in one `enrollExclusive` call.

**Exact reason string:** unknown (not logged). Treat primary class as **attestation hard-reject** until a captured `error_code` confirms the substring.

---

## 2. Backend rejection point

| Item | Location |
|---|---|
| HTTP entry | `DotoidDeviceController.verifyPdsEnrollment` → `POST /api/v1/device/pds/enroll/verify` |
| Service | `PdsDeviceIdentityService.completeEnrollmentVerification` → durable path enabled |
| Durable verify | `PdsDurableEnrollmentService.completeVerification` |
| Crypto verify (passed or not reached for 403-after-sig) | `verifyDeviceEcdsaSignature` (`pds-enrollment-crypto-server.ts`) — failure would be **401** |
| Attestation | `PdsAttestationService.evaluateAttestation` (`pds-attestation.service.ts`) |
| Throw | `PdsEnrollmentHttpError.forbidden(\`attestation_failed: ${reasons}\`)` at durable lines ~266–269 |
| Envelope | `QuasarApiExceptionFilter.catch` |

Challenge issuance (success) logged:

`PdsDurableEnrollmentService.pds_challenge_issued challenge_id=chl-128cd484-e882-40de-a121-bfe3ef929c2d tenant=830cb9cf-…`

---

## 3. Challenge lifecycle state (SELECT-only)

| Field | Value |
|---|---|
| `challenge_id` | `chl-128cd484-e882-40de-a121-bfe3ef929c2d` |
| `tenant_id` | `830cb9cf-5717-4b68-8e29-dfb28ce755f3` |
| `issued_at` (UTC) | `2026-11-05 17:33:19` |
| `expires_at` (UTC) | `2026-11-05 17:38:19` (**5-minute TTL**) |
| `consumed_at` | **NULL** (unconsumed) |
| `consumed_by_device_id` | NULL |
| `public_key_fingerprint_sha256` | NULL (SPKI-less initiate — expected) |
| Token hash association | **present** (hash not printed) |
| Hardware fingerprint | **present** (64-hex; equals device pending) |
| `attestation_challenge` | `bytea` **32 bytes**; pending Base64 decodes to **identical** bytes |
| Audit | Single event `pds.enrollment.challenge_issued`, lifecycle `PENDING_ENROLLMENT`, metadata manufacturer `Topwise`, model `MP45P` |

**Validity now:** **expired** (`expires_at < NOW()`). **Do not reuse.**

Device pending file correlates 1:1:

- Same challenge id / tenant / issue / expiry  
- `attestation_challenge_b64` ↔ DB bytea **match**  
- `pds_key_attestation.bind` == SHA-256(attestation challenge bytes) **match**  
- `enroll_signature_b64url` **present** (signature completed locally)

---

## 4. Android cryptographic evidence (read-only)

| Check | Evidence | Conclusion |
|---|---|---|
| EC P-256 Keystore key generated | Bind file written `18:33`; enroll signature persisted | **Yes** (alias `pds_device_key_v1` per `PdsDeviceKeyManager`) |
| Hardware- vs software-backed | API 27 classifier uses `KeyInfo.isInsideSecureHardware` only (`PdsHardwareBackingClassifier` / `keyinfo_api26_27`) | **Not directly re-inspected** this stage (no Keystore dump via `dumpsys`); generation used AndroidKeyStore + `setAttestationChallenge` (no software private-key fallback in production wiring) |
| Public-key fingerprint persisted | Pending `public_key_fingerprint_sha256` empty (challenge had null SPKI binding) | Fingerprint exists only inside Keystore cert until successful verify |
| Pending state vs challenge | Pending matches outstanding (now expired) challenge | **Yes** |
| Signature operation | `enroll_signature_b64url` length ~95 (DER ECDSA → Base64URL unpadded) | **Completed** |
| Algorithm | Android `SHA256withECDSA` DER → `PdsCanonicalCommandEnvelope.toBase64Url`; server accepts DER when sig ≠ 64 bytes (`verifyDeviceEcdsaSignature`) | **Protocol-compatible** |
| Attestation chain supplied | Collector always sends `certificate_chain_b64` from `KeyStore.getCertificateChain`; classifies `unsupported` / `vendor_hardware` / `tee` / … | Chain **attempted**; exact on-wire classification for this attempt **not logged**. Soft “unsupported/empty” would **not** 403 under `hardware_relaxed`. |

No private keys exported; no signatures printed in this report.

---

## 5. Transcript comparison (Android ↔ Quasar)

Independent source comparison — **no field-order / delimiter / hashing mismatch found**.

| Element | Android | Quasar durable |
|---|---|---|
| Version prefix | `pds-enroll-v1` (`PdsEnrollmentCrypto.ENROLLMENT_TRANSCRIPT_PREFIX`) | `pds-enroll-v1` (`buildEnrollmentChallengeTranscript` in `pds-enrollment-recovery-transcript.ts`) |
| Field order / delimiter | `prefix\|challengeId\|nonce\|tokenSha256Hex\|tenant\|pubFp\|hwFp` join `\|` | Identical |
| Token hashing | UTF-8 SHA-256 hex of trimmed token | Identical |
| Challenge nonce | From server challenge / pending | DB `challenge_nonce` |
| Tenant UUID | Explicit `830cb9cf-…` | Same |
| Pubkey fingerprint | SHA-256 hex of SPKI DER, lowercased | Same (`parseAndValidateEcP256PublicKey`) |
| Hardware fingerprint | `mfr\|model\|hardwareId\|serial\|buildFingerprint` rules aligned with `computeCanonicalHardwareFingerprint` | Same (`pds-enrollment-crypto-server.ts`) |
| Attestation challenge bytes | `SHA-256("pds-attest-v1\|id\|nonce\|tokenHash")` server-side; Android uses server-issued bytes only | Durable initiate matches; pending↔DB equal |
| Base64URL signatures | `Base64.getUrlEncoder().withoutPadding()` | `fromBase64UrlStrict` + DER or P-1363 verify |
| ECDSA input | Raw transcript UTF-8 bytes | Raw transcript `Buffer` |

**Conclusion:** A pure transcript construction bug is **not** supported. A transcript/signature failure would also surface as **401**, not 403.

---

## 6. Enrollment policy & attestation assessment

| Policy input | Value |
|---|---|
| Tenant DB `metadata` | **null** / empty |
| Request `tenant_minimum_attestation` | Android does **not** send it → durable default **`hardware_relaxed`** |
| Vendor catalog (Topwise) | Supported models `T1`, `MP35P`, `T3`, `M3` — **`MP45P` not listed** |
| Effect of missing model | Blocks `hardware_relaxed` **tier upgrade** only; incomplete attestation still `valid: true` via `softResult` |
| Trust store default | Empty roots → chain cannot fully anchor → incomplete → soft accept (not 403) |
| API 27 Keystore | `setAttestationChallenge` supported; StrongBox APIs require API 28+ (not used). Attestation chains on OEM POS builds are often incomplete or OEM-specific |

**Attestation assessment:** Under `hardware_relaxed`, incomplete/unsupported evidence is accepted. HTTP 403 implies a **hard reject** inside `evaluateAttestation` (see §1.4), not merely “MP45P unverified tier” or “no Google root”.

**Do not** weaken tenant minimum attestation or trust policy as a fix without a separate approved security review.

---

## 7. Database before/after / side effects (SELECT-only)

| Table / check | Baseline (2.5D.3) | Forensic now |
|---|---|---|
| `devices` | 0 | **0** |
| `pds_device_keys` | 0 | **0** |
| `pds_enrollment_challenges` | 0 → 1 | **1** unconsumed, **expired** |
| `pds_identity_audit` | 0 → 1 | **1** (`challenge_issued` only) |
| `do_mdm_devices` | 0 | **0** |
| Enrollment token | 1 valid | **1** unrevoked, `expires_at` null (`59006ead-…`) |
| `device_attestation_history` | — | **0** |
| `do_mdm_request_logs` | — | **0** |
| `wallets` | — | **11** rows, all created `2026-11-05 04:58:07Z` (tenant bootstrap — **before** enrollment) |
| `ledger_entries` | — | **0** |
| Legacy MDM register | Not called | Still no MDM device row |

No unexpected financial activity tied to the enrollment attempt.

---

## 8. Root-cause classification

| Axis | Classification |
|---|---|
| Failure hop | PDS **verify** (hop 2); legacy register not attempted |
| HTTP class | **403 Forbidden** / Quasar `responseCode=03` |
| Likely machine code family | **`attestation_failed: <reject-reason>`** via durable enrollment + attestation service |
| Not signature/transcript | Ruled out by status mapping + source parity |
| Not challenge expiry at verify instant | Challenge issued 17:33:19Z, TTL +5m; verify occurred in the same minute (pending/bind timestamps 18:33 local); expiry is a **now** state, not the then-cause |
| Observability defect | Server does not LOG verify rejections; Android discards `error_code` for non-409 |

---

## 9. Recommended minimal remediation (for review — not executed)

1. **Observability (minimal, first)**  
   - Android: include sanitized `error_code` / `responseMessage` in the verify `SecurityException`.  
   - Quasar: WARN-log durable verify `error_code` (no tokens/signatures).  

2. **Do not reuse** `chl-128cd484-…` (expired, unconsumed).  

3. **Key/pending recovery**  
   - Pending challenge expired **and** Keystore key + bind hash remain.  
   - `PdsEnrollmentCoordinator.resolveChallenge` will throw **`PdsAuthorizedRotationRequiredException`** if retry is attempted with expired pending while the alias exists.  
   - **Safe recovery requires an approved follow-up stage:** authorized Keystore rebind/replacement (explicit delete+regenerate under policy), clear pending, then **one** new challenge/verify — still without attestation-policy weakening.  

4. **Next controlled verify** must capture the Quasar `data.error_code` string to confirm the exact `reject()` reason (KeyDescription mismatch vs leaf mismatch vs cert validity).  

5. **Optional security-sensitive code review (separate approval):** whether KeyDescription challenge mismatch should hard-reject when the chain is **not** fully verified (today it rejects before soft downgrade). Do **not** implement here.

---

## 10. Pending challenge / key recoverability

| Asset | State | Reuse? |
|---|---|---|
| Server challenge | Unconsumed, **expired** | **No** |
| Enrollment token | Valid, unrevoked | **Yes** (same token OK for a **new** challenge after key handling) |
| Device pending file | Present, mirrors expired challenge + signature | **Not safely resumable** |
| Keystore key + bind | Present, bound to expired attestation challenge hash | **Cannot** generate against a new challenge without authorized replacement (`PdsKeystoreBindingPolicy` / coordinator expiry path) |

**Answer:** Existing pending challenge/key **cannot** be safely recovered for a successful verify. A **new challenge** is required, and it must be preceded by an **approved key-rebind** step.

---

## 11. Confirmation checklist

- [x] Read-only only (no code/DB/device mutations; no enroll API calls)  
- [x] Challenge/DB/audit/token/side-effect SELECTs  
- [x] MP45P local pending/bind inspected; secrets redacted  
- [x] Transcript source comparison  
- [x] Attestation/policy assessment without weakening  
- [x] Report written; stop for review  

**Stop.**
