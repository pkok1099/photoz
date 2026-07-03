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

### M7. Cryptographic Plausible Deniability (Decoy Vault Done Right)
**Reason**: 2026 research shows decoy vaults with config flags CAN be detected by forensic tools (Cellebrite finds flag → proves concealment). Solution: pattern-based key derivation — every password derives a different key, no flag, no master index.

**Implementation**:
- Password → PBKDF2 → KEK → try decrypt VaultProtection(Password) → if match, unlock vault
- No `is_decoy` flag in DB. Decoy password derives different KEK → decrypts different blob → different vault
- Storage padding: decoy vault has plausible size (not too small)
- No "wrong password" error — decoy password still "succeeds" (opens empty/fake vault)
- Est: ~500 lines

### M8. BFU-Safe Key Handling (Re-key on Backgrounding)
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

### M10. Embedded Photo Picker (Android 16+)
**Reason**: Android 16 Embedded Photo Picker — no `READ_MEDIA_IMAGES` permission needed. App only receives temporary URI, cannot access full gallery. Major privacy win.

**Implementation**:
- Replace `ActivityResultContracts.OpenDocument` with `PickVisualMedia` (Photo Picker API)
- Remove `READ_MEDIA_IMAGES` permission from manifest (if no longer needed)
- User experience: faster, more private (app cannot "see" user's gallery)
- Est: ~100 lines

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

### L3. Self-Destruct Timer
**Reason**: User sets timer → if vault not opened within X days → auto-wipe local data.

**Implementation**:
- Settings → "Self-destruct after: 7/14/30/60 days inactive"
- WorkManager periodic check: if last unlock > X days → panic wipe (reuse P3 logic)
- Est: ~150 lines

### L4. Multi-Profile Support
**Reason**: Multiple vaults in 1 app. e.g. "Personal" + "Work" with different passwords.

**Implementation**:
- App startup: select profile (or auto-detect from password)
- Each profile: VMK + VaultProtection + separate DB
- Est: ~800 lines (complex — DB isolation, UI switcher, sync per profile)

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
Sprint 1:  P4 (Dynamic Color) + P5 (Edge-to-Edge) + P6 (GCM upgrade) + P7 (Strong Password)
Sprint 2:  M7 (Crypto Decoy Vault)
Sprint 3:  M8 (BFU-Safe) + M10 (Embedded Photo Picker)
Sprint 4:  M2 (Favorites) + M3 (Advanced Sort)
Sprint 5:  M1 (Compose Migration) + M9 (Material 3 Expressive)
Sprint 6:  M4 (EXIF Search) + M5 (Cache Rotation)
Sprint 7:  L1 (Biometric) + L2 (Fake Crash) + P2 (Break-in Detection) + P3 (Panic Mode)
Sprint 8:  M6 (Modularization) + L5 (CI/CD) + L7 (Baseline Profiles)
Sprint 9:  L6 (On-Device Semantic Search) + L8 (AAPM)
Sprint 10: L4 (Multi-Profile) + L3 (Self-Destruct)
```
