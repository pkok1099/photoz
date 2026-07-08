# Audit Cabang B2 — Photok Sync Fork

> **Repository**: github.com/pkok1099/photok-sync-fork
> **Cabang diaudit**: `B2` (HEAD: `fdd56761`)
> **Tanggal audit**: 2026-07-08
> **Auditor**: Super Z (automated deep audit, 3 parallel sub-agents + main orchestrator)
> **Mode**: Read-only audit + perbaikan Critical/High ke cabang `audit-b2-fixes`

---

## Ringkasan Eksekutif

Cabang B2 berisi migrasi besar-besaran dari rclone **subprocess** (`ProcessBuilder` + `librclone.so` EXEC) ke **gomobile JNI** (`libgojni.so` via `dlopen`, RC API), plus aktivasi **SQLCipher** untuk enkripsi DB at-rest (v15→v16), plus dukungan **Argon2id** KDF, **chunked GCM** encryption, dan **two-layer key escrow**.

Migrasi ini **mekanis sudah selesai** — kode Kotlin sudah memakai `Class.forName("gomobile.Gomobile")` + `rcloneRPC`, AAR `app/libs/librclone.aar` (37 MB) berisi `gomobile.Gomobile.class` + `libgojni.so` (arm64-v8a + armeabi-v7a), dan `app/src/main/jniLibs/` tidak ada (binary subprocess lama sudah dihapus).

**Tetapi** lapisan-lapisan di atas batas JNI masih banyak yang berpikir dalam terminologi subprocess lama. Audit mendalam menemukan **72 temuan**:

| Severity | Jumlah | Catatan |
|----------|-------:|---------|
| **Critical** | 9 | Risiko eksploitasi / kehilangan data senyap |
| **High** | 23 | Bug fungsional / kelemahan keamanan / pelanggaran aturan AGENTS.md |
| **Medium** | 27 | Code smell / stale / inkonsistensi |
| **Low** | 13 | Kosmetik / dokumentasi / missing test |
| **Total** | **72** | |

### Tema utama pasca-migrasi yang belum diselesaikan

1. **Logging diagnostik production-leak**: 70+ pemakaian `android.util.Log` di 20 file dengan tag `RcloneDiag` yang seharusnya hanya untuk debugging. Aturan AGENTS.md §12 ("Timber only") dilanggar masif. Beberapa log membocorkan metadata sensitif (status vault, nama file, ukuran, jalur remote).

2. **Package rename tidak tuntas**: `dev.leonlatsch.photok` → `onlasdan.gallery` sudah 99% selesai di source code, tetapi `app/proguard-rules.pro` baris 14 & 17 **masih memakai nama lama** — akibatnya keep rule R8 tidak match dan kelas-kelas kritis mungkin di-obfuscate di build release.

3. **Komentar/dokumentasi stale**: `build.gradle.kts:135-167` masih menjustifikasi `useLegacyPackaging = true` dengan alasan ProcessBuilder; `README.md` menyebut schema v9 ( aktual v16) dan "rcd child process" ( aktual gomobile JNI); `AGENTS.md` masih bilang package root adalah `dev/leonlatsch/photok/`; `ROADMAP2.md` menandai SQLCipher sebagai TODO padahal sudah diimplementasi penuh.

4. **Dead code / file sisa**: `TfliteTagExtractor.kt.bak` (205 baris), `scripts/build-rclone.sh` + `.github/workflows/build-rclone.yml` (subprocess build, sudah tidak dipakai), `github_log.txt` (log Azure error di root repo).

5. **Penanganan error rclone berbasis substring**: 6 call site memeriksa kegagalan RPC dengan `result.contains("\"error\":")` alih-alih parse JSON. False positive pada nama file yang mengandung kata "error"; false negative pada status non-200 tanpa field `error`.

6. **Tiga vektor kehilangan data senyap pada fresh-install login**:
   - `loginRepo()` menganggap error download escrow sebagai "tidak ada escrow" → user dipaksa setup vault baru → foto lama tidak terdekripsi (F-SYNC-004, F-SYNC-005).
   - `submitPassword()` navigasi ke gallery meski `createPasswordProtectionFromSession()` gagal → next launch dibuat VMK baru → foto hilang (F-SYNC-005).
   - `PhotoSyncWorker.performUpload()` punya dua `catch (e: Exception)` beruntun → catch kedua dead code → error verifikasi upload diabaikan → foto ditandai `UPLOADED` tanpa verifikasi (F-SYNC-003).

7. **SQLCipher Keystore fallback senyap**: Jika Android Keystore gagal, `SqlCipherKeyProvider` silently turun ke `FallbackSqlCipherKeyProvider` yang menyimpan passphrase 32-byte di `SharedPreferences` plaintext. Tidak ada UI warning (F-ENC-001). Plus passphrase hex bisa bocor via logcat jika migration gagal (F-ENC-002).

8. **Inkonsistensi KDF**: `PasswordVaultProtectionHandler.create()` pakai Argon2id, tetapi `RecoveryPhraseVaultProtectionHandler.create()` hardcoded PBKDF2, dan `VaultService.createPasswordProtectionFromSession()` juga hardcoded PBKDF2 — pengguna dapat postur keamanan berbeda tergantung path mana yang dipakai (F-ENC-003, F-ENC-004, F-ENC-005).

9. **Backup V5 tidak komprehensif**: `PhotoBackup` hanya menyimpan 6 field (fileName, importedAt, lastModified, type, size, uuid) — 13 kolom post-V3 (contentHash, albumPath, vaultId, relativePath, deletedAt, isFavorite, exif*, aiTags, canonicalUuid, syncState) **hilang** pada backup+restore (F-BACK-001). V5 restore juga tidak menulis `vault_protection` row ke `BootstrapDatabase` — hasilnya hanya "merge foto ke vault saat ini", bukan "restore vault dari backup" (F-BACK-002).

---

## Konteks Audit

### Apa yang diaudit

| Modul | Path | File | LOC |
|-------|------|-----:|----:|
| Sync / rclone | `app/src/main/java/onlasdan/gallery/sync/` + `reposetup/ui/` | 18 | ~5500 |
| Encryption / Crypto | `app/src/main/java/onlasdan/gallery/encryption/` + `transcoding/` | 67 | ~6500 |
| Backup / DB | `app/src/main/java/onlasdan/gallery/backup/` + `model/` | 28 | ~4500 |
| Build / Config | `app/build.gradle.kts`, `proguard-rules.pro`, `gradle.properties`, `settings.gradle.kts`, `AndroidManifest.xml`, `scripts/`, `.github/workflows/` | 12 | n/a |
| Dokumentasi | `*.md` (root + `agent-ctx/` + `adr/`) | 22 | n/a |
| **Total** | | **147** | **~16500 LOC** |

### Apa yang TIDAK diaudit (out of scope)

- Cabang lain (`main`, `jules-6205648941741968112-dc100c23`) — user minta B2 saja.
- UI Compose screens (gallery, albums, settings) — bukan fokus pasca-migrasi.
- Resource files (drawables, strings.xml, layouts).
- Module `:baselineprofile` (scaffold untuk macrobenchmark, tidak ada logika produksi).
- License HTML (`open_source_licenses.html` auto-generated).

### Metodologi

1. **Main agent** (Task 0): Clone repo, checkout B2, baca semua `.md` konteks, scan pola kritis (`dev.leonlatsch.photok`, `ProcessBuilder`, `SQLCipher`, `android.util.Log`, `RcloneDiag`, `.bak` files, `TODO/FIXME`).
2. **Sub-agent 1** (encryption-auditor): Baca 55 file di `encryption/` + 12 file di `transcoding/`. Output: 28 findings.
3. **Sub-agent 2** (sync-auditor): Baca 16 file di `sync/` + `reposetup/` + 2 workflow + 2 build script. Output: 28 findings.
4. **Sub-agent 3** (backup-db-auditor): Baca 28 file di `backup/` + `model/` + `model/io/` + `sort/` + verifikasi 17 schema JSON. Output: 16 findings.
5. **Konsolidasi**: 9 Critical + 23 High + 27 Medium + 13 Low = 72 findings total.

Setiap temuan diverifikasi dengan membaca file terkait penuh; cross-reference antar modul dilakukan untuk mendeteksi inkonsistensi (mis. komentar stale yang menyebut API yang sudah tidak ada).

---

## Statistik Temuan per Kategori

| Kategori | Critical | High | Medium | Low | Total |
|----------|---------:|-----:|-------:|----:|------:|
| Migrasi JNI (rclone gomobile) | 4 | 5 | 5 | 3 | 17 |
| Package rename | 1 | 0 | 1 | 0 | 2 |
| Build / Gradle / ProGuard | 0 | 1 | 2 | 1 | 4 |
| DB schema / Room migration | 0 | 0 | 2 | 0 | 2 |
| Sync / Backup workflow | 1 | 4 | 4 | 2 | 11 |
| Security / Crypto | 2 | 6 | 5 | 1 | 14 |
| Dead code / .bak / stale docs | 0 | 2 | 4 | 4 | 10 |
| Logging hygiene | 0 | 3 | 2 | 0 | 5 |
| Test coverage | 0 | 0 | 0 | 4 | 4 |
| Dokumentasi stale | 0 | 0 | 1 | 0 | 1 |
| UI / UX | 1 | 2 | 1 | 1 | 5 |
| **Total** | **9** | **23** | **27** | **13** | **72** |

---

## Temuan CRITICAL (9)

### [CRITICAL] F-MAIN-001: `proguard-rules.pro` masih memakai package lama `dev.leonlatsch.photok`
**File**: `app/proguard-rules.pro:14, 17`
**Deskripsi**: Dua keep rule R8 masih merujuk package lama:
```
-keepclassmembers class dev.leonlatsch.photok.** { <fields>; }
-keep class dev.leonlatsch.photok.**
```
Padahal seluruh kode sudah pindah ke `onlasdan.gallery.*`. Karena `dev.leonlatsch.photok.**` tidak ada class-nya, rule ini match nothing — tidak ada class yang di-keep oleh rule ini. Untungnya ada rule tambahan yang lebih spesifik (mis. `-keep class onlasdan.gallery.model.database.entity.**`), tetapi rule umum di baris 14 & 17 secara efektif dead.
**Why it matters**: Build release mungkin meng-obfuscate class yang seharusnya di-keep (Gson SerializedName, Room entities di luar `model.database.entity`, dll), menyebabkan crash runtime di release-only. Bug yang sangat sulit direproduksi di debug.
**Fix**: Ganti `dev.leonlatsch.photok` → `onlasdan.gallery` di kedua baris. (Sudah diterapkan di cabang `audit-b2-fixes`.)

### [CRITICAL] F-ENC-001: `SqlCipherKeyProvider` silently turun ke plaintext SharedPreferences saat Keystore gagal
**File**: `app/src/main/java/onlasdan/gallery/encryption/data/SqlCipherKeyProvider.kt:112-124, 224-245`
**Deskripsi**: Saat Android Keystore gagal (korup, hardware issue), `getOrCreatePassphrase()` catch exception, log via `android.util.Log.e`, lalu delegasi ke `FallbackSqlCipherKeyProvider` yang menyimpan passphrase 32-byte SQLCipher sebagai hex di plaintext SharedPreferences (`photoz_sqlcipher_fallback/prefs.xml`).
**Why it matters**: User tidak dapat warning UI bahwa "encrypted DB" mereka sekarang efektif plaintext. Attacker dengan akses disk (rooted device, forensic extraction) bisa baca passphrase dari SharedPreferences dan langsung decrypt `photok.db`. Class comment sendiri bilang "provides NO security benefit over a plaintext DB" — tetapi runtime path tidak surfacing ini ke user. Silent security downgrade — jenis terburuk.
**Fix**: Tambah `Config.keystoreFallbackActive` flag, surface sebagai warning banner persisten di Settings. Pertimbangkan crash di release build alih-alih silent downgrade. (Fix penuh memerlukan UI work — fix minimal di cabang audit-b2-fixes: log via Timber + tambah flag Config.)

