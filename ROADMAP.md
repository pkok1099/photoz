# PhotoZ Development Roadmap

> Last updated: 2026-07-04
> Status: Active — all items below are planned, not yet implemented unless marked ✅

---

## Current State (versionCode 94)

| Category | Status |
|---|---|
| Encryption | AES-256-CBC + VMK + key escrow 2-layer (password wraps phrase, phrase wraps VMK) |
| Cloud Sync | rclone subprocess (CGO+NDK+16KB aligned), dedup (content-hash), GCM encrypted registry (GZIP compressed), thumbnail packing (5MB packs) |
| Video | Progressive streaming (playback starts while download in progress) |
| Media | Photos + files (PDF/ZIP/audio), auto-album from folder path |
| Security | Auto-lock (persisted), stealth mode, FLAG_SECURE, hash verification (optional), panic wipe |
| UI | Material 3 partial (Compose in gallery, XML in settings/setup) |
| Storage | Recycle bin (soft delete, 30-day retention, DB v10), storage analytics, .crypt toggle + manual clean |
| Backup | ZIP export/import, cloud restore (thumbnails only, originals on-demand) |
| Testing | 4 unit test files (HashRegistry, SyncBatchTracker, Dedup, PhraseEscrowWrapper), ProGuard rules |
| Search | Filename search + type filter chips (All/Photos/Videos/Files) |

---

## JANGKA PENDEK (1-2 weeks per item)

### P4. Dynamic Color (Material You) — TO DO
**Reason**: Android 12+ default. Apps that don't support it look outdated.

**Implementation**:
- `themes.xml`: `Theme.Material3.DynamicColors.DayNight`
- `BaseApplication.onCreate()` or `MainActivity`: `DynamicColors.applyToActivityIfAvailable(this)`
- Verify all Compose components use `MaterialTheme.colorScheme.*` (not hardcoded colors)
- Est: ~50 lines + audit hardcoded colors

### P5. Edge-to-Edge Display — TO DO
**Reason**: Android 15+ mandatory. `enableEdgeToEdge()` already in MainActivity but needs verification across all screens.

**Implementation**:
- Audit all Fragment/Screen: ensure `WindowInsets.statusBars` / `WindowInsets.navigationBars` handled
- Fix hardcoded status bar padding
- Est: ~100 lines (mostly fixes)

### P6. AES-256-GCM for New Files (Upgrade from CBC)
**Reason**: 2026 trend — GCM is the standard (authentication tag prevents bit-flipping). Currently only registry uses GCM; photo files still use CBC.

**Implementation**:
- New files: use GCM (version byte 0x03), old files stay CBC (backwards compat)
- `CbcCryptoEngine` → new `GcmCryptoEngine` or extend existing with version dispatch
- `AesCbcRandomAccessDataSource` → add GCM support (GCM supports random access via counter mode)
- Est: ~150 lines

### P7. Strong Password Enforcement
**Reason**: 2026 forensics research shows 6-digit PINs "can always be cracked" by Cellebrite-class tools via TEE exploits. Strong passwords are essential.

**Implementation**:
- Minimum 8 characters for master password
- Strength indicator (already exists — verify it enforces minimum)
- Refuse PIN-only (4-6 digits) as master password
- Est: ~50 lines

---

## JANGKA MENENGAH (2-4 weeks per item)

### M1. Full Jetpack Compose Migration
**Reason**: Settings, Setup, Unlock still use XML (ViewBinding). Compose = faster development, consistency, declarative.

**Implementation**:
- Migrate `SetupFragment` → Compose (partial — phrase display already Compose)
- Migrate `UnlockFragment` → Compose
- Migrate `SettingsFragment` → Compose (partial — `SettingsScreen` Compose exists but `SettingsFragment` is XML host)
- Migrate import/export dialogs → Compose
- Remove ViewBinding dependencies after completion
- Est: ~2000 lines rewrite (significant but decreases long-term maintenance)

