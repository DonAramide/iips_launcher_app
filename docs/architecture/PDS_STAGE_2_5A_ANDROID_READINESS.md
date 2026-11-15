# PDS Stage 2.5A — Android Enrollment Readiness Inspection

**Status:** Inspection only — no enrollment HTTP, install, token decrypt, DB writes, or app code mutations performed.  
**Date:** 2026-11-05  
**Revision:** Device inventory refreshed after POS fleet reconnect (replaces earlier SM-T397U-only snapshot).  
**Android worktree:** `C:\dev\Dotroid-phase-1d`  
**Backend worktree:** `C:\dev\quasar-phase-1d-2`  
**Integration API:** `127.0.0.1:4010` (confirmed healthy during re-inspection)  
**Tenant UUID (Stage 2.4B.2):** `830cb9cf-5717-4b68-8e29-dfb28ce755f3`

---

## 1. Android worktree verification

| Check | Result |
|---|---|
| Branch | `feat/phase-1d-android-enrollment` |
| HEAD | `c4fed4e` (`c4fed4e3a6a40085ea129ae014e783233ee891b5`) |
| Message | `fix(pds): parse Quasar API response envelopes` |
| Working tree | Clean at original inspection; this file is the only readiness-doc update |
| Phase 1C baseline (`C:\dev\Dotroid-phase-1c`) | `01ee761`, clean — untouched |
| Backend (`quasar-phase-1d-2`) | `feat/phase-1d-2-durable-device-identity` @ `c97d702` |

### Stage 2.2B envelope implementation

Present at HEAD `c4fed4e`:

- `QuasarApiEnvelope.kt` — `{responseCode,responseMessage,data}`
- `ConfigService` PDS methods return `Response<QuasarApiEnvelope<…>>`
- `PdsHttpEnrollmentTransport` / `PdsHttpIdentityStatusClient` unwrap success data; R-01 recovery from HTTP 409 `errorBody`
- Unit tests: `PdsQuasarEnvelopeParsingTest`, `PdsHttpEnrollmentTransportEnvelopeTest`

No Android application or backend source was modified for this refresh (documentation only).

---

## 2. Device discovery (read-only) — refreshed

`adb devices -l` now shows **3 physical POS devices** (0 emulators).

| # | Transport | Manufacturer | Model | Android / SDK | Notes |
|---|---|---|---|---|---|
| 1 | USB `0123456789ABCDEF` | Horizonpay | **K11** | **7.1.2 / API 25** | Below app `minSdk 26` |
| 2 | USB `HE8558H000567` | Topwise | **MP45P** | **8.1.0 / API 27** | Meets `minSdk 26` |
| 3 | TCP `192.168.1.145:45161` | Dspread | **D60** | **11 / API 30** | Wi‑Fi ADB; meets `minSdk 26` |

| Item | Result |
|---|---|
| `adb version` | 1.0.41 (37.0.1-15733141), Windows host |
| Emulator / QEMU | None (`ro.kernel.qemu` empty on all three) |
| Prior SM-T397U | **Not connected** in this refresh |

No APK install or app launch was performed.

### First-device recommendation

| Priority | Device | Rationale |
|---|---|---|
| **Primary candidate** | **MP45P** (`HE8558H000567`) | USB + API 27 ≥ minSdk 26; simplest `adb reverse` path |
| Secondary | **D60** | API 30 OK; Wi‑Fi ADB — reverse still possible, LAN path more awkward while Quasar is loopback-only |
| Blocked for current APK | **K11** | API **25** &lt; `minSdk 26` — install of current `Dotroid-phase-1d` build should fail without an approved minSdk change |

---

## 3. API configuration inspection

### Build-time base URL

From `app/build.gradle` + `gradle.properties`:

- Default: `LOCAL_IP=192.168.1.134`, `LOCAL_PORT=4000`
- `BuildConfig.BASE_URL` = `http://192.168.1.134:4000/api/v1/`
- Flavors: `launcher` (`com.iips.dotroid`), `guard` (`com.iips.dotroid.guard`)
- Debug/release both embed the same `localBaseUrl` pattern
- `minSdk 26` / `targetSdk 34` / `compileSdk 34`

**`http://127.0.0.1:4010/api/v1/` is not configured today.** A controlled override is required for Phase 1D integration.

### Runtime URL override (already implemented)