### [CRITICAL] F-ENC-002: Passphrase SQLCipher bisa bocor ke logcat jika migration gagal
**File**: `app/src/main/java/onlasdan/gallery/encryption/data/SqlCipherMigrationHelper.kt:161-162, 186-188, 245-256`
**Deskripsi**: Passphrase 32-byte di-hex-encode ke `passphraseStr` (line 162), lalu di-embed di SQL `ATTACH DATABASE ... KEY 'x"<hex>"'` (line 187). Jika `plaintextDb.execSQL(attachSql)` throw, catch block line 245 panggil `Timber.e(e, "SqlCipherMigration: migration failed")` — exception message/stacktrace bisa mengandung SQL statement dengan passphrase hex.
**Why it matters**: Attacker dengan ADB (USB debugging enabled) bisa baca logcat tanpa root. Jika migration pernah gagal (disk full, DB v15 korup), DB key tertulis ke logcat. Combined with `adb pull photok.db`, attacker dapat full decrypt semua foto. Ini threat model yang persis ingin SQLCipher cegah.
**Fix**: Jangan log exception detail saat SQL mungkin ada di message. Log pesan ter-scrub:
```kotlin
Timber.e("SqlCipherMigration: migration failed (${e.javaClass.simpleName}: ${e.message?.take(60)?.replace(Regex("'x\"[0-9a-f]+\"'"), "'<REDACTED>'")})")
```
Lebih baik: restrukturisasi untuk tidak embed passphrase di SQL sama sekali — pakai overload `SQLiteDatabase.openOrCreateDatabase` yang accept `byte[]` passphrase.

### [CRITICAL] F-SYNC-001: Deteksi error RPC rclone berbasis substring `"error":` — false positive/negative
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt:172, 200, 222, 244, 263, 278`
**Deskripsi**: Setiap keputusan success/failure RPC dibuat dengan `result.contains("\"error\":")` (atau `result.contains("\"error\"")`), bukan parse JSON atau cek `RcloneRPCResult.getStatus()`.
**Why it matters**:
1. **False negative**: response sukses yang kebetulan mengandung substring `error` (filename `error.jpg`, field name, UUID dengan huruf `error`) dilaporkan sebagai gagal → upload/download silently fail, foto ditandai `UPLOAD_FAILED`.
2. **False positive**: saat JNI call sendiri throw, `rpc()` return `"{\"error\":\"${e.message}\"}"` dengan exception message di-interpolasi. Jika `e.message` null (umum untuk `NoSuchMethodException`), response `{"error":"null"}` — tetap trip contains check, jadi plumbing failure JNI terlihat seperti error rclone, masking root cause.
3. **`RcloneRPCResult.getStatus()` tidak pernah dicek** — status non-200 tanpa field `error` di output silently diperlakukan sebagai sukses.
**Fix**: Ganti contains-based check dengan parse JSON via `org.json.JSONObject` (sudah ada di Android):
```kotlin
val json = JSONObject(output)
if (json.has("error")) Result.failure(IOException("rclone: ${json.optString("error")}"))
else Result.success(Unit)
```
Tambah cek `getStatus() != 200` → failure. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [CRITICAL] F-SYNC-002: Tidak ada timeout pada panggilan RPC — rclone hang freeze worker forever
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt:138-153`
**Deskripsi**: `rpc()` panggil `rpcMethod.invoke(null, method, input)` sinkron di `Dispatchers.IO` tanpa timeout. Jika rclone go runtime deadlock (network stall, mutex contention, infinite loop di backend), coroutine tidak pernah return.
**Why it matters**: WorkManager `doWork()` tidak pernah mencapai `Result.retry()` atau `Result.failure()` — worker hang di state RUNNING forever. Foreground notification tetap muncul tak terbatas. User tidak bisa cancel via UI (WorkManager cancellation hanya sinyal coroutine cancellation; JNI call tidak observe). Di Android 14+, system akhirnya kill foreground service setelah 6 jam, tetapi user tidak ada indikasi progress. Untuk vault yang upload video besar, satu upload stall bisa brick seluruh sync queue.
**Fix**: Wrap JNI invoke dalam `withTimeoutOrNull(30_000L)` di calling coroutine. Pada timeout, panggil `rcloneFinalize()` + re-init, return `Result.failure(IOException("RPC timeout"))`. Untuk operasi panjang, pakai rclone async RC API (`operations/copyfile` dengan `_async=true`) + poll `job/status`.

