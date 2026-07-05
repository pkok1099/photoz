# PhotoZ ROADMAP2 — Post-Beta Enhancement Plan

> Created: 2026-07-05
> Based on: TODO1.md (16 items) + ROADMAP.md (completed items)

---

## Summary

Dari 16 item di TODO1.md, status:

| Status | Count | Items |
|---|---|---|
| ✅ DONE | 4 | #1 QtFastStart, #5 Jetpack Security (verified skip), #7 VMK zeroing, #8 FLAG_SECURE, #10 Duress PIN |
| 📝 TODO | 8 | #2 Chunked encryption, #3 Argon2id, #4 TEE audit, #6 SQLCipher, #9 Root detection, #13 Rclone Phase 2, #15 TFLite, #16 M7 v2 merge |
| ⏸️ DEFERRED | 3 | #11 Key Attestation, #12 Play Integrity, #14 M6 Modularization |

**Sisa TODO yang perlu dikerjakan: 8 item**

---

## Phase 1 — Security Hardening (next 2-3 sessions)

### 1. SQLCipher untuk Room DB encryption (TODO #6) — HIGH PRIORITY
- **Why**: Room DB plaintext → investigator bisa mapping vault tanpa decrypt
- **Library**: `net.zetetic:android-database-sqlcipher` + Room `SupportFactory`
- **Key**: Derive dari VMK — vault lock = DB lock
- **Migration**: open plaintext → copy to encrypted → replace
- **Trade-off**: ~5-15% slower queries, +3-5MB APK
- **Est**: 1 session

### 2. Argon2id KDF upgrade (TODO #3) — HIGH PRIORITY
- **Why**: PBKDF2 rentan GPU/ASIC. Argon2id = memory-hard (2025 standard)
- **Library**: Bouncy Castle (`org.bouncycastle.crypto.generators.Argon2BytesGenerator`)
- **Migration**: New vaults = Argon2id, old = PBKDF2 (version dispatch)
- **Est**: 1 session

### 3. TEE key storage audit (TODO #4) — MEDIUM
- **Why**: Confirm KEK tidak di StrongBox (terlalu lambat)
- **Action**: Audit BiometricVaultProtectionHandler + KeyGen
- **Est**: 0.5 session (audit only)

---

## Phase 2 — Architecture Improvements (next 3-4 sessions)

### 4. Chunked streaming encryption (TODO #2) — DESIGN NEEDED
- **Why**: Per-chunk auth tag + random access decryption per-chunk
- **Challenge**: Format file berubah, backwards compat complex
- **Est**: 1-2 sessions (design + implement)

### 5. Rclone command stripping (TODO #13 Phase 2) — LOW EFFORT
- **Why**: Strip mount/serve/bisync/ncdu → save 5-10MB
- **Action**: Edit `cmd/all/all.go` in build-rclone.sh
- **Est**: 0.5 session

### 6. M7 v2 full multi-vault registry merge (TODO #16)
- **Why**: Current overwrite loses other vaults' entries
- **Action**: Read-existing + append on upload
- **Est**: 1 session

---

## Phase 3 — Feature Activation (on-demand)

### 7. L6 TFLite model activation (TODO #15)
- **Why**: Semantic search scaffold ready, needs model file
- **Action**: Add `mobilenet_v2.tflite` to assets/ + implement TfliteTagExtractor
- **Est**: 0.5 session (scaffold ready)

### 8. Root/debugger detection (TODO #9)
- **Why**: Casual root detection + debugger check
- **Action**: RootBeer or custom multi-check + isDebuggerConnected()
- **Est**: 0.5 session

---

## Deferred (no timeline)

### 9. Hardware Key Attestation (TODO #11)
- Enterprise only — not relevant for consumer vault

### 10. Play Integrity API (TODO #12)
- Play Store only — not viable for FOSS/F-Droid

### 11. M6 Modularization (TODO #14)
- Needs IDE refactor tooling (300+ files, circular deps)

### 12. Rclone gomobile JNI migration (TODO #13 Phase 3)
- Post-beta architecture cleanup
- Eliminates subprocess complexity (W^X, 16KB alignment)

---

## Completed in this cycle ✅

| Item | Description | Commit |
|---|---|---|
| TODO #1 | QtFastStart — MP4 MOOV relocation | `80b8838e` |
| TODO #5 | Jetpack Security — verified not used | N/A |
| TODO #7 | VMK zeroing — Destroyable.destroy() | `31d74f1a` |
| TODO #8 | FLAG_SECURE AAPM enforcement | `31d74f1a` |
| TODO #10 | Duress PIN — already done (M7+P3+L2) | N/A |
