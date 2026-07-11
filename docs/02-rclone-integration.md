# 02 — Rclone Integration

> **File referensi**: `app/src/main/java/onlasdan/gallery/sync/rclone/`
> **AAR**: `app/libs/librclone.aar` (37 MB, rclone v1.68.2 gomobile build)

> **F-SYNC-RPC-FIX (critical)**: rclone RC API hanya punya method berikut:
> `operations/copyfile`, `operations/copydir`, `operations/movefile`, `operations/movedir`,
> `operations/list`, `operations/deletefile`, `operations/mkdir`, `operations/rmdir`,
> `operations/purge`, `operations/about`, `operations/hash`, `operations/cleanup`,
> `operations/filesize`, `operations/fsinfo`, `operations/statfs`, `operations/publiclink`.
>
> Method **`operations/copyto`** dan **`operations/stat`** TIDAK ADA — return 404
> `"couldn't find method operations/copyto"`. Jangan gunakan.
>
> Selain itu, untuk local backend, `srcFs`/`dstFs` HARUS berupa **directory** (bukan path file).
> Passing full file path sebagai `srcFs` dengan `srcRemote=""` akan gagal dengan
> `"is a file not a directory"` karena build gomobile tidak auto-handle `fs.ErrorIsFile`.
> Helper `splitLocalPath()` dan `splitRemotePath()` di `RcloneController` melakukan split ini.

---

## Architecture Overview

PhotoZ mengintegrasikan rclone via **gomobile JNI** (bukan subprocess). Ini W^X-safe di Android 16.

```
┌─────────────────────────────────────────────────────────┐
│                    Kotlin App                            │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │           RcloneController (Singleton)            │  │
│  │  - uploadFile / downloadFile / listRemote         │  │
│  │  - createDir / moveFile / moveDir / copyDir       │  │
│  │  - removeDir / hashFile / verifyFileExists        │  │
│  │  - downloadFileWithProgress (async RC + polling)  │  │
│  └────────────────────┬─────────────────────────────┘  │
│                       │ reflection                      │
│  ┌────────────────────▼─────────────────────────────┐  │
│  │        gomobile.Gomobile (from AAR)              │  │
│  │  - rcloneInitialize()                            │  │
│  │  - rcloneRPC(method, input) → RcloneRPCResult    │  │
│  │  - rcloneFinalize()                               │  │
│  └────────────────────┬─────────────────────────────┘  │
│                       │ JNI                             │
└───────────────────────┼─────────────────────────────────┘
                        │
                ┌───────▼───────┐
                │  libgojni.so  │  (arm64-v8a, 16KB-aligned)
                │  (dlopen'd)   │
                └───────────────┘
                        │
                ┌───────▼───────┐
                │  rclone RC    │  (in-process, no subprocess)
                │  API server   │
                └───────────────┘
```

---

## RcloneController API

### File Operations

| Method | RC API | Deskripsi |
|--------|--------|-----------|
| `uploadFile(localPath, remotePath)` | `operations/copyfile` | Upload local file ke remote |
| `downloadFile(remotePath, localPath)` | `operations/copyfile` | Download remote file ke local |
| `downloadFileWithProgress(remotePath, localPath, onProgress)` | `operations/copyfile` `_async=true` + `job/status` poll | Download dengan progress callback (F-SYNC-021) |
| `deleteFile(remotePath)` | `operations/deletefile` | Hapus file di remote |
| `verifyFileExists(remotePath, expectedSize)` | `operations/list` (filter by name + size) | Verifikasi file exists dengan size yang benar. F-SYNC-RPC-FIX: `operations/stat` tidak ada di rclone RC API — pakai `operations/list` + filter |
| `hashFile(remotePath, hashType)` | `operations/hash` | Server-side hash (sha256/md5/sha1) |

### Folder Operations

| Method | RC API | Deskripsi |
|--------|--------|-----------|
| `createDir(remotePath)` | `operations/mkdir` | Buat folder (like `mkdir -p`) |
| `moveFile(srcRemotePath, dstRemotePath)` | `operations/movefile` | Pindah/rename single file |
| `moveDir(srcRemotePath, dstRemotePath)` | `operations/movedir` | Pindah/rename directory tree |
| `copyDir(srcRemotePath, dstRemotePath)` | `operations/copydir` | Copy directory tree (non-destructive) |
| `removeDir(remotePath, recursive)` | `operations/purge` atau `operations/rmdir` | Hapus folder (recursive atau empty-only) |

### Query Operations

| Method | RC API | Deskripsi |
|--------|--------|-----------|
| `listRemote(remoteFs, remoteDir)` | `operations/list` | List files di remote directory |
| `verifyRemote(remoteName)` | `operations/about` | Cek remote reachable + quota |
| `checkRcloneAlive()` | `core/version` | Health check |

### Lifecycle

| Method | Deskripsi |
|--------|-----------|
| `ensureInitialized()` | Call `rcloneInitialize()` sekali + apply config path |
| `invalidateConfigPath()` | Reset cached config path (call setelah `RcloneConfigManager.clear()`) |
| `stop()` | Call `rcloneFinalize()` — safe multiple calls |

---

## RemoteFileInfo

Hasil `listRemote` di-parse ke `RemoteFileInfo`:

```kotlin
data class RemoteFileInfo(
    val name: String,          // filename
    val size: Long,            // bytes
    val isDir: Boolean = false, // true jika directory
    val mimeType: String? = null, // MIME type (null untuk dirs)
)
```

---

## Config Management

### RcloneConfigManager