### M2. Favorites & Pin
**Reason**: Standard gallery app feature. Users want to mark favorite photos for quick access.

**Implementation**:
- DB: add `is_favorite: Boolean` column (v10→v11 migration)
- Gallery: heart icon badge on thumbnail + filter chip "Favorites"
- Sort: "Favorites first" option
- Est: ~200 lines

### M3. Advanced Sort & Filter
**Reason**: Users with 1000+ photos need granular filtering.

**Implementation**:
- Sort by: date (asc/desc), name, size, type, album
- Filter by: date range (calendar picker), type, album, favorites, file size range
- Save filter preset
- Est: ~300 lines

### M4. Metadata Search (EXIF)
**Reason**: Search by date, location, camera — not just filename.

**Implementation**:
- Extract EXIF at import (date taken, GPS coordinates, camera model)
- Store in registry (encrypted)
- Search: "date:2024-01", "camera:Canon", "location:Jakarta"
- Est: ~400 lines

### M5. Encrypted Thumbnail Cache Rotation
**Reason**: Thumbnail `.crypt.tn` files accumulate in `filesDir`. Need cache management.

**Implementation**:
- LRU cache: delete thumbnails not accessed > 30 days (if syncState=UPLOADED)
- Settings → "Cache size limit" (e.g. 500 MB)
- Est: ~200 lines

### M6. Codebase Modularization
**Reason**: Monolith is too large (300+ files). Modularization = faster builds, clearer boundaries.

**Implementation**:
- `:app` — UI layer only
- `:core` — encryption, crypto, models
- `:sync` — rclone, sync worker, registry
- `:data` — Room DB, DAOs, repositories
- Est: ~500 lines Gradle config + package moves

### M7. Cryptographic Plausible Deniability (Multi-Vault, No Flag) — IN PROGRESS (Sprint 2 done, UI Sprint 3)
**Reason**: 2026 research shows decoy vaults with config flags CAN be detected by forensic tools (Cellebrite finds flag → proves concealment). Solution: pattern-based key derivation — every password derives a different vault. No flag, no master index, no concept of "real" vs "decoy" in the data model.

**Design principles (final, post-design-review)**:
1. **Unlimited vaults** — every distinct password derives a distinct vault. App only knows "the vault that unlocked". No real/decoy distinction anywhere.
2. **No flags** — no `is_real`, no `is_decoy`, no `vault_role`. Vault identity is derived cryptographically from VMK, not stored as metadata.
3. **No stock photos in decoys** — same stock photos across installs = fingerprint. Decoy starts empty; user fills it themselves if they want.
4. **No separate registry files** — `registry.real` / `registry.decoy` would expose the existence of multiple vaults to remote forensic access.
5. **First vault syncs; additional vaults local-only** — implicit signal: vault WITH recovery phrase = syncs. Vaults without recovery phrase = local-only. No flag, no UI exposure.
6. **Recovery phrase: first vault only** — additional vaults are password-only, local-only. Decoy is sacrificial by design — forgetting the decoy password = losing decoy data, which is acceptable for the use case.

**Schema change (DB v10 → v11)**:
- `Photo` table: add `vault_id TEXT NULL` column (nullable for backfill during migration)
- `Album` table: add `vault_id TEXT NULL` column
- `HashRegistryEntry` table: add `vault_id TEXT NULL` column
- `VaultProtection` table: NO schema change — already allows multiple Password-type rows (no unique constraint on type)

**Vault ID computation**:
```
vault_id = HMAC-SHA256(VMK, "photoz-vault-id-v1").take(16 bytes).toHex()
```
- Computed once per unlock from VMK in memory
- Stored on `VaultSession` (in-memory only — never persisted)
- Used to filter all DB queries (WHERE vault_id = ?)
- Migration backfill: on first unlock after upgrade, compute vault_id from current VMK, UPDATE all rows WHERE vault_id IS NULL

