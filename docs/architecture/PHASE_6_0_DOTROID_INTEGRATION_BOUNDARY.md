# Phase 6.0 — Dotroid Integration Boundary (Companion)

**Status:** READ-ONLY architecture companion  
**Worktree:** `C:\dev\Dotroid-phase-1d`  
**Branch:** `feat/phase-1d-android-enrollment`  
**HEAD (actual):** `c4fed4e3a6a40085ea129ae014e783233ee891b5`  
**Stage 1 baseline:** `bf29d04e5dd40daaab581d38b4bc55e4e705d98b` (1 commit behind HEAD)  
**Authoritative Quasar docs:** `C:\dev\quasar-phase-1d-2\docs\architecture\PHASE_6_0_*.md`

This file does **not** duplicate the full Quasar contract. It records Dotroid-side capabilities and the agreed boundary.

---

## 1. What this worktree is

Android Device Owner / launcher / Guard / **PDS enrollment agent**. It talks to **Quasar Backend** for:

- Dual-hop enrollment: PDS challenge/verify → MDM `device/register` with `known_device_id` = Quasar UUID  
- Identity status, keystore-bound key material, signed manifest / parameters  
- Heartbeat / events / inventory telemetry (HMAC-signed where applicable)  
- Commands via WebSocket `do-mdm/devices/ws` and/or HTTP poll + ack (`PdsCommandGate` JWS + local replay ledger)  
- Geofence config / policy pull  

Canonical device id after enrollment: **Quasar `devices.id`**.  
Local `device_identity_ref` / `device_key_id` are bindings — not competing primary keys.  
Guard flavor `manager/*` APIs are a separate plane from POS Suite `/tenant-portal/*`.

---

## 2. What this worktree is not

- Not Quasar Web (internal admin)  
- Not Dotroid customer Web (that lives in `C:\dev\Dotroid_Website`)  
- Not an inventory or institution authority  
- Not a second device identity database of record  

---

## 3. Customer portal boundary

Customer fleet UX is **Dotroid Web → Quasar `/api/v1/tenant-portal/*`** (Tenant JWT).  
Remote lock/geofence **administration** for customers is largely **PROPOSED** on Quasar tenant APIs; device-side execution already exists here.

Android must continue to:

- Accept only Quasar-authenticated commands  
- Enforce local command idempotency (`executed_command_ids`)  
- Respect lifecycle-allowed command sets  
- Preserve attestation / keystore binding strength  

---

## 4. Phase 6.0 constraints observed

- No application source modifications in this phase  
- Dirty local enrollment files preserved  
- No remote commands sent  
- No physical enrollment performed  

---

## 5. Pointers

| Topic | Quasar document |
|-------|-----------------|
| Full audit | `PHASE_6_0_CROSS_PLATFORM_ARCHITECTURE_AUDIT.md` |
| API contract | `PHASE_6_0_QUASAR_DOTROID_INTEGRATION_CONTRACT.md` |
| AuthZ matrix | `PHASE_6_0_DEVICE_OWNERSHIP_AND_AUTHORIZATION_MATRIX.md` |
| Events | `PHASE_6_0_DEVICE_LIFECYCLE_AND_EVENT_MODEL.md` |
| ADRs | `PHASE_6_0_ARCHITECTURE_DECISIONS.md` |
| **Product decision closure (6.0.1)** | `PHASE_6_0_1_PRODUCT_DECISION_CLOSURE.md` |
| Roadmap | `PHASE_6_0_IMPLEMENTATION_ROADMAP.md` |

### 6.0.1 freezes relevant to Dotroid

- POS Suite = **fleet-only** UX (no financial admin nav).  
- Visibility = ENROLLED inventory bind only; suspended = read-only.  
- Customers never see pre-enrollment allocated inventory.  
- Customer commands = tenant-scoped design only until Phase 6.2.
