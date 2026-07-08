# 05 — Cloud Sync Workflow

> **File referensi**: `app/src/main/java/onlasdan/gallery/sync/`

---

## Overview

Cloud sync memungkinkan PhotoZ mirror encrypted artifacts ke cloud storage sendiri via rclone. Sync berjalan sebagai WorkManager foreground service.

```
┌─────────────────────────────────────────────────────────┐
│                    User Action                           │
│         (import photo, atau trigger manual)             │
└──────────────────────┬──────────────────────────────────┘
                       │
              ┌────────▼────────┐
              │ PhotoRepository │
              │  .safeImport()  │
              └────────┬────────┘
                       │
              ┌────────▼────────┐
              │   compute       │
              │   contentHash   │ (SHA-256 plaintext)
              └────────┬────────┘
                       │
              ┌────────▼────────┐
              │ PhotoSyncWorker │ (WorkManager foreground)
              │   .doWork()     │
              └────────┬────────┘
                       │
         ┌─────────────┼─────────────┐
         │             │             │
    ┌────▼───┐   ┌────▼────┐  ┌────▼────┐
    │ Dedup  │   │ Upload  │  │Verify   │
    │ check  │   │ file    │  │(size+   │
    │        │   │ to remote│  │ hash)   │
    └────┬───┘   └────┬────┘  └────┬────┘
         │            │            │
         └────────────┼────────────┘
                      │
              ┌───────▼───────┐
              │ HashRegistry  │
              │ .addEntry()   │
              │ .uploadToRemote│
              └───────────────┘
```

---

## PhotoSyncWorker

```kotlin
@HiltWorker
class PhotoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val photoRepository: PhotoRepository,
    private val rcloneController: RcloneController,
    private val hashRegistry: HashRegistry,
    private val sessionRepository: SessionRepository,
    private val dedup: Dedup,
    // ...
) : CoroutineWorker(appContext, params)
```

### Sync States

```kotlin
enum class SyncState(val storageKey: String) {
    LOCAL_ONLY("LOCAL_ONLY"),      // Belum upload
    UPLOAD_PENDING("UPLOAD_PENDING"), // Antrian upload
    UPLOADED("UPLOADED"),          // Sudah di remote
    UPLOAD_FAILED("UPLOAD_FAILED"), // Gagal upload
}
```

### Upload Flow

```
1. Query photos WHERE syncState = UPLOAD_PENDING
2. For each photo:
   a. Dedup check (contentHash already on remote?)
      → YES: skip upload, mark UPLOADED, create symlink (canonical_uuid)
      → NO: continue
   b. Upload original (<uuid>.crypt)
   c. Upload thumbnail (<uuid>.crypt.tn) — atau pack
   d. Upload video preview (<uuid>.crypt.vp) — jika video
   e. Verify upload (size + optional hash)
   f. If verified: mark UPLOADED
   g. If failed: mark UPLOAD_FAILED, schedule retry
3. At batch end: flush HashRegistry to remote
```

### Foreground Notification

```kotlin
// SyncBatchTracker tracks batch-level progress
"Uploading N of M: filename (size)"
"{N} photos backed up"  // batch complete
```

---

## HashRegistry — Content-Hash Dedup

### Local Cache

```kotlin
@Entity(tableName = "hash_registry")
data class HashRegistryEntry(
    @PrimaryKey val contentHash: String,
    val uuid: String,           // canonical UUID di remote
    val filename: String,
    val albumPath: String?,
    val size: Long,
    val type: String,
    val thumbnailPack: String?,  // pack filename
    val thumbnailOffset: Long,
    val thumbnailLength: Long,
    val deleted: Boolean,        // tombstone untuk GC
    val vaultId: String?,        // multi-vault scope (F-BACK-008)
)
```

### Multi-Vault Isolation (F-BACK-008)

```kotlin
@Query("SELECT * FROM hash_registry WHERE content_hash = :hash AND vault_id = :vaultId AND deleted = 0 LIMIT 1")
suspend fun findByHash(hash: String, vaultId: String): HashRegistryEntry?
```

**Sebelumnya**: query tanpa `vault_id` filter → vault B bisa reference vault A's UUID di remote → break isolation.

### Remote Registry Format

`registry.json.crypt` — AES-256-GCM encrypted dengan VMK. Format per-entry:

```
[0x05 (version byte)]
[entry_0: nonce(12) + GCM(JSON_bytes) + tag(16)]
[entry_1: nonce(12) + GCM(JSON_bytes) + tag(16)]
...
```

Setiap entry berisi: `content_hash`, `uuid`, `filename`, `album_path`, `size`, `type`, `thumbnail_*`, `deleted`.

### Upload Merge (M7 v2 multi-vault)

```kotlin
suspend fun uploadToRemote(vmkBytes: ByteArray) {
    1. Download existing remote registry
    2. Extract raw encrypted blobs
    3. For each blob: try decrypt with current VMK
       - Success → current vault → will be replaced
       - Failure → other vault → PRESERVE as-is
    4. Serialize: fresh current-vault entries + preserved blobs
    5. F-SYNC-014: Re-download RIGHT BEFORE upload (optimistic concurrency)
    6. Upload merged result
}
```

### Optimistic Concurrency (F-SYNC-014)

Mencegah race condition saat dua device upload bersamaan:

```kotlin
// Right before upload, re-download to check for concurrent writes
val recheckResult = rcloneController.downloadFile(remotePath, recheckTemp.absolutePath)
if (recheckResult.isSuccess) {
    val recheckBlobs = extractRawBlobs(recheckData)
    val recheckPreserved = recheckBlobs.filterNot { canDecryptBlob(it, vmkBytes) }
    if (recheckPreserved.size != preservedBlobs.size) {
        // Concurrent write detected — re-merge with latest state
        val remergedData = serializeMergedEncrypted(entries, recheckPreserved, vmkBytes)
        tempFile.writeBytes(remergedData)
    }
}
rcloneController.uploadFile(tempFile.absolutePath, remotePath).getOrThrow()
```

---

## Thumbnail Packs

Thumbnails di-pack menjadi file ≤5 MB untuk efisiensi:

```
<remote>:photoz-backup/thumbnails/pack-<timestamp>.pack
```

Setiap entry di registry menyimpan `thumbnail_pack` (filename) + `thumbnail_offset` + `thumbnail_length`.

### Pack Creation

```kotlin
// HashRegistry.flushPendingThumbnailPacks()
val pack = ByteArray(THUMBNAIL_PACK_SIZE_BYTES) // 5 MB
var offset = 0
for (entry in pendingThumbnails) {
    System.arraycopy(thumbnailBytes, 0, pack, offset, thumbnailBytes.size)
    entry.thumbnailPack = packFilename
    entry.thumbnailOffset = offset.toLong()
    entry.thumbnailLength = thumbnailBytes.size.toLong()
    offset += thumbnailBytes.size
}
rcloneController.uploadFile(packFile.absolutePath, remotePackPath)
```

### Restore

```kotlin
// SyncRestorer.restoreThumbnailsAfterLogin()
1. Download pack file sekali
2. For each entry: extract thumbnail by offset+length
3. Save ke local storage
```

---

## Two-Layer Key Escrow

Fresh-install recovery via 2 encrypted artifacts:

### Layer 1: `recovery-phrase.json.crypt`

VMK wrapped dengan phrase-derived KEK + outer GCM encryption dengan VMK.

### Layer 2: `wrapped-phrase.json.crypt`

Recovery phrase wrapped dengan password-derived KEK + outer GCM encryption dengan VMK.

### Login Flow (RepoManager.loginRepo)

```
1. Detect repo (download marker file)
2. Download Layer 1 (recovery-phrase.json.crypt)
   - F-SYNC-004: download ERROR ≠ "no escrow" — return LoginResult.Failure
3. Download Layer 2 (wrapped-phrase.json.crypt)
   - F-SYNC-012: same — download error returns Failure, not PHRASE_ONLY fallback
4. User enters password
5. Try Layer 2 unwrap (password → phrase) → Layer 1 unwrap (phrase → VMK)
6. If success: unlock vault, persist local Password protection (F-SYNC-005)
```

### Data-Loss Prevention (F-SYNC-004/005/012)

**Critical fixes** untuk mencegah silent vault brick:

```kotlin
// F-SYNC-004: Layer 1 download error → Failure (was: EscrowType.NONE → new VMK → data loss)
if (escrowResult.isFailure) {
    return LoginResult.Failure("Layer 1 escrow download failed: ...")
}

// F-SYNC-005: createPasswordProtectionFromSession failure → Error state (was: navigate to gallery → next open creates new VMK)
try {
    vaultService.createPasswordProtectionFromSession(password, session)
} catch (e: Exception) {
    _state.value = RepoSetupState.Error("Critical: unable to persist vault password...")
    return@launch
}
```

---

## SyncRestorer

Restore missing artifacts dari cloud:

```kotlin
class SyncRestorer @Inject constructor(
    private val photoDao: PhotoDao,
    private val rcloneController: RcloneController,
    private val config: Config,
    // ...
)
```

### ensureLocalOriginalWithProgress (F-SYNC-021)

```kotlin
suspend fun ensureLocalOriginalWithProgress(
    uuid: String,
    onProgress: (Float) -> Unit,
): Result<Unit>
```

Menggunakan `downloadFileWithProgress` (async RC API + job/status polling) untuk real progress bar (was: single 100f tick → indeterminate spinner).

---

## Notification Permission

```kotlin
// SyncModule.provideWorkManagerConfiguration()
.setMinimumLoggingLevel(
    if (BuildConfig.DEBUG) android.util.Log.INFO
    else android.util.Log.WARN  // F-SYNC-017: was INFO always
)
```

Foreground notification butuh `POST_NOTIFICATIONS` permission (Android 13+).

---

## Common Issues

### "upload worker enqueued but never runs"

**Penyebab**: WorkManager default initializer conflict dengan HiltWorkerFactory.
**Fix**: `AndroidManifest.xml` — remove `WorkManagerInitializer` via `tools:node="remove"`.

### "didn't find section in config file"

**Penyebab**: `options/set` diabaikan rclone.
**Fix**: Gunakan `config/setpath` (commit `8e2bd21e`).

### Silent vault brick on fresh-install login

**Penyebab**: Error download escrow diperlakukan sebagai "no escrow" → user dipaksa setup vault baru → new VMK → foto lama undecryptable.
**Fix**: F-SYNC-004/005/012 — surface download errors sebagai LoginResult.Failure.