**Unlock flow (multi-vault aware)**:
1. User enters password
2. Iterate ALL `VaultProtection` rows of type `Password`:
   - For each: derive KEK from password + row's salt
   - Try to unwrap the row's wrapped VMK (GCM decrypt — auth tag verifies the password is correct for THIS row)
   - First success = active vault (return its VMK + compute vault_id)
3. If no row succeeds → "wrong password" error (same UX as today)
- Performance: N rows × PBKDF2(100k iterations) ≈ 100N ms. N=5 → 500ms (acceptable). N=10 → 1s (annoying but rare).

**Create new vault flow** (Settings → "Create new vault"):
1. User enters new password (must NOT unlock any existing vault — verify by running unlock flow above)
2. Generate fresh VMK (`KeyGen.generateVaultMasterKey()`)
3. Derive KEK from new password + fresh salt
4. Wrap VMK with KEK → new `VaultProtection(Password)` row
5. Compute new vault_id from new VMK
6. Reset session, unlock with new password → active vault is the new (empty) one
7. NO recovery phrase created → no sync (vault is local-only by design)
8. User can now import photos into the new vault; they're tagged with the new vault_id

**Switch vault flow**:
- "Switch vault" in Settings → user enters different password → existing unlock flow runs → different vault unlocks
- Or simply: lock app from Settings → unlock screen → enter different password
- No explicit "switch" UI needed — password IS the vault selector

**Sync model**:
- `HashRegistry.flushRegistryIfBatchComplete()`: only serialize entries WHERE `vault_id = <syncing_vault_id>`
- `RepoManager.downloadRegistry()`: upsert entries with the syncing vault's vault_id
- `SyncRestorer.restoreAllOriginals()`: only restore photos WHERE `vault_id = <syncing_vault_id>`
- "Syncing vault" = the vault that has a `VaultProtection(RecoveryPhrase)` row. Implicit signal — no flag stored.

**PhotoRepository changes**:
- All `PhotoDao` queries: add `WHERE vault_id = ?` filter using current session's vault_id
- `safeImportPhoto()`: set `photo.vault_id = session.vaultId` before insert
- Album auto-create: only link to albums in the same vault
- Delete: only affect photos in current vault (per-album delete via symlink, see M10)

**Migration v10 → v11**:
- AutoMigration adds nullable `vault_id` column to 3 tables
- Backfill NOT done in migration (no VMK available in migration context)
- Backfill done lazily in `VaultService.unlock()` post-success: if any row has `vault_id IS NULL`, compute from current VMK and UPDATE all
- One-time cost on first unlock after upgrade — transparent to user

**What this design AVOIDS (forensic-resistance)**:
- ❌ No `is_real` / `is_decoy` flag anywhere (DB, registry, file headers)
- ❌ No `vault_count` or `vault_index` metadata
- ❌ No separate registry files per vault (single `registry.json.crypt`)
- ❌ No stock photo fingerprints
- ❌ No "wrong password" differentiation (decoy password succeeds like any other)

**Future (M7 v2, not in this sprint)**:
- Per-entry encrypted registry (1 file, multiple VMKs) — would allow all vaults to sync without exposing vault count
- Per-vault recovery phrase (UX burden, deferred)
- Storage padding (decoy vaults padded to plausible size) — currently decoys start empty, which IS suspicious to a careful forensic analyst who knows the app; padding is a future hardening

**Estimate**: ~800 lines + DB migration v10→v11

### M8. BFU-Safe Key Handling (Re-key on Backgrounding) — DONE (Sprint 3)
**Reason**: 2026 trend — BFU (Before First Unlock) state is dramatically harder for forensics. VMK should never persist in CE storage when app is backgrounded.

**Implementation**:
- `onStop()`: clear VMK from memory (`sessionRepository.reset()`) — verify this already happens
- VMK only in memory when app is foregrounded + unlocked
- Audit: no VMK leak to SharedPreferences, logs, or crash dumps
- Est: ~100 lines (mostly audit + fix)

