# PhotoZ ROADMAP2 — Post-Beta Enhancement Plan

> Created: 2026-07-05
> Based on: TODO1.md (16 items) + ROADMAP.md (completed items)
> Migration update: rclone gomobile JNI migration (item #12) is DONE — see `TODO_SYNC.md`.

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
- **Trade-off**: ~5-15% slower queries, +3-5MB APK
- **Est**: 1 session

#### Design (revised for M7 multi-vault)

**Original plan**: "Derive DB key dari VMK — vault lock = DB lock"
**Problem**: M7 multi-vault allows multiple `vault_protection(Password)` rows
with distinct VMKs. A single DB file cannot be encrypted with multiple VMKs.
Chicken-and-egg: to read `vault_protection` table (which tells us which VMK
to use), we'd need the DB already open.

**Revised plan**: Split-room + shared DB key wrapped per-vault.

```
┌─────────────────────────────────────────────────────────────────┐
│  BootstrapDatabase (plaintext, Singleton)                       │
│  File: photok_meta.db                                           │
│  Tables: vault_protection (incl. wrapped_db_key column)         │
│  Open: always (no key required)                                 │
│  Reads: VaultService.unlock iterates rows to find matching VMK  │
└─────────────────────────────────────────────────────────────────┘
                              ↓ unwrap wrapped_db_key with VMK
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  PhotoZDatabase (encrypted with SQLCipher)                      │
│  File: photok.db                                                │
│  Tables: photo, album, album_photo_cross_ref, sort,             │
│          hash_registry                                          │
│  Open: only when vault unlocked (EncryptedDatabaseHolder)       │
│  Close: on session.reset() (vault lock)                         │
│  Key: random 32 bytes, generated on first vault creation.       │
│       Stored as wrapped_db_key in EACH vault_protection row     │
│       (same DB key, wrapped with each vault's VMK). This means  │
│       unlocking ANY vault opens the SAME encrypted DB; multi-   │
│       vault separation is enforced by the vault_id column on    │
│       every photo/album/hash_registry row (Sprint 2 / M7).      │
└─────────────────────────────────────────────────────────────────┘
```

**Key flow**:
1. First-time setup: `KeyGen.generateDbKey()` → 32 random bytes.
   Wrapped via AES-GCM with VMK → `wrapped_db_key` (12B IV + 32B ct + 16B tag = 60B).
   Stored in new `wrapped_db_key` column on `vault_protection` row.
2. Unlock: read `vault_protection` row that unwraps successfully → unwrap
   `wrapped_db_key` with VMK → open SQLCipher DB with that key.
3. New vault creation: unwrap existing `wrapped_db_key` from active
   session's row, re-wrap with new vault's VMK, store in new row.
4. Lock: close SQLCipher DB, zero DB key bytes.

**Why this is secure**:
- Disk forensic without vault password: cannot read `vault_protection`'s
  `wrapped_db_key` (encrypted with VMK-derived KEK), so cannot open
  `photok.db`. Cannot read photo metadata. ✓
- Disk forensic with vault password (one vault unlocked): can read that
  vault's photos, but other vaults' rows are still filtered out by
  `vault_id` column queries. (Decrypted DB contains all vaults' rows,
  but the app never shows cross-vault data.) ✓
- Runtime forensic (memory dump while unlocked): DB key + VMK both in
  memory. Same threat model as today (without SQLCipher). SQLCipher
  doesn't help here; it's purely at-rest protection. ✓

**Migration** (one-time, on first unlock after v15 → v16 upgrade):
1. Open old plaintext `photok.db` (still has `vault_protection` table).
2. Read each row, copy to `photok_meta.db` (new bootstrap DB).
3. Generate DB key, wrap with active session's VMK, store in
   `vault_protection` row (extended with `wrapped_db_key` column).
4. Open new SQLCipher `photok.db` (initially empty).
5. Use SQLCipher `ATTACH DATABASE 'old.db' AS old` + `INSERT INTO new
   SELECT * FROM old.<table>` for each non-vault_protection table.
6. Delete old plaintext `photok.db`.
7. Mark migration complete via Config flag.

**Schema changes** (DB v15 → v16):
- `vault_protection` table: add `wrapped_db_key BLOB NOT NULL` column.
  (Stored OUTSIDE the encrypted DB, in the new bootstrap plaintext DB.)
- All other tables: no schema change (they live in encrypted DB now,
  but Room doesn't care — same DAOs, same queries).
- New database file: `photok_meta.db` (plaintext bootstrap).
- Existing `photok.db` becomes SQLCipher-encrypted.

**Why a separate plaintext bootstrap DB instead of a JSON file**:
- Room gives us type-safe CRUD on `vault_protection` rows.
- Future migrations (adding fields to VaultProtectionParams) are
  handled by Room's AutoMigration, not ad-hoc JSON parsing.
- DAO-based queries stay consistent with the rest of the codebase.

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

### 12. Rclone gomobile JNI migration (TODO #13 Phase 3) — DONE (2026-07-08)
- Migrated to gomobile JNI (`libgojni.so` via `dlopen`, RC API). Eliminates subprocess
  complexity (W^X, 16KB alignment). Verification + leftover audit → `TODO_SYNC.md`.

---

## Completed in this cycle ✅

| Item | Description | Commit |
|---|---|---|
| TODO #1 | QtFastStart — MP4 MOOV relocation | `80b8838e` |
| TODO #5 | Jetpack Security — verified not used | N/A |
| TODO #7 | VMK zeroing — Destroyable.destroy() | `31d74f1a` |
| TODO #8 | FLAG_SECURE AAPM enforcement | `31d74f1a` |
| TODO #10 | Duress PIN — already done (M7+P3+L2) | N/A |
