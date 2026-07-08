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
import java.io.IOException
import java.lang.reflect.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
			private val getOutputMethod: Method? by lazy {
				runCatching { Class.forName("gomobile.RcloneRPCResult") }.getOrNull()?.getMethod("getOutput")
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
			initError?.let { return }  // already failed; dont retry silently
			try {
				// F-SYNC-010: use cached reflection handles (looked up once via lazy).
				val clazz = gomobileClass ?: error("gomobile.Gomobile class not found in AAR")
				initMethod?.invoke(null) ?: error("rcloneInitialize method not found")
				initialized = true
				Timber.i("rclone initialized via JNI")
				applyConfigPath(clazz)
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
		private fun applyConfigPath(clazz: Class<*>) {
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
		 * @param method RC API method (e.g. "operations/copyfile")
		 * @param input JSON input parameters
		 * @return JSON output
		 */
		private fun rpc(method: String, input: String): String {
			ensureInitialized()
			return try {
				val clazz = Class.forName("gomobile.Gomobile")
				applyConfigPath(clazz)
				val rpcMethod = clazz.getMethod("rcloneRPC", String::class.java, String::class.java)
				val resultObj = rpcMethod.invoke(null, method, input)
				// resultObj is a gomobile.RcloneRPCResult
				val getOutputMethod = resultObj.javaClass.getMethod("getOutput")
				val output = getOutputMethod.invoke(resultObj) as String
				output
			} catch (e: Exception) {
				Timber.e("RPC call failed: method=$method error=${e.message}")
				"{\"error\":\"${e.message}\"}"
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
					val (remote, path) = parseRemotePath(remotePath)
					val input =
						"""
						{"srcFs":"$localPath","srcRemote":"","dstFs":"$remote:","dstRemote":"$path"}
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
		 * Download a file from the remote.
		 *
		 * @param remotePath full rclone remote path
		 * @param localPath local file path to save to
		 * @return Result<Unit>
		 */
		suspend fun downloadFile(remotePath: String, localPath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				try {
					val (remote, path) = parseRemotePath(remotePath)
					val input =
						"""
						{"srcFs":"$remote:","srcRemote":"$path","dstFs":"$localPath","dstRemote":""}
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
					val (remote, path) = parseRemotePath(remotePath)
					val input = """{"fs":"$remote:","remote":"$path"}"""
					val result = rpc("operations/stat", input)
					// Check if response has "item" with correct size
					val hasItem = result.contains("\"item\"") && result.contains("\"Size\":$expectedSize")
					Result.success(hasItem)
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
					if (name.isEmpty()) null
					else RemoteFileInfo(name = name, size = obj.optLong("Size", 0L))
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
)
