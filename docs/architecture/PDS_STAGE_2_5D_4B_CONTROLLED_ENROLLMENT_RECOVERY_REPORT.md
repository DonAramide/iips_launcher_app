# PDS Stage 2.5D.4B — Controlled Enrollment Recovery

**Status:** STOPPED — Phase 4 engaged once; enrollment rejected **401** (MDM profile token valid; `device_enrollment_tokens` path mismatch)  
**Date:** 2026-11-07  
**Authorized device:** MP45P / `HE8558H000567` / API 27 / `com.iips.dotroid`  
**Authorized backend:** `C:\dev\quasar-phase-1d-2` / `127.0.0.1:4010` / DB `pds_phase1d_integration` / tenant `830cb9cf-5717-4b68-8e29-dfb28ce755f3`

**Not done:** no successful challenge/verify; no second intentional CONTINUE; no enrollment-token rotation/disclosure; no DB writes; no migrations; no push/merge/commit/deploy; no K11/D60/Samsung ADB usage; no policy/trust-store/attestation weaken. Ngrok `apk_url` from operator payload was **not** installed.

---

## Verdict

| Phase | Result |
|---|---|
| 1–3 (prior) | **COMPLETE** — alias deleted; pending/bind cleared |
| 4 Step 1 Infrastructure | **PASS** — PG `:55580`; API PID **28364** cwd `quasar-phase-1d-2\iips-pay`; env `DATABASE_PORT=55580` / `DATABASE_NAME=pds_phase1d_integration`; `GET /health` 200 |
| 4 Step 2 Device preflight | **PASS** — MP45P online; reverse `tcp:4010`; pending/bind absent; ScrollView APK on device (`setup_scroll`) |
| 4 Step 3 One CONTINUE | **ENGAGED** — `OnboardingActivity: CONTINUE clicked`; network enroll returned **401** `DeviceNotActivatedException` (~1.3s later). No pending/bind created |
| 4 Step 4 DB verification | **SELECT-only** — devices still **0**; no new challenge row; operator token **matches** `mdm_provisioning_profiles` but **not** `device_enrollment_tokens` |
| 4 Step 5 Report | This document |

---

## Timeline (2026-11-07 resume)

1. Operator supplied Phase-1D-shaped payload (`backend_url=http://127.0.0.1:4010/api/v1` + enrollment_token). Token stored DPAPI-only; **not** printed.  
2. Injected via `pds-2.5d2-inject-token.ps1` → `import_status.txt` `token_present=true`, tenant UUID match.  
3. Confirmed listener cwd is Phase 1D worktree (native modules resolve via linked `node_modules`).  
4. Setup UI: one swipe reveals password fields under `setup_scroll`. Filled Business Name `PDS Phase1D Integration`, local admin password/confirm; cleared Institutional Code.  
5. **Exactly one** CONTINUE at ~04:37:54 local → log `CONTINUE clicked` → enroll **401**.  
6. SELECT-only: presented token MD5 prefix **matches** active `mdm_provisioning_profiles` (`backend_url=http://127.0.0.1:4010/api/v1`, uses 0) but **≠** `device_enrollment_tokens` row for same tenant.  
7. **STOP** — no second CONTINUE; no token rotation.

---

## 1. Infrastructure — PASS

| Check | Result |
|---|---|
| `pds_phase1d_integration_pg` | Up; `127.0.0.1:55580→5432` |
| API process | PID **28364** — cwd `C:\dev\quasar-phase-1d-2\iips-pay` |
| Process env | `PORT=4010` `LISTEN_HOST=127.0.0.1` `DATABASE_PORT=55580` `DATABASE_NAME=pds_phase1d_integration` |
| Health | 200 |
| Redis | `:6379` OK |

---

## 2. Device preflight — PASS

| Check | Result |
|---|---|
| Serial / model | `HE8558H000567` / **MP45P** |
| Reverse | `(reverse) tcp:4010 tcp:4010` |
| Pending / bind | Absent before and after failed enroll |
| Import | `token_present=true`; tenant `830cb9cf-5717-4b68-8e29-dfb28ce755f3` |
| Other USB | K11 + Samsung visible; **unused** |

---

## 3. Enrollment attempt — ENGAGED, FAILED (401)

### 3.1 Inputs

| Field | Value |
|---|---|
| Business Name | `PDS Phase1D Integration` |
| Local admin password | Same Stage 2.5D.3 local password (not printed) |
| Institutional Code | Cleared |
| Enrollment token | Operator-supplied `:4010` payload (DPAPI + SecurePreferences); field left to prefs fallback |
| CONTINUE | **Once** |

### 3.2 Evidence

| Event | Evidence |
|---|---|
| CONTINUE | `I OnboardingActivity: CONTINUE clicked (setup step)` @ 04:37:55 |
| HTTP outcome | `E DeviceEnrollmentManager: DeviceNotActivatedException: Device is not activated or token is invalid. (401)` @ 04:37:57 |
| Pending / bind | Still absent |
| UI after | Remained on Business Setup (toast failure path) |
| New challenge | **None** |

Per Stage 2.5D.3 rules this **counts** as the authorized network attempt (CONTINUE log + enroll error). **No second CONTINUE.**

### 3.3 Root cause (SELECT-only)

| Store | Token length | MD5 prefix (8) | Matches presented? |
|---|---|---|---|
| Presented (operator payload) | 43 | `73689522` | — |
| `mdm_provisioning_profiles` (tenant, active, uses 0/∞) | 43 | `73689522` | **Yes** + `backend_url` loopback `:4010` |
| `device_enrollment_tokens` (tenant) | 43 | `771c7bfa` | **No** |

Hypothesis: the engaged enroll HTTP path validated against `device_enrollment_tokens` (or equivalent) and returned **401**, despite a valid Quasar MDM/QR profile token. Expired unconsumed challenge `chl-128cd484-…` unchanged; devices **0**.

---

## 4. Isolation confirmation

- Only MP45P serial used for ADB enroll path.  
- Backend = Phase 1D worktree + `pds_phase1d_integration`.  
- No token print/rotation; no DB mutation; no commit/push.  
- Ngrok APK URL ignored.

---

## 5. Resume requirements (new authorization needed)

1. Align enroll validation with the Quasar MDM/QR profile token **or** issue a matching `device_enrollment_tokens` row for tenant `830cb9cf-…` in Phase 1D (outside this STOP).  
2. Re-authorize a successor stage for **one** new CONTINUE after that fix.  
3. Do **not** reuse this failed attempt as a silent retry under 4B.

**STOP after this report. No subsequent stage started.**
