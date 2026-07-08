# 10 — Troubleshooting

> Common issues, debug tips, dan FAQ

---

## Build Issues

### "Failed to find target with hash string 'android-37'"

**Penyebab**: AGP 9.1.0 max support compileSdk 36.1. compileSdk 37 butuh AGP yang lebih baru.

**Fix**:
```bash
# Option 1: Install platform + temporary set compileSdk=36
sdkmanager "platforms;android-36.1"
sed -i 's/compileSdk = 37/compileSdk = 36/' app/build.gradle.kts

# Option 2: Suppress warning (production)
echo "android.suppressUnsupportedCompileSdk=37.0" >> gradle.properties
```

### "Could not read Password for github.com"

**Penyebab**: Token expired atau tidak punya write scope.

**Fix**: Buat PAT baru:
- Classic: scope `repo` (full control)
- Fine-grained: `Contents: Read and write` untuk repo target

```bash
# Push dengan token baru
GIT_ASKPASS=/tmp/askpass.sh git push https://github.com/user/repo branch:branch
```

### "Unresolved reference: gomobile.Gomobile"

**Penyebab**: `librclone.aar` tidak ada di `app/libs/`.

**Fix**:
```bash
# Build dari source (butuh Go + NDK)
bash scripts/build-rclone-gomobile.sh

# Atau download dari CI artifacts
# .github/workflows/build-rclone-gomobile.yml → Run workflow → download AAR
```

### "kaptGenerateStubsFossDebugKotlin FAILED — JAVA_COMPILER not provided"

**Penyebab**: Hanya JRE terinstall, bukan JDK.

**Fix**:
```bash
# Debian/Ubuntu
apt install openjdk-21-jdk-headless

# Verify
javac -version  # harus work
```

### Hilt dependency cycle

**Error**:
```
Dagger/DependencyCycle: Found a dependency cycle:
A is injected at B(…, a)
B is injected at A(…, b)
```

**Fix**: Gunakan `dagger.Lazy` di salah satu constructor:
```kotlin
class RcloneConfigManager @Inject constructor(
    private val rcloneController: dagger.Lazy<RcloneController>, // breaks cycle
)
```

---

## Runtime Issues

### "didn't find section in config file"

**Penyebab**: `options/set {"main":{"Config":...}}` diabaikan rclone.

**Fix**: Sudah fixed di commit `8e2bd21e` — gunakan `config/setpath`.

**Debug**:
```bash
adb shell run-as onlasdan.gallery cat files/rclone/rclone.conf
# Verify file exists + has [section] headers
```

### rclone hang / worker stuck forever

**Penyebab**: Tidak ada timeout di RPC call (sebelum F-SYNC-002).

**Fix**: Sudah fixed — `rpc()` membungkus invoke dengan `withTimeoutOrNull(30_000L)`.

**Debug**:
```bash
adb shell run-as onlasdan.gallery cat files/sync_log.txt | tail -50
# Look for "RPC timeout" entries
```

### Silent vault brick setelah fresh-install login

**Symptom**: User login di device baru → foto lama tidak muncul → thumbnails merah/kosong.

**Penyebab** (sebelum fix): Error download escrow diperlakukan sebagai "no escrow" → user dipaksa setup vault baru → new VMK → foto lama undecryptable.

**Fix**: F-SYNC-004/005/012 — download errors sekarang return `LoginResult.Failure`.

**Verify fix aktif**:
```bash
adb shell run-as onlasdan.gallery cat files/sync_log.txt | grep "loginRepo"
# Should see "Layer 1 escrow download FAILED (blocking)" on error
```

### "upload worker enqueued but never runs"

**Penyebab**: WorkManager default initializer conflict dengan HiltWorkerFactory.

**Fix**: `AndroidManifest.xml` sudah remove `WorkManagerInitializer`:
```xml
<provider android:name="androidx.startup.InitializationProvider" ...>
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

**Debug**:
```bash
adb shell dumpsys jobscheduler | grep onlasdan
adb shell run-as onlasdan.gallery cat files/crash_log.txt
```

### Photos disappear setelah lock/unlock

**Penyebab**: VMK cleared mid-upload (BFU-Safe key handling terlalu agresif).

**Fix**: `BaseApplication.onStop()` tidak lagi clear VMK (comment di line 237-255). VMK di-clear hanya saat lock timeout tercapai.

### "database disk image is malformed"

**Penyebab**: SQLCipher key mismatch atau corrupt DB.

**Fix**:
```bash
# Check if DB is encrypted (should NOT start with "SQLite format 3")
adb shell run-as onlasdan.gallery ls -la databases/
adb shell run-as onlasdan.gallery xxd databases/photok.db | head -1

# If corrupt, factory reset (data loss):
adb shell pm clear onlasdan.gallery
```

---

## Debug Tools

### Log Locations

```
<dataDir>/sync_log.txt      # Sync operations (capped 1MB, rotated)
<dataDir>/crash_log.txt     # Uncaught exceptions
<dataDir>/rclone/rclone.conf # User-imported rclone config
```

### Access via adb

```bash
# Read sync log
adb shell run-as onlasdan.gallery cat files/sync_log.txt

# Read crash log
adb shell run-as onlasdan.gallery cat files/crash_log.txt

# List all files
adb shell run-as onlasdan.gallery ls -laR files/

# Check DB encryption
adb shell run-as onlasdan.gallery xxd databases/photok.db | head -1
# Encrypted: random bytes (not "SQLite format 3")
# Plaintext: "SQLite format 3\0..."
```

### Logcat Filters

```bash
# Sync + rclone
adb logcat -s RcloneController:* RcloneDiag:* PhotoSyncWorker:* SyncLogger:*

# Crypto
adb logcat -s SqlCipherKeyProvider:* SqlCipherMigrationHelper:* VaultService:*