### M9. Material 3 Expressive + Compose Adaptive Layouts
**Reason**: 2026 trend — Material 3 Expressive (2025 release) + Compose adaptive (foldable/tablet support) becoming standard. 60% of top-1000 Play apps use Compose.

**Implementation**:
- Update to Material 3 Expressive components (new motion, new shapes)
- Adaptive layout: `WindowSizeClass` for phone/tablet/foldable
- Predictive back gesture support
- Two-pane layout for tablet (gallery + detail side by side)
- Est: ~400 lines

### M10. Embedded Photo Picker (Android 16+) + Path Maker + Symlink Album — PARTIAL (Photo Picker + Path Maker v1 done in Sprint 3; symlink album deferred)
**Reason**: Android 16 Embedded Photo Picker — no `READ_MEDIA_IMAGES` permission needed. App only receives temporary URI, cannot access full gallery. Major privacy win. **However**, Photo Picker URI does NOT expose `RELATIVE_PATH` (by design — privacy), so the auto-album-from-folder logic in `PhotoRepository.ensureAlbumForPhoto` won't fire for picker imports. We need an explicit Path Maker UX to compensate.

**Implementation — 3 parts**:

**Part 1: Dual import entry point (mandatory)**
- Keep existing MediaStore import ("Import from Gallery") — auto-album from `RELATIVE_PATH` works as-is
- Add "Import via Photo Picker" entry — uses `PickVisualMedia` (no `READ_MEDIA_IMAGES` permission)
- User chooses import source at gallery overflow menu
- Note: rclone upload path (`remote:photoz-backup/originals/<uuid>.crypt`) is UNCHANGED — picker only changes the **source URI reader**, not the encrypted-file path

**Part 2: Path Maker dialog (for picker + MediaStore override)**
- Triggered after photo(s) selected (both picker AND gallery — picker is mandatory, gallery is optional override)
- Default suggestion: hard-coded `"Picker"` album for picker imports; `RELATIVE_PATH` for MediaStore (current behavior preserved)
- UI:
  - Show N selected photo count
  - Album dropdown (existing albums) + "Create new album" option
  - "Save to album: [____]" — one-shot for all N photos in this batch
  - Warning banner if picker is used: "Photo Picker tidak menyimpan path asli. Pilih album tujuan."
- On confirm: write `albumPath` to Photo row + call `ensureAlbumForPhoto()` (existing logic reuses)

**Part 3: Symlink album (canonical UUID scheme)**
**Problem solved**: User imports `A/B/photo.jpg` then later imports same hash from `C/D/photo.jpg`. Currently dedup deletes the second import entirely — photo only appears in album "A/B". With symlink, the photo can appear in BOTH albums without duplicating the encrypted file.

