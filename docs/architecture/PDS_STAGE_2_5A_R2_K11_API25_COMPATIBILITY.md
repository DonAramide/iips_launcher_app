# PDS Stage 2.5A-R2 — Horizonpay K11 API 25 Compatibility Assessment

**Status:** Inspection only — complete. No minSdk, dependency, source, manifest, or build-config changes.  
**Date:** 2026-11-05  
**Device under assessment:** Horizonpay **K11** (`0123456789ABCDEF`), Android **7.1.2 / API 25**, USB, `adb reverse` supported  
**Worktree:** `C:\dev\Dotroid-phase-1d` @ `feat/phase-1d-android-enrollment` / `c4fed4e`

---

## Recommendation (executive)

**Keep K11 unsupported and retain `minSdk 26`.**

Do **not** lower minSdk or ship an API 25 compatibility path for Phase 1D enrollment until (a) product explicitly accepts a dedicated, tested API 25 track and (b) Keystore attestation quality on this Horizonpay/msm8909 firmware is proven acceptable. A “quick” minSdk drop would still leave hard runtime and trust blockers.

---

## 1. Current minSdk and product flavors

| Setting | Value |
|---|---|
| `minSdk` | **26** |
| `targetSdk` | 34 |
| `compileSdk` | 34 |
| Flavors | `launcher` (`com.iips.dotroid`), `guard` (`com.iips.dotroid.guard`) |
| Build types | `debug` / `release` |
| Debug API URL (Stage 2.5B) | `http://127.0.0.1:4010/api/v1/` |

**Install gate:** Package Manager rejects APKs with `minSdk 26` on API **25**. K11 cannot install the current APK as built.

---

## 2. PDS enrollment / Keystore / EC P-256 API usage

Primary implementation: `PdsDeviceKeyManager`, `PdsEnrollmentCrypto`, transport/models under `security/pds` + `network`.

| Capability | Implementation | Platform API floor | On API 25 |
|---|---|---|---|
| EC P-256 in Android Keystore | `KeyPairGenerator` + `ECGenParameterSpec("secp256r1")` + `KeyGenParameterSpec` | Keystore EC / `KeyGenParameterSpec`: **API 23+** | Available in platform |
| Attestation-bound generation | `KeyGenParameterSpec.setAttestationChallenge(...)` | **API 24+** | Available in platform |
| Sign enrollment transcript | `Signature("SHA256withECDSA")` via Keystore private key | API 23+ Keystore | Available in platform |
| StrongBox | `setIsStrongBoxBacked` gated to API **28+** | API 28+ | Not used (correctly skipped) |
| Hardware backing classify | `KeyInfo` / `isInsideSecureHardware`; API 31 `securityLevel` | Docs assume API 26–27 path | Would use pre-28 TEE/software classification **if** app ran |
| SPKI / nonce encoding | **`java.util.Base64`** in production PDS code | **`java.util.Base64` = API 26+** | **Hard runtime failure** without desugar/replacement |

### Critical crypto-adjacent finding: `java.util.Base64`

Production PDS code imports `java.util.Base64` in at least:

- `PdsDeviceKeyManager.kt` (SPKI / cert chain encoding)
- `PdsEnrollmentCrypto.kt` (challenge decode, recovery nonce)
- `PdsCanonicalCommandEnvelope.kt` (signature base64url)
- `PdsCommandVerifier.kt`

There is **no** `coreLibraryDesugaring` in `app/build.gradle`.  
On API 25, first touch of `java.util.Base64` → `NoClassDefFoundError` / similar, **before** enrollment can complete, even if minSdk were lowered.

Other modules already use `android.util.Base64` (API 8+) in places — PDS path does not.

### Additional PDS JDK APIs (API 26+ without desugar)

| API | Used in | Floor without desugar |
|---|---|---|
| `java.time.Instant` / formatters | `PdsCanonicalCommandEnvelope`, `PdsPendingEnrollmentStore` | API **26+** |
| `java.nio.file.Files` / `Path` | `PdsEnrollmentStateStore`, `PdsAttestationBindingStore`, `PdsPendingEnrollmentStore`, replay store | API **26+** |

These reinforce that the **current** PDS persistence/crypto helpers are packaged for API 26+, not API 25.

### Keystore security on API 25 (without weakening crypto)

- Algorithm choice (P-256, SHA-256 ECDSA, non-exportable Keystore key) is **not** inherently API-26-only.
- StrongBox is unavailable below API 28; app already falls back to TEE Keystore — **no crypto weakening required** for that path.
- **Attestation quality** on Horizonpay K11 (msm8909, API 25, `user/dev-keys` fingerprint) is **unproven**. Many API 24–25 devices produce missing, broken, or software-backed attestation chains. Accepting weak attestation to “make K11 work” **would** weaken PDS trust policy — not recommended.
- No on-device keygen was performed in this inspection (per stage rules).

---

## 3. Dependencies / libraries vs API 26+

