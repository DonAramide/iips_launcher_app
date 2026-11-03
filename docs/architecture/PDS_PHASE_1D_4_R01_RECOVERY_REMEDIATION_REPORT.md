# PDS × Dotroid — Phase 1D.4 R-01 Recovery Remediation Report (R2)

| Field | Value |
| --- | --- |
| Worktree | `C:\dev\Dotroid-phase-1d` |
| Branch | `feat/phase-1d-android-enrollment` |
| Baseline HEAD | `01ee761b5966b218bfb69a6f02abd26d4ddfa4ee` |
| Date | 2026-11-03 |
| Authorization | R1 Backend + R2 Android (Android portion) |

---

## Original R-01 defect

Quasar recovery success returns **HTTP 409** with:

- top-level `error_code = enrollment_already_completed`
- recovered identity fields (`device_id`, …) in the **same JSON body**

Retrofit exposes unsuccessful bodies via `errorBody()`, leaving `body()` null.  
`PdsHttpEnrollmentTransport` previously read `response.body()` after recovery → fail-closed `SecurityException` despite a valid recovery payload.

---

## HTTP response contract (unchanged backend)

- Status: **409 Conflict**
- Classification field: top-level `error_code`
- Success recovery code: exact `enrollment_already_completed`
- Identity fields: snake_case (`device_id`, `device_identity_ref`, …) co-located with `error_code`
- Backend was **not** changed to HTTP 200 for this remediation

---

## Error-body parsing implementation

### `PdsEnrollmentHttpErrors.parseRecoveryEnrollmentResult`

- Requires `parseErrorCode(json) == enrollment_already_completed`
- Gson → `PdsEnrollmentVerifyResponse`
- Requires canonical Quasar device UUID
- Requires non-blank: `deviceIdentityRef`, `tenantId`, `deviceKeyId`, `publicKeyFingerprintSha256`, `lifecycleState`
- Returns null on malformed / incomplete / wrong code

### `PdsHttpEnrollmentTransport.verifyEnrollment`

1. Capture `errorBody()?.string()` once  
2. Parse `error_code`  
3. Without recovery proof: enter recovery only on exact code; else conflict  
4. With recovery proof: require exact `enrollment_already_completed`, then parse recovery identity from **error JSON**  
5. Do not use `body()` for 409 recovery  

---

## Strict error-code validation

| Condition | Behavior |
| --- | --- |
| 409 + `enrollment_already_completed` + no recovery sent | `PdsEnrollmentNeedsRecoveryException` |
| 409 + other code | `PdsEnrollmentConflictException` |
| 409 + recovery + exact code + valid identity | Return recovered UUID to coordinator |
| 409 + recovery + wrong/missing code | Conflict / SecurityException |
| 409 + recovery + malformed / missing UUID / missing fields | SecurityException |
| Non-409 failure | SecurityException with HTTP code |

---

## Tests and results

Command (JDK 17):

```text
.\gradlew.bat :app:testLauncherDebugUnitTest
  --tests com.iips.launcher.security.pds.PdsPhase1D4R01RecoveryTest
  --tests com.iips.launcher.security.pds.PdsPhase1D4RemediationTest
  --tests com.iips.launcher.security.pds.PdsPhase1D4EnrollmentTest
```

**BUILD SUCCESSFUL** — XML results:

| Suite | Tests | Failures |
| --- | ---: | ---: |
| `PdsPhase1D4R01RecoveryTest` | 7 | 0 |
| `PdsPhase1D4RemediationTest` | 12 | 0 |
| `PdsPhase1D4EnrollmentTest` | 8 | 0 |
| **Total** | **27** | **0** |

R-01 cases covered:

- Valid 409 + `enrollment_already_completed` + identity  
- Unrelated 409 error code  
- Malformed JSON  
- Missing / non-UUID device id  
- Missing required fields  
- Recovery retry after first verify interrupted (`NeedsRecovery` path)

Note: HTTP 500 / network timeout are covered by existing fail-closed `SecurityException` paths in transport; not separately stubbed with Retrofit Response fakes in R01 suite (no mockito/mockk in module). Coordinator recovery retry after lost original response is covered via fake transport.

---

## Remaining hardware verification requirements

These remain **NOT RUN** (JVM fakes only):

- Android Keystore attestation challenge binding  
- StrongBox-backed key generation  
- Physical POS / Topwise field enrollment against Quasar  
- End-to-end Retrofit wire call to live Quasar 409 recovery  

**Android R-01 remediation status: PASS (JVM)** — hardware/E2E still required before production authorization.
