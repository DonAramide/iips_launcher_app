# PHASE 6.5.4.3A — Android Forensic Capture Implementation

**Verdict: PASS**

Debug-only, non-PEM attestation forensic capture is implemented in the Dotroid Android worktree, gated by compile-time BuildConfig + release source-set hard-disable, covered by 15 JVM unit tests (0 failures). No Quasar policy changes, no MP45P enrollment retry, no commits.

---

## Worktree identity

| Item | Value |
| --- | --- |
| Worktree | `C:\dev\Dotroid-phase-1d` |
| Branch | `feat/phase-1d-android-enrollment` |
| HEAD | `c4fed4e3a6a40085ea129ae014e783233ee891b5` |
| Dirty state | Preserved (pre-existing + this change set) |

---

## Files created and modified

### Created

| Path | Role |
| --- | --- |
| `app/src/main/java/com/iips/launcher/security/pds/PdsAttestationForensicCapture.kt` | Capture, inspect, persist, privacy asserts |
| `app/src/debug/java/com/iips/launcher/security/pds/PdsAttestationForensicGate.kt` | Debug gate: `DEBUG && PDS_ATTESTATION_FORENSIC_CAPTURE` |
| `app/src/release/java/com/iips/launcher/security/pds/PdsAttestationForensicGate.kt` | Release gate: compile-time `false` |
| `app/src/test/java/com/iips/launcher/security/pds/PdsAttestationForensicCaptureTest.kt` | JVM unit tests (synthetic certs) |
| `app/src/androidTest/java/com/iips/launcher/security/pds/PdsAttestationForensicCaptureAndroidTest.kt` | Instrumentation smoke for X.509 metadata |
| `docs/architecture/PHASE_6_5_4_3A_ANDROID_FORENSIC_CAPTURE_IMPLEMENTATION.md` | This report |

### Modified

| Path | Change |
| --- | --- |
| `app/build.gradle` | `PDS_ATTESTATION_FORENSIC_CAPTURE` true (debug) / false (release); BC test deps |
| `app/src/main/.../PdsEnrollmentCoordinator.kt` | Capture after chain retrieval, before Quasar verify |
| `app/src/launcher/.../DeviceEnrollmentManager.kt` | Passes app-private forensic output directory |

---

## Capture architecture

```
Keystore chain retrieved (signer.attestationChainB64)
        │
        ▼
PdsAttestationForensicCapture.captureBeforeQuasarTransmit
  ├─ Gate: PdsAttestationForensicGate.isEnabled()
  ├─ Decode Base64 → X509Certificate (transient; not persisted)
  ├─ Inspect notBefore/notAfter/SHA-256/OID/type
  ├─ Collect KeyInfo-backed keystore evidence (separate claims)
  ├─ Atomic write files/pds_attestation_forensic/latest.json
  └─ Append bounded history.jsonl
        │
        ▼
PdsAttestationCollector.classifyLocalCapabilities(...)
        │
        ▼
Quasar verifyEnrollment(...)   ← capture already done if verify fails
```

Capture soft-fails: any exception returns `false` and enrollment continues unchanged.

---

## Exact fields captured

### Per certificate

- `certificate_index`
- `certificate_chain_length`
- `der_sha256` (SHA-256 of `certificate.encoded`)
- `not_before_utc` / `not_after_utc` (UTC ISO-8601 from Android/Java X.509 API)
- `evaluated_at_utc`
- `not_yet_valid` / `expired` / `validity_inverted`
- `certificate_type`
- `key_description_present` (OID `1.3.6.1.4.1.11129.2.1.17`)

### Keystore evidence (separate)

- `alias_present`, `chain_retrieved`, `chain_length`
- `is_inside_secure_hardware`, `security_level_name`, `inspection_source`
- `key_algorithm`, `key_size_bits`
- `strongbox_applicable` (API ≥ 28 only), `strongbox_backed` (null on API 27)
- `attestation_challenge_configured`

### Device profile

- `manufacturer`, `model`, `android_api`
- `build_fingerprint_redacted`, `build_tags`, `build_type`, `security_patch_level`

Never persisted: PEM, DER, SPKI, enrollment tokens, JWTs, passwords, challenge secrets.

