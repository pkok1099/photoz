# PhotoZ TODO List

> Migration update: rclone gomobile JNI migration (item #13 Phase 3) is DONE — see `TODO_SYNC.md`.

## High Priority

### 1. QtFastStart — MP4 MOOV relocation untuk progressive video streaming
- **Library**: `net.ypresto.qtfaststartjava:qtfaststart:0.1.0` (7KB, zero deps, pure Java)
- **Action**: Saat import video MP4/MOV (sebelum encrypt), jalankan faststart untuk pindah MOOV atom ke awal file
- **Result**: ExoPlayer bisa baca metadata dari awal → play dalam 1-2 detik (progressive streaming benar-benar bekerja)
- **Scope**: Hanya MP4/MOV (WEBM/MKV sudah streaming-friendly by design)
- **Integration point**: `PhotoRepository.createPhotoFile()` — setelah baca plaintext, sebelum encrypt
- **Status**: DONE — see `FastStartUseCase.kt` + commits 8355dce5, 80b8838e

### 2. Chunked streaming encryption untuk file besar (video)
- **Issue**: AES-GCM `doFinal()` pada seluruh file video butuh memory besar + tidak ada integrity check per-chunk
- **Solution**: Encrypt per chunk (~64KB-1MB), masing-masing dengan IV sendiri + auth tag
- **Pattern**: Mirip Tink / Jetpack Security untuk file besar
- **Trade-off**: Format file berubah (butuh version byte baru atau chunk header) → backwards compat needed
- **Status**: TODO — perlu design diskusi lebih lanjut

### 3. KDF upgrade: PBKDF2 → Argon2id
- **Issue**: PBKDF2 (100k iterasi) rentan GPU/ASIC brute force. 2025 standard = Argon2id (memory-hard)
- **Challenge**: Argon2 tidak ada di Android standard library. Perlu library tambahan:
  - Option A: Bouncy Castle (sudah ada di Android, support Argon2)
  - Option B: Tink (Google, lebih berat tapi well-maintained)
  - Option C: Native Argon2 binding (complex)
- **Migration**: New vaults use Argon2id, old vaults stay PBKDF2 (version dispatch, sama seperti CBC→GCM)
- **Status**: TODO — perlu eval library choice + memory parameters untuk Android

## Medium Priority

### 4. Key storage audit: confirm VMK di TEE (bukan StrongBox)
- **Current**: VMK architecture — password-derived KEK wraps VMK, VMK encrypts files in software
- **Question**: Apakah KEK sudah TEE-backed? Atau pure software?
- **Action**: Audit `BiometricVaultProtectionHandler` dan `KeyGen` — pastikan `setUserAuthenticationRequired` + `setIsStrongBoxBacked(false)` (TEE, bukan StrongBox)
- **Reason**: StrongBox 15-37x lebih lambat dari TEE untuk payload besar. TEE = ~0.4s/1MB (acceptable)
- **Status**: TODO — audit only, mungkin sudah benar

### 5. Jetpack Security deprecation check
- **Issue**: `security-crypto` library deprecated di 1.1.0 stable
- **Action**: Cek apakah PhotoZ pakai `EncryptedSharedPreferences` atau `MasterKey` dari Jetpack Security
- **If yes**: Migrate ke alternatif (Tink atau manual AES-GCM dengan KeyStore-backed key)
- **Status**: VERIFIED — PhotoZ TIDAK pakai `security-crypto`. Skip.

## Anti-Forensic Hardening (from AI review)

### 6. SQLCipher untuk Room DB encryption
- **Issue**: Room DB tidak terenkripsi — metadata foto (path, timestamp, UUID, syncState, albumPath, contentHash, EXIF, aiTags) tersimpan plaintext. Investigator bisa mapping isi vault tanpa decrypt file sama sekali.
- **Solution**: SQLCipher untuk enkripsi DB Room (AES-256 page-level encryption)
- **Library**: `net.zetetic:android-database-sqlcipher` + Room `SupportFactory`
- **Key**: Derive dari VMK (sama seperti photo encryption) — vault lock = DB lock
- **Migration**: Existing DB perlu di-migrate (open plaintext → copy to encrypted → replace)
- **Trade-off**: ~5-15% slower DB queries, +3-5MB APK (SQLCipher native lib)
- **Priority**: HIGH — anti-forensic paling impactful
- **Status**: TODO

### 7. VMK zeroing dari memory (explicit ByteArray.fill(0))
- **Issue**: Kotlin/JVM tidak menjamin kapan GC membersihkan object. VMK bytes bisa tertinggal di heap sampai GC jalan — bisa detikan sampai menit.
- **Solution**: Setelah `sessionRepository.reset()`, explicitly zero the VMK ByteArray:
  ```kotlin
  vmk.encoded?.fill(0)  // zero the key bytes before dereferencing
  ```
- **Also**: Zero plaintext frame buffers setelah foto/video selesai ditampilkan
- **Priority**: MEDIUM — effort rendah, impact forensic moderate
- **Status**: TODO

### 8. Screenshot prevention (FLAG_SECURE audit)
- **Issue**: FLAG_SECURE mencegah OS screenshot + mencegah plaintext muncul di recent apps thumbnail
- **Current**: PhotoZ punya config `SECURITY_ALLOW_SCREENSHOTS` — perlu audit apakah FLAG_SECURE di-set di semua Activity/Window
- **Action**: 
  - Verify FLAG_SECURE di-set di MainActivity saat `allowScreenshots = false`
  - Verify FLAG_SECURE di-set di image viewer / video player
  - AAPM mode (L8) should force FLAG_SECURE on regardless of user setting
- **Priority**: HIGH — effort sangat rendah, impact tinggi
- **Status**: TODO — audit only, mungkin sudah ada sebagian

### 9. Root detection + debugger detection
- **Issue**: Di perangkat root, VMK bisa di-extract lebih mudah. Debugger attach bisa intercept crypto operations.
- **Solution**:
  - RootBeer library atau custom multi-check (su binary, Magisk, rooted apps)
  - `Debug.isDebuggerConnected()` check di critical paths (unlock, encrypt)
  - `android:debuggable=false` di release manifest (already via build type)
- **Trade-off**: Root detection bisa di-bypass oleh attacker advanced. Tapi cukup untuk casual root.
- **Priority**: LOW — nice to have, bukan critical
- **Status**: TODO

### 10. Duress PIN / Panic password (already implemented as M7)
- **Status**: ALREADY DONE — M7 Multi-Vault (Sprint 2) + P3 Panic Wipe (Sprint 7) covers this:
  - M7: setiap password derive vault berbeda → password "duress" membuka vault kosong/decoy
  - P3: panic dial code triggers wipe
  - L2: wrong dial code shows fake crash
- No additional work needed.

### 11. Hardware Key Attestation
- **Issue**: Android Keystore bisa membuktikan key dibuat di TEE hardware yang belum di-compromise
- **Use case**: Enterprise deployment — verifikasi device integrity sebelum allow restore
- **Action**: Tambah attestation certificate check saat vault setup (optional, opt-in)
- **Priority**: LOW — enterprise only, tidak relevant untuk consumer vault
- **Status**: DEFERRED

### 12. Play Integrity API
- **Issue**: Verifikasi bahwa app yang berjalan adalah build resmi (bukan repackaged APK)
- **Action**: Integrate Play Integrity API di app start — reject modified APKs
- **Priority**: LOW — Play Store only, tidak relevant untuk F-Droid/self-install
- **Status**: DEFERRED

## Low Priority

### 13. Rclone binary optimization
- **Current**: 84MB (arm64-v8a, CGO+NDK+16KB aligned, all backends + all commands)
- **Phase 1 (NOW)**: Accept 84MB. Beta user terbatas, size bukan blocker.
- **Phase 2 (short term)**: Command stripping only — strip `mount`, `serve`, `bisync`, `ncdu`, `dedupe`, `cryptcheck`, `cryptdecode`, `tree`, dll. PhotoZ pakai RC API (rcd mode), bukan CLI commands. Estimasi savings 5-10MB. Zero risk, no backend breakage. Update `build-rclone.sh` + CI workflow.
- **Phase 3 (long term, post-beta)**: Evaluate gomobile JNI migration — bukan untuk size, tapi untuk eliminasi subprocess complexity (W^X, 16KB alignment, exec from nativeLibraryDir). Size tetap ~45MB tapi bisa di-download on-demand via `dlopen()` dari `filesDir` (W^X blocks `exec()` tapi tidak `dlopen()`). APK base jadi ~60MB, rclone di-download saat user setup cloud sync.
- **NOT doing**: Backend stripping (breaks user rclone.conf), UPX (W^X violation + 16KB alignment break + AV false positive), Play Feature Delivery (not viable for FOSS flavor), Optional download via subprocess (W^X blocks exec from writable dirs)
- **Key insight**: W^X policy blocks `exec()` di `filesDir`/`codeCacheDir` — hanya `nativeLibraryDir` (APK-installed) yang executable. Tapi `dlopen()`/`System.load()` TIDAK di-block di writable dirs. Gomobile JNI pakai `dlopen()`, subprocess pakai `exec()`. Itulah kenapa gomobile JNI enable optional download tapi subprocess tidak.
- Status: Phase 1 (accept), Phase 2 (TODO command stripping), Phase 3 (DONE — gomobile JNI migrated; see `TODO_SYNC.md`)

### 14. M6 Modularization
- Split 300+ files into :core/:sync/:data/:app modules
- Needs IDE refactor tooling
- Status: DEFERRED

### 15. L6 TFLite model activation
- Scaffold ready (TagExtractor interface + StubTagExtractor)
- Needs `mobilenet_v2.tflite` model file in assets/
- Status: TODO — needs model file

### 16. M7 v2 full multi-vault registry merge
- Current: each upload overwrites remote with current vault's entries
- Future: read-existing + append new entries (true multi-vault sync)
- Status: TODO