### [CRITICAL] F-SYNC-003: `PhotoSyncWorker.performUpload()` punya dua `catch (e: Exception)` beruntun — catch kedua unreachable
**File**: `app/src/main/java/onlasdan/gallery/sync/work/PhotoSyncWorker.kt:680-697`
**Deskripsi**: Dua klausa `catch (e: Exception)` berurutan di try block yang sama. Kotlin compile dengan clause kedua unreachable (yang pertama catch semua subtype `Exception`). Catch kedua (yang jelas-jelas dimaksudkan untuk re-throw real error via `throw e`) adalah dead code.
**Why it matters**: Setelah `verifyFileExists()` konfirmasi size (line 666), worker panggil `verifyRemote()` (line 678) untuk server-side hash verification. **Setiap** failure — `HashNotSupportedException` (benign, expected di Koofr/Drive), network blip, rclone RPC error, JNI crash — jatuh ke catch pertama, yang log "verification skipped" dan fallthrough ke mark photo `UPLOADED`. Perilaku yang dimaksudkan (re-throw real error supaya worker retry) silently rusak. Foto yang upload-nya corrupt oleh network fault (size kebetulan match, atau backend return 200 untuk incomplete upload) lolos verifikasi dan ditandai `UPLOADED` — user kehilangan original lokal (jika `syncDeleteAfterUpload` aktif) dan remote file korup.
**Fix**: Ganti duplicate catch dengan type-specific handlers:
```kotlin
} catch (e: onlasdan.gallery.sync.rclone.HashNotSupportedException) {
    diag("performUpload: remote verification skipped — backend doesn't support hashsum")
} catch (e: Exception) {
    diag("performUpload: hash verification FAILED", e)
    reportUploadFailureNotification(photo.fileName, batchState)
    throw e
}
```
Memerlukan `RcloneController.verifyRemote()` melempar typed `HashNotSupportedException` alih-alih generic `IOException`. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [CRITICAL] F-SYNC-004: `loginRepo()` menganggap error download escrow sebagai `EscrowType.NONE` → silent data loss
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RepoManager.kt:524-536` (+ Layer 2 di line 550-559)
**Deskripsi**: Saat `downloadVaultProtectionEscrow()` return `Result.failure`, `loginRepo()` log "non-fatal" dan return `LoginResult.Success(EscrowType.NONE)`. Downstream UI route user ke `NoEscrowAvailable` → `continueWithoutEscrow` → `SetupFragment` → new password → **new VMK** → semua foto yang sebelumnya terenkripsi menjadi permanently undecryptable.
**Why it matters**: `downloadVaultProtectionEscrow()` membedakan "file not on remote" (return `Result.success(null)`) dari "download error" (return `Result.failure(exception)`). Tetapi `loginRepo()` menggabung keduanya menjadi `EscrowType.NONE`. Transient network error, temporary rclone auth refresh, atau 5-second DNS hiccup saat fresh-install login permanently brick vault foto user. Ini vektor data-loss terburuk di codebase.
**Fix**: Di `loginRepo()`:
```kotlin
val escrowResult = downloadVaultProtectionEscrow()
when {
    escrowResult.isSuccess -> {
        val layer1 = escrowResult.getOrNull()
        if (layer1 == null) return@runCatching LoginResult.Success(EscrowType.NONE)
        // ... continue with Layer 2 ...
    }
    escrowResult.isFailure -> {
        return@runCatching LoginResult.Failure(
            "Layer 1 escrow download failed: ${escrowResult.exceptionOrNull()?.message}. " +
            "Do NOT continue — retry on stable network."
        )
    }
}
```
Apply fix yang sama ke Layer 2 (line 551-559). (Fix diterapkan di cabang `audit-b2-fixes`.)

### [CRITICAL] F-SYNC-005: `submitPassword()` navigasi ke gallery meski `createPasswordProtectionFromSession()` gagal → data loss pada next app open
**File**: `app/src/main/java/onlasdan/gallery/reposetup/ui/RepoSetupViewModel.kt:657-674`
**Deskripsi**: Setelah login-branch unlock sukses, kode bungkus `VaultProtection(Password)` lokal dalam try/catch. Pada failure, log `"DATA-LOSS RISK"` lalu lanjut ke `_state.value = RepoSetupState.Unlocked` — navigasi user ke gallery seolah sukses.
**Why it matters**: Comment developer sendiri (line 645-656) acknowledge vektor data-loss: tanpa local `VaultProtection(Password)` row, next `canUnlock()` return `false` → app anggap fresh install → `SetupFragment` → new password → **new VMK** → semua foto sebelumnya undecryptable. DB write failure (disk full, SQLCipher corruption, Room schema mismatch) silently brick vault di launch berikutnya.
**Fix**: Pada `createPasswordProtectionFromSession` failure, JANGAN navigasi ke gallery. Set state ke `RepoSetupState.Error("Critical: unable to persist vault password. Do not close the app. Contact support.")` + surface modal dialog. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [CRITICAL] F-BACK-001: Backup V5 kehilangan SEMUA metadata Photo post-V3 saat restore
**File**: `app/src/main/java/onlasdan/gallery/backup/data/BackupMappers.kt:23-31` + `BackupMetaData.kt:97-104`
**Deskripsi**: `Photo.toBackup()` hanya map 6 field (fileName, importedAt, lastModified, type, size, uuid). `PhotoBackup` data class TIDAK include `contentHash`, `albumPath`, `vaultId`, `relativePath`, `deletedAt`, `isFavorite`, `exifDateTaken`, `exifGpsLat`, `exifGpsLon`, `exifCamera`, `aiTags`, `canonicalUuid`, `syncState` — semua 13 kolom post-V3 silently drop saat backup creation.
**Why it matters**: Setelah V5 restore, semua foto restored punya null/default untuk field-field ini. Ini break: (1) content-hash dedup (foto re-upload ke remote); (2) multi-vault scoping (vault_id=null → visible di SEMUA vault sampai first-unlock backfill claim untuk vault mana yang unlock lebih dulu); (3) symlink albums (canonicalUuid=null → symlink point ke file non-existent); (4) favorites (isFavorite=false); (5) EXIF search (semua EXIF null); (6) semantic search (aiTags=null); (7) auto-album creation (albumPath=null).
**Fix**: Extend `PhotoBackup` untuk include semua 13 field post-V3. Bump ke format V6 (karena V5 schema locked untuk backward compat). V5 restore path tetap untuk legacy backup; backup baru pakai V6. (Out of scope untuk fix cepat — butuh design + test round-trip.)

### [CRITICAL] F-BACK-002: V5 restore tidak menulis source vault protection rows
**File**: `app/src/main/java/onlasdan/gallery/backup/domain/RestoreBackupV5.kt:75-141` + `UnlockBackupUseCase.kt:110-130`
**Deskripsi**: V5 format bawa `wrappedVMK` + `params` di metadata, tetapi `UnlockBackupUseCase.createSessionFromV5` pakai ini HANYA untuk unwrap VMK untuk decrypt foto. `VaultSession` yang restored dipakai `RestoreBackupV5.restore` untuk `cryptoEngine.createDecryptStream(stream, session)` (dekripsi), sementara re-encryption pakai `vaultFileStorage.openEncryptedOutput(ze.name)` yang panggil `sessionRepository.require()` (session vault SAAT INI, bukan backup). Source vault `vault_protection` row (Password/Biometric/RecoveryPhrase) TIDAK PERNAH ditulis ke local `BootstrapDatabase`.
**Why it matters**: V5 backup secara fungsional adalah operasi "merge foto ke vault saat ini", bukan "restore vault dari backup". Setelah restore: (1) user punya protection vault SAAT INI (bukan backup); (2) foto di-re-encrypt dengan VMK vault saat ini (bukan source VMK); (3) jika restore ke fresh install tanpa vault, `sessionRepository.require()` throw dan restore fail silently. User yang expect "restore my vault from backup" (terutama setelah device loss / factory reset) dapat half-restore yang confusing.
**Fix**: Tambah step "restore vault protection" di `RestoreBackupV5` yang tulis `wrappedVMK` + `params` backup ke `BootstrapDatabase.vault_protection` (create new vault row). Lalu user bisa unlock vault baru (restored) atau vault existing. Dokumentasi behavior di restore UI. (Out of scope untuk fix cepat — butuh design multi-vault + UI work.)

---

## Temuan HIGH (23)

### [HIGH] F-ENC-003: `VaultService.createPasswordProtectionFromSession` pakai PBKDF2 alih-alih Argon2id
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/VaultService.kt:281-329`
**Deskripsi**: Path sync-login (`RepoSetupViewModel.submitPassword` → `VaultService.createPasswordProtectionFromSession`) hardcoded `Kdf.PBKDF2WithHmacSHA256` dengan 100k iterasi dan `Algorithm.AesCbcPkcs7Padding`, bypass Argon2id upgrade (TODO #3). `PasswordVaultProtectionHandler.create()` kanonik (line 110) dan `wrapExistingVmk()` (line 178) keduanya pakai Argon2id.
**Why it matters**: User yang setup app via sync-login dapat Password protection row dengan brute-force resistance lebih lemah (PBKDF2 GPU-parallelizable; Argon2id memory-hard). App yang sama, user yang sama, postur keamanan berbeda tergantung code path mana yang create row. Inkonsisten dan mengejutkan.
**Fix**: Delegate ke `passwordProtectionHandler.wrapExistingVmk(password, session.vmk)` (yang sudah pakai Argon2id + 4-byte memory-cost IV encoding). Hapus inline wrapping logic.

### [HIGH] F-ENC-004: `RecoveryPhraseVaultProtectionHandler.create()` hardcoded PBKDF2
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/handlers/RecoveryPhraseVaultProtectionHandler.kt:84`
**Deskripsi**: `create()` hardcoded `val kdf = Kdf.PBKDF2WithHmacSHA256` — inkonsisten dengan `PasswordVaultProtectionHandler.create()` yang pakai Argon2id.
**Why it matters**: Recovery phrase seharusnya metode unlock "kuat" (12/24-word BIP39 entropy), tetapi di-wrap dengan KDF lebih lemah dari password. Attacker yang curi bootstrap DB bisa brute-force recovery phrase (jika somehow guess) pakai GPU-accelerated PBKDF2.
**Fix**: Ganti ke `Kdf.Argon2id` + tambah 4-byte memory-cost IV encoding (match `PasswordVaultProtectionHandler.create()` lines 118-123). Fix bersamaan dengan F-ENC-005.

### [HIGH] F-ENC-005: `RecoveryPhraseVaultProtectionHandler.unlock()` tidak decode Argon2id IV/memory prefix
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/handlers/RecoveryPhraseVaultProtectionHandler.kt:60-76`
**Deskripsi**: `unlock()` panggil `keyGen.derivePasswordKeyEncryptionKey` tanpa `argon2MemoryKB`, dan pass `Base64.decode(params.iv)` langsung ke `IvParameterSpec`. Jika `params.kdf == Argon2id`, field `iv` berisi 4-byte memory-cost prefix + 16-byte IV (20 byte total) per TODO #3 encoding. Pass 20 byte ke `IvParameterSpec` throw `InvalidAlgorithmParameterException` (AES-CBC butuh exactly 16-byte IV).
**Why it matters**: Hari ini latent (F-ENC-004 keep recovery phrase di PBKDF2). Tetapi begitu seseorang fix F-ENC-004 untuk pakai Argon2id, setiap recovery-phrase unlock akan crash. Unlock path missing Argon2id IV decoding logic yang `PasswordVaultProtectionHandler.unlock()` punya (lines 62-83).
**Fix**: Port IV-decoding `when(params.kdf)` block dari `PasswordVaultProtectionHandler.unlock()` lines 65-83. Lalu fix F-ENC-004. Keduanya harus land bersamaan.

### [HIGH] F-ENC-006: `ChunkedGcmRandomAccessDataSource` tidak validasi `chunkSize`
**File**: `app/src/main/java/onlasdan/gallery/transcoding/data/ChunkedGcmRandomAccessDataSource.kt:102`
**Deskripsi**: `chunkSize = ByteBuffer.wrap(headerBuf, 1, 4).int` baca chunk size dari file header tanpa validasi. `ChunkedGcmInputStream` validasi (lines 65-67: `if (chunkSize <= 0 || chunkSize > 100 * CHUNK_SIZE) throw IOException`), tetapi random-access path tidak.
**Why it matters**: File korup atau maliciously crafted dengan `chunkSize = Int.MAX_VALUE` (2GB) cause `loadChunk` alokasi `chunkSize + GCM_TAG_SIZE` ≈ 2GB → OOM crash. `chunkSize = 0` cause GCM decryption failures. Karena format file version-dispatched di single byte, attacker yang bisa write ke vault (mis. via malicious sync) bisa craft file 0x04 yang crash app saat playback.
**Fix**: Tambah validasi yang sama:
```kotlin
if (chunkSize <= 0 || chunkSize > 100 * CHUNK_SIZE) {
    throw IOException("ChunkedGcmRA: invalid chunk_size=$chunkSize")
}
```

### [HIGH] F-ENC-007: `ChunkedGcmRandomAccessDataSource.loadChunk` tidak fully read chunk
**File**: `app/src/main/java/onlasdan/gallery/transcoding/data/ChunkedGcmRandomAccessDataSource.kt:154-162`
**Deskripsi**: `channel.read(ByteBuffer.wrap(cipherBuf))` return bytes read, yang mungkin kurang dari `cipherBuf.size` untuk file yang sedang ditulis (progressive download). Kode trim dengan `cipherBuf.copyOf(read)` hanya jika `read < maxCipherLen`, tetapi untuk non-last chunk saat progressive streaming, partial read produce ciphertext terpotong → GCM `doFinal` throw `AEADBadTagException`.
**Why it matters**: Progressive streaming video chunked-GCM break jika file masih downloading. ExoPlayer fail play sampai full chunk available, tetapi kode saat ini tidak wait untuk full chunk — hanya read yang available dan fail.
**Fix**: Loop sampai full chunk read atau EOF:
```kotlin
var totalRead = 0
while (totalRead < maxCipherLen) {
    val n = channel.read(ByteBuffer.wrap(cipherBuf, totalRead, maxCipherLen - totalRead))
    if (n <= 0) break
    totalRead += n
}
```

### [HIGH] F-ENC-008: `ChunkedGcmRandomAccessDataSource` leak FileInputStream channel
**File**: `app/src/main/java/onlasdan/gallery/transcoding/data/ChunkedGcmRandomAccessDataSource.kt:93-126, 185-192`
**Deskripsi**: Di `open()`, channel dibuat line 93 tetapi `channel.close()` di line 126 skipped jika `loadChunk` throw. Di `read()`, channel dibuat line 185 tetapi `channel.close()` di line 192 skipped jika `loadChunk` throw.
**Why it matters**: File descriptor leak. Sesi playback panjang dengan banyak chunk boundary bisa exhaust FD limit dan cause `TooManyOpenFilesException` crash. Path `read()` buka NEW FileInputStream setiap chunk boundary (F-ENC-008), jadi leak rate ~1 FD per MB video.
**Fix**: Pakai try/finally. Lebih baik: keep single channel open untuk lifetime DataSource (open di `open()`, close di `close()`).

### [HIGH] F-ENC-009: `VaultService.canUnlock` log protection-setup state via RcloneDiag
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/VaultService.kt:360-366`
**Deskripsi**: `canUnlock()` log `passwordSetup=$passwordSetup biometricSetup=$biometricSetup recoveryPhraseSetup=$recoveryPhraseSetup protectionsAreSetup=$protectionsAreSetup canMigrate=$canMigrate` via `android.util.Log.e("RcloneDiag", ...)`.
**Why it matters**: Ini reveal protection setup state user ke siapapun dengan ADB access (USB debugging). Attacker belajar apakah user punya biometric, recovery phrase, atau hanya password — berguna untuk planning attack (mis. jika biometric setup, attacker tahu harus paksa fingerprint; jika tidak, fokus ke password). Metadata leak. Juga violate AGENTS.md "Timber only" rule.
**Fix**: Hapus log (debug leftover), atau downgrade ke `Timber.d` dengan content ter-sanitize. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-ENC-010: Pervasive `android.util.Log` + tag `RcloneDiag` di encryption/transcoding
**File**: `VaultService.kt:180, 324, 360`; `VaultIdBackfillUseCase.kt:88`; `SqlCipherKeyProvider.kt:121`; `AesCbcRandomAccessDataSource.kt:288, 299, 306, 408`; `RecoveryPhraseRestoreViewModel.kt:174-190`
**Deskripsi**: 20+ pemakaian `android.util.Log.{e,w,i}` dengan tag `"RcloneDiag"` di module encryption dan transcoding. AGENTS.md §12 mewajibkan "Timber only". Tag `RcloneDiag` adalah leftover B5 TODO_SYNC.
**Why it matters**: `android.util.Log` bypass Timber debug-tree gating — log ini emit di release build. Tag `RcloneDiag` juga dipakai sync module, jadi logcat filter untuk tag itu reveal baik sync + crypto state. `AesCbcRandomAccessDataSource` log setiap 50ms selama video streaming (lines 288, 299, 408) → logcat spam + minor battery drain.
**Fix**: Ganti semua `android.util.Log.x("RcloneDiag", ...)` dengan `Timber.x(...)`. Untuk high-frequency streaming log, gate behind `BuildConfig.DEBUG` atau hapus total. (Mass-replace diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-ENC-011: `ChunkedGcmInputStream` silently swallow GCM auth failures
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/crypto/ChunkedGcmInputStream.kt:160-163`
**Deskripsi**: `loadNextChunk()` catch `Exception` (termasuk `AEADBadTagException`), log via `Timber.e`, dan return `false` (diperlakukan sebagai EOF oleh caller). User lihat output terpotong tanpa indikasi error.
**Why it matters**: Untuk media playback, chunk tampered atau korup produce garbled video frame atau silent truncation alih-alih error "file corrupted" yang jelas. Untuk use case tamper-detection (key selling point GCM over CBC), ini kacau — auth tag diverified tetapi failure silently swallow.
**Fix**: Distinguish auth failure dari EOF. Throw `IOException("Chunk N GCM auth tag verification failed — file may be corrupted or tampered", e)` untuk `AEADBadTagException`.

### [HIGH] F-ENC-012: `VaultProtectionParams.version` field adalah dead code
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/models/VaultProtectionParams.kt:26`
**Deskripsi**: Field `version: Int = 1` tidak pernah dibaca atau dicek di manapun. Gson TIDAK respect Kotlin default values — pakai Java defaults (0 untuk Int). Jadi v15 row yang JSON-nya tidak include `"version"` deserialize ke `version = 0`, bukan `1` sebagaimana dimaksud.
**Why it matters**: Dead code yang terlihat seperti schema versioning tetapi bukan. Developer masa depan mungkin tambah version-dispatch logic dengan asumsi `version >= 1` untuk semua row, tetapi v15 row punya `version = 0`. Silent semantic mismatch.
**Fix**: Hapus field jika benar-benar unused, atau tambah `@JsonProperty("version")` + custom deserializer yang default ke 1, atau tambah migration yang backfill `version = 1`.

### [HIGH] F-SYNC-006: `RcloneConfigManager.clear()` tidak invalidate rclone in-memory config
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneConfigManager.kt:107-114` + `RcloneController.kt:117-129`
**Deskripsi**: `clear()` hapus local `rclone.conf` file dan reset `Config.syncChosenRemote`. Tetapi `RcloneController.configPathApplied` (companion object state) masih point ke deleted file path, dan rclone go runtime sudah cache config di memory. Operasi berikutnya lanjut pakai OLD config sampai app restart.
**Why it matters**: Skenario user: import config A → pilih remote A → operasi work. User ke Settings → "Reset sync" (panggil `clear()`) → import config B → pilih remote B. Upload baru silently pergi ke remote A (karena rclone masih pakai config A cached). Foto user berakhir di cloud account salah, mungkin yang dimaksud disconnect. Diperparah F-SYNC-001: error path tidak surface — upload "sukses" dari pandangan rclone.
**Fix**: `RcloneConfigManager.clear()` harus panggil `RcloneController.stop()` (yang panggil `rcloneFinalize()` + reset `configPathApplied = null`). Operasi berikutnya re-init rclone + re-apply config path baru. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-SYNC-007: Regex-based JSON parser truncate value yang mengandung escaped quote
**File**: `RcloneController.kt:325` (`parseListResult`), `RepoManager.kt:1918-1951` (`parseVaultProtection`), `RepoManager.kt:2028-2035` (`parseMarker`), `HashRegistry.kt:1187-1274` (`parseRegistryJson`)
**Deskripsi**: Keempat parser pakai `"([^"]+)"` regex untuk extract string value. JSON-escaped string (`"name":"foo\"bar.jpg"`) terminate match di `\"` pertama, return `foo\` alih-alih `foo"bar.jpg`.
**Why it matters**: (1) `parseListResult` truncate filename dengan quote → restore download file salah atau fail match local UUID → foto "hilang" setelah fresh-install restore. (2) `parseVaultProtection` truncate `wrappedVMK` base64 → GCM decryption fail dengan auth-tag mismatch → user lock out vault. (3) `parseMarker` truncate `repo_id` UUID → repo detection fail → user dipaksa re-register, potentially overwrite existing repo. (4) `parseRegistryJson` silently drop entry dengan quote di filename → dedup miss → duplicate upload.
**Fix**: Ganti semua 4 regex parser dengan `org.json.JSONObject` (zero new deps, available since API 1) atau kotlinx.serialization. Performance difference negligible. (Fix diterapkan sebagian di cabang `audit-b2-fixes` — `parseListResult` + `verifyFileExists`.)

### [HIGH] F-SYNC-008: `RcloneController.ensureInitialized()` swallow semua exception silently
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt:84-101`
**Deskripsi**: `ensureInitialized()` catch `Exception` dan log di level `Log.e` saja. Flag `initialized` tetap `false`. Setiap `rpc()` call berikutnya retry `ensureInitialized()` (yang fail silently lagi) lalu proceed ke `rpcMethod.invoke(null, ...)` — yang throw karena class tidak ter-init, ditangkap oleh outer try/catch `rpc()` dan return `{"error":"..."}`.
**Why it matters**: Jika gomobile AAR fail load (corrupt .so, unsupported ABI, Android 16 W^X policy change), user lihat "upload failed: rclone error: null" forever. Tidak ada diagnostic surface di UI.
**Fix**: (1) Catch `Throwable` (bukan `Exception`). (2) Track init failure sebagai singleton `initError: Throwable?`. (3) Surface di `checkRcloneAlive()` supaya UI bisa show "rclone failed to initialize: <reason>". (4) Throw typed `RcloneUnavailableException` dari `rpc()` jika `initError != null` alih-alih return fake JSON error string.

### [HIGH] F-SYNC-009: `RcloneController` companion-object state tidak `@Volatile` — race condition
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt:52-53`
**Deskripsi**: `initialized: Boolean` dan `configPathApplied: String?` adalah plain field di companion object (global state). Dimutasi dari `ensureInitialized()` (dipanggil dari coroutine apapun di `Dispatchers.IO`) dan dibaca dari `applyConfigPath()`. Tidak ada `@Volatile`, tidak ada synchronization.
**Why it matters**: `RcloneController` adalah `@Singleton`, jadi semua coroutine share satu instance. Dua upload concurrent (mis. WorkManager run dua `PhotoSyncWorker` di IO dispatcher thread pool) keduanya panggil `rpc()` → `ensureInitialized()` simultan. Interleaving yang mungkin: (1) Keduanya lihat `initialized == false`, keduanya panggil `rcloneInitialize()` → double-init. (2) Write thread satu ke `initialized = true` tidak visible ke thread lain → thread lain re-init forever. (3) `configPathApplied` write di-reorder dengan `rcloneRPC` call → rclone pakai stale path. Java Memory Model mengizinkan semua ini tanpa `@Volatile`.
**Fix**: Mark both field `@Volatile`. Lebih baik: pakai `Mutex` untuk make `ensureInitialized()` + `applyConfigPath()` atomic. (Fix `@Volatile` diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-SYNC-010: `RcloneController` reflection (`Class.forName` + `getMethod`) tidak di-cache — diulang setiap RPC call
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt:90, 122, 141-143, 146, 291-292`
**Deskripsi**: Setiap `rpc()` call lakukan: `Class.forName("gomobile.Gomobile")` + `getMethod("rcloneRPC", ...)` + (setelah invoke) `resultObj.javaClass.getMethod("getOutput")`. Reflection lookup ~10-100µs each; tiga lookup per call × ratusan foto per batch × ribuan batch = detik overhead per upload session.
**Why it matters**: (1) Performance — measurable latency di batch upload. (2) Correctness: `getMethod` return `Method` tied ke declaring class. Jika gomobile version rename method, runtime error path (F-SYNC-008) silently swallow.
**Fix**: Cache `Class<*>` dan `Method` reference di `companion object` lazy field. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-SYNC-011: `RcloneController.verifyFileExists()` Size-substring match punya false-positive bug
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt:253-268`
**Deskripsi**: `verifyFileExists()` cek `result.contains("\"Size\":$expectedSize")`. Jika actual size remote `12345` dan `expectedSize` `123`, substring `"Size":123` ditemukan di dalam `"Size":12345` → return `true` (file exists dengan size benar), padahal actual file 100× lebih besar.
**Why it matters**: Ini adalah **satu-satunya** upload-verification check yang run by default (hash verification di line 678 best-effort dan fall back ke "skipped" di mayoritas backend). Jika dua foto kebetulan punya encrypted file size dengan prefix-colliding (mis. 1234-byte dan 12345-byte original), `verifyFileExists` report file salah sebagai upload sukses. Foto ditandai `UPLOADED`, dan jika `syncDeleteAfterUpload` aktif, local original dihapus. Hasil: silent data corruption — local original user hilang, remote punya foto berbeda di bawah UUID expected.
**Fix**: Parse JSON response: `val size = JSONObject(output).getJSONObject("item").getLong("Size")`. Return `size == expectedSize`. Juga: verify `"Name"` match expected filename. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-SYNC-012: `loginRepo()` Layer 2 download network error diperlakukan sebagai `PHRASE_ONLY` fallback
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RepoManager.kt:550-559`
**Deskripsi**: Companion ke F-SYNC-004. Saat `downloadWrappedPhraseEscrow()` return `Result.failure`, `loginRepo()` log "non-fatal" dan return `LoginResult.Success(EscrowType.PHRASE_ONLY)`. User di-route ke phrase-entry UI alih-alih password-entry UI.
**Why it matters**: Jika user hanya ingat password (bukan recovery phrase) dan Layer 2 download fail karena transient network error, user dipaksa ke phrase-entry path — yang tidak bisa diselesaikan — dan mungkin menyerah atau coba `SetupFragment` (→ new VMK → data loss). Same root cause F-SYNC-004.
**Fix**: `if (wrappedResult.isFailure) return LoginResult.Failure(...)`. Hanya fall back ke `PHRASE_ONLY` jika `wrappedResult.isSuccess && wrappedResult.getOrNull() == null`. (Fix diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-SYNC-013: `parseListResult()` `Name` regex truncate filename dengan escaped quote
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneController.kt:322-342`
**Deskripsi**: Sama dengan F-SYNC-007 untuk parser ini — captured value terpotong di `\"` pertama.
**Fix**: Sudah tercakup di F-SYNC-007 fix.

### [HIGH] F-SYNC-014: `HashRegistry.uploadToRemote()` bisa encrypt registry dengan VMK salah dan overwrite yang benar
**File**: `app/src/main/java/onlasdan/gallery/sync/work/HashRegistry.kt:381-455` + `PhotoSyncWorker.kt:955-975`
**Deskripsi**: `uploadToRemote(vmkBytes)` dipanggil dari `PhotoSyncWorker.flushRegistryToRemote()`, yang dapat `vmkBytes` dari `sessionRepository.get().vmk.encoded`. Jika `sessionRepository` return session salah (race saat unlock, multi-vault switch), registry di-encrypt dengan VMK salah. Merge logic preserve "other-vault" blob (yang tidak bisa di-decrypt current VMK), tetapi fresh entry current vault di-encrypt dengan key salah — dan old current-vault entry (di-encrypt VMK benar) di-filter out (karena BISA di-decrypt current VMK... tunggu, tidak — mereka tidak bisa, karena current VMK salah, jadi mereka preserved). Sebenarnya merge logic: `preservedBlobs = allBlobs.filterNot { canDecryptBlob(it, vmkBytes) }`. Dengan VMK salah, SEMUA blob fail decrypt → SEMUA preserved → wrong-VMK-encrypted fresh entry DITAMBAH di atas. Remote registry sekarang punya [old-vault-A-entries (preserved), old-vault-B-entries (preserved), wrong-VMK-fresh-entries (added)].
**Why it matters**: Dedup silently break. Worst case: registry bloat indefinitely dengan entry un-decryptable (setiap batch tambah N lagi). Multi-vault forensic surface grow. Bug silent karena tidak ada error throw.
**Fix**: Di `flushRegistryToRemote()`, validate `session.vmk.encoded.size == 32` + verify session match syncing vault's `vaultId` sebelum `uploadToRemote()`. Set serialization, round-trip verify: re-download registry baru, coba decrypt dengan VMK sama, assert entry count match.

### [HIGH] F-BACK-003: `android.util.Log.i("RcloneDiag", ...)` di PhotoRepository.kt:217
**File**: `app/src/main/java/onlasdan/gallery/model/repositories/PhotoRepository.kt:217-220`
**Deskripsi**: FastStart integration path pakai `android.util.Log.i("RcloneDiag", "[FastStart] Using faststarted file for import (${fastStartedFile.length()} bytes)")` alih-alih Timber.
**Fix**: Ganti ke `Timber.i(...)`. (Diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-BACK-004: `android.util.Log.i("RcloneDiag", ...)` di CacheRotationUseCase.kt:162
**File**: `app/src/main/java/onlasdan/gallery/model/io/CacheRotationUseCase.kt:162-165`
**Deskripsi**: Cache rotation success log pakai `android.util.Log.i("RcloneDiag", ...)`. Function sudah log via Timber.i di line 156-161, lalu DUPLIKASI pesan yang sama via android.util.Log di line 162-165.
**Fix**: Hapus line 162-165 (duplicate call). Timber.i di line 156 sudah log info yang sama. (Diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-BACK-005: `TfliteTagExtractor.kt.bak` (205 baris) adalah dead code
**File**: `app/src/main/java/onlasdan/gallery/model/io/TfliteTagExtractor.kt.bak`
**Deskripsi**: File `.bak` adalah leftover dari Sprint 8 TFLite disable. `build.gradle.kts:408` punya `// implementation("org.tensorflow:tensorflow-lite:2.16.1")` (commented out). `AppModule.kt:199-200` wire `StubTagExtractor` sebagai `TagExtractor` aktif. File `.bak` import `org.tensorflow.lite.Interpreter` yang tidak ada di classpath — jika tidak sengaja direname ke `.kt`, build akan break.
**Fix**: Hapus file `.bak`. Jika TFLite re-enable direncanakan, track di branch/commit history terpisah. (Diterapkan di cabang `audit-b2-fixes`.)

### [HIGH] F-BACK-006: Zero test coverage untuk backup/restore
**File**: `app/src/test/java/onlasdan/gallery/...`
**Deskripsi**: Tidak ada test untuk: V1-V5 restore dispatch, V5 `wrappedVMK` unwrap+decrypt round-trip, `BackupStrategyImpl` vs `LegacyBackupStrategyImpl` dispatch, `UnlockBackupUseCase` V1/V4/V5 path, `DumpDatabaseUseCase` V3/V5 emission, `MigrationSpec1To2` v1→v2, `MIGRATION_15_16` no-op safety, `SqlCipherMigrationHelper` v15→v16 flow.
**Why it matters**: Backup/restore adalah path CRITICAL data-preservation. Dengan 5 restore version, 3 unlock path, dan 2 backup strategy, combinatorial space besar. Critical finding F-BACK-001/002 (metadata loss, no vault-protection restore) akan tertangkap oleh simple round-trip test.
**Fix**: Tambah minimal: (1) `RestoreBackupV5Test` round-trip; (2) `UnlockBackupUseCaseTest` untuk setiap V1/V4/V5 path; (3) `PhotokDatabaseMigrationTest` pakai `MigrationTestHelper` untuk v1→v16. (Out of scope untuk fix cepat.)

### [HIGH] F-MAIN-002: `build.gradle.kts:135-167` `useLegacyPackaging = true` + comment block stale
**File**: `app/build.gradle.kts:135-167`
**Deskripsi**: Comment block justifikasi `useLegacyPackaging = true` untuk OLD subprocess approach (`ProcessBuilder.start()` butuh on-disk file, bukan mmap-from-APK). Migrasi gomobile replace subprocess dengan `System.loadLibrary("gojni")` (dlopen), yang work fine dengan `useLegacyPackaging = false` (default — mmap `.so` langsung dari APK, no extraction). Setting masih `= true`, force extraction `libgojni.so` (~110 MB across two ABI) ke `nativeLibraryDir` saat install.
**Why it matters**: (1) Disk waste — ~110 MB extracted di disk plus ~110 MB di APK. (2) Slower install/update. (3) Stale comment menyesatkan maintainer future. (4) 16KB-alignment requirement (Android 15+) berlaku untuk `useLegacyPackaging = false` — build script sudah verify 16KB alignment, jadi switch ke `false` aman.
**Fix**: (1) Set `useLegacyPackaging = false` (atau hapus block `packaging { jniLibs { ... } }` entirely — `false` adalah default untuk minSdk ≥ 23). (2) Replace comment block dengan note singkat. (Fix diterapkan di cabang `audit-b2-fixes`.)

---

## Temuan MEDIUM (27)

### [MEDIUM] F-ENC-013: 6 file test di stale `dev/leonlatsch/photok/` directory
**File**: `app/src/test/java/dev/leonlatsch/photok/encryption/integration/{RecoveryPhraseLifecycleTest, CryptoMigrationV2CompatibilityTest, VaultLifecycleTest, CryptoMigrationV1ToV3Test}.kt`; `app/src/test/java/dev/leonlatsch/photok/encryption/crypto/{Bip39AlgorithmTest, CryptoEnginesTest}.kt`
**Deskripsi**: 6 file test ini punya CORRECT `package` declaration (`onlasdan.gallery.encryption.integration` / `.crypto`) tetapi live under OLD `dev/leonlatsch/photok/` directory path. Kotlin/Java tidak require directory == package, jadi test compile dan run, tetapi mismatch confusing dan non-idiomatic.
**Fix**: `git mv app/src/test/java/dev/leonlatsch/photok/encryption/ app/src/test/java/onlasdan/gallery/encryption/`.

### [MEDIUM] F-ENC-014: Orphan `vault_protection` table ditinggal di encrypted `photok.db`
**File**: `app/src/main/java/onlasdan/gallery/encryption/data/SqlCipherMigrationHelper.kt:192-218`; `PhotokDatabase.kt:62-66, 309-310`
**Deskripsi**: `sqlcipher_export('new')` copy SEMUA table dari plaintext v15 DB ke encrypted v16 DB, termasuk `vault_protection`. Migration lalu JUGA copy `vault_protection` row ke plaintext `BootstrapDatabase`. Hasil: `vault_protection` ada di KEDUA database. Room ignore orphan di `photok.db` (tidak di entity list), tetapi wrapped VMK + KDF params terduplikasi.
**Fix**: Tambah `DROP TABLE IF EXISTS vault_protection` ke `MIGRATION_15_16` (atau `MIGRATION_16_17`) setelah bootstrap copy confirmed.

### [MEDIUM] F-ENC-015: `CbcCryptoEngine` class name misleading
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/crypto/CbcCryptoEngine.kt:34-54`
**Deskripsi**: Walau namanya CBC, class ini handle CBC (v0x01/0x02), single-stream GCM (v0x03), DAN chunked GCM (v0x04). Class comment acknowledge: "class name kept to avoid touching Hilt binding graph".
**Fix**: Rename ke `HybridCryptoEngine` atau `VersionDispatchCryptoEngine`. Update `EncryptionBindingModule.bindCryptoEngine` + test file.

### [MEDIUM] F-ENC-016: `ChunkedGcmOutputStream` selalu tulis `totalPlaintextSize=0`
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/crypto/ChunkedGcmOutputStream.kt:73-75`
**Deskripsi**: 8-byte `total_plaintext_size` header field selalu ditulis 0 ("unknown") karena `OutputStream` tidak support seek back untuk update setelah `close()`.
**Why it matters**: `ChunkedGcmRandomAccessDataSource.open()` return `Long.MAX_VALUE` saat `totalPlaintextSize == 0`, telling ExoPlayer content length unknown. ExoPlayer tidak bisa seek past downloaded portion, dan MP4 dengan MOOV atom di akhir mungkin tidak seekable sama sekali. Regression dari CBC (di mana `AesCbcRandomAccessDataSource` return `dataSpec.length`).
**Fix**: Untuk local file (bukan progressive streaming), post-process file setelah `close()` untuk seek back dan tulis actual `totalBytesWritten`. Atau pakai `RandomAccessFile` wrapper.

### [MEDIUM] F-ENC-017: `VaultProtectionTable` pakai camelCase column name `wrappedVMK`
**File**: `app/src/main/java/onlasdan/gallery/encryption/data/VaultProtectionTable.kt:72`
**Deskripsi**: Room pakai Kotlin property name sebagai column name by default. `wrappedVMK` jadi column `wrappedVMK` (camelCase). SQL case-insensitive untuk identifier, jadi work, tetapi inkonsisten dengan conventional snake_case.
**Fix**: Tambah `@ColumnInfo(name = "wrapped_vmk")`. Memerlukan migration (column rename).

### [MEDIUM] F-ENC-018: `createPasswordProtectionFromSession` duplikasi wrapping logic
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/VaultService.kt:281-329`
**Deskripsi**: Method re-implement VMK-wrapping logic (salt gen, IV gen, KEK derive, cipher init, `doFinal`) inline, alih-alih delegate ke `PasswordVaultProtectionHandler.wrapExistingVmk()`. DRY violation — root cause F-ENC-003.
**Fix**: Delegate ke `wrapExistingVmk`.

### [MEDIUM] F-ENC-019: `unlockMultiVaultPassword` catch semua exception
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/VaultService.kt:212-221`
**Deskripsi**: Multi-vault unlock loop catch `Exception` broadly dan treat setiap failure sebagai "wrong password for this row, try next". Ini catch `AEADBadTagException`/`BadPaddingException` (correct — auth failure) tetapi juga `IOException`, `IllegalStateException`, dll.
**Why it matters**: `wrappedVMK` blob korup atau DB I/O error silently diperlakukan sebagai "wrong password" — user lihat "incorrect password" padahal issue sebenarnya data corruption.
**Fix**: Catch hanya auth-failure exception. Let other exception propagate sebagai real error.

### [MEDIUM] F-ENC-020: `CbcCryptoEngine.createDecryptStream` ignore `input.read()` return value
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/crypto/CbcCryptoEngine.kt:113-115, 125-126, 141-142`
**Deskripsi**: `input.read(salt, 0, salt.size)` dan `input.read(iv, 0, iv.size)` tidak cek return value. `InputStream.read(byte[], int, int)` TIDAK dijamin fill buffer — mungkin return lebih sedikit byte. Pada truncated stream, salt/IV partial zero-fill, leading ke wrong cipher init.
**Fix**: Pakai `input.readNBytes(buffer, 0, size)` (Java 9+, available via Kotlin extension) yang loop sampai full atau EOF, atau cek return value dan throw pada short read.

### [MEDIUM] F-ENC-021: Misleading comment di `SqlCipherMigrationHelper.copyVaultProtectionToBootstrap`
**File**: `app/src/main/java/onlasdan/gallery/encryption/data/SqlCipherMigrationHelper.kt:292-311`
**Deskripsi**: Comment claim "v15 schema for vault_protection is: id TEXT PRIMARY KEY, type TEXT, wrapped_vmk BLOB, salt TEXT, iv TEXT, kdf TEXT, kdf_iterations INT, algorithm TEXT, key_size INT, version INT" — lalu kontradiksi diri sendiri dengan "wait, looking at VaultProtectionTable, `params` is a VaultProtectionParams object stored via Room's type converter." Ini mid-investigation rambling yang tidak pernah dibersihkan.
**Fix**: Replace dengan: "v15 schema has 4 columns (id, type, wrappedVMK, params) — same as bootstrap DB. We copy rows as-is via dynamic column-name INSERT."

### [MEDIUM] F-ENC-022: `VaultProtectionRepository.removeProtection(type)` API misleadingly named
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/VaultProtectionRepository.kt:47-51`; `VaultProtectionDao.kt:31-32`
**Deskripsi**: `removeProtection(type)` hapus SEMUA row dari type yang diberikan (`DELETE FROM vault_protection WHERE type = :type`). Untuk type `Password` dengan multi-vault, ini hapus SEMUA password vault. Nama API `removeProtection` (singular) suggest per-row deletion.
**Fix**: Rename ke `removeAllProtectionsOfType(type)`. Atau keep nama tetapi make docstring lebih prominent.

### [MEDIUM] F-ENC-023: `SqlCipherKeyProvider` doc comment claim PBKDF2 tetapi sebenarnya pakai raw key
**File**: `app/src/main/java/onlasdan/gallery/encryption/data/SqlCipherKeyProvider.kt:103-110`
**Deskripsi**: Doc bilang "SQLCipher accepts this directly as a passphrase (it runs PBKDF2-HMAC-SHA512 internally to derive the actual page-encryption key)." Tetapi zetetic `SupportOpenHelperFactory(byte[], ...)` convert byte[] ke hex dan wrap dalam `x"<hex>"` raw-key format — TIDAK ada PBKDF2 yang run.
**Fix**: Update comment: "zetetic SupportOpenHelperFactory converts the 32-byte key to hex and passes it to SQLCipher in raw-key format (`x"<hex>"`). SQLCipher uses the raw bytes directly as the page-encryption key — no PBKDF2 is run, because the Keystore key is already a 256-bit random secret."

### [MEDIUM] F-ENC-024: `CbcCryptoEngine` return null on exception (ambiguous error handling)
**File**: `app/src/main/java/onlasdan/gallery/encryption/domain/crypto/CbcCryptoEngine.kt:93-96, 158-161`
**Deskripsi**: Keduanya `createEncryptStream` dan `createDecryptStream` catch `Exception` dan return `null`. Caller harus null-check, tetapi `null` ambiguous — bisa berarti "wrong key", "corrupted header", "IO error", "unsupported version byte", dll.
**Fix**: Throw typed exception: `InvalidVersionByteException`, `CorruptHeaderException`, `UnsupportedAlgorithmException`. Breaking API change — schedule untuk major version.

### [MEDIUM] F-SYNC-015: `RcloneConfigManager.parseConfig()` tidak handle multi-line / quoted / continuation values
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RcloneConfigManager.kt:178-210`
**Deskripsi**: Parser split di `text.lineSequence()` dan treat setiap non-blank, non-comment line sebagai `key = value`. rclone.conf support: (1) multi-line value dengan leading whitespace (continuation), (2) quoted value dengan embedded `#` atau `;` (bukan comment), (3) value span multiple line untuk secret seperti `crypt_secret = long_base64...`. Parser saat ini treat setiap continuation line sebagai key=value pair baru.
**Fix**: Implement INI-style parsing dengan continuation support, atau pakai `java.util.Properties`, atau — lebih baik — jangan parse config sama sekali: hanya validate bahwa ada minimal satu `[section]` header (satu-satunya validasi yang dipakai). Untuk `RemoteInfo` list, hanya `name` dan `type` yang dibutuhkan — keduanya single-line. Drop multi-value map entirely.

### [MEDIUM] F-SYNC-016: `RepoManager.detectRepo()` / `hasRepo()` error-classification logic cek dead "binary"/"rclone" substring
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RepoManager.kt:284-303, 373-385`
**Deskripsi**: Klasifikasi `isDirNotFound` exclude error yang mengandung "binary" atau "rclone" — workaround untuk OLD subprocess approach di mana `locateRcloneBinary()` bisa return "rclone binary not found". `locateRcloneBinary()` sudah tidak ada. Semua "not found" error sekarang dari rclone RC API sendiri, yang tidak pernah mengandung "binary".
**Fix**: Hapus cek substring "binary" dan "rclone". Match `directory not found` atau `error in ListJSON` langsung.

### [MEDIUM] F-SYNC-017: `SyncModule.provideWorkManagerConfiguration()` set `MinimumLoggingLevel = Log.INFO` — too verbose untuk release
**File**: `app/src/main/java/onlasdan/gallery/sync/di/SyncModule.kt:121-126`
**Deskripsi**: `setMinimumLoggingLevel(android.util.Log.INFO)` tell WorkManager untuk log semua INFO+ message ke logcat. WorkManager chatty di INFO level. Di release build, ini clutter logcat dan visible ke app lain dengan permission `READ_LOGS`.
**Fix**: Pakai `if (BuildConfig.DEBUG) Log.INFO else Log.WARN` (atau `Log.ERROR`). Hapus hardcoded INFO. (Diterapkan di cabang `audit-b2-fixes`.)

### [MEDIUM] F-SYNC-018: `PhotoSyncWorker` dan `RepoManager` bypass `SyncLogRotator` dengan direct `File.appendText` ke `sync_log.txt`
**File**: `PhotoSyncWorker.kt:1380, 1396, 1463, 1507, 1521, 1546`; `RepoManager.kt:260-262`
**Deskripsi**: Enam call site di `PhotoSyncWorker.enqueue()` / `dumpWorkInfo()` / `dumpAllWorkInfo()` dan satu di `RepoManager.detectRepo()` write langsung via `java.io.File(context.filesDir, "sync_log.txt").appendText(...)` — bypass `SyncLogRotator.append()` (yang cap di 1 MB) DAN `SyncLogger`'s own 500 KB trim logic.
**Why it matters**: `sync_log.txt` live di `filesDir` (persistent storage, NOT cache). Direct-append site tidak punya rotation. Pada device dengan banyak `enqueue()` call + banyak `detectRepo()` call, file grow tanpa batas.
**Fix**: Replace setiap `File(...).appendText(...)` dengan `SyncLogRotator.append(context, text)`. Make `SyncLogRotator.append()` single entry point.

### [MEDIUM] F-SYNC-019: `RepoSetupViewModel.submitPassword()` heavy `RcloneDiag` debug logging — 12 occurrence
**File**: `app/src/main/java/onlasdan/gallery/reposetup/ui/RepoSetupViewModel.kt:596, 617, 632, 637, 659, 665`
**Deskripsi**: Enam `android.util.Log.e("RcloneDiag", ...)` call di dalam `submitPassword()` log ke logcat dan di-tag `Log.e` (ERROR level). Log include: "downloadRegistry loaded N entries", "applyRegistryMetadataToPhotos backfilled N rows", "restoreThumbnailsFromPacks restored N thumbnails", "downloadRegistry FAILED: <exception message>", "created local VaultProtection(Password)", "FAILED to create local VaultProtection(Password) — next open may re-trigger setup (data-loss risk!)".
**Fix**: (1) Ganti semua `android.util.Log.e` dengan `Timber.i` (sukses) / `Timber.e` (error). (2) Gate verbose di `if (BuildConfig.DEBUG)`. (3) "data-loss risk" log harus `CrashLogger.logCrash()` call supaya masuk `crash_log.txt` untuk support.

### [MEDIUM] F-SYNC-020: `HashRegistry.uploadToRemote()` multi-vault merge racy under concurrent multi-device uploads
**File**: `app/src/main/java/onlasdan/gallery/sync/work/HashRegistry.kt:381-455`
**Deskripsi**: Merge logic download existing remote registry, preserve blob yang tidak bisa di-decrypt current VMK (other vault), upload `[our fresh entries + preserved other-vault blob]`. Correct untuk SEQUENTIAL upload. Under CONCURRENT upload dari dua device dengan vault berbeda: device A download [A1, A2, A3], device B download [A1, A2, A3] simultan. Keduanya preserve A's entries. A upload [A1', A2', A3'] (replacing A1/A2/A3). B upload [B1, B2, A1, A2, A3] (preserving A's entries yang baru saja A replace). Jika B's upload land AFTER A's, remote punya [B1, B2, A1, A2, A3] — A's fresh A1'/A2'/A3' HILANG.
**Fix**: (1) Update doc. (2) Implement optimistic concurrency: sebelum upload, re-download remote registry, re-run merge dengan state terbaru, lalu upload. (3) Long-term: rclone `operations/copyfile` dengan `If-Match`/ETag headers untuk atomic compare-and-swap.

### [MEDIUM] F-SYNC-021: `SyncRestorer.ensureLocalOriginalWithProgress()` — JNI migration regress progress callback ke single 100% tick
**File**: `app/src/main/java/onlasdan/gallery/sync/work/SyncRestorer.kt:122-172`
**Deskripsi**: OLD `RcloneController.downloadFile()` (subprocess) accept `onProgress: (Float) -> Unit` callback dan emit periodic size-based progress. NEW gomobile-based `downloadFile()` adalah single blocking `operations/copyfile` RC call tanpa progress. `ensureLocalOriginalWithProgress()` sekarang hanya panggil `onProgress(100f)` setelah download complete.
**Fix**: Pakai rclone async RC API: `operations/copyfile` dengan `_async=true` return `jobid`. Poll `job/status` setiap 500ms dengan `withTimeoutOrNull`, parse `bytes`/`totalBytes`, panggil `onProgress(bytes/total*100)`. Cancel job pada coroutine cancellation (`job/stop`).

### [MEDIUM] F-SYNC-022: `SyncLogger.trimIfNeeded()` baca seluruh 500 KB file ke memory + split via regex di setiap log write
**File**: `app/src/main/java/onlasdan/gallery/sync/debug/SyncLogger.kt:141-165`
**Deskripsi**: Saat file mendekati cap 500 KB, `trimIfNeeded()` lakukan `file.readText()` (load semua 500 KB ke String), split di `(?=\n--- )` regex (create list of substring, each a copy), lalu write back. Dua writer race: `SyncLogger` dan `SyncLogRotator` keduanya append ke file sama tanpa synchronization.
**Fix**: (1) Consolidate ke SATU writer — `SyncLogger` harus panggil `SyncLogRotator.append()`. (2) Make `SyncLogRotator.append()` `@Synchronized`. (3) Pakai `RandomAccessFile` untuk in-place truncation.

### [MEDIUM] F-SYNC-023: `RepoManager.registerRepo()` tulis marker file sebagai plaintext JSON
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RepoManager.kt:418-422`
**Deskripsi**: Marker file `repo-config.json` di-upload sebagai plaintext. File escrow (recovery-phrase.json.crypt, wrapped-phrase.json.crypt) adalah AES-256-GCM encrypted dengan VMK.
**Why it matters**: Inkonsistensi. Attacker dengan read access ke remote bisa lihat: (1) bahwa PhotoZ repo ada (revealed by filename `photoz-backup/repo-config.json`), (2) kapan dibuat (timestamp), (3) repo_id (tidak sensitive per se, tapi berguna untuk correlation).
**Fix**: (a) encrypt marker dengan VMK (chicken-and-egg: marker dibaca SEBELUM vault unlock, jadi butuh key terpisah), atau (b) accept leak dan dokumentasikan (marker sengaja public — cara app detect "is this a PhotoZ repo"). Opsi (b) reasonable — keberadaan PhotoZ repo sudah leak oleh `photoz-backup/` directory name.

### [MEDIUM] F-BACK-007: `RestoreBackupV2/V3/V4/V5` log "Skipping dead file" dengan filename SALAH
**File**: `app/src/main/java/onlasdan/gallery/backup/domain/RestoreBackupV2.kt:87-91` (also V3:92-96, V4:92-96, V5:94-98)
**Deskripsi**: Dead-file skip block baca:
```kotlin
if (metaData.photos.none { ze.name.contains(it.uuid) }) {
    ze = stream.nextEntry     // ← advances to NEXT entry
    Timber.i("Skipping dead file in backup: ${ze.name}")  // ← logs NEXT entry's name
    continue
}
```
`ze = stream.nextEntry` terjadi SEBELUM `Timber.i` log, jadi log message show NEXT entry's name, bukan entry yang sedang di-skip.
**Fix**: Log SEBELUM advancing: `Timber.i("Skipping dead file in backup: ${ze.name}"); ze = stream.nextEntry; continue`.

### [MEDIUM] F-BACK-008: `HashRegistryDao.findByHash` tidak filter by vault_id
**File**: `app/src/main/java/onlasdan/gallery/sync/work/HashRegistryDao.kt:36-37`
**Deskripsi**: `@Query("SELECT * FROM hash_registry WHERE content_hash = :hash AND deleted = 0 LIMIT 1")` return first matching row regardless of vault. `hash_registry` table punya `vault_id` column (sejak v11) tetapi `content_hash` adalah PRIMARY KEY, jadi setiap content_hash hanya bisa belong ke ONE vault.
**Why it matters**: Multi-vault scenario, jika vault A upload hash H dan vault B kemudian coba upload hash H sama, upload worker consult `findByHash(H)` dan find vault A's entry → skip upload → vault B's foto reference vault A's UUID di remote. Break multi-vault data isolation.
**Fix**: (1) Ganti PK ke `(content_hash, vault_id)` composite + update `findByHash` filter by vault_id, ATAU (2) dokumentasikan bahwa dedup sengaja cross-vault (deliberate trade-off untuk storage efficiency).

### [MEDIUM] F-BACK-009: `TODO1.md` line 13 QtFastStart Status STALE
**File**: `TODO1.md:13`
**Deskripsi**: `**Status**: TODO` untuk item #1 (QtFastStart), tetapi implementasi complete (commit `8355dce5` "feat: QtFastStart", `80b8838e` "fix: FastStartUseCase — use actual QtFastStart API"). `FastStartUseCase.kt` fully implemented (170 baris), integrate dengan vendored `net.ypresto.qtfaststart.QtFastStart.java`, dan wired ke `PhotoRepository.safeImportPhoto` di lines 210-225 + 289-294.
**Fix**: Update TODO1.md line 13: `- **Status**: DONE — see FastStartUseCase.kt + commits 8355dce5, 80b8838e`. (Diterapkan di cabang `audit-b2-fixes`.)

### [MEDIUM] F-BACK-010: PhotoRepository TODO #1 comment stale (3 occurrence)
**File**: `app/src/main/java/onlasdan/gallery/model/repositories/PhotoRepository.kt:90, 199, 293`
**Deskripsi**: Tiga `TODO #1 — QtFastStart` comment tetap di PhotoRepository.kt walau integrasi complete.
**Fix**: Replace `TODO #1 —` dengan `QtFastStart:` atau `@since TODO #1 —`. (Diterapkan di cabang `audit-b2-fixes`.)

### [MEDIUM] F-BACK-011: FastStart temp file cleanup tidak di try/finally
**File**: `app/src/main/java/onlasdan/gallery/model/repositories/PhotoRepository.kt:289-294`
**Deskripsi**: Jika `safeCreatePhoto` throw (mis. `createThumbnail` throw, atau `photoDao.insert` throw `SQLiteConstraintException`), line `fastStartTempFile?.let { fastStartUseCase.cleanup(it) }` tidak pernah execute. Temp file (`cacheDir/faststart-output-<ts>.mp4`, potentially 100s MB untuk video) leak di cacheDir sampai OS reclaim.
**Fix**: Wrap body dalam try/finally, dengan `fastStartTempFile?.let { fastStartUseCase.cleanup(it) }` di finally block. (Diterapkan di cabang `audit-b2-fixes`.)

### [MEDIUM] F-BACK-012: `BackupMetaData.V1` punya unused `albums` dan `albumPhotoRefs` field
**File**: `app/src/main/java/onlasdan/gallery/backup/data/BackupMetaData.kt:34-41`
**Deskripsi**: `BackupMetaData.V1` data class declare `albums: List<AlbumBackup>` dan `albumPhotoRefs: List<AlbumPhotoRefBackup>`, tetapi `RestoreBackupV1.restore` hanya iterate `metaData.getPhotosInOriginalOrder()` — TIDAK restore album atau album-photo ref. V1 format docstring (RestoreBackupV1.kt line 55-56) eksplisit bilang "Only `photos` are tracked".
**Fix**: (1) Hapus `albums` dan `albumPhotoRefs` dari `BackupMetaData.V1`, ATAU (2) tambah comment ke V1 bahwa field selalu empty di V1 backup.

### [MEDIUM] F-MAIN-003: `README.md` stale — schema v9 + rcd subprocess description
**File**: `README.md:158-167`
**Deskripsi**: README bilang "The cloud-sync feature uses an embedded `rclone` binary (started as an rcd child process on first use)." Aktual: gomobile JNI via dlopen. README juga bilang "current schema version is 9" — aktual v16. Baris 119 bilang "AES-256 (CBC for media bodies, GCM for metadata)" — aktual juga support chunked GCM (v0x04) sejak TODO #2.
**Fix**: Update README: ganti "rcd child process" → "gomobile JNI shared library"; "schema version 9" → "schema version 16"; tambah mention chunked GCM. (Diterapkan di cabang `audit-b2-fixes`.)

### [MEDIUM] F-MAIN-004: `AGENTS.md` stale — bilang source di `dev/leonlatsch/photok/`
**File**: `AGENTS.md:35`
**Deskripsi**: "All source code lives under `app/src/main/java/dev/leonlatsch/photok/`." Aktual: `app/src/main/java/onlasdan/gallery/`.
**Fix**: Update ke path yang benar. (Diterapkan di cabang `audit-b2-fixes`.)

### [MEDIUM] F-MAIN-005: `ROADMAP2.md` menandai SQLCipher sebagai TODO padahal sudah implemented
**File**: `ROADMAP2.md:16, 25-29`
**Deskripsi**: Tabel summary di line 16 menandai `#6 SQLCipher` sebagai TODO. Section "Phase 1 — Security Hardening" line 25-29 menjelaskan SQLCipher sebagai "HIGH PRIORITY" dengan estimasi 1 session. Aktual: SQLCipher fully implemented (Sprint 3 / TODO #6), dengan `SqlCipherKeyProvider`, `SqlCipherMigrationHelper`, `BootstrapDatabase`, `MIGRATION_15_16`. Schema v16.
**Fix**: Update ROADMAP2.md: tandai SQLCipher sebagai DONE, refer ke commit + file. (Diterapkan di cabang `audit-b2-fixes`.)

---

## Temuan LOW (13)

### [LOW] F-ENC-025: "TODO #N" comment adalah documentation marker, bukan pending work
**File**: Multiple — 30+ comment seperti `// TODO #2 — Chunked GCM` dan `* @since v15 — TODO #2 chunked streaming encryption` sebenarnya `@since` documentation marker untuk work yang sudah selesai, bukan TODO yang pending.
**Fix**: Reformat sebagai `@since v15 (roadmap #2)` atau `@since v15` saja — drop prefix "TODO".

### [LOW] F-ENC-026: Tidak ada test untuk Argon2id KDF path
**File**: `app/src/test/java/` — tidak ada test file cover `KeyGen.deriveArgon2idKey`, `PasswordVaultProtectionHandler.create` (Argon2id path), atau `ChangePasswordUseCase` (Argon2id path).
**Fix**: Tambah test: `KeyGenTest.deriveArgon2idKey produces correct length`, `PasswordVaultProtectionHandlerTest.create uses Argon2id with correct IV encoding`, dst.

### [LOW] F-ENC-027: Tidak ada test untuk `ChunkedGcmRandomAccessDataSource` random access
**File**: `app/src/test/java/` — tidak ada test file cover `ChunkedGcmRandomAccessDataSource`.
**Fix**: Tambah `ChunkedGcmRandomAccessDataSourceTest`: multi-chunk file, seek ke middle chunk, verify plaintext; seek to chunk boundary; seek past EOF; corrupted chunkSize header; tampered chunk → exception.

### [LOW] F-ENC-028: Tidak ada test untuk `SqlCipherMigrationHelper` v15→v16 migration
**File**: `app/src/test/java/` — tidak ada test file cover `SqlCipherMigrationHelper.migrateIfNecessary`.
**Fix**: Tambah `SqlCipherMigrationHelperTest`: create plaintext v15 DB dengan vault_protection row, run migration, verify (a) `photok.db` sekarang encrypted (header != "SQLite format 3"), (b) bootstrap DB punya copied row, (c) `config.sqlCipherMigrationDone == true`, (d) Room bisa open encrypted DB dengan Keystore passphrase.

### [LOW] F-SYNC-024: `RepoManager.detectRepo()` comment reference nonexistent `locateRcloneBinary()`
**File**: `app/src/main/java/onlasdan/gallery/sync/rclone/RepoManager.kt:252, 286`
**Deskripsi**: Line 252: "the first call that triggers `locateRcloneBinary()` during login/repo-init". Line 286: "'rclone binary not found' from `locateRcloneBinary()`". `locateRcloneBinary()` tidak exist di codebase.
**Fix**: Hapus reference `locateRcloneBinary()`. (Diterapkan di cabang `audit-b2-fixes`.)

### [LOW] F-SYNC-025: `.github/workflows/build-rclone.yml` + `scripts/build-rclone.sh` adalah dead code
**File**: `.github/workflows/build-rclone.yml` (145 baris), `scripts/build-rclone.sh` (251 baris)
**Deskripsi**: Workflow build `librclone.so` sebagai ELF EXEC binary (CGO+NDK) dan place di `app/src/main/jniLibs/arm64-v8a/`. App tidak punya `jniLibs/` directory lagi. Active workflow adalah `build-rclone-gomobile.yml` yang build gomobile AAR.
**Fix**: Hapus kedua file. Tambah note di `README.md`/`CONTRIBUTING.md` bahwa gomobile build adalah satu-satunya path yang didukung. (Diterapkan di cabang `audit-b2-fixes`.)

### [LOW] F-SYNC-026: `app/proguard-rules.pro:54-56` comment stale (reference `librclone.so` + `ProcessBuilder`)
**File**: `app/proguard-rules.pro:54-56`
**Deskripsi**: Comment baca: "rclone binary (librclone.so) — keep the JNI-bound wrapper classes that load and invoke the bundled Go executable via ProcessBuilder." Keep rule (`-keep class onlasdan.gallery.sync.rclone.** { *; }`) masih berguna, tetapi comment describe OLD subprocess approach.
**Fix**: Rephrase: "rclone gomobile — keep the JNI-bound wrapper classes (RcloneController uses reflection to invoke gomobile.Gomobile methods)." (Diterapkan di cabang `audit-b2-fixes`.)

### [LOW] F-SYNC-027: `RepoManager.registerRepo()` tulis marker file sebagai plaintext JSON
Sudah tercakup di F-SYNC-023.

### [LOW] F-SYNC-028: Thumbnail pack size doc inconsistency — `SyncConfig.THUMBNAIL_PACK_SIZE_BYTES = 5 MB` tetapi `HashRegistry.kt:522-524` dan `RepoManager.kt:700-702` bilang "50 MB"
**File**: `app/src/main/java/onlasdan/gallery/sync/domain/SyncConfig.kt:76`, `HashRegistry.kt:533-535`, `RepoManager.kt:700-702`
**Fix**: Ganti "50 MB" → "5 MB" di kedua lokasi doc. Atau, jika 50 MB adalah intended size, ganti constant ke `50L * 1024 * 1024` dan update doc.

### [LOW] F-BACK-013: `AlbumDao.internalLink` parameter order confusing
**File**: `app/src/main/java/onlasdan/gallery/model/database/dao/AlbumDao.kt:97-102`
**Deskripsi**: SQL column order `(album_uuid, photo_uuid, linked_at)` tetapi Kotlin parameter order `(photoId, albumId, linkedAt)`. Room bind by name, jadi work, tetapi order mismatch maintenance hazard.
**Fix**: Reorder Kotlin parameter ke match SQL column order: `(albumId, photoId, linkedAt)`.

### [LOW] F-BACK-014: `RestoreBackupV1` pakai substring UUID match
**File**: `app/src/main/java/onlasdan/gallery/backup/domain/RestoreBackupV1.kt:78-80`
**Deskripsi**: `metaData.photos.find { ze.name.contains(it.uuid) }` pakai `String.contains` (substring match) alih-alih exact prefix match.
**Fix**: Pakai `ze.name.startsWith(it.uuid + ".")` untuk exact prefix match.

### [LOW] F-BACK-015: `ImportBottomSheetDialogFragment` `albumUUID` default `""` bukan `null`
**File**: `app/src/main/java/onlasdan/gallery/gallery/ui/importing/ImportBottomSheetDialogFragment.kt:43`
**Deskripsi**: Caller yang omit `albumUUID` argumen dapat `""` (empty string), bukan `null`. Downstream `viewModel.albumUUID = albumUUID` set ViewModel `albumUUID` ke `""`, dan `albumUUID?.let { albumRepository.link(listOf(photoUUID), it) }` akan invoke `albumRepository.link(listOf(photoUUID), "")`.
**Fix**: Ganti default ke `null`: `private val albumUUID: String? = null`.

### [LOW] F-BACK-016: `Photo.kt:33` TODO "Add a domain model for photos"
**File**: `app/src/main/java/onlasdan/gallery/model/database/entity/Photo.kt:33`
**Deskripsi**: Room `@Entity` class dipakai langsung di UI layer. Original upstream Photok debt, bukan diperkenalkan oleh B2 fork migration.
**Fix**: Out of scope B2 audit — track sebagai architectural refactor terpisah.

### [LOW] F-MAIN-006: `github_log.txt` di root repo adalah leftover debug file
**File**: `github_log.txt` (root)
**Deskripsi**: File berisi single Azure auth error XML (`AuthenticationFailed`/`Signature fields not well formed`). Sepertinya leftover dari debugging GitHub Actions upload.
**Fix**: Hapus file. (Diterapkan di cabang `audit-b2-fixes`.)

---

## Yang Terverifikasi BENAR (tidak perlu aksi)

Berikut adalah hal-hal yang diverifikasi oleh audit dan ditemukan **sudah benar**:

### Migrasi JNI
- **Nama class gomobile** (`gomobile.Gomobile`) dan method (`rcloneInitialize`, `rcloneRPC`, `rcloneFinalize`, `getOutput`) **match** dengan yang ada di `app/libs/librclone.aar`.
- **`System.loadLibrary("gojni")`** resolve ke `libgojni.so` di AAR (bukan `librclone.so`).
- **Tidak ada ProcessBuilder/Runtime.exec residue** di code Kotlin/Java (hanya di comment yang stale).
- **AAR berisi kedua ABI** (`arm64-v8a` + `armeabi-v7a`), meski `app/build.gradle.kts:46` restrict `abiFilters` ke `arm64-v8a` saja.
- **16KB page alignment** di-build oleh `scripts/build-rclone-gomobile.sh` (sesuai Android 16 requirement).

### SQLCipher integration
- **StrongBox → TEE fallback** (TODO #4) terverifikasi benar — `SqlCipherKeyProvider.kt:153-176` try StrongBox, catch Exception, rebuild spec tanpa StrongBox, regenerate.
- **VMK zeroing** (TODO #7) terverifikasi — `SessionRepositoryImpl.kt:44-59` panggil `Destroyable.destroy()` pada VMK SecretKeySpec.
- **Version byte dispatch** (0x01/0x02/0x03/0x04) terverifikasi benar di kedua stream dan random-access path.
- **KDF dispatch di `KeyGen`** terverifikasi benar (`when(kdf)` → PBKDF2 atau Argon2id).
- **MIGRATION_15_16 no-op stub** adalah approach yang benar (Room tidak bisa auto-migrate entity removal; manual migration advance `user_version`).
- **`sqlcipher_export()` flow** copy data dengan benar dari plaintext v15 ke encrypted v16 sebelum Room open DB.

### Schema DB
- **Semua 17 schema JSON file** (`PhotoZDatabase/{1..16}.json` + `BootstrapDatabase/1.json`) hadir dan match entity definition Kotlin.
- **`MigrationSpec1To2`** (`@DeleteColumn "id"` + `@RenameColumn uuid→photo_uuid`) terverifikasi benar terhadap `1.json` vs `2.json`.
- **`DATABASE_VERSION = 16`** match `16.json`.
- **V1-V5 restore dispatch** di `RestoreBackupViewModel.kt:125-131` benar.
- **V1-V5 unlock dispatch** di `UnlockBackupUseCase.kt:54-61` benar.
- **`BackupStrategy` dispatch** di `BackupViewModel.kt:58-63` benar (`Default → BackupStrategyImpl` V5, `Legacy → LegacyBackupStrategyImpl` V3).
- **DAO SQL** — semua `@Query` pakai parameterized `:param` binding. `@RawQuery` hanya interpolate enum-hardcoded constant. Tidak ada SQL injection risk.
- **`Converters.kt`** — semua 6 type converter bidirectional benar.
- **`StubTagExtractor`** ter-wire sebagai `TagExtractor` aktif via `AppModule.provideTagExtractor`.
- **`FastStartUseCase`** ter-integrasi dengan vendored `net.ypresto.qtfaststart.QtFastStart.java`.

### Tidak ditemukan
- Tidak ada hardcoded IV.
- Tidak ada ECB mode.
- Tidak ada key material di log output (kecuali F-ENC-002 yang passphrase hex di-embed di SQL string — bukan direct key logging).
- Tidak ada `dev.leonlatsch.photok` reference di source code `encryption/` atau `transcoding/`.

---

## Rekomendasi Prioritas Fix

### Tier 1 — Fix sebelum release berikutnya (Critical, data-loss / security)

1. **F-MAIN-001** (proguard-rules.pro package rename) — 1 baris fix, impact besar.
2. **F-SYNC-004 + F-SYNC-012** (loginRepo escrow download error collapse) — silent vault data loss.
3. **F-SYNC-005** (submitPassword navigasi ke gallery meski persist vault gagal) — silent vault data loss.
4. **F-SYNC-003** (duplicate catch block) — silent upload verification bypass.
5. **F-SYNC-001** (substring `"error":` detection) — false positive/negative error detection.
6. **F-ENC-001** (SqlCipher silent Keystore fallback) — silent security downgrade.
7. **F-ENC-002** (passphrase leak via logcat) — DB key exposure.
8. **F-BACK-001 + F-BACK-002** (V5 backup kehilangan metadata + tidak restore vault protection) — butuh design V6, tidak untuk fix cepat.

### Tier 2 — Fix dalam sprint berikutnya (High, fungsional/security)

9. **F-ENC-003 + F-ENC-004 + F-ENC-005** (KDF consistency) — land bersamaan.
10. **F-ENC-006 + F-ENC-007 + F-ENC-008** (chunked-GCM robustness) — land bersamaan.
11. **F-ENC-009 + F-ENC-010 + F-BACK-003 + F-BACK-004** (logging hygiene) — quick win, mass replace.
12. **F-SYNC-006** (clear() tidak invalidate config) — 1 baris fix.
13. **F-SYNC-007 + F-SYNC-013** (regex JSON parser) — replace dengan JSONObject.
14. **F-SYNC-009** (`@Volatile` companion state) — 2 kata fix.
15. **F-SYNC-010** (cache reflection) — performance + correctness.
16. **F-SYNC-011** (Size substring match) — replace dengan JSON parse.
17. **F-BACK-005** (hapus `.bak` file) — `rm` satu file.
18. **F-MAIN-002** (`useLegacyPackaging = false`) — hemat ~110 MB disk.

### Tier 3 — Cleanup batch (Medium/Low, code smell/docs)

19. **F-ENC-013** (`git mv` 6 test file) — trivial.
20. **F-SYNC-025 + F-MAIN-006** (hapus dead workflow + script + `github_log.txt`) — `rm`.
21. **F-MAIN-003 + F-MAIN-004 + F-MAIN-005 + F-BACK-009 + F-BACK-010** (update stale docs) — batch.
22. **F-ENC-015** (rename `CbcCryptoEngine`) — low-risk refactor.
23. Sisa Medium/Low — batch ke cleanup PR.

### Tier 4 — Investasi test (High value, butuh waktu)

24. **F-ENC-026 + F-ENC-027 + F-ENC-028 + F-BACK-006** — tambah test coverage untuk Argon2id, chunked GCM random access, SQLCipher migration, backup/restore round-trip.

---

## Lampiran: Daftar File yang Diaudit (Ringkasan)

### Sync / rclone (16 file)
- `sync/rclone/RcloneController.kt` (352 baris)
- `sync/rclone/RcloneConfigManager.kt` (233 baris)
- `sync/rclone/RepoManager.kt` (2098 baris)
- `sync/work/PhotoSyncWorker.kt` (1556 baris)
- `sync/work/HashRegistry.kt` (1533 baris)
- `sync/work/HashRegistryDao.kt`, `HashRegistryEntry.kt`
- `sync/work/SyncBatchTracker.kt`, `SyncRestorer.kt`
- `sync/debug/SyncLogger.kt`, `SyncLogRotator.kt`, `CrashLogger.kt`
- `sync/di/SyncModule.kt`
- `sync/domain/Dedup.kt`, `SyncState.kt`, `SyncConfig.kt`
- `reposetup/ui/RepoSetupFragment.kt`, `RepoSetupViewModel.kt`

### Encryption / Crypto (55+ file)
- `encryption/data/SqlCipherKeyProvider.kt`, `SqlCipherMigrationHelper.kt`, `BootstrapDatabase.kt`, `VaultProtectionTable.kt`, `SessionRepositoryImpl.kt`
- `encryption/domain/VaultService.kt`, `ChangePasswordUseCase.kt`, `VaultIdBackfillUseCase.kt`
- `encryption/domain/handlers/PasswordVaultProtectionHandler.kt`, `BiometricVaultProtectionHandler.kt`, `RecoveryPhraseVaultProtectionHandler.kt`
- `encryption/domain/crypto/KeyGen.kt`, `CbcCryptoEngine.kt`, `ChunkedGcmOutputStream.kt`, `ChunkedGcmInputStream.kt`, `Constants.kt`, `PhraseEscrowWrapper.kt`, `CryptoEngine.kt`
- `encryption/domain/models/Algorithm.kt`, `Kdf.kt`, `VaultProtectionParams.kt`
- `encryption/migration/MigrationService.kt`
- `encryption/di/EncryptionModule.kt`
- `transcoding/data/AesCbcRandomAccessDataSource.kt`, `ChunkedGcmRandomAccessDataSource.kt`, `VersionDispatchDataSourceFactory.kt`, `ImageStorageImpl.kt`, `EncryptedImageFetcher.kt`, `EncryptedImageFetcherFactory.kt`

### Backup / DB (28 file)
- `backup/domain/{BackupStrategy,BackupStrategyImpl,LegacyBackupStrategyImpl,RestoreBackupStrategy,RestoreBackupV1-V5,UnlockBackupUseCase,ValidateBackupUseCase,DumpDatabaseUseCase,RestoreResult}.kt`
- `backup/data/{BackupMetaData,BackupMappers,ReadBackupMetadataUseCase}.kt`
- `backup/ui/{BackupViewModel,RestoreBackupViewModel,UnlockBackupViewModel,ConfirmPasswordDialog,RestoreBackupDialogFragment,BackupBottomSheetDialogFragment,UnlockBackupDialogFragment}.kt`
- `model/database/{PhotokDatabase,Converters}.kt`
- `model/database/entity/{Photo,AlbumTable,PhotoType,FilenameExtensions}.kt`
- `model/database/dao/{PhotoDao,AlbumDao}.kt`
- `model/database/ref/AlbumPhotoRelation.kt`
- `model/repositories/{PhotoRepository,ImportSource,CleanupDeadFilesUseCase}.kt`
- `model/io/{FastStartUseCase,ExifExtractor,TagExtractor,CreateThumbnailsUseCase,CacheRotationUseCase}.kt`
- `encryption/data/{BootstrapDatabase,VaultProtectionTable}.kt`

### Build / Config (12 file)
- `app/build.gradle.kts`, `app/proguard-rules.pro`, `gradle.properties`, `settings.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
- `app/src/main/AndroidManifest.xml`
- `scripts/build-rclone.sh`, `scripts/build-rclone-gomobile.sh`
- `.github/workflows/{android,build-rclone,build-rclone-gomobile,build-signed-release,create-rc,translation-badges}.yml`

### Dokumentasi (22 file)
- Root: `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `ENCRYPTION.md`, `LICENSE`, `ROADMAP.md`, `ROADMAP2.md`, `TODO1.md`, `TODO_SYNC.md`, `github_log.txt`
- `agent-ctx/*.md` (14 file)
- `adr/how-to-release.md`

---

## Penutup

Cabang B2 secara mekanis sudah berhasil dimigrasi (gomobile JNI, SQLCipher, Argon2id, chunked GCM, two-layer escrow). Yang tertinggal adalah **lapisan-lapisan di atas batas JNI** yang masih berpikir dalam terminologi subprocess lama, plus beberapa **silent data-loss vector** yang harus segera diatasi sebelum release berikutnya.

Audit ini mengidentifikasi 72 temuan dengan 9 Critical dan 23 High. Cabang `audit-b2-fixes` dibuat dari B2 dan berisi fix untuk sebagian besar Critical + High yang low-risk (lihat commit message di branch tersebut untuk detail fix yang diterapkan). Critical yang memerlukan design lebih lanjut (F-BACK-001 V6 backup format, F-BACK-002 vault protection restore) didokumentasikan tetapi tidak di-fix di cabang ini.

Untuk pertanyaan atau follow-up, refer ID temuan (mis. `F-SYNC-004`) di laporan ini.
