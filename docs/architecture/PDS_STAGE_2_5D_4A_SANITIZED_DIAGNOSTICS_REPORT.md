# PDS Stage 2.5D.4A — Sanitized Attestation Diagnostics

**Status:** COMPLETE — diagnostics only (uncommitted; stop for review)  
**Date:** 2026-11-06  
**Android worktree:** `C:\dev\Dotroid-phase-1d` / `feat/phase-1d-android-enrollment` @ `c4fed4e` (+ local deltas)  
**Backend worktree:** `C:\dev\quasar-phase-1d-2` / `feat/phase-1d-2-durable-device-identity` @ `c97d702` (+ local deltas)

**Not done (by design):** no POS connect/modify; no challenge/verify; no Keystore/pending/token changes; no API restart; no policy/trust-store edits; **no git stage/commit/push/merge/deploy/migrations**.

---

## Verdict

Minimal sanitized diagnostics are in place for the next authorized MP45P verify attempt:

| Side | Change |
|---|---|
| Backend | WARN JSON on `PdsAttestationService.reject()` and correlated WARN before durable/in-memory enrollment throws `attestation_failed` |
| Android | `PdsEnrollmentHttpException` retains Quasar `error_code`; debug WARN logs status + code only |
| HTTP contract | Unchanged — still 403 / Quasar `03` / `data.error_code` |
| Validation | Unchanged — hard rejects remain hard rejects |

---

## 1. Files changed (this stage)

### Backend (`quasar-phase-1d-2`)

| File | Change |
|---|---|
| `iips-pay/src/modules/device/pds/pds-attestation-rejection-diagnostics.ts` | **New** — category/`reason_code` classifier + sanitized diagnostic builder |
| `iips-pay/src/modules/device/pds/pds-attestation-rejection-diagnostics.spec.ts` | **New** — classifier, redaction, reject logging, 403 envelope tests |
| `iips-pay/src/modules/device/pds/pds-attestation.service.ts` | WARN on `reject()` via sanitized builder |
| `iips-pay/src/modules/device/pds/pds-durable-enrollment.service.ts` | Correlated WARN before `PdsEnrollmentHttpError.forbidden('attestation_failed:…')` |
| `iips-pay/src/modules/device/pds/pds-device-identity.service.ts` | Correlated WARN before in-memory `ForbiddenException('attestation_failed:…')` |

### Android (`Dotroid-phase-1d`)

| File | Change |
|---|---|
| `app/src/main/java/com/iips/launcher/security/pds/PdsEnrollmentExceptions.kt` | `PdsEnrollmentHttpException(httpStatus, errorCode)` |
| `app/src/main/java/com/iips/launcher/security/pds/PdsHttpEnrollmentTransport.kt` | Throw typed exception; debug sanitized WARN (JVM-safe) |
| `app/src/test/java/com/iips/launcher/security/pds/PdsHttpEnrollmentTransportEnvelopeTest.kt` | 403 `error_code` preservation + fail-closed tests |
| `docs/architecture/PDS_STAGE_2_5D_4A_SANITIZED_DIAGNOSTICS_REPORT.md` | This report |

---

## 2. Exact diagnostic fields logged (backend)

WARN payload is a JSON object with:

| Field | Meaning |
|---|---|
| `event` | Always `pds_attestation_rejected` |
| `category` | Stable enum: `public_key`, `challenge_mismatch`, `chain_parse`, `leaf_mismatch`, `certificate_revoked`, `certificate_validity`, `key_description_challenge`, `verified_boot`, `other` |
| `reason_code` | Stable snake_case (e.g. `key_description_challenge_mismatch`) — **not** raw cert serials |
| `trust_tier` | From evaluation result |
| `security_level` | From evaluation result |
| `chain_verified` | boolean |
| `challenge_matched` | boolean |
| `has_cert_chain_digest` | boolean (presence of digest only; digest hex not required in reject() path) |
| `reason_count` | Count of reason strings |
| `challenge_id` | When caller supplies (durable / in-memory enrollment) |
| `tenant_id` | When caller supplies |
| `correlation_id` | When caller supplies |
| `manufacturer` / `model` | Truncated hardware labels when supplied |

`reject()` logs the base fields; enrollment callers add `challenge_id` / `tenant_id` / model labels.

Client-facing `error_code` remains:  
`attestation_failed: <original reasons joined>` — unchanged.

---

## 3. Redaction guarantees

**Never logged / never placed on the new exception message beyond `error_code`:**

- Enrollment tokens  
- Private keys  
- Signatures  
- Full certificate chains / PEMs  
- Raw attestation challenge bytes / `attestation_challenge_b64`  
- SPKI / public key material  
- Vendor attestation tokens  
- Full HTTP bodies or sensitive headers  
- Certificate serial numbers (revocation mapped to category codes only)

Android debug log line format:  
`PDS verify failed http=<status> error_code=<code|absent>`

---

## 4. Tests / build — commands and results

### Backend

```text
cd C:\dev\quasar-phase-1d-2\iips-pay
npm test -- --testPathPatterns="pds-attestation-rejection-diagnostics|quasar-pds-envelope" --coverage=false
```

**Result:** `Test Suites: 2 passed`, `Tests: 9 passed`

Covers: reason classification, forbidden keys, `reject()` WARN content, HTTP 403 Quasar envelope unchanged for `attestation_failed`.

### Android unit tests + launcherDebug APK

```text
JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
cd C:\dev\Dotroid-phase-1d
.\gradlew.bat :app:testLauncherDebugUnitTest ^
  --tests "com.iips.launcher.security.pds.PdsHttpEnrollmentTransportEnvelopeTest" ^
  --tests "com.iips.launcher.security.pds.PdsQuasarEnvelopeParsingTest" ^
  :app:assembleLauncherDebug
```

**Result:** `BUILD SUCCESSFUL`  
APK: `app/build/outputs/apk/launcher/debug/app-launcher-debug.apk` (built 2026-11-06 08:56)

Android tests assert `PdsEnrollmentHttpException` preserves `error_code`, message omits token/spki/signature, and no automatic retry/key deletion path is introduced.

---

## 5. State confirmation

| Asset | Touched? |
|---|---|
| Physical POS / ADB enrollment | **No** |
| Challenge / verify HTTP | **No** |
| Keystore / pending / bind | **No** |
| Enrollment token / tenant policy / trust store | **No** |
| API process restart | **No** |
| Database writes / migrations | **No** |
| Git commit / push / merge | **No** |

---

## 6. Git status (uncommitted)

### Backend `feat/phase-1d-2-durable-device-identity`

Relevant 2.5D.4A paths (among other pre-existing local dirty files):

- Modified: `pds-attestation.service.ts`, `pds-durable-enrollment.service.ts`, `pds-device-identity.service.ts`
- Untracked: `pds-attestation-rejection-diagnostics.ts`, `pds-attestation-rejection-diagnostics.spec.ts`

**HEAD:** `c97d702` — not advanced (no commit).

### Android `feat/phase-1d-android-enrollment`

Relevant 2.5D.4A paths (among other Phase 2.5 local dirty files):

- Modified: `PdsEnrollmentExceptions.kt`, `PdsHttpEnrollmentTransport.kt`, `PdsHttpEnrollmentTransportEnvelopeTest.kt`
- Untracked: this report

**HEAD:** `c4fed4e` — not advanced (no commit).

---

## 7. Next stage (not started)

After review/approval: install the new debug APK (or restart process with rebuilt backend), then an authorized recovery stage (key replacement + fresh challenge/verify) so the new WARN/`error_code` surface can capture the exact attestation reject class.

**Stop.**