| Area | Observation |
|---|---|
| App `minSdk 26` | Sole hard install blocker today |
| `androidx.security:security-crypto` + `MasterKey` | Supports API 23+ for AES256_GCM scheme used by `SecurePreferences` — not an API-26 exclusive |
| Retrofit / OkHttp | Fine on API 25 |
| CameraX / ML Kit | Generally minSdk 21; not the enrollment crypto blocker |
| Hilt / Room / WorkManager | Compatible with API 25 at library level in typical configs |
| `coreLibraryDesugaring` | **Not enabled** → JDK 8+ APIs like `java.util.Base64` are **not** backported |

No dependency audit replaces the app-level minSdk gate; lowering minSdk without fixing Base64 (and retesting) is unsafe.

---

## 4. Network security and HTTP connectivity

| Topic | API 25 (K11) | Notes |
|---|---|---|
| Cleartext HTTP default | Allowed by platform default (pre-API 28) | Unlike API 28+ tablets |
| Stage 2.5B debug NSC | Loopback-only cleartext for debug | Harmless on API 25; not required for cleartext |
| Loopback via `adb reverse` | Supported on this USB device | Connectivity path OK if app ran |
| Release HTTPS-only posture | Unchanged | Not affected by this assessment |

Network is **not** the primary K11 blocker.

---

## 5. Does existing enrollment crypto “work” on API 25?

| Layer | Verdict |
|---|---|
| Install current APK | **No** (`minSdk 26`) |
| Run existing PDS code as-is after forced sideload/hypothetical minSdk drop | **No** — `java.util.Base64` crash |
| Platform Keystore P-256 + attestation challenge APIs | **Present** on API 25 |
| End-to-end PDS enrollment with current Quasar trust expectations | **Unknown / high risk** — attestation/TEE quality on this firmware not validated; StrongBox N/A |

**Conclusion:** The **existing** application cannot safely enroll on K11. Cryptographic *algorithms* are supportable in principle; the *current implementation and packaging* are not API-25-safe.

---

## 6. Is a safe compatibility implementation possible without weakening crypto?

| Approach | Safe without crypto weakening? | Effort / risk |
|---|---|---|
| Lower `minSdk` only | **No** — Base64 crash; attestation unknown | Unsafe |
| Lower minSdk + replace `java.util.Base64` with `android.util.Base64` (or enable desugar) + full PDS regression | **Possibly**, **if** Keystore attestation on K11 meets Quasar policy **without** relaxing trust tiers | Non-trivial engineering + device attestation lab work |
| Accept software keys / skip attestation for K11 | **No** — weakens cryptographic security | Reject for Phase 1D |
| Vendor upgrades K11 to API ≥ 26 (or ships attested Keystore firmware) | Best long-term if vendor can deliver | Firmware/SDK dependency |

A properly tested API 25 track is **possible only as a deliberate project**, not as a config tweak, and only if attestation remains at least TEE-backed with Quasar-acceptable chains.

---

## Fleet comparison

| Criterion | K11 (API 25) | MP45P (API 27) | D60 (API 30) |
|---|---|---|---|
| Connection | USB | USB | TCP/IP |
| Install with `minSdk 26` | **Fail** | Pass | Pass |
| `java.util.Base64` | Crash if forced | OK | OK |
| Keystore EC P-256 APIs | Platform OK | OK | OK |
| StrongBox | No | No | Unlikely / not advertised |
| App StrongBox fallback (TEE) | Would apply | Applies | Applies |
| Cleartext to loopback | Platform allows | Platform allows (pre-28) | Needs Stage 2.5B debug NSC (API 30) |
| `adb reverse` | Supported | Supported | Supported |
| Phase 1D primary candidate | **No** | **Yes** | Secondary |

---

## Blockers summary (K11)

| ID | Blocker | Type |
|---|---|---|
| K-1 | App `minSdk 26` > device API 25 | Packaging / install |
| K-2 | PDS uses `java.util.Base64`, `java.time`, `java.nio.file` without desugaring | Runtime / implementation |
| K-3 | Key attestation / TEE quality on msm8909 API 25 firmware unproven | Trust / vendor |
| K-4 | Supporting API 25 expands test matrix without StrongBox benefit | Process / risk |

---

## Final recommendation (choose one)

**Selected: Keep K11 unsupported and retain `minSdk 26`.**

- Continue Phase 1D first enrollment on **MP45P**, then **D60**.
- Treat K11 as out-of-scope unless a future approved stage funds Base64/API-25 remediation **and** vendor attestation validation.
- **Vendor firmware/SDK changes** are required if product insists on this exact K11 image without accepting a separate, attested API-25 engineering track.

---

## Explicit non-actions

- No minSdk / dependency / source / manifest / build changes  
- No install, launch, enrollment, reverse configuration, backend or DB changes  

**Stop.** Awaiting approval to proceed with MP45P connectivity/install (not K11).