`DynamicBaseUrlInterceptor` (`NetworkModule.kt`) rewrites host/port when encrypted prefs hold:

1. `provisioning_backend_url`, else  
2. `backend_url`, else  
3. `config_url`

Provisioning / intent extras already call `SecurePreferences.setProvisioningBackendUrl` / `setBackendUrl` / `setConfigUrl` (`DeviceEnrollmentManager`).

### Retrofit / transport

- Retrofit 2.9 + Gson; `ConfigService` paths:
  - `POST device/pds/enroll/challenge`
  - `POST device/pds/enroll/verify`
  - `GET device/pds/identity/{deviceId}` (+ `X-Enrollment-Token`)
- Wire models in `PdsEnrollmentModels.kt` match Quasar field names (`enrollment_token`, `tenant_id`, SPKI, signatures, etc.).
- Enrollment token accepted via method args, SecurePreferences, or Device Owner / intent extras (`enrollment_token` / `enrollmentToken`).

### Network security / cleartext

| Item | Finding |
|---|---|
| `usesCleartextTraffic` | **Not set** (defaults to false under modern target SDK) |
| `networkSecurityConfig` | **Absent** |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Present |
| Target / min SDK | 34 / **26** |

**Blocker:** Cleartext `http://` to the loopback integration API will be rejected by the platform network security policy on API 28+ unless a **debug-only** cleartext exception (or HTTPS) is approved. API 25–27 devices historically allowed cleartext more permissively, but relying on that is unsafe for a controlled Stage 2.5 plan — prefer an explicit debug exception.

### Logging risk

OkHttp `HttpLoggingInterceptor.Level.BODY` is enabled in `NetworkModule`. Enrollment tokens and verify payloads could appear in logcat during a live test. A temporary debug logging downgrade should be approved before first live enrollment.

---

## 4. Connectivity plan

### Current Quasar bind

API listens on **`127.0.0.1:4010` only** (not `0.0.0.0`). Host health check succeeded during re-inspection.

### Emulator path

Not applicable (no emulator attached). If one appears later:

```text
adb reverse tcp:4010 tcp:4010
```

### Physical POS fleet

Do **not** change Quasar bind without a separate approval.

| Option | Description | Requires approval? |
|---|---|---|
| **A (recommended)** | Per-device `adb -s &lt;serial&gt; reverse tcp:4010 tcp:4010` so device `127.0.0.1:4010` maps to host loopback; keep Quasar on `127.0.0.1` | Yes (connectivity only; no bind change) |
| B | Rebind Quasar to LAN + use host LAN IP (relevant for Wi‑Fi D60 without reverse) | **No** unless explicitly approved — expands attack surface |
| C | Reverse proxy / SSH tunnel on device | Higher complexity; not preferred |

**Recommended first target:** USB **MP45P** with Option A.

**D60 note:** Wi‑Fi ADB (`192.168.1.145:45161`) can still use `adb reverse` while the TCP session is up. Without reverse, the device cannot reach the host’s `127.0.0.1:4010` and Quasar will not accept LAN clients on the current bind.

**K11 note:** Even with reverse, current APK `minSdk 26` blocks install.

---

## 5. Secure enrollment token handling (proposal only)

Token already stored host-side as DPAPI:  
`…\credential-handoff\pds.enrollment-token.dpapi`  
(not decrypted in this stage).

### Do not

- Hardcode token/tenant in source or `BuildConfig`
- Commit secrets to Git
- Print token in chat, reports, or screenshots
- Rely on OkHttp BODY logs

### Proposed temporary local-only injection (awaiting approval)

1. On the **host**, decrypt DPAPI enrollment token **in memory only**.
2. Push non-secret tenant id + backend URL, and token, into the app via one of:
   - **Preferred:** `am start` / provisioning extras with `enrollment_token`, `tenant_id`, `backend_url=http://127.0.0.1:4010/api/v1/` (arguments redacted in operator scripts; no `echo` of token), targeted with `adb -s &lt;serial&gt;`.
   - **Alternative:** host-side script writing EncryptedSharedPreferences via instrumentation/`run-as` (more complex).
3. App already persists token via `SecurePreferences.setEnrollmentToken` (AndroidX Security Crypto).
4. Clear host plaintext/`PDS_*` env immediately after injection.
5. After enrollment test: clear app provisioning extras (`SecurePreferences.clearProvisioningExtras`) and retain host DPAPI files only.

