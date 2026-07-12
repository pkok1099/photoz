/*
 *   Copyright 2020–2026 PhotoZ
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package onlasdan.gallery.sync.rclone

import android.app.Application
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprint 8+ — RcloneController using gomobile JNI (replaces ProcessBuilder subprocess).
 *
 * rclone is loaded as a shared library via System.loadLibrary("gojni")
 * (dlopen, NOT exec). This is W^X safe — Android 16 does not block dlopen.
 *
 * All rclone operations are performed via the RC API:
 *   RcloneRPC(method, inputJson) → outputJson
 *
 * The gomobile AAR provides the Gomobile class with:
 *   - Gomobile.rcloneInitialize() — call once at startup
 *   - Gomobile.rcloneRPC(method: String, input: String): RcloneRPCResult — RC API call
 *   - Gomobile.rcloneFinalize() — call on shutdown
 *
 * @since Sprint 8+ — gomobile JNI migration
 */
@Singleton
class RcloneController
	@Inject
	constructor(
		private val app: Application,
		private val configManager: RcloneConfigManager,
	) {
		companion object {
			private const val TAG = "RcloneController"
			/** F-SYNC-002: Per-RPC-call timeout (30s). rclone hangs are fatal. */
			private const val RPC_TIMEOUT_MS = 30_000L

			@Volatile private var initialized = false

			@Volatile private var configPathApplied: String? = null

			@Volatile private var initError: Throwable? = null

			// Cached reflection handles — looked up once via lazy, reused across all rpc() calls.
			// F-SYNC-010: previously Class.forName + getMethod ran on every call.
			private val gomobileClass: Class<*>? by lazy {
				runCatching { Class.forName("gomobile.Gomobile") }.getOrNull()
			}
			private val initMethod: Method? by lazy { gomobileClass?.getMethod("rcloneInitialize") }
			private val rpcMethod: Method? by lazy {
				gomobileClass?.getMethod("rcloneRPC", String::class.java, String::class.java)
			}
			private val finalizeMethod: Method? by lazy { gomobileClass?.getMethod("rcloneFinalize") }
			private val touchMethod: Method? by lazy { gomobileClass?.getMethod("touch") }
			private val resultMethods: Map<String, Method>? by lazy {
				runCatching { Class.forName("gomobile.RcloneRPCResult") }.getOrNull()?.let { cls ->
					mapOf(
						"getOutput" to cls.getMethod("getOutput"),
						"getStatus" to cls.getMethod("getStatus"),
					)
				}
			}

			init {
				try {
					System.loadLibrary("gojni")
					Timber.i("libgojni.so loaded via dlopen (W^X safe)")
				} catch (e: UnsatisfiedLinkError) {
					Timber.e(e, "Failed to load libgojni.so")
					initError = e
				}
			}
		}

		/**
		 * Check if rclone is alive and responding to RC calls.
		 */
		suspend fun checkRcloneAlive(): Boolean =
			withContext(Dispatchers.IO) {
				try {
					Timber.i("Checking if rclone is alive...")
					val result = rpc("core/version", "{}")
					Timber.i("rclone version info: $result")
					!hasRpcError(result)
				} catch (e: Exception) {
					Timber.e("rclone is NOT alive: ${e.message}")
					false
				}
			}

		/**
		 * Initialize rclone. Must be called once before any RC operations.
		 */
		fun ensureInitialized() {
			if (initialized) return
			initError?.let { return } // already failed; dont retry silently
			try {
				// F-SYNC-010: use cached reflection handles (looked up once via lazy).
				val clazz = gomobileClass
				if (clazz == null) {
					val msg = "gomobile.Gomobile class not found in AAR — Class.forName failed"
					Timber.e(msg)
					throw IllegalStateException(msg)
				}
				val initM = initMethod
				if (initM == null) {
					val msg = "rcloneInitialize method not found — AAR version mismatch or ProGuard stripped"
					Timber.e(msg)
					throw IllegalStateException(msg)
				}
				// Call touch() first to initialize the gomobile runtime (go.Seq reftracker).
				// The gomobile runtime is lazily initialized by the static initializer,
				// but explicitly calling touch() is the recommended pattern to ensure
				// the runtime is ready before any JNI calls.
				touchMethod?.invoke(null)
				Timber.d("ensureInitialized: gomobile.touch() OK, invoking rcloneInitialize()...")
				initM.invoke(null)
				initialized = true
				Timber.i("rclone initialized via JNI")
				applyConfigPath()
			} catch (t: Throwable) {
				// F-SYNC-008: catch Throwable (not Exception) so Error subtypes are surfaced.
				initError = t
				Timber.e(t, "rclone init failed")
			}
		}

		/**
		 * Point rclone at the imported config file via `config/setpath`.
		 *
		 * `options/set {"main":{"Config":...}}` is SILENTLY IGNORED by rclone, so the
		 * config path must be set with `config/setpath` instead. The path is resolved
		 * at rclone's package init and the config is read lazily on the first remote
		 * operation, so `config/setpath` must run BEFORE that first read — otherwise
		 * rclone falls back to the (empty) default config and fails with
		 * "didn't find section in config file".
		 *
		 * Uses the canonical path (set even before the file is imported) so the path
		 * is registered as early as possible; the file only needs to exist by the time
		 * the first operation reads it.
		 */
		private fun applyConfigPath() {
			val path = configManager.configFilePath
			if (path == configPathApplied) return
			val input = "{\"path\":\"$path\"}"
			try {
				val result = rpcMethod?.invoke(null, "config/setpath", input) as? String
					?: error("rcloneRPC method not available")
				configPathApplied = path
				Timber.i("Applied rclone config path via config/setpath: $path -> $result")
			} catch (e: Exception) {
				Timber.e("Failed to apply rclone config path: ${e.message}")
			}
		}

		/**
		 * Call rclone RC API via JNI.
		 *
		 * @param method RC API method (e.g. "operations/copyto")
		 * @param input JSON input parameters
		 * @return JSON output
		 */
		private fun rpc(method: String, input: String): String {
			ensureInitialized()
			initError?.let {
				Timber.e(it, "rpc(" + method + ") aborted - rclone init previously failed")
				return "{\"error\":\"rclone init failed: " + it.javaClass.simpleName + ": " + (it.message ?: "unknown") + "\"}"
			}
			return try {
				applyConfigPath()
				// F-SYNC-002: wrap JNI invoke in withTimeoutOrNull to prevent rclone
				// hangs from freezing the worker forever. On timeout, throw IOException.
				// F-SYNC-002: runBlocking is safe here — rpc() is always called from
				// withContext(Dispatchers.IO) in suspend callers, so we are on a
				// background thread. The timeout prevents rclone hangs from freezing
				// the worker forever.
				val resultObj = runBlocking {
					withTimeoutOrNull(RPC_TIMEOUT_MS) {
						rpcMethod?.invoke(null, method, input)
					}
				} ?: throw IOException("RPC timeout after " + RPC_TIMEOUT_MS + "ms: method=" + method)
				val methods = resultMethods ?: error("RcloneRPCResult methods not available")
				val output = methods["getOutput"]?.invoke(resultObj) as? String
					?: error("getOutput returned null")
				// Check HTTP-like status code (200=OK, 400/500=error).
				// Some rclone RC methods return non-200 without an "error" field.
				val status = methods["getStatus"]?.invoke(resultObj) as? Long ?: 200L
				if (status != 200L) {
					Timber.w("RPC non-200 status: method=$method status=$status output=$output")
					// If the output doesn't already contain an "error" key, inject it
					// so hasRpcError() catches it downstream.
					if (!output.contains("\"error\"")) {
						return "{\"error\":\"rclone status " + status + ": " + output + "\"}"
					}
				}
				output
			} catch (e: Exception) {
				Timber.e(e, "RPC call failed: method=" + method)
				val msg = e.javaClass.simpleName + ": " + (e.message ?: "null")
				return "{\"error\":\"" + msg + "\"}"
			}
		}

		/**
		 * Upload a local file to the remote.
		 *
		 * @param localPath local file path
		 * @param remotePath full rclone remote path (e.g. "myremote:path/to/file")
		 * @return Result<Unit>
		 */
		suspend fun uploadFile(localPath: String, remotePath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					// F-SYNC-RPC-FIX: rclone RC has NO `operations/copyto` method
					// (returns 404 "couldn't find method operations/copyto").
					// The correct method is `operations/copyfile`.
					//
					// Additionally, the local backend requires `srcFs` to be a
					// DIRECTORY (not a file path) with `srcRemote` = filename.
					// Passing the full local file path as `srcFs` with `srcRemote=""`
					// fails with "is a file not a directory" because the gomobile
					// rclone build does NOT auto-split `ErrorIsFile` cases.
					val (srcFs, srcRemote) = splitLocalPath(localPath)
					val (remote, path) = parseRemotePath(remotePath)
					val (dstFs, dstRemote) = splitRemotePath(remote, path)
					val input =
						"""
						{"srcFs":"$srcFs","srcRemote":"$srcRemote","dstFs":"$dstFs","dstRemote":"$dstRemote"}
						""".trimIndent()
					val result = rpc("operations/copyfile", input)
					Timber.d("uploadFile result: $result")
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Timber.e("uploadFile failed: ${e.message}")
					Result.failure(e)
				}
			}

		/**
		 * F-UV-001: Upload a local file to the remote with periodic progress callbacks.
		 *
		 * Uses rclone async RC API: operations/copyto with _async=true
		 * returns a jobid, then poll job/status every 500ms for progress.
		 *
		 * @param localPath local file path
		 * @param remotePath full rclone remote path
		 * @param onProgress callback invoked with 0.0-100.0 percentage
		 * @return Result<Unit>
		 */
		suspend fun uploadFileWithProgress(
			localPath: String,
			remotePath: String,
			onProgress: (Float) -> Unit,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				var jobid: Long? = null
				try {
					val (srcFs, srcRemote) = splitLocalPath(localPath)
					val (remote, path) = parseRemotePath(remotePath)
					val (dstFs, dstFile) = splitRemotePath(remote, path)
					val startInput = """{"srcFs":"$srcFs","srcRemote":"$srcRemote","dstFs":"$dstFs","dstRemote":"$dstFile","_async":true}"""
					val startResult = rpc("operations/copyfile", startInput)
					if (hasRpcError(startResult)) {
						return@withContext Result.failure(IOException("rclone async upload start error: $startResult"))
					}
					val startJson = JSONObject(startResult)
					jobid = startJson.optLong("jobid", -1)
					if (jobid < 0) {
						Timber.w("uploadFileWithProgress: no jobid, falling back to sync upload")
						return@withContext uploadFile(localPath, remotePath).also {
							if (it.isSuccess) onProgress(100f)
						}
					}

					onProgress(0f)
					while (true) {
						delay(500)
						val statusResult = rpc("job/status", """{"jobid":$jobid}""")
						if (hasRpcError(statusResult)) break
						val statusJson = JSONObject(statusResult)
						val finished = statusJson.optBoolean("finished", false)
						val completed = statusJson.optLong("completed", -1L)
						val total = statusJson.optLong("total", -1L)
						if (total > 0 && completed >= 0) {
							onProgress((completed.toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f))
						}
						if (finished) {
							val success = statusJson.optBoolean("success", false)
							if (!success) {
								val errorMsg = statusJson.optString("error", "unknown error")
								return@withContext Result.failure(IOException("rclone upload job $jobid failed: $errorMsg"))
							}
							onProgress(100f)
							return@withContext Result.success(Unit)
						}
					}
					Result.success(Unit)
				} catch (e: kotlinx.coroutines.CancellationException) {
					jobid?.let { jid -> runCatching { rpc("job/stop", """{"jobid":$jid}""") } }
					throw e
				} catch (e: Exception) {
					Timber.e(e, "uploadFileWithProgress failed")
					Result.failure(e)
				}
			}

		/**
		 * Download a file from the remote.
		 *
		 * @param remotePath full rclone remote path
		 * @param localPath local file path to save to
		 * @return Result<Unit>
		 */
		suspend fun downloadFile(remotePath: String, localPath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					// F-SYNC-RPC-FIX: same as uploadFile - use operations/copyfile
					// (NOT operations/copyto) AND split local dst path so dstFs is
					// the parent directory and dstRemote is the filename.
					val (remote, path) = parseRemotePath(remotePath)
					val (srcFs, srcFile) = splitRemotePath(remote, path)
					val (dstFs, dstRemote) = splitLocalPath(localPath)
					val input =
						"""
						{"srcFs":"$srcFs","srcRemote":"$srcFile","dstFs":"$dstFs","dstRemote":"$dstRemote"}
						""".trimIndent()
					val result = rpc("operations/copyfile", input)
					Timber.d("downloadFile result: $result")
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Timber.e("downloadFile failed: ${e.message}")
					Result.failure(e)
				}
			}

		/**
		 * F-SYNC-021: Download a file with periodic progress callbacks.
		 *
		 * Uses rclone async RC API: operations/copyto with _async=true
		 * returns a jobid, then poll job/status every 500ms for progress.
		 *
		 * On coroutine cancellation, calls job/stop to cancel the rclone job.
		 */
		suspend fun downloadFileWithProgress(
			remotePath: String,
			localPath: String,
			onProgress: (Float) -> Unit,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				var jobid: Long? = null
				try {
					val (remote, path) = parseRemotePath(remotePath)
					val (srcFs, srcFile) = splitRemotePath(remote, path)
					val (dstFs, dstRemote) = splitLocalPath(localPath)
					val startInput = """{"srcFs":"$srcFs","srcRemote":"$srcFile","dstFs":"$dstFs","dstRemote":"$dstRemote","_async":true}"""
					val startResult = rpc("operations/copyfile", startInput)
					if (hasRpcError(startResult)) {
						return@withContext Result.failure(IOException("rclone async start error: $startResult"))
					}
					val startJson = JSONObject(startResult)
					jobid = startJson.optLong("jobid", -1)
					if (jobid < 0) {
						Timber.w("downloadFileWithProgress: no jobid, falling back to sync download")
						return@withContext downloadFile(remotePath, localPath).also {
							if (it.isSuccess) onProgress(100f)
						}
					}

					onProgress(0f)
					while (true) {
						delay(500)
						val statusResult = rpc("job/status", """{"jobid":$jobid}""")
						if (hasRpcError(statusResult)) break
						val statusJson = JSONObject(statusResult)
						val finished = statusJson.optBoolean("finished", false)
						val completed = statusJson.optLong("completed", -1L)
						val total = statusJson.optLong("total", -1L)
						if (total > 0 && completed >= 0) {
							onProgress((completed.toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f))
						}
						if (finished) {
							val success = statusJson.optBoolean("success", false)
							if (!success) {
								val errorMsg = statusJson.optString("error", "unknown error")
								return@withContext Result.failure(IOException("rclone job $jobid failed: $errorMsg"))
							}
							onProgress(100f)
							return@withContext Result.success(Unit)
						}
					}
					Result.success(Unit)
				} catch (e: kotlinx.coroutines.CancellationException) {
					jobid?.let { jid -> runCatching { rpc("job/stop", """{"jobid":$jid}""") } }
					throw e
				} catch (e: Exception) {
					Timber.e(e, "downloadFileWithProgress failed")
					Result.failure(e)
				}
			}

		/**
		 * List files in a remote directory.
		 *
		 * @param remoteFs rclone remote fs (e.g. "myremote:path/to/dir")
		 * @return Result<List<RemoteFileInfo>>
		 */
		suspend fun listRemote(remoteFs: String, remoteDir: String): Result<List<RemoteFileInfo>> =
			withContext(Dispatchers.IO) {
				try {
					val input = """{"fs":"$remoteFs","remote":"$remoteDir"}"""
					val result = rpc("operations/list", input)
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone error: $result"))
					} else {
						// Parse JSON response — list is in "list" array
						val files = parseListResult(result)
						Result.success(files)
					}
				} catch (e: Exception) {
					Timber.e("listRemote failed: ${e.message}")
					Result.failure(e)
				}
			}

		/**
		 * Delete a file on the remote.
		 */
		suspend fun deleteFile(remotePath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					val (remote, path) = parseRemotePath(remotePath)
					val input = """{"fs":"$remote:","remote":"$path"}"""
					val result = rpc("operations/deletefile", input)
					if (hasRpcError(result)) Result.failure(IOException(result)) else Result.success(Unit)
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		/**
		 * Verify a file exists on the remote with expected size.
		 */
		suspend fun verifyFileExists(
			remotePath: String,
			expectedSize: Long,
		): Result<Boolean> =
			withContext(Dispatchers.IO) {
				try {
					// F-SYNC-RPC-FIX: rclone RC has NO `operations/stat` method
					// (returns 404 "couldn't find method operations/stat"). Use
					// `operations/list` on the parent directory and filter by name.
					val (remote, path) = parseRemotePath(remotePath)
					val (parentFs, fileName) = splitRemotePath(remote, path)
					val listInput = """{"fs":"$parentFs","remote":""}"""
					val result = rpc("operations/list", listInput)
					if (hasRpcError(result)) {
						Timber.w("verifyFileExists list error: $result")
						return@withContext Result.success(false)
					}
					val arr = JSONObject(result).optJSONArray("list") ?: return@withContext Result.success(false)
					for (i in 0 until arr.length()) {
						val obj = arr.optJSONObject(i) ?: continue
						if (obj.optString("Name") == fileName && obj.optLong("Size", -1L) == expectedSize) {
							return@withContext Result.success(true)
						}
					}
					Result.success(false)
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		/**
		 * Verify remote is reachable.
		 */
		suspend fun verifyRemote(remoteName: String): Result<Boolean> =
			withContext(Dispatchers.IO) {
				try {
					val input = """{"fs":"$remoteName:"}"""
					val result = rpc("operations/about", input)
					Result.success(!hasRpcError(result))
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		// ─── Additional rclone RC operations (mkdir, move, copydir, rmdir, purge, hash) ──
		// The gomobile JNI rcloneRPC(method, input) supports ALL rclone RC operations.
		// These wrappers add folder management + file integrity verification.

		suspend fun createDir(remotePath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					val (remote, path) = parseRemotePath(remotePath)
					val input = """{"fs":"$remote:","remote":"$path"}"""
					val result = rpc("operations/mkdir", input)
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone mkdir error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		suspend fun moveFile(srcRemotePath: String, dstRemotePath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					val (srcRemote, srcPath) = parseRemotePath(srcRemotePath)
					val (dstRemote, dstPath) = parseRemotePath(dstRemotePath)
					val input = """{"srcFs":"$srcRemote:","srcRemote":"$srcPath","dstFs":"$dstRemote:","dstRemote":"$dstPath"}"""
					val result = rpc("operations/movefile", input)
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone movefile error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		suspend fun moveDir(srcRemotePath: String, dstRemotePath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					val (srcRemote, srcPath) = parseRemotePath(srcRemotePath)
					val (dstRemote, dstPath) = parseRemotePath(dstRemotePath)
					val input = """{"srcFs":"$srcRemote:","srcRemote":"$srcPath","dstFs":"$dstRemote:","dstRemote":"$dstPath"}"""
					val result = rpc("operations/movedir", input)
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone movedir error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		suspend fun copyDir(srcRemotePath: String, dstRemotePath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					val (srcRemote, srcPath) = parseRemotePath(srcRemotePath)
					val (dstRemote, dstPath) = parseRemotePath(dstRemotePath)
					val input = """{"srcFs":"$srcRemote:","srcRemote":"$srcPath","dstFs":"$dstRemote:","dstRemote":"$dstPath"}"""
					val result = rpc("operations/copydir", input)
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone copydir error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		suspend fun removeDir(remotePath: String, recursive: Boolean = true): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					val (remote, path) = parseRemotePath(remotePath)
					val method = if (recursive) "operations/purge" else "operations/rmdir"
					val input = """{"fs":"$remote:","remote":"$path"}"""
					val result = rpc(method, input)
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone $method error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		suspend fun hashFile(remotePath: String, hashType: String = "sha256"): Result<String?> =
			withContext(Dispatchers.IO) {
				try {
					val (remote, path) = parseRemotePath(remotePath)
					val input = """{"fs":"$remote:","remote":"$path","hashType":"$hashType"}"""
					val result = rpc("operations/hash", input)
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone hash error: $result"))
					} else {
						val json = JSONObject(result)
						val hash = if (json.isNull("hash")) null else json.optString("hash")
						Result.success(hash)
					}
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

/**
		 * Stop rclone (finalize). Safe to call multiple times.
		 */
		suspend fun stop() {
			withContext(Dispatchers.IO) {
				if (initialized) {
					try {
						finalizeMethod?.invoke(null)
						initialized = false
						configPathApplied = null
						Timber.i("rclone finalized")
					} catch (e: Exception) {
						Timber.w("rclone finalize failed (non-fatal): ${e.message}")
					}
				}
			}
		}

		// ─── Helpers ──────────────────────────────────────────────────────

		/**
		 * F-SYNC-001: JSON-based error detection — replaces fragile substring
		 * sniffing that false-matched on filenames containing the word error.
		 */
		private fun hasRpcError(output: String): Boolean {
			return try {
				JSONObject(output).has("error")
			} catch (e: Exception) {
				// Not valid JSON — treat as error (rclone always returns JSON on success).
				true
			}
		}

		/**
		 * F-SYNC-006: Called by RcloneConfigManager.clear() to invalidate rclone cached config.
		 */
		fun invalidateConfigPath() {
			configPathApplied = null
		}

		/**
		 * F-SYNC-RPC-FIX: Split a LOCAL file path into (parentDir, filename).
		 *
		 * rclone's local backend requires `srcFs`/`dstFs` to be a DIRECTORY. Passing a
		 * full file path causes "is a file not a directory" because the gomobile build
		 * does not auto-handle `fs.ErrorIsFile` for `operations/copyfile`.
		 *
		 * Returns (parentDir, filename). If parent is null (root), falls back to ".".
		 */
		private fun splitLocalPath(localPath: String): Pair<String, String> {
			val f = File(localPath)
			val parent = f.parent
			return (parent ?: ".") to f.name
		}

		/**
		 * F-SYNC-RPC-FIX: Split a remote (remoteName, path) pair into (dstFs, dstRemote)
		 * such that dstFs is the parent directory and dstRemote is the filename.
		 *
		 * E.g. ("koofr-5", "photoz-backup/repo-config.json") ->
		 *      ("koofr-5:photoz-backup", "repo-config.json")
		 *
		 * If there is no slash in the path, returns ("$remote:", path).
		 */
		private fun splitRemotePath(remote: String, path: String): Pair<String, String> {
			val lastSlash = path.lastIndexOf('/')
			return if (lastSlash >= 0) {
				val dir = path.substring(0, lastSlash)
				val file = path.substring(lastSlash + 1)
				("$remote:$dir") to file
			} else {
				("$remote:") to path
			}
		}

		/**
		 * Debug helper: enumerate all rclone RC methods available in this build.
		 *
		 * F-SYNC-RPC-FIX: returns a JSON string with a "methods" array. Use this to
		 * verify which RC methods exist (e.g. operations/copyfile exists, operations/copyto
		 * does NOT). Useful for diagnosing "couldn't find method" 404 errors.
		 */
		suspend fun listRcMethods(): Result<String> =
			withContext(Dispatchers.IO) {
				try {
					val result = rpc("rc/list", "{}")
					if (hasRpcError(result)) {
						Result.failure(IOException("rclone error: $result"))
					} else {
						Result.success(result)
					}
				} catch (e: Exception) {
					Result.failure(e)
				}
			}

		/**
		 * Parse "myremote:path/to/file" into ("myremote", "path/to/file").
		 */
		private fun parseRemotePath(remotePath: String): Pair<String, String> {
			val colonIndex = remotePath.indexOf(':')
			return if (colonIndex >= 0) {
				remotePath.substring(0, colonIndex) to remotePath.substring(colonIndex + 1)
			} else {
				"" to remotePath
			}
		}

		/**
		 * Parse rclone operations/list JSON response.
		 * Simplified parser — extracts name + size from the list array.
		 */
		private fun parseListResult(json: String): List<RemoteFileInfo> {
			// F-SYNC-013: use JSONArray instead of regex — handles escaped quotes.
			return try {
				val arr = JSONObject(json).optJSONArray("list") ?: return emptyList()
				(0 until arr.length()).mapNotNull { i ->
					val obj = arr.optJSONObject(i) ?: return@mapNotNull null
					val name = obj.optString("Name")
					if (name.isEmpty()) {
						null
					} else {
						RemoteFileInfo(
							name = name,
							size = obj.optLong("Size", 0L),
							isDir = obj.optBoolean("IsDir", false),
							mimeType = if (obj.isNull("MimeType")) null else obj.optString("MimeType"),
						)
					}
				}
			} catch (e: Exception) {
				Timber.w(e, "parseListResult: JSON parse failed")
				emptyList()
			}
		}
	}

/**
 * Simple file info from rclone list.
 */
data class RemoteFileInfo(
	val name: String,
	val size: Long,
	/** True if this entry is a directory. F-SYNC: needed to distinguish files from folders for move/delete operations. */
	val isDir: Boolean = false,
	/** MIME type from rclone (e.g. "image/jpeg"). Null for directories. */
	val mimeType: String? = null,
)