# All app logs (Timber)
adb logcat -s onlasdan.gallery:*
```

### rclone RPC Debug

Enable verbose rclone logging:
```kotlin
// RcloneController.rpc()
Timber.d("RPC: method=$method input=$input")
Timber.d("RPC result: $output")
```

---

## Testing Issues

### "No value passed for parameter 'vaultId'"

**Penyebab**: `HashRegistryDao.findByHash` signature berubah (F-BACK-008) — test mock belum di-update.

**Fix**: Update test mock:
```kotlin
// Old
coEvery { dao.findByHash(hash) } returns entry

// New
coEvery { dao.findByHashAnyVault(hash) } returns entry
// OR
coEvery { dao.findByHash(hash, vaultId) } returns entry
```

### Robolectric tests fail dengan "JSONObject not found"

**Penyebab**: `org.json.JSONObject` adalah Android SDK class — tidak available di plain JVM.

**Fix**: Tambah `@RunWith(RobolectricTestRunner::class)`:
```kotlin
@RunWith(RobolectricTestRunner::class)
class MyJsonTest { ... }
```

### Test timeout / hang

**Penyebab**: Coroutine test tidak pakai `runTest` atau `TestDispatcher`.

**Fix**:
```kotlin
@Test
fun `my test`() = runTest {
    // coroutine code here
}
```

---

## Crypto Issues

### "AEADBadTagException" saat decrypt

**Penyebab**: Wrong key (VMK mismatch), corrupted file, atau tampered ciphertext.

**Fix**:
- Vault lock + unlock dengan password yang benar
- Jika file corrupt, restore dari backup atau re-download dari remote
- F-ENC-011: sekarang throw IOException dengan info chunk position (was: silent swallow)

### "Argon2id iv field too short"

**Penyebab**: `params.iv` kurang dari 20 bytes (4-byte memory prefix + 16-byte IV).

**Fix**: Pastikan `create()` menulis IV dengan memory-cost prefix:
```kotlin
val ivWithMemory = ByteArray(4 + IV_SIZE)
ivWithMemory[0] = (argon2MemoryKB ushr 24).toByte()
// ... 3 more bytes
System.arraycopy(iv, 0, ivWithMemory, 4, IV_SIZE)
```

### Keystore fallback active

**Symptom**: Warning banner di Settings — "Database encryption disabled".

**Penyebab**: Android Keystore gagal (corrupt, hardware issue).

**Fix**:
1. Factory reset device (clears Keystore)
2. Re-install app
3. Jika masih gagal, hubungi support — hardware issue

---

## Network Issues

### "RPC timeout after 30000ms"

**Penyebab**: rclone hang (network stall, backend mutex, infinite loop).

**Fix**:
- F-SYNC-002: 30s timeout aktif — worker retry dengan backoff
- Check network connection
- Verify rclone.conf remote config

### "rclone hash error: hash type not supported"

**Penyebab**: Backend tidak support hash type yang diminta (mis. S3 tidak support SHA-256).

**Fix**:
```kotlin
// Use hashFile dengan fallback
val hash = rcloneController.hashFile(remotePath, "sha256").getOrNull()
if (hash == null) {
    // Fallback: use md5 or size-only verification
    rcloneController.verifyFileExists(remotePath, expectedSize)
}
```

---

## FAQ

### Q: Kenapa `compileSdk = 37` tapi AGP 9.1.0 max 36?

**A**: Repo ini target Android 16 (API 37). AGP 9.1.0 secara official support max 36.1. Untuk production, gunakan AGP yang lebih baru, atau suppress warning:
```properties
android.suppressUnsupportedCompileSdk=37.0
```

### Q: Kenapa `arm64-v8a` only?

**A**: rclone gomobile AAR dibuild hanya untuk arm64 (size concern — full AAR ~110MB untuk 2 ABIs). armeabi-v7a excluded karena mayoritas device modern arm64.

### Q: Kenapa `useLegacyPackaging = false`?

**A**: F-MAIN-002 — gomobile JNI pakai `dlopen` (bukan `exec`), jadi default `false` (mmap dari APK) works fine. `true` (extract ke disk) hemat ~110MB disk + faster install.

### Q: Kenapa 2 databases (photok.db + photok_meta.db)?

**A**: BootstrapDatabase (photok_meta.db) plaintext untuk chicken-and-egg: harus readable sebelum encrypted DB unlock (butuh baca `vault_protection` rows untuk cari VMK yang benar).

### Q: Kenapa SQLCipher key dari Keystore, bukan dari VMK?

**A**: Keystore-backed key memungkinkan background DB queries (CleanupDeadFilesUseCase, PhotoSyncWorker) tanpa vault unlock. Trade-off: "vault lock = DB lock" semantics hilang, tapi gain UX (no re-unlock untuk background sync).

### Q: Bagaimana cara test fresh-install login flow?

**A**:
```bash
# 1. Clear app data (simulate fresh install)
adb shell pm clear onlasdan.gallery

# 2. Launch app, setup vault dengan password + sync ke remote

# 3. Clear app data lagi
adb shell pm clear onlasdan.gallery

# 4. Launch app — should prompt login (not setup)
# 5. Enter password — should unlock + restore dari remote
```

### Q: Bagaimana cara verify SQLCipher aktif?

**A**:
```bash
# Check DB file header — should NOT be "SQLite format 3"
adb shell run-as onlasdan.gallery xxd databases/photok.db | head -1
# Encrypted: 5351 4c69 7465... NO, should be random bytes

# Check Config flag
adb shell run-as onlasdan.gallery cat shared_prefs/onlasdan.gallery_preferences.xml | grep sqlcipher
# Should show: <boolean name="sqlcipher^migrationDone" value="true" />
```