**Schema change (DB v10 → v11)**:
- Add `canonical_uuid TEXT NULL` column to `Photo` table
- `canonical_uuid = NULL` → this Photo OWNS the encrypted file (its UUID is the file's UUID)
- `canonical_uuid = <other-uuid>` → this Photo is a SYMLINK; the encrypted file lives under the canonical UUID
- File lookup helper: `internalFileName(photo.canonicalUuid ?: photo.uuid)` — applies to original, thumbnail, video preview

**Dedup at import time (modified behavior)**:
- When `findByContentHash(hashHex)` matches an existing Photo:
  - Do NOT delete the new Photo row
  - Set `canonical_uuid = existingPhoto.uuid` on the new Photo
  - Do NOT write new encrypted file (reuse canonical's)
  - Do NOT add new registry entry (canonical's entry covers it)
  - But DO call `ensureAlbumForPhoto(newPhoto)` so the symlink lands in the user's chosen album

**Delete semantics (per-album, refcounted)**:
- Delete Photo row X:
  - If X has `canonical_uuid = NULL` (it's a canonical):
    - Query: any other Photo with `canonical_uuid = X.uuid`?
    - If YES: promote the oldest such Photo to canonical — set its `canonical_uuid = NULL`, copy X's `contentHash` to it (already same), keep X's encrypted file (rename `<X.uuid>.crypt` → `<promoted.uuid>.crypt`? NO — too expensive). Instead: leave file as `<X.uuid>.crypt` but update `promoted.canonical_uuid = NULL` and `promoted.uuid = X.uuid` (swap UUIDs in DB row) — OR keep `promoted.uuid` and update file lookup to use `promoted.canonicalUuid ?: promoted.uuid` where `canonicalUuid` now points to X's uuid (the file's actual UUID).
    - **Simpler approach**: file lookup is always `canonicalUuid ?: uuid`. When deleting canonical X, find oldest symlink S, set `S.canonical_uuid = NULL` AND keep file as `<X.uuid>.crypt` — but then S.uuid != file UUID. Need a `file_uuid` column OR keep `canonical_uuid` set to X's uuid on S (so S still references X's file) and just mark X as deleted. **Decision: file UUID is decoupled from Photo UUID**. Add `file_uuid` column (defaults to own uuid for new rows). Lookup: `internalFileName(photo.fileUuid)`.
  - If NO symlinks: delete file physically (local + remote), mark registry entry `deleted=true`
- Delete Photo row X where `canonical_uuid != NULL` (it's a symlink):
  - Just delete the DB row. File untouched (canonical still references it).

**Registry extension (backwards-compatible)**:
- Current `HashRegistryEntry.albumPath: String?` (single album per hash)
- Add `additionalAlbums: List<String>?` (null for old entries, populated when symlinks exist)
- On symlink creation: append new albumPath to canonical's `additionalAlbums` in registry
- On symlink deletion: remove from `additionalAlbums`
- On cross-device restore: read `albumPath` (canonical) AND `additionalAlbums` (symlinks) — recreate all Photo rows + albums

**Thumbnail handling**:
- Each Photo row has its own `uuid`, so `internalThumbnailFileName(photo.uuid)` differs per symlink
- Thumbnail lookup: `internalThumbnailFileName(photo.fileUuid)` (same as original)
- Decision: thumbnails are content-addressed too — same hash = same thumbnail bytes. Reuse canonical's thumbnail file via `fileUuid`.

**Path Maker improvement roadmap (Sprint 4+)**:
- v1 (Sprint 3): one-shot "Save all N to album X" — fast, simple, covers 90% use case
- v2 (Sprint 4): per-batch with override — default album for batch, long-press individual photo to assign to different album
- v3 (Sprint 6): smart suggestion — based on EXIF date / location, suggest album ("Photos from June 2026", "Photos in Jakarta")
- v4 (Sprint 9): drag-and-drop album assignment in gallery multi-select mode

**Estimate**:
- Part 1 (dual entry): ~50 lines
- Part 2 (Path Maker dialog v1): ~200 lines
- Part 3 (Symlink schema + dedup modify + delete refcount + registry extension): ~600 lines + DB migration v10→v11
- Total: ~850 lines + migration

---

## JANGKA PANJANG (1+ month per item)

### L1. Biometric Unlock with BiometricPrompt
**Reason**: Face unlock / fingerprint = expected UX in 2025+. Photok upstream has it but needs verification post-rebrand.

**Implementation**:
- Verify existing `BiometricVaultProtectionHandler` still works
- Setup: user enables biometric → derive KEK from biometric-signed challenge → wrap VMK
- Unlock: BiometricPrompt → challenge → KEK → unwrap VMK
- Est: ~300 lines (mostly verify + fix)

### L2. Fake Crash Screen
**Reason**: Stealth mode enhancement. When opened without dialer code → show fake "PhotoZ has stopped" dialog.

**Implementation**:
- Custom `UncaughtExceptionHandler` that detects: app launched via stealth → show fake crash dialog
- Dialog mimics Android system crash dialog ("Open app again" / "Send feedback" — both close app)
- Est: ~200 lines

### L3. Self-Destruct Timer — DONE (Sprint 10)
**Reason**: User sets timer → if vault not opened within X days → auto-wipe local data.

**Implementation**:
- Settings → "Self-destruct after: 7/14/30/60 days inactive"
- WorkManager periodic check: if last unlock > X days → panic wipe (reuse P3 logic)
- Est: ~150 lines

### L4. Multi-Profile Support — SUPERSEDED by M7 (Sprint 2)
**Reason**: Multiple vaults in 1 app. e.g. "Personal" + "Work" with different passwords.

**Status**: **Superseded by M7 (Multi-Vault)** implemented in Sprint 2. M7 provides
unlimited vaults via HMAC-derived `vault_id` — every password derives a distinct
vault, all sharing the same DB with `WHERE vault_id = ?` filtering. This is strictly
better than L4's "separate DB per profile" design:
  - No DB isolation complexity (M7 uses one DB, filtered queries)
  - No UI profile switcher needed (M7's password IS the vault selector)
  - No sync-per-profile (M7 syncs the first vault; additional vaults are local-only)
  - Forensic-resistance: M7 has no `is_real`/`is_decoy` flag, no profile count metadata

L4's "separate DB" approach would actually be a REGRESSION from M7's design.
**Not implementing L4 — M7 covers this use case completely.**

### L5. CI/CD Pipeline (GitHub Actions)
**Reason**: Automated build + test + lint on every PR.

**Implementation**:
- `.github/workflows/ci.yml`: build debug APK + run unit tests + lint
- Cache Gradle dependencies
- Upload APK artifact
- Est: ~100 lines YAML

### L6. On-Device Semantic Search (MediaPipe/TFLite)
**Reason**: #1 trending gallery feature in 2026. On-device AI (Google AI Edge Gallery, Gemma) runs fully offline. User searches "sunset" or "beach" → local ML model classifies. No cloud, no telemetry.

**Implementation**:
- TFLite image classification model (MobileNet/EfficientNet) runs locally
- At import: run inference → generate tags → store in registry (encrypted)
- Search: "beach" → match tags → show results
- Est: ~600 lines + ~5MB model file

### L7. Baseline Profiles + R8 Full Mode
**Reason**: 2026 standard for app performance. R8 full mode for tree-shaking. Baseline profiles reduce cold start 15-40%.

**Implementation**:
- Generate baseline profile via `androidx.benchmark:macro-junit4`
- Enable R8 full mode: `android.enableR8.fullMode=true`
- Est: ~200 lines config + test

### L8. Advanced Protection Mode Awareness (AAPM)
**Reason**: Android 16+ AAPM — when user enables Advanced Protection, app must respect stricter policies.

**Implementation**:
- Detect `Settings.Global.ADVANCED_PROTECTION_MODE` → disable risky features
- Force stronger crypto defaults
- Disable export to external storage
- Est: ~100 lines

---

## Previously Completed (from earlier sessions)

- ✅ CGO + DNS fix (Go binary on Android 16)
- ✅ 16KB page alignment
- ✅ W^X fix (exec from nativeLibraryDir)
- ✅ Key escrow 2-layer (password wraps phrase, phrase wraps VMK)
- ✅ Cross-device restore (login → password → unlock)
- ✅ Dedup (content-hash, skip upload for duplicates)
- ✅ Encrypted GCM registry (GZIP compressed, 1 file for all metadata)
- ✅ Thumbnail packing (5MB packs, batch download)
- ✅ Progressive video streaming
- ✅ File upload (PDF, ZIP, audio)
- ✅ Search & filter (filename + type)
- ✅ Batch operations (delete/export/restore)
- ✅ Registry GC (soft delete + repack)
- ✅ Notification (FOREGROUND_SERVICE_IMMEDIATE, progress, batch summary)
- ✅ Single-page login (remote picker + password inline)
- ✅ Auto-album from folder path
- ✅ Full rebrand (Photok → PhotoZ, package onlasdan.gallery)
- ✅ Auto-lock timer fix (persisted)
- ✅ Hash verification (optional)
- ✅ Re-encrypt fix (escrow re-upload after password change)
- ✅ ZIP backup export/import
- ✅ Recycle bin (soft delete, 30-day retention)
- ✅ Slideshow mode
- ✅ Storage analytics
- ✅ Unit tests (4 files)
- ✅ ProGuard/R8 rules
- ✅ Dependency bumps (Hilt, Coroutines, Lifecycle, Navigation, WorkManager, Media3, Serialization)

---

## Features to AVOID (per 2026 research)

| Feature | Reason |
|---|---|
| Decoy vault with config flag | Forensic tools detect flag → proves concealment. Use cryptographic approach (M7) |
| PIN-only unlock | Cellebrite can crack via TEE exploit. Strong password required (P7) |
| Preserved EXIF in stored photos | EXIF contains GPS → privacy risk. Strip or encrypt separately |
| Ads in any form | Leaks vault usage to ad networks |
| Mandatory account/email | Proves server can identify user |
| "Hiding" files via rename/move | Not encryption, easily detected |
| Cloud AI tagging | User explicitly excluded. On-device AI (L6) is OK |
| Social sharing | Gallery vault = no social. Adds complexity without value |
| Video editing | Not core competency. Use external editor via Intent |
| Sync conflict resolution | Photo vault = single-user. Last-write-wins is sufficient |

---

## Recommended Sprint Order

```
Sprint 1:  P4 (Dynamic Color) + P5 (Edge-to-Edge) + P6 (GCM upgrade) + P7 (Strong Password)     ✅
Sprint 2:  M7 (Crypto Multi-Vault)                                                              ✅
Sprint 3:  M8 (BFU-Safe) + M10 (Photo Picker + Path Maker v1)                                   ✅
Sprint 4:  M2 (Favorites) + M3 (Advanced Sort)                                                  ✅
Sprint 5:  M1 (Compose Migration partial) + M9 (Material 3 Expressive)                          ✅
Sprint 6:  M4 (EXIF Search) + M5 (Cache Rotation)                                               ✅
Sprint 7:  L1 (Biometric) + L2 (Fake Crash) + P2 (Break-in Detection) + P3 (Panic Mode)        ✅
Sprint 8:  L5 (CI/CD) + L7 (R8 Full Mode + Baseline Profile scaffold) [M6 deferred]             ✅
Sprint 9:  L6 (On-Device Semantic Search scaffold) + L8 (AAPM)                                  ✅
Sprint 10: L3 (Self-Destruct) [L4 superseded by M7]                                             ✅
```

---

## Final Implementation Summary (all sprints complete)

### Sprint 1 — Crypto & UI Hardening ✅
- **P4 Dynamic Color**: Material3.DayNight.Bridge theme + DynamicColors API + Compose dynamicLightColorScheme/dynamicDarkColorScheme
- **P5 Edge-to-Edge**: statusBarsPadding/navigationBarsPadding audit + fixes
- **P6 AES-256-GCM**: version byte 0x03, hybrid CBC/GCM engine (GCM for non-video, CBC for video streaming)
- **P7 Strong Password**: StrongPasswordPolicy (min 8, no PINs, blacklist, class diversity) + SetupFragment UI

### Sprint 2 — Multi-Vault Foundation ✅
- **M7 Multi-Vault**: unlimited vaults, HMAC-SHA256 vault_id, no real/decoy flag, first-vault-only sync, per-vault-scoped queries, VaultIdBackfillUseCase

### Sprint 3 — UI + Privacy ✅
- **M7 UI**: CreateVaultSheet + Switch Vault entry in Settings
- **M8 BFU-Safe**: clear VMK on background (respects lock timeout setting)
- **M10 Photo Picker**: PickMultipleVisualMedia + PathMakerDialog v1 (default "Picker" album)

### Sprint 4 — Gallery Features ✅
- **M2 Favorites**: isFavorite column, heart badge, Favorites filter chip, multi-selection toggle
- **M3 Type Sort**: Sort.Field.Type (5, "type")

### Sprint 5 — Compose + Expressive ✅
- **M2 finish**: multi-selection bar heart button + "Remove from favorites" dropdown
- **M9 Material 3 Expressive**: ExpressiveShapes (14/14/20/28/32dp) + Motion.kt (spring + easing constants) + predictive back gesture

### Sprint 6 — Search + Cache ✅
- **M4 EXIF Search**: exif_date_taken/gps_lat/gps_lon/camera columns, ExifExtractor, SearchQueryParser (date:/camera:/location: prefixes, haversine distance)
- **M5 Cache Rotation**: LRU age + size eviction (LOCAL_ONLY never evicted), CacheRotationUseCase on app start

### Sprint 7 — Security Hardening ✅
- **L1 Biometric**: verified existing handler post-rebrand
- **L2 Fake Crash**: FakeCrashActivity ("PhotoZ keeps stopping"), triggered on wrong dial code
- **P2 Break-in Detection**: BreakInDetector (failed attempt count + timestamp), AlertDialog warning on next successful unlock
- **P3 Panic Wipe**: PanicWipeUseCase + panic dial code (default "9111") + DialLauncher 3-way dispatch

### Sprint 8 — CI/CD + Performance ✅
- **L5 CI/CD**: GitHub Actions upgrade (JDK 21, cache, lint, APK artifact, PR trigger, path filter)
- **L7 R8 Full Mode**: android.enableR8.fullMode=true + isShrinkResources + :baselineprofile module scaffold
- **M6 Modularization**: DEFERRED (too large without IDE refactor tooling)

### Sprint 9 — AI + Protection ✅
- **L6 Semantic Search**: ai_tags column, TagExtractor interface + StubTagExtractor, tag: prefix in search, Config toggle (scaffold — TFLite model file needed for activation)
- **L8 AAPM**: AdvancedProtectionModeAwareness (detect, shouldDisableExport, shouldForceFlagSecure, shouldForceGcmOnly)

### Sprint 10 — Self-Destruct ✅
- **L3 Self-Destruct**: SelfDestructWorker (periodic 24h, PanicWipeUseCase on inactivity threshold), Config.selfDestructDays
- **L4 Multi-Profile**: SUPERSEDED by M7 (M7's vault_id scheme is strictly better than L4's separate-DB approach)

### Post-Sprint Completions ✅
- **M1 Compose Migration**: 7 screens migrated (Unlock, Setup, Credits, UnlockBackup, RestoreBackup, OnBoarding, Settings already Compose) + 10 XML layouts deleted
- **M10 Part 3 Symlink Album**: canonical_uuid scheme, dedup creates symlink instead of deleting, per-album refcounted delete with canonical promotion
- **M7 v2 Per-Entry Encrypted Registry**: format version 0x03, each entry independently GCM-encrypted, try-decrypt on download (GCM auth tag filters other vaults)
- **P2/P3/L2 Triggers**: Break-in warning UI (AlertDialog), panic dial code interception, fake crash on wrong dial code

### Still Deferred (require external resources)
- **M6 Modularization**: needs IDE refactor tooling (300+ files, circular dep resolution)
- **L6 TFLite activation**: needs mobilenet_v2.tflite model file in assets/ (scaffold ready, just add file + implement TfliteTagExtractor)
- **M7 v2 full multi-vault merge**: current implementation overwrites remote with current vault's entries; full read-existing+append merge is future enhancement