Mengelola user-supplied `rclone.conf`:

```kotlin
class RcloneConfigManager @Inject constructor(
    private val app: Application,
    private val config: Config,
    private val rcloneController: dagger.Lazy<RcloneController>, // break DI cycle
)
```

### Config File Path

```
<dataDir>/rclone/rclone.conf
```

### Status Flow

```
NotConfigured → import(uri) → AwaitingRemoteChoice → chooseRemote(name) → Configured
                                    ↓
                              Invalid(NO_SECTIONS | UNREADABLE)
```

### Config Path Application (F-SYNC-006)

rclone cache config untuk process lifetime. `config/setpath` hanya berlaku sebelum first config access:

```kotlin
// RcloneController.applyConfigPath()
rpc("config/setpath", """{"path":"$configFilePath"}""")
```

**Penting**: `RcloneConfigManager.clear()` harus call `invalidateConfigPath()` supaya config baru dibaca setelah re-import.

---

## Error Handling

### JSON-based Error Detection (F-SYNC-001)

Semua RPC success/failure dicek via `hasRpcError()`:

```kotlin
private fun hasRpcError(output: String): Boolean =
    try {
        JSONObject(output).has("error")
    } catch (e: Exception) {
        true // not valid JSON → treat as error
    }
```

**Sebelumnya** (buggy): `result.contains("\"error\":")` — false positive pada filename mengandung kata "error".

### RPC Timeout (F-SYNC-002)

`rpc()` membungkus JNI invoke dengan `withTimeoutOrNull(30_000L)` via `runBlocking`:

```kotlin
val resultObj = runBlocking {
    withTimeoutOrNull(RPC_TIMEOUT_MS) {
        rpcMethod?.invoke(null, method, input)
    }
} ?: throw IOException("RPC timeout after ${RPC_TIMEOUT_MS}ms: method=$method")
```

Mencegah rclone hang freeze worker forever.

### Reflection Caching (F-SYNC-010)

Class/Method lookup di-cache via `lazy`:

```kotlin
private val gomobileClass: Class<*>? by lazy {
    runCatching { Class.forName("gomobile.Gomobile") }.getOrNull()
}
private val rpcMethod: Method? by lazy {
    gomobileClass?.getMethod("rcloneRPC", String::class.java, String::class.java)
}
// ... initMethod, finalizeMethod, getOutputMethod
```

Sebelumnya: `Class.forName` + `getMethod` di setiap RPC call (10-100µs overhead per call).

### Thread Safety (F-SYNC-009)

Companion object state ditandai `@Volatile`:

```kotlin
@Volatile private var initialized = false
@Volatile private var configPathApplied: String? = null
@Volatile private var initError: Throwable? = null
```

---

## Async Progress (F-SYNC-021)

`downloadFileWithProgress` menggunakan rclone async RC API:

```
1. POST operations/copyfile with _async=true → returns jobid
2. Poll job/status every 500ms:
   - Parse "completed" + "total" bytes
   - Call onProgress(completed/total * 100)
3. When "finished" == true:
   - Check "success" field
   - Call onProgress(100f) + return success
4. On coroutine cancellation: call job/stop
```

Fallback: jika rclone tidak return jobid (old backend), gunakan sync `downloadFile` dengan single 100% tick.

---

## Build dari Source

### Prerequisites

- Go 1.22+
- Android NDK r27c
- gomobile: `go install golang.org/x/mobile/cmd/gomobile@latest`

### Build Script

```bash
bash scripts/build-rclone-gomobile.sh
```

Output: `app/libs/librclone.aar` (37 MB)

### CI Workflow

`.github/workflows/build-rclone-gomobile.yml` — manual trigger atau tag push `rclone-v*`.

### 16KB Page Alignment

Build script memastikan `.so` di-align ke 16KB (requirement Android 15+):

```bash
# Verified via:
readelf -lW libgojni.so | grep LOAD
# Must show: align 0x4000
```

---

## Remote Layout

PhotoZ menyimpan artifacts di remote dengan struktur:

```
<remote>:photoz-backup/
├── repo-config.json                          # marker file (plaintext JSON)
├── registry.json.crypt                       # dedup registry (GCM encrypted)
├── originals/
│   ├── <uuid>.crypt                          # encrypted photo/video
│   └── ...
├── thumbnails/
│   └── pack-*.pack                           # thumbnail packs (≤5 MB each)
├── video-previews/
│   └── <uuid>.crypt.vp                       # encrypted video preview
└── vault-protection/
    ├── recovery-phrase.json.crypt            # Layer 1: VMK wrapped with phrase KEK
    └── wrapped-phrase.json.crypt             # Layer 2: phrase wrapped with password KEK
```

---

## Common Issues

### "didn't find section in config file"

**Penyebab**: `options/set {"main":{"Config":...}}` diabaikan rclone.
**Fix**: Gunakan `config/setpath` (commit `8e2bd21e`).

### rclone hang / worker stuck forever

**Penyebab**: Tidak ada timeout di RPC call.
**Fix**: F-SYNC-002 — `withTimeoutOrNull(30s)` + `rcloneFinalize()` + re-init on timeout.

### Config tidak terbaca setelah re-import

**Penyebab**: rclone cache config di memory; `config/setpath` hanya berlaku sebelum first read.
**Fix**: F-SYNC-006 — `RcloneConfigManager.clear()` call `invalidateConfigPath()`.

### False positive error detection

**Penyebab**: `result.contains("\"error\":")` match pada filename mengandung "error".
**Fix**: F-SYNC-001 — `JSONObject.has("error")`.