---

## Privacy controls

- App-private directory: `context.filesDir/pds_attestation_forensic/`
- Atomic writes (`*.tmp` → rename)
- History capped at 64 KiB (keeps last 8 lines)
- `assertNoSecrets()` rejects PEM markers and long Base64 blobs before write
- No automatic upload / analytics
- No certificate fingerprints written to Logcat
- Capture cannot rotate keys, mutate pending enrollment, or suppress Quasar rejection

---

## Debug / release enforcement

| Mechanism | Debug | Release |
| --- | --- | --- |
| `BuildConfig.PDS_ATTESTATION_FORENSIC_CAPTURE` | `true` | `false` |
| `PdsAttestationForensicGate` source set | `DEBUG && flag` | **literal `false`** |
| Default | Capture eligible when wired with output dir | Unreachable |

Release builds do not rely solely on a mutable runtime Boolean.

---

## Test results

Command:

```text
./gradlew :app:testLauncherDebugUnitTest --tests com.iips.launcher.security.pds.PdsAttestationForensicCaptureTest
```

Result: **15 tests, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESSFUL`).

Covered:

1. Valid window  
2. Expired  
3. Not-yet-valid  
4. Inverted (epoch pair matching Phase 6.5.4.1 diagnostic)  
5. Multi-cert independent metadata  
6. SHA-256 fingerprint correctness  
7. Missing KeyDescription OID  
8. Present KeyDescription OID  
9. Undecodable chain (encoding failure path)  
10. Empty chain  
11. Debug capture disabled  
12. Release/disabled gate constant path + debug BuildConfig contract  
13. Diagnostic write failure does not throw  
14. No PEM/DER/SPKI/credentials in persisted JSON  
15. Sample sanitized record shape  

Instrumentation test class added (`PdsAttestationForensicCaptureAndroidTest`); not executed on MP45P in this phase (stop condition: no device enrollment).

---

## Sample sanitized diagnostic record (synthetic test certificate)

Illustrative record from the inverted-validity unit test certificate (not MP45P hardware evidence):

```json
{
  "event": "pds_attestation_forensic_capture",
  "capture_version": 1,
  "manufacturer": "Topwise",
  "model": "MP45P",
  "android_api": 27,
  "certificate_index": 0,
  "certificate_chain_length": 1,
  "der_sha256": "<64-char-hex-of-synthetic-der>",
  "not_before_utc": "1970-01-01T00:00:00.000Z",
  "not_after_utc": "1969-12-31T23:59:59.000Z",
  "evaluated_at_utc": "2026-11-09T00:45:59.000Z",
  "not_yet_valid": false,
  "expired": true,
  "validity_inverted": true,
  "certificate_type": "X.509",
  "key_description_present": false
}
```

Full on-device reports also nest `device` and `keystore` objects in `latest.json`.

---

## Export instructions (future authorised disposable validation)

Do **not** run enrollment until separately authorised. When a debug build has performed capture:

```text
adb -s <serial> shell "run-as com.iips.dotroid cat files/pds_attestation_forensic/latest.json"
adb -s <serial> pull /data/local/tmp/...   # optional: copy via run-as cp to /data/local/tmp first
```

Compare:

1. Android `not_before_utc` / `not_after_utc` vs Quasar `pds_attestation_cert_validity_window`
2. Android `der_sha256` vs Quasar-side SHA-256 of received leaf DER (when Quasar adds matching sanitized digest diagnostics)
3. `certificate_chain_length`, KeyDescription presence, KeyInfo fields, firmware profile

If fingerprints differ, investigate serialization/transport before concluding OEM defect.

---

## Remaining limitations

- Does not by itself prove the Phase 6.5.4.1 MP45P leaf DER (capture was not present then).
- Quasar still does not emit leaf `der_sha256` for cross-check (out of scope for 6.5.4.3A).
- Instrumentation test not run on physical MP45P in this phase.
- Local `is_inside_secure_hardware` remains a **claim**, not server-verified trust.

---

## Explicit stop

Stopped after implementation + automated JVM validation. No enrollment retry, Quasar policy change, catalogue update, remote commands, commit, push, merge, or deploy.