Tenant UUID for injection (non-secret): `830cb9cf-5717-4b68-8e29-dfb28ce755f3`.

**Enrollment token is single-use capability for one device identity path** — first live enrollment should use **one** chosen POS (recommend MP45P), not all three in parallel, unless a new token is issued.

---

## 6. Test readiness (crypto / protocol)

| Capability | Status | Evidence |
|---|---|---|
| EC P-256 keypair (Android Keystore) | **Ready** | `PdsDeviceKeyManager` — `secp256r1`, alias `pds_device_key_v1`, bound to server attestation challenge |
| SPKI public key export | **Ready** | `publicKeySpkiB64()` / cert `publicKey.encoded` Base64 |
| Enrollment challenge transcript + sign | **Ready** | `PdsEnrollmentCrypto.buildEnrollmentChallengeTranscript` + Keystore `signPayload` |
| Request field serialization | **Ready** | `PdsEnrollmentModels` + `PdsHttpEnrollmentTransport` |
| Quasar success / error envelopes | **Ready** | Stage 2.2B unwrap + 409 `enrollment_already_completed` recovery |
| Coordinator orchestration | **Ready** | `PdsEnrollmentCoordinator` / `PdsOnboardingEnrollmentFlow` / launcher `DeviceEnrollmentManager` |

### Missing / blocked for first live integration

1. **API base URL** not pointed at `127.0.0.1:4010`.
2. **Cleartext HTTP** not explicitly permitted by manifest/NSC.
3. **Device ↔ loopback** requires `adb reverse` (or other approved path).
4. **Enrollment token** not yet injected into any device (correctly deferred).
5. **BODY logging** should be reduced before live call to avoid token leakage.
6. App must be **built/installed** in a later approved stage (not done here).
7. **K11 (API 25)** incompatible with current `minSdk 26`.
8. StrongBox unlikely on these POS SoCs; TEE / software-keystore fallback path in `PdsDeviceKeyManager` should still apply.

---

## Blockers summary

| ID | Blocker | Severity |
|---|---|---|
| B-1 | `BASE_URL` defaults to `http://192.168.1.134:4000/api/v1/` | High |
| B-2 | No cleartext / network-security exception for HTTP loopback | High |
| B-3 | POS cannot reach host `127.0.0.1:4010` without `adb reverse` (or bind change) | High |
| B-4 | Token not on device yet (by design) | Expected |
| B-5 | OkHttp BODY logging may expose secrets during live test | Medium |
| B-6 | App not installed for this worktree build in this stage | Expected |
| B-7 | **K11 API 25 &lt; minSdk 26** — cannot install current APK | High (for K11 only) |

---

## Recommended execution sequence (next stages — not started)

1. **Select primary device:** USB **MP45P** (`HE8558H000567`); park K11 until minSdk policy is decided; treat D60 as secondary.
2. **Approve connectivity:** `adb -s HE8558H000567 reverse tcp:4010 tcp:4010`; keep Quasar on `127.0.0.1:4010`.
3. **Approve debug cleartext** for loopback (or temporary debug `network_security_config` allowing `127.0.0.1`) — controlled Android change in a later stage.
4. **Approve URL override:** gradle `-PLOCAL_IP=127.0.0.1 -PLOCAL_PORT=4010` **or** runtime `SecurePreferences` backend URL `http://127.0.0.1:4010/api/v1/`.
5. **Approve logging downgrade** for the enrollment session (`HEADERS` or `BASIC`, not `BODY`).
6. **Build & install** `launcherDebug` from `Dotroid-phase-1d` onto **MP45P only** (no Phase 1C touch).
7. **Decrypt DPAPI token on host → inject** tenant id + token + backend URL into that device; scrub host memory.
8. **Then** (separate approval): single challenge → verify enrollment against tenant `830cb9cf-5717-4b68-8e29-dfb28ce755f3`.
9. SELECT-only backend verification; clear device provisioning secrets after success/failure handling.

---

## Explicit non-actions (this stage / refresh)

- No API challenge or verify requests  
- No enrollment-token decryption  
- No DB writes / migrations  
- No Android install or enrollment  
- No backend or Android application code changes  
- No Phase 1C baseline changes  
- No staging/production access  

**Stop — awaiting approval before Stage 2.5B (connectivity/config) or first enrollment attempt.**
