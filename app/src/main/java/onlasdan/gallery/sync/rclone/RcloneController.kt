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
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
			private var initialized = false

			init {
				try {
					System.loadLibrary("gojni")
					Log.i(TAG, "libgojni.so loaded via dlopen (W^X safe)")
				} catch (e: UnsatisfiedLinkError) {
					Log.e(TAG, "Failed to load libgojni.so: ${e.message}")
				}
			}
		}

		/**
		 * Check if rclone is alive and responding to RC calls.
		 */
		suspend fun checkRcloneAlive(): Boolean =
			withContext(Dispatchers.IO) {
				try {
					Log.i(TAG, "Checking if rclone is alive...")
					val result = rpc("core/version", "{}")
					Log.i(TAG, "rclone version info: $result")
					!result.contains("\"error\"")
				} catch (e: Exception) {
					Log.e(TAG, "rclone is NOT alive: ${e.message}")
					false
				}
			}

		/**
		 * Initialize rclone. Must be called once before any RC operations.
		 */
		fun ensureInitialized() {
			if (!initialized) {
				try {
					// gomobile-generated Gomobile class
					// The class name depends on the gomobile package path.
					// rclone/librclone/gomobile produces package "gomobile"
					val clazz = Class.forName("gomobile.Gomobile")
					val initMethod = clazz.getMethod("rcloneInitialize")
					initMethod.invoke(null)
					initialized = true
					Log.i(TAG, "rclone initialized via JNI")

					// Set config file path immediately after initialization
					val configFile = configManager.configFile
					if (configFile != null && configFile.exists()) {
						Log.i(TAG, "Setting rclone config path to: ${configFile.absolutePath}")
						val setConfigInput = "{\"main\": {\"Config\": \"${configFile.absolutePath}\"}}"
						// We use raw rpc call here to avoid recursion
						val result = try {
							val rpcMethod = clazz.getMethod("rcloneRPC", String::class.java, String::class.java)
							rpcMethod.invoke(null, "options/set", setConfigInput) as String
						} catch (e: Exception) {
							"Error: ${e.message}"
						}
						Log.i(TAG, "Set rclone config result: $result")
					} else {
						Log.w(TAG, "No rclone config file found to set path.")
					}
				} catch (e: Exception) {
					Log.e(TAG, "rclone init failed: ${e.message}")
				}
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
				val rpcMethod = clazz.getMethod("rcloneRPC", String::class.java, String::class.java)
				val resultObj = rpcMethod.invoke(null, method, input)
				// resultObj is a gomobile.RcloneRPCResult
				val getOutputMethod = resultObj.javaClass.getMethod("getOutput")
				val output = getOutputMethod.invoke(resultObj) as String
				output
			} catch (e: Exception) {
				Log.e(TAG, "RPC call failed: method=$method error=${e.message}")
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
					Log.d(TAG, "uploadFile result: $result")
					if (result.contains("\"error\":")) {
						Result.failure(IOException("rclone error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Log.e(TAG, "uploadFile failed: ${e.message}")
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
					Log.d(TAG, "downloadFile result: $result")
					if (result.contains("\"error\":")) {
						Result.failure(IOException("rclone error: $result"))
					} else {
						Result.success(Unit)
					}
				} catch (e: Exception) {
					Log.e(TAG, "downloadFile failed: ${e.message}")
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
					if (result.contains("\"error\":")) {
						Result.failure(IOException("rclone error: $result"))
					} else {
						// Parse JSON response — list is in "list" array
						val files = parseListResult(result)
						Result.success(files)
					}
				} catch (e: Exception) {
					Log.e(TAG, "listRemote failed: ${e.message}")
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
					if (result.contains("\"error\":")) Result.failure(IOException(result)) else Result.success(Unit)
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
					Result.success(!result.contains("\"error\""))
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
						val clazz = Class.forName("gomobile.Gomobile")
						val finalizeMethod = clazz.getMethod("rcloneFinalize")
						finalizeMethod.invoke(null)
						initialized = false
						Log.i(TAG, "rclone finalized")
					} catch (e: Exception) {
						Log.w(TAG, "rclone finalize failed (non-fatal): ${e.message}")
					}
				}
			}
		}

		// ─── Helpers ──────────────────────────────────────────────────────

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
			val files = mutableListOf<RemoteFileInfo>()
			// Simple JSON parsing — look for "Name" and "Size" fields
			val nameRegex = """"Name"\s*:\s*"([^"]+)"""".toRegex()
			val sizeRegex = """"Size"\s*:\s*(\d+)""".toRegex()
			// Split by "Path" entries
			val entries = json.split("""{"Path":""".dropLast(1))
			for (entry in entries.drop(1)) { // skip first (before first entry)
				val nameMatch = nameRegex.find(entry)
				val sizeMatch = sizeRegex.find(entry)
				if (nameMatch != null) {
					files.add(
						RemoteFileInfo(
							name = nameMatch.groupValues[1],
							size = sizeMatch?.groupValues[1]?.toLongOrNull() ?: 0L,
						),
					)
				}
			}
			return files
		}
	}

/**
 * Simple file info from rclone list.
 */
data class RemoteFileInfo(
	val name: String,
	val size: Long,
)
