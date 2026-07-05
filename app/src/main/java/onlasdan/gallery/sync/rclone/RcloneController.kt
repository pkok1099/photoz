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
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import onlasdan.gallery.sync.debug.SyncLogger
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps a bundled rclone binary running in `rcd` (remote control daemon) mode as a localhost
 * subprocess, and exposes suspend-friendly operations to upload / download / verify encrypted
 * PhotoZ artifacts against a remote configured via `rclone.conf`.
 */
@Singleton
class RcloneController
	@Inject
	constructor(
		private val app: Application,
		private val configManager: RcloneConfigManager,
	) {
		private val json = Json { ignoreUnknownKeys = true }

		// ─── DIAGNOSTIC LOGGING (Step B) ─────────────────────────────────────
		// Unconditional (NOT debug-flag-gated). Writes to BOTH:
		//   1. Log.e under tag "RcloneDiag" → visible via `adb logcat -d | grep RcloneDiag`
		//   2. files/sync_log.txt → visible via
		//      `adb shell run-as onlasdan.gallery cat files/sync_log.txt`
		// Called from every layer of the call chain so we can trace exactly how far
		// execution gets before the "binary not found" / EACCES / ENOEXEC failure surfaces.
		// The `app` instance is non-null at any call site (constructor-injected).
		//
		// The file-append path goes through [SyncLogRotator.append], which caps
		// `sync_log.txt` at 1 MB (truncating to the last 512 KB when exceeded).
		// Without that cap the file would grow without bound — it lives in
		// `filesDir` (persistent storage, NOT cache), so it never gets reclaimed
		// by the OS. See [SyncLogRotator] for the full rotation policy.
		private fun diag(
			msg: String,
			throwable: Throwable? = null,
		) {
			android.util.Log.e("RcloneDiag", msg, throwable)
			try {
				val entry =
					buildString {
						append("\n[RcloneDiag] ")
						append(msg)
						append('\n')
						if (throwable != null) {
							append(throwable.stackTraceToString())
							append('\n')
						}
					}
				onlasdan.gallery.sync.debug.SyncLogRotator
					.append(app, entry)
			} catch (_: Exception) {
				// Never let diag() itself throw and disrupt the calling code path.
			}
		}

		private val _state = MutableStateFlow(RcdState.STOPPED)
		val state = _state.asStateFlow()

		private val readinessMutex = Mutex()
		private val callMutex = Mutex()

		@Volatile private var process: Process? = null

		@Volatile private var port: Int = 0

		@Volatile private var authUser: String = "photok"

		@Volatile private var authToken: String = ""

		suspend fun uploadFile(
			localPath: String,
			remotePath: String,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val localFile = File(localPath)
					require(localFile.exists() && localFile.isFile) {
						"Local file missing or not a regular file: $localPath"
					}

					awaitRcdReady().getOrThrow()
					callMutex.withLock {
						invokeRc(
							op = "operations/copyfile",
							params =
								buildJsonObject {
									put("srcFs", localFile.parentFile?.absolutePath ?: "")
									// FIX: rclone's operations/copyfile requires "srcRemote", NOT "srcLeaf".
									// Using "srcLeaf" silently fails — rclone returns HTTP 400
									// "Didn't find key srcRemote in input" but the old code's error
									// field check wasn't catching it (Bug B). Even with the error-field
									// check, the param name was wrong, so every upload was rejected.
									// Root cause of false "UPLOADED" — empirically verified via
									// local rcd test: srcLeaf → 400 error, srcRemote → file appears.
									put("srcRemote", localFile.name)
									val idx = remotePath.indexOf(':')
									put("dstFs", if (idx < 0) "" else remotePath.substring(0, idx + 1))
									put("dstRemote", remotePath.substringAfter(':'))
								},
						).getOrThrow()
					}
					Unit
				}
			}

		/**
		 * Upload a file with periodic progress callbacks.
		 *
		 * rclone's `operations/copyfile` is synchronous (it returns only when the
		 * upload is done) and the rcd `core/stats` endpoint does NOT track
		 * `operations/copyfile` transfers — the `transferring` array stays empty
		 * for the entire duration. Without a real signal from rclone, we
		 * approximate progress with a size-based estimate:
		 *
		 *   estimated_percent = min(95,
		 *       (elapsed_ms × ASSUMED_SPEED_BYTES_PER_MS) / file_size × 100)
		 *
		 * The estimate is capped at 95% so the progress bar visibly transitions
		 * to 100% only when the actual upload completes (via [onProgress](100f)
		 * after the copyfile call returns). This avoids the misleading "100% then
		 * hang for 5 seconds" UX that a naive elapsed-time estimate would
		 * produce for slow uploads.
		 *
		 * `ASSUMED_SPEED_BYTES_PER_MS = 5000` (5 MB/s) is a deliberately
		 * conservative default — slower than most home Wi-Fi upload speeds, so
		 * the estimate under-promises and the bar moves to 100% sooner than the
		 * estimate would predict. The user perceives this as "upload finished
		 * faster than expected" rather than "stuck at 95%", which is the better
		 * failure mode.
		 *
		 * Concurrency model: the `copyfile` call holds [callMutex] for its full
		 * duration (preserving serialization with other uploaders). The progress
		 * estimator runs in a sibling coroutine and only touches locals — no
		 * mutex contention, no extra HTTP calls to rcd.
		 *
		 * The existing [uploadFile] is unchanged — callers that don't need
		 * progress feedback (e.g. thumbnail uploads, video-preview uploads)
		 * should continue to use it.
		 *
		 * @param onProgress invoked on the same dispatcher (Dispatchers.IO) as
		 *   the upload. May be called zero or more times; never called with a
		 *   value outside `0f..100f`. Implementors are responsible for
		 *   rate-limiting UI updates triggered from this callback (see
		 *   [onlasdan.gallery.sync.work.PhotoSyncWorker.updateNotification] for
		 *   the rate-limited notification pattern).
		 *
		 * @since v8 — stable upload notification with real progress percentage
		 * @since real-upload-progress feature — replaced core/stats polling
		 *   (which never returned data for copyfile) with a size-based estimate.
		 */
		suspend fun uploadFileWithProgress(
			localPath: String,
			remotePath: String,
			onProgress: (Float) -> Unit,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val localFile = File(localPath)
					require(localFile.exists() && localFile.isFile) {
						"Local file missing or not a regular file: $localPath"
					}

					awaitRcdReady().getOrThrow()

					val uploadStartMs = System.currentTimeMillis()
					val fileSize = localFile.length()
					diag(
						"uploadFileWithProgress: ENTRY localPath=$localPath size=$fileSize remotePath=$remotePath pollIntervalMs=$PROGRESS_POLL_INTERVAL_MS assumedSpeedBps=${ASSUMED_SPEED_BYTES_PER_MS * 1000}",
					)

					// ─── Edge case: zero-byte file ──────────────────────────────────
					// Skip the estimate loop entirely — the upload is essentially a
					// no-op (rclone still creates the remote file, just with no
					// content). Report 100% immediately after the call returns.
					// Avoids divide-by-zero in the elapsed-time estimate below.
					if (fileSize == 0L) {
						diag("uploadFileWithProgress: zero-byte file, skipping estimate loop")
						callMutex.withLock {
							invokeRc(
								op = "operations/copyfile",
								params =
									buildJsonObject {
										put("srcFs", localFile.parentFile?.absolutePath ?: "")
										put("srcRemote", localFile.name)
										val idx = remotePath.indexOf(':')
										put("dstFs", if (idx < 0) "" else remotePath.substring(0, idx + 1))
										put("dstRemote", remotePath.substringAfter(':'))
									},
							).getOrThrow()
						}
						onProgress(100f)
						diag("uploadFileWithProgress: EXIT OK (zero-byte) — totalDuration=${System.currentTimeMillis() - uploadStartMs}ms")
						return@runCatching Unit
					}

					coroutineScope {
						// Launch the copyfile call in a child coroutine. It holds
						// callMutex for its full duration — same serialization
						// contract as uploadFile().
						val uploadDeferred =
							async(Dispatchers.IO) {
								callMutex.withLock {
									invokeRc(
										op = "operations/copyfile",
										params =
											buildJsonObject {
												put("srcFs", localFile.parentFile?.absolutePath ?: "")
												put("srcRemote", localFile.name)
												val idx = remotePath.indexOf(':')
												put("dstFs", if (idx < 0) "" else remotePath.substring(0, idx + 1))
												put("dstRemote", remotePath.substringAfter(':'))
											},
									).getOrThrow()
								}
							}

						// ─── Size-based progress estimate ───────────────────────────
						// Polls every PROGRESS_POLL_INTERVAL_MS (200ms) while the
						// upload is in flight. Each tick computes:
						//
						//   estimated_bytes = elapsed_ms × ASSUMED_SPEED_BYTES_PER_MS
						//   estimated_percent = min(95, estimated_bytes / fileSize × 100)
						//
						// The 95% cap is intentional: the bar visibly transitions to
						// 100% only when the real upload completes (after the loop
						// exits, below). This gives the user a clear "done" signal.
						//
						// For very fast uploads (small files / fast networks), the
						// upload may complete before the first poll fires — that's
						// fine, the loop exits and we jump straight to 100%.
						//
						// For very slow uploads (fileSize > 5MB × elapsed), the
						// estimate climbs at 5MB/s until it hits 95%, then sits there
						// until the real upload finishes. The user sees movement,
						// then a brief pause, then 100% — much better than an
						// indeterminate spinner.
						while (!uploadDeferred.isCompleted) {
							val elapsedMs = System.currentTimeMillis() - uploadStartMs
							val estimatedBytes = elapsedMs * ASSUMED_SPEED_BYTES_PER_MS
							val percentRaw = (estimatedBytes.toFloat() / fileSize.toFloat()) * 100f
							// Cap at 95% — only the post-loop onProgress(100f) call
							// signals true completion. Without the cap, the estimate
							// would hit 100% before the upload actually finishes and
							// the user would see a "stuck at 100%" state.
							val percentCapped = percentRaw.coerceIn(0f, 95f)
							onProgress(percentCapped)
							delay(PROGRESS_POLL_INTERVAL_MS)
						}

						// Await the upload result — propagates any exception thrown
						// inside the async block (CancellationException flows naturally).
						uploadDeferred.await()
						// Real completion — push the bar to 100% so the user sees the
						// upload finish. The caller's notification rate-limiter
						// (see PhotoSyncWorker.updateNotification) treats 100% as a
						// state change and always emits it immediately.
						onProgress(100f)
						diag(
							"uploadFileWithProgress: EXIT OK — totalDuration=${System.currentTimeMillis() - uploadStartMs}ms fileSize=$fileSize",
						)
						Unit
					}
				}
			}

		/**
		 * Parse a `core/stats` response and return the current upload progress as a
		 * percentage `0f..100f`, or `null` if no usable progress info is available.
		 *
		 * Returns null when:
		 *   - `transferring` is missing or not an array (no transfer section in response)
		 *   - `transferring` is empty (upload hasn't registered yet, or already finished)
		 *   - The first entry is missing `bytes` or `totalBytes`
		 *   - `totalBytes` is 0 (divide-by-zero guard)
		 *
		 * @since v8 — stable upload notification with real progress percentage
		 */
		private fun computeUploadProgressPercent(stats: JsonObject): Float? {
			val transferring = stats["transferring"] as? JsonArray ?: return null
			if (transferring.isEmpty()) return null
			val entry = transferring[0] as? JsonObject ?: return null
			val bytes =
				(entry["bytes"] as? JsonPrimitive)?.content?.toLongOrNull()
					?: return null
			val totalBytes =
				(entry["totalBytes"] as? JsonPrimitive)?.content?.toLongOrNull()
					?: return null
			if (totalBytes <= 0L) return null
			val percent = (bytes.toFloat() / totalBytes.toFloat()) * 100f
			return percent.coerceIn(0f, 100f)
		}

		suspend fun downloadFile(
			remotePath: String,
			localPath: String,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val localFile = File(localPath)
					localFile.parentFile?.mkdirs()

					awaitRcdReady().getOrThrow()
					callMutex.withLock {
						invokeRc(
							op = "operations/copyfile",
							params =
								buildJsonObject {
									val remoteName = remotePath.substringBefore(':')
									val remoteRelPath = remotePath.substringAfter(':')
									put("srcFs", "$remoteName:")
									// FIX: same as uploadFile — "srcRemote" not "srcLeaf"
									put("srcRemote", remoteRelPath.removePrefix("/"))
									put("dstFs", localFile.parentFile?.absolutePath ?: "")
									put("dstRemote", localFile.name)
								},
						).getOrThrow()
					}
					Unit
				}
			}

		/**
		 * Download a file with periodic progress callbacks.
		 *
		 * rclone's `operations/copyfile` is synchronous and `core/stats` doesn't
		 * track it — see [uploadFileWithProgress] for the full rationale. The
		 * same size-based estimate approach is reused here:
		 *
		 *   estimated_percent = min(95,
		 *       (elapsed_ms × ASSUMED_SPEED_BYTES_PER_MS) / file_size × 100)
		 *
		 * [expectedSize] is the photo's stored `size` column. If unknown (≤ 0),
		 * the call degrades to indeterminate — `onProgress` is invoked once with
		 * `0f` at the start and once with `100f` at the end. Otherwise, the bar
		 * climbs at the assumed speed until 95%, then jumps to 100% when the real
		 * download completes.
		 *
		 * @since video-loading-indicator feature — on-demand video download with
		 *   visible progress
		 */
		suspend fun downloadFileWithProgress(
			remotePath: String,
			localPath: String,
			expectedSize: Long?,
			onProgress: (Float) -> Unit,
		): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val localFile = File(localPath)
					localFile.parentFile?.mkdirs()

					awaitRcdReady().getOrThrow()

					val downloadStartMs = System.currentTimeMillis()
					val fileSize = expectedSize ?: 0L
					diag("downloadFileWithProgress: ENTRY remotePath=$remotePath localPath=$localPath expectedSize=$fileSize")

					// Build the rc params once — same as downloadFile.
					val params =
						buildJsonObject {
							val remoteName = remotePath.substringBefore(':')
							val remoteRelPath = remotePath.substringAfter(':')
							put("srcFs", "$remoteName:")
							put("srcRemote", remoteRelPath.removePrefix("/"))
							put("dstFs", localFile.parentFile?.absolutePath ?: "")
							put("dstRemote", localFile.name)
						}

					// Edge case: zero-byte file OR unknown size — skip the estimate
					// loop, just invoke the download and emit 0% → 100%.
					if (fileSize <= 0L) {
						diag("downloadFileWithProgress: unknown/zero size, skipping estimate loop")
						onProgress(0f)
						callMutex.withLock {
							invokeRc(op = "operations/copyfile", params = params).getOrThrow()
						}
						onProgress(100f)
						diag(
							"downloadFileWithProgress: EXIT OK (no-estimate) — totalDuration=${System.currentTimeMillis() - downloadStartMs}ms",
						)
						return@runCatching Unit
					}

					coroutineScope {
						val downloadDeferred =
							async(Dispatchers.IO) {
								callMutex.withLock {
									invokeRc(op = "operations/copyfile", params = params).getOrThrow()
								}
							}

						// Size-based estimate loop — same shape as uploadFileWithProgress.
						while (!downloadDeferred.isCompleted) {
							val elapsedMs = System.currentTimeMillis() - downloadStartMs
							val estimatedBytes = elapsedMs * ASSUMED_SPEED_BYTES_PER_MS
							val percentRaw = (estimatedBytes.toFloat() / fileSize.toFloat()) * 100f
							val percentCapped = percentRaw.coerceIn(0f, 95f)
							onProgress(percentCapped)
							delay(PROGRESS_POLL_INTERVAL_MS)
						}

						downloadDeferred.await()
						onProgress(100f)
						diag(
							"downloadFileWithProgress: EXIT OK — totalDuration=${System.currentTimeMillis() - downloadStartMs}ms fileSize=$fileSize",
						)
						Unit
					}
				}
			}

		/**
		 * Independent verification that a file exists on the remote at [remotePath], with the
		 * expected [expectedSize] in bytes.
		 */

		/**
		 * List files in a directory on the remote. Returns a list of [RemoteFileInfo] (name + size).
		 * Used by [RepoManager.detectRepo] to check for the repo marker file.
		 *
		 * @since PR1 sync — mandatory repo setup
		 */
		suspend fun listRemote(
			remoteFs: String,
			remoteDir: String,
		): Result<List<RemoteFileInfo>> =
			withContext(Dispatchers.IO) {
				runCatching {
					diag("listRemote: BEGIN fs=$remoteFs dir=$remoteDir state=${_state.value}")

					awaitRcdReady().getOrThrow()
					callMutex.withLock {
						val resp =
							invokeRc(
								op = "operations/list",
								params =
									buildJsonObject {
										put("fs", remoteFs)
										put("remote", remoteDir)
									},
							).getOrThrow()

						val list =
							resp["list"] as? JsonArray
								?: throw IOException("Malformed list response (no 'list' array): $resp")

						val mapped =
							list.mapNotNull { entry ->
								val obj = entry as? JsonObject ?: return@mapNotNull null
								val name =
									(obj["Name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
										?: return@mapNotNull null
								val size =
									(obj["Size"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
										?: -1L
								RemoteFileInfo(name = name, size = size)
							}
						diag("listRemote: DONE, ${mapped.size} entries")
						mapped
					}
				}
			}

		/**
		 * Delete a single file from the remote.
		 *
		 * Wraps rclone's `operations/deletefile` rc call. Idempotent-ish: if the
		 * file doesn't exist, rclone returns an error which we surface via
		 * `Result.failure` — the caller (typically the GC path) is expected to
		 * catch and treat as non-fatal (the file is already gone, which is what
		 * the caller wanted).
		 *
		 * @param remotePath full rclone remote path, e.g. `myremote:photoz-backup/originals/abc.crypt`
		 * @since registry GC feature — soft-delete + remote cleanup
		 */
		suspend fun deleteFile(remotePath: String): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					awaitRcdReady().getOrThrow()
					callMutex.withLock {
						val remoteName = remotePath.substringBefore(':')
						val remoteRelPath = remotePath.substringAfter(':').removePrefix("/")
						invokeRc(
							op = "operations/deletefile",
							params =
								buildJsonObject {
									put("fs", "$remoteName:")
									put("remote", remoteRelPath)
								},
						).getOrThrow()
					}
					Unit
				}
			}

		suspend fun verifyFileExists(
			remotePath: String,
			expectedSize: Long,
		): Result<Boolean> =
			withContext(Dispatchers.IO) {
				runCatching {
					awaitRcdReady().getOrThrow()
					callMutex.withLock {
						val remoteName = remotePath.substringBefore(':')
						val remoteRelPath = remotePath.substringAfter(':').removePrefix("/")
						// The file's parent dir on the remote (e.g. "originals" for "originals/abc.crypt")
						val parentDir = remoteRelPath.substringBeforeLast('/', "")
						val fileName = remoteRelPath.substringAfterLast('/')

						val resp =
							invokeRc(
								op = "operations/list",
								params =
									buildJsonObject {
										put("fs", "$remoteName:")
										put("remote", parentDir)
									},
							).getOrThrow()

						val list =
							resp["list"] as? JsonArray
								?: throw IOException("Malformed list response (no 'list' array): $resp")

						val found =
							list.any { entry ->
								val obj = entry as? JsonObject ?: return@any false
								val name = (obj["Name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
								val size = (obj["Size"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
								name == fileName && size == expectedSize
							}

						if (!found) {
							throw IOException(
								"File not found on remote after upload: " +
									"$remoteName:$remoteRelPath (expected size=$expectedSize). " +
									"Remote listing: $resp",
							)
						}
						true
					}
				}
			}

		suspend fun verifyRemote(
			remotePath: String,
			expectedSha256: String,
		): Result<Boolean> =
			withContext(Dispatchers.IO) {
				runCatching {
					awaitRcdReady().getOrThrow()
					callMutex.withLock {
						val remoteName = remotePath.substringBefore(':')
						val remoteRelPath = remotePath.substringAfter(':').removePrefix("/")

						val resp =
							invokeRc(
								op = "operations/hashsum",
								params =
									buildJsonObject {
										put("fs", "$remoteName:")
										put("remote", remoteRelPath)
										put("hashType", "sha256")
									},
							).getOrThrow()

						// rclone's operations/hashsum response format (empirically verified
						// via local rcd test against Koofr — see test harness):
						//   {"hashType":"sha256","hashsum":["<64-char-hash-or-spaces> <path>"]}
						// The hashsum array contains STRINGS (not objects as the rclone docs
						// imply). Each string is "<left-padded-hash> <path>".
						//
						// If the backend doesn't support the hash type, the hash field is
						// all spaces (blank). Koofr, for example, does NOT support sha256
						// natively — the hash comes back blank.
						val arr =
							resp["hashsum"] as? JsonArray
								?: throw IOException("Malformed hashsum response (no 'hashsum' array): $resp")

						if (arr.isEmpty()) {
							throw IOException("Empty hashsum response (file may not exist): $resp")
						}

						// Find the entry matching our file path (hashsum may list multiple files
						// if the remote path is a directory)
						val targetFileName = remoteRelPath.substringAfterLast('/')
						val matchingEntry =
							arr.firstOrNull { entry ->
								when (entry) {
									is kotlinx.serialization.json.JsonPrimitive -> {
										val str = entry.content
										str.contains(targetFileName)
									}
									is JsonObject -> {
										// Alternate format: {"<hash>": "<path>"} or {"hash":"...","hash_value":"..."}
										val keys = entry.keys
										keys.any { it.contains(targetFileName) } ||
											(entry["hash_value"] as? kotlinx.serialization.json.JsonPrimitive)?.content != null
									}
									else -> false
								}
							} ?: throw IOException("File not found in hashsum response: $targetFileName (full: $resp)")

						val actualHash: String =
							when (matchingEntry) {
								is kotlinx.serialization.json.JsonPrimitive -> {
									// Format: "<64-char-hash-or-spaces> <path>"
									// Extract the first 64 chars (the hash field), trim spaces
									val str = matchingEntry.content
									val hashField = str.take(64).trim()
									hashField
								}
								is JsonObject -> {
									// Try hash-is-key format
									val hexKey =
										matchingEntry.keys.firstOrNull {
											it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" }
										}
									if (hexKey != null) {
										hexKey
									} else {
										// Try hash_value field
										(matchingEntry["hash_value"] as? kotlinx.serialization.json.JsonPrimitive)?.content
											?: throw IOException("Could not extract hash from hashsum entry: $matchingEntry")
									}
								}
								else -> throw IOException("Unexpected hashsum entry type: $matchingEntry")
							}

						if (actualHash.isBlank()) {
							// Backend doesn't support this hash type (e.g. Koofr + sha256).
							// Throw a specific exception so the caller can decide to skip hash
							// verification and rely on size check (verifyFileExists) only.
							throw HashNotSupportedException(
								"Backend does not support sha256 hash (returned blank). " +
									"Rely on verifyFileExists() for verification. Response: $resp",
							)
						}

						val match = actualHash.equals(expectedSha256, ignoreCase = true)
						if (!match) {
							throw IOException(
								"Hash mismatch: local=$expectedSha256 remote=$actualHash (response: $resp)",
							)
						}
						true
					}
				}
			}

		suspend fun stop() =
			withContext(Dispatchers.IO) {
				readinessMutex.withLock {
					process?.let { p ->
						runCatching {
							p.destroy()
							if (!p.waitFor(3, TimeUnit.SECONDS)) {
								p.destroyForcibly()
								p.waitFor(2, TimeUnit.SECONDS)
							}
						}
					}
					process = null
					_state.value = RcdState.STOPPED
				}
			}

		private suspend fun awaitRcdReady(): Result<Unit> {
			diag("awaitRcdReady: BEGIN state=${_state.value}")
			if (_state.value == RcdState.READY && ping().isSuccess) {
				diag("awaitRcdReady: already READY (fast path)")
				return Result.success(Unit)
			}

			return readinessMutex.withLock {
				if (_state.value == RcdState.READY && ping().isSuccess) {
					diag("awaitRcdReady: already READY (under mutex)")
					return@withLock Result.success(Unit)
				}

				process?.let { existing ->
					if (!existing.isAlive) {
						diag("awaitRcdReady: existing process not alive, clearing")
						process = null
						_state.value = RcdState.DEAD
					} else if (!ping().isSuccess) {
						diag("awaitRcdReady: existing process alive but ping failed, killing")
						runCatching { existing.destroyForcibly() }
						process = null
						_state.value = RcdState.DEAD
					}
				}

				diag("awaitRcdReady: about to call startRcdProcess()")
				startRcdProcess().getOrElse { err ->
					diag(
						"awaitRcdReady: startRcdProcess FAILED — exception class=${err.javaClass.name} message=${err.message}",
						err,
					)
					_state.value = RcdState.DEAD
					return@withLock Result.failure(err)
				}
				diag("awaitRcdReady: startRcdProcess returned OK, polling for readiness")

				val deadline = System.currentTimeMillis() + RCD_READY_TIMEOUT_MS
				while (System.currentTimeMillis() < deadline) {
					if (ping().isSuccess) {
						diag("awaitRcdReady: ping succeeded, state=READY")
						_state.value = RcdState.READY
						return@withLock Result.success(Unit)
					}
					delay(POLL_INTERVAL_MS)
				}

				diag("awaitRcdReady: TIMEOUT after ${RCD_READY_TIMEOUT_MS}ms — rcd did not become ready")
				_state.value = RcdState.DEAD
				Result.failure(IOException("rclone rcd did not become ready within ${RCD_READY_TIMEOUT_MS} ms"))
			}
		}

		private suspend fun startRcdProcess(): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					diag(
						"startRcdProcess: BEGIN state=${_state.value} " +
							"filesDir=${app.filesDir.absolutePath} " +
							"codeCacheDir=${app.codeCacheDir.absolutePath} " +
							"nativeLibraryDir=${app.applicationInfo.nativeLibraryDir}",
					)
					val binary = locateRcloneBinary()
					diag(
						"startRcdProcess: locateRcloneBinary returned ${binary.absolutePath} " +
							"size=${binary.length()} canExecute=${binary.canExecute()}",
					)
					val conf =
						configManager.configFile
							?: throw IllegalStateException(
								"No rclone.conf imported. Call RcloneConfigManager.import() first.",
							)
					diag("startRcdProcess: config=${conf.absolutePath} size=${conf.length()}")

					port = pickFreePort()
					authToken = generateToken()
					authUser = "photok"
					diag("startRcdProcess: spawning rcd on port=$port")

					val argv =
						mutableListOf(
							binary.absolutePath,
							"rcd",
							"--rc-addr=127.0.0.1:$port",
							"--rc-user=$authUser",
							"--rc-pass=$authToken",
							"--config=${conf.absolutePath}",
							"--use-cookies=false",
							"--contimeout=30s",
							"--low-level-retries=3",
							"--stats=0",
							"--log-level=ERROR",
						)

					val pb = ProcessBuilder(argv).redirectErrorStream(true)
					pb.directory(app.filesDir)

					// ─── Force Go's cgo DNS resolver (Layer 4 fix) ────────────────────────
					// The rclone binary is now built with CGO_ENABLED=1 (see
					// scripts/build-rclone.sh), so Go SHOULD prefer the cgo resolver
					// automatically (goosPrefersCgo() returns true for "android"). But
					// as a belt-and-suspenders safety net, explicitly force it via
					// GODEBUG=netdns=cgo+1. The "+1" enables debug logging so we can
					// confirm via logcat which resolver is actually in use.
					//
					// Background: Go's pure-Go DNS resolver does NOT work on Android
					// because there's no /etc/resolv.conf accessible to app processes.
					// It falls back to hardcoded defaultNS = ["127.0.0.1:53", "[::1]:53"]
					// — nothing listens there, so every DNS query fails with
					// "connection refused". This is Go issue #10714. The cgo resolver
					// goes through Bionic getaddrinfo() → Android netd → real DNS.
					//
					// See skills/android-native-binary-bundling/references/go-dns-on-android.md
					// for the full writeup.
					pb.environment()["GODEBUG"] = "netdns=cgo+1"

					diag("startRcdProcess: about to pb.start(), cmd=${argv.joinToString(" ")}")
					val p =
						try {
							pb.start()
						} catch (e: IOException) {
							// THE critical log point: this is where W^X / EACCES / ENOEXEC surfaces.
							// Log the FULL cause chain — the actual errno string is usually in the
							// innermost cause (e.g. "Permission denied" / "EACCES" / "Not executable").
							diag("startRcdProcess: pb.start() THREW ${e.javaClass.name}: ${e.message}", e)
							var cause: Throwable? = e.cause
							var depth = 0
							while (cause != null && depth < 10) {
								diag("startRcdProcess:   caused by [depth=$depth] ${cause.javaClass.name}: ${cause.message}")
								cause = cause.cause
								depth++
							}
							throw IOException("Failed to spawn rclone binary at ${binary.absolutePath}", e)
						}
					process = p
					_state.value = RcdState.STARTING
					diag("startRcdProcess: pb.start() OK, alive=${p.isAlive} toString=$p")

					Thread({
						runCatching {
							p.inputStream.bufferedReader().useLines { lines ->
								lines.forEach { line ->
									diag("rclone-stdout: $line")
									if (line.isNotBlank()) Timber.tag("rclone").v(line)
								}
							}
						}
						// Drain thread exits when the process exits or the stream closes.
						// Capture the exit code — if rcd starts and immediately crashes,
						// this is the only signal we'll get.
						val exitCode = runCatching { p.waitFor() }.getOrDefault(-1)
						diag("rclone-stdout-drain: EXIT — process exitCode=$exitCode alive=${p.isAlive}")
						_state.value = RcdState.DEAD
					}, "rclone-stdout-drain").apply {
						isDaemon = true
						start()
					}

					diag("startRcdProcess: DONE, returning")
					Unit
				}
			}

		/**
		 * Locate the rclone binary at runtime and return a path that can be `exec()`'d.
		 *
		 * ## The bug history (read this if debugging "binary not found" or EACCES)
		 *
		 * The rclone binary is packaged as `librclone.so` under `jniLibs/<abi>/` (Android
		 * requires the `lib*.so` naming convention for jniLibs). Three layers of issues
		 * had to be peeled back to get to a working subprocess execution:
		 *
		 * **Issue 1 (solved in build.gradle.kts):** AGP's default
		 * `useLegacyPackaging = null` → `extractNativeLibs = false` → `.so` files are
		 * mmap'd from the APK zip by `dlopen`, NEVER extracted to `nativeLibraryDir`
		 * on disk. Symptom: `nativeSrcLib.exists = false`. Fix:
		 * `packaging { jniLibs { useLegacyPackaging = true } }` forces on-disk extraction
		 * at install time. After this fix, `librclone.so` is present in
		 * `nativeLibraryDir` as a real file.
		 *
		 * **Issue 2 (solved in this function — CURRENT FIX):** After Issue 1 was solved,
		 * the prior approach copied `librclone.so` from `nativeLibraryDir` to
		 * `codeCacheDir/rclone` and exec'd from there. This fails with `EACCES` (errno 13)
		 * at the kernel `exec()` syscall level on Android 10+ (API 29+), enforced
		 * increasingly strictly in every release (Android 16 included). This is the
		 * **W^X (write XOR execute) policy**: Android blocks executing any file the app
		 * wrote itself at runtime — including `files/`, `code_cache/`, `cache/`, and
		 * any other app-private writable directory. This is enforced via SELinux policy
		 * + `noexec`-equivalent mount semantics, NOT via POSIX file mode bits. Setting
		 * `File.setExecutable(true)` succeeds (Java reports `canExecute()=true`) but the
		 * kernel still refuses `execve()`.
		 *
		 * **The one exception** to the W^X policy: files under `nativeLibraryDir` —
		 * because those are extracted by the **system package installer** from the
		 * app's own **signed, verified APK** at install time, not written by the app
		 * process itself at runtime. That's the entire reason `nativeLibraryDir` is
		 * exempted: the OS trusts what it extracted from a signed package, not what
		 * the app wrote to its own sandbox afterward.
		 *
		 * ## Current fix: exec directly from nativeLibraryDir, no copy
		 *
		 * When the binary is found in `nativeLibraryDir` (Layer 1, the normal case
		 * after Issue 1 is solved), **return that path directly** — do not copy it
		 * anywhere, do not rename it. Exec the OS-extracted file in place. This is
		 * the only location where Android's W^X policy allows `exec()` of an
		 * app-bundled binary on Android 10+.
		 *
		 * ## Layer 2 fallback: APK-zip extraction (last-ditch, expected to fail on 10+)
		 *
		 * If `nativeLibraryDir` is somehow empty (OEM ROM quirk, future AGP regression,
		 * user-side install glitch), `extractBinaryFromApkZip()` extracts the binary
		 * from the app's own APK zip into `codeCacheDir/rclone`. **Note: this will
		 * fail with EACCES on Android 10+ per the W^X policy above** — it exists only
		 * to produce a more informative error than "binary not found" on devices where
		 * Layer 1 didn't apply, and to keep working on the (rare) Android versions
		 * where W^X is not yet enforced. If Layer 2 is reached AND fails with EACCES,
		 * the only remaining viable path is rebuilding rclone as a real JNI library
		 * (via `github.com/rclone/rclone/librclone`, the gomobile-bindable package)
		 * and calling into it via `System.loadLibrary()` — that's a separate project.
		 *
		 * @since PR1 sync — fix for "binary not found" / EACCES on Android 14+
		 */
		private fun locateRcloneBinary(): File {
			diag("locateRcloneBinary: START")

			// Step 1: find the source binary in nativeLibraryDir
			val nativeLibDir = app.applicationInfo.nativeLibraryDir
			diag("nativeLibraryDir=$nativeLibDir")

			// Detect symlink: with `useLegacyPackaging = null` (AGP default), .so files
			// are stored uncompressed+page-aligned inside the APK and accessed via a
			// virtual symlink-like path at nativeLibraryDir. If absolutePath !=
			// canonicalPath, the file is a symlink into the APK — ProcessBuilder.start()
			// on it will fail because there's no real on-disk file to execve().
			try {
				val nativeLibDirFile = java.io.File(nativeLibDir)
				diag(
					"nativeLibraryDir canonical=${nativeLibDirFile.canonicalPath} " +
						"absolute=${nativeLibDirFile.absolutePath} " +
						"isSymlink=${nativeLibDirFile.absolutePath != nativeLibDirFile.canonicalPath}",
				)
			} catch (e: Exception) {
				diag("nativeLibraryDir canonicalPath FAILED: ${e.message}", e)
			}

			val nativeSrcLib =
				if (nativeLibDir.isNotBlank()) {
					java.io.File(nativeLibDir, BINARY_LIB_NAME)
				} else {
					null
				}
			val nativeSrcPlain =
				if (nativeLibDir.isNotBlank()) {
					java.io.File(nativeLibDir, BINARY_NAME)
				} else {
					null
				}

			diag(
				"nativeSrcLib(${BINARY_LIB_NAME}) exists=${nativeSrcLib?.exists()} size=${nativeSrcLib?.length() ?: -1} canRead=${nativeSrcLib?.canRead()}",
			)
			diag(
				"nativeSrcPlain(${BINARY_NAME}) exists=${nativeSrcPlain?.exists()} size=${nativeSrcPlain?.length() ?: -1} canRead=${nativeSrcPlain?.canRead()}",
			)

			val devPath = java.io.File(configManager.binDir, BINARY_NAME)
			diag("devPath(${devPath.absolutePath}) exists=${devPath.exists()} size=${devPath.length()} canRead=${devPath.canRead()}")

			// Detect symlinks on each candidate source file too.
			for (cand in listOf(nativeSrcLib, nativeSrcPlain, devPath)) {
				if (cand == null) continue
				try {
					val isSymlink = cand.absolutePath != cand.canonicalPath
					diag("cand ${cand.absolutePath} canonical=${cand.canonicalPath} isFile=${cand.isFile} isSymlink=$isSymlink")
				} catch (e: Exception) {
					diag("cand ${cand.absolutePath} canonicalPath FAILED: ${e.message}", e)
				}
			}

			val onDiskSource =
				nativeSrcLib?.takeIf { it.exists() && it.isFile }
					?: nativeSrcPlain?.takeIf { it.exists() && it.isFile }
					?: devPath.takeIf { it.exists() && it.isFile }

			// ─── Layer 1: nativeLibraryDir — exec directly, NO COPY ───────────────────
			//
			// This is the ONLY location where Android 10+ W^X policy allows exec() of an
			// app-bundled binary. The file was extracted by the system package installer
			// from the signed APK at install time, so the OS trusts it. Copying it to
			// codeCacheDir/filesDir/cacheDir would mark it as "app-written at runtime"
			// and the kernel would refuse to execve() it with EACCES, regardless of the
			// file's mode bits. So: do NOT copy. Return the nativeLibraryDir path
			// directly. The OS already set the executable bit during install-time
			// extraction (and `ProcessBuilder.start()` does not require Java's
			// `canExecute()` to return true — it goes straight to `execve()`).
			//
			// `devPath` (files/rclone/bin/rclone) is also handled here for backwards
			// compatibility — but note that on Android 10+ this path is also W^X-blocked
			// (it's app-writable). It will only work on older Android versions.
			if (onDiskSource != null) {
				diag("locateRcloneBinary: Layer 1 — returning ${onDiskSource.absolutePath} directly (no copy, no codeCacheDir)")
				diag(
					"locateRcloneBinary: WARNING — if this path is NOT under nativeLibraryDir (e.g. it's under files/), expect EACCES on Android 10+",
				)
				diag(
					"locateRcloneBinary: DONE, returning ${onDiskSource.absolutePath} (sourced from nativeLibraryDir-or-binDir, Layer 1, NO COPY)",
				)
				return onDiskSource
			}

			// ─── Layer 2: APK-zip extraction fallback (EXPECTED TO FAIL on Android 10+) ─
			// Used when Layer 1 found nothing on disk (extractNativeLibs=false install,
			// OEM ROM quirk, future AGP regression, etc.). Sources the binary directly
			// from the app's own APK at `applicationInfo.sourceDir`, entry
			// `lib/<abi>/librclone.so`. The app can always read its own APK file.
			// ZipFile.getInputStream() transparently decompresses Stored OR Defl:N.
			//
			// ⚠️ This writes to codeCacheDir, which is W^X-blocked on Android 10+. The
			// file will be created and `setExecutable(true)` will return true, but
			// `ProcessBuilder.start()` will subsequently fail with EACCES at execve()
			// time. Layer 2 is kept only to produce a more informative error than
			// "binary not found" on devices where Layer 1 didn't apply, and to keep
			// working on the rare Android versions where W^X is not yet enforced.
			// If Layer 2 is reached AND fails with EACCES, the only remaining viable
			// path is rebuilding rclone as a real JNI library via
			// `github.com/rclone/rclone/librclone` (gomobile-bindable, distinct from
			// the CLI `main` package currently bundled) and calling into it via
			// `System.loadLibrary()` — that's a separate project.
			diag(
				"locateRcloneBinary: Layer 1 found nothing on disk; falling back to Layer 2 (APK zip extraction — EXPECTED TO FAIL on Android 10+ per W^X)",
			)
			val apkSource = extractBinaryFromApkZip()
			if (apkSource == null) {
				diag("locateRcloneBinary: Layer 2 ALSO failed — no source obtainable")
				throw IllegalStateException(
					"rclone binary not found. " +
						"nativeLibraryDir=$nativeLibDir, " +
						"checked on-disk: ${BINARY_LIB_NAME} (exists=${nativeSrcLib?.exists()}), " +
						"${BINARY_NAME} (exists=${nativeSrcPlain?.exists()}) in nativeLibraryDir; " +
						"${BINARY_NAME} in ${configManager.binDir} (exists=${devPath.exists()}); " +
						"also tried APK-zip extraction (Layer 2) — see RcloneDiag logs above for details. " +
						"Run scripts/build-rclone.sh first; see README sync section.",
				)
			}
			diag(
				"locateRcloneBinary: Layer 2 succeeded (file extracted to codeCacheDir), but EXPECT EACCES on Android 10+ when pb.start() is called",
			)
			diag("locateRcloneBinary: DONE, returning ${apkSource.absolutePath} (sourced from apk-zip-extraction, Layer 2)")
			return apkSource
		}

		/**
		 * Layer 2 fallback: extract `librclone.so` directly from the app's own APK zip.
		 *
		 * Opens `applicationInfo.sourceDir` (the on-disk path of the installed base APK) as a
		 * `java.util.zip.ZipFile`, locates entry `lib/<abi>/librclone.so` for the current ABI,
		 * streams it to `codeCacheDir/rclone`, and sets the executable bit.
		 *
		 * This works regardless of `useLegacyPackaging` / `extractNativeLibs` because the app
		 * can always read its own APK file, and `ZipFile.getInputStream()` transparently
		 * decompresses both `Stored` (uncompressed) and `Defl:N` (deflate-compressed) entries.
		 *
		 * @return the extracted `File` (in `codeCacheDir`, executable bit set), or `null` if
		 *   the entry could not be found in the APK or extraction failed (failure is logged
		 *   via `diag()` before returning null — never throws, so the caller can fall through
		 *   to its own error handling).
		 */
		private fun extractBinaryFromApkZip(): java.io.File? {
			val apkPath = app.applicationInfo.sourceDir
			diag("extractBinaryFromApkZip: BEGIN apkPath=$apkPath")
			if (apkPath.isNullOrBlank()) {
				diag("extractBinaryFromApkZip: ABORT — applicationInfo.sourceDir is null/blank")
				return null
			}
			val apkFile = java.io.File(apkPath)
			if (!apkFile.exists() || !apkFile.canRead()) {
				diag(
					"extractBinaryFromApkZip: ABORT — apk file not readable: ${apkFile.absolutePath} exists=${apkFile.exists()} canRead=${apkFile.canRead()}",
				)
				return null
			}
			diag("extractBinaryFromApkZip: apk exists size=${apkFile.length()} canRead=${apkFile.canRead()}")

			// Determine the current ABI. We use `Build.SUPPORTED_ABIS[0]` (the device's
			// preferred ABI) rather than `applicationInfo.primaryCpuAbi` because the latter
			// is hidden behind Android's system-API surface and not directly accessible
			// from Kotlin app code without reflection. For an arm64-v8a-only APK installed
			// on an arm64 device (the only supported configuration), SUPPORTED_ABIS[0] is
			// `arm64-v8a` — exactly what we want. If the APK were multi-ABI, the OS would
			// pick the best match and we'd want the same one for extraction; since we ship
			// arm64-v8a-only, this is deterministic.
			val abi =
				android.os.Build.SUPPORTED_ABIS
					.firstOrNull()
			diag("extractBinaryFromApkZip: chosenAbi=$abi SUPPORTED_ABIS=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
			if (abi.isNullOrBlank()) {
				diag("extractBinaryFromApkZip: ABORT — no ABI determinable")
				return null
			}

			// Inside the APK, jniLibs entries are packed under `lib/<abi>/`, NOT `jniLibs/<abi>/`.
			// This is the post-build in-APK path; the `jniLibs/` source path is only the
			// gradle source-set name.
			val entryName = "lib/$abi/$BINARY_LIB_NAME"
			diag("extractBinaryFromApkZip: looking for zip entry '$entryName'")

			val execDir = app.codeCacheDir
			execDir.mkdirs()
			val execFile = java.io.File(execDir, "rclone")

			return try {
				java.util.zip.ZipFile(apkFile).use { zf ->
					val entry = zf.getEntry(entryName)
					if (entry == null) {
						diag("extractBinaryFromApkZip: entry '$entryName' NOT FOUND in APK. Listing all lib/* entries:")
						// List available lib entries to aid debugging (e.g. wrong ABI name,
						// binary not packaged at all, etc.).
						val entries = zf.entries()
						while (entries.hasMoreElements()) {
							val e = entries.nextElement()
							if (e.name.startsWith("lib/")) {
								diag("extractBinaryFromApkZip:   apk contains: ${e.name} size=${e.size} method=${e.method}")
							}
						}
						return null
					}
					diag(
						"extractBinaryFromApkZip: entry found, compressedSize=${entry.compressedSize} size=${entry.size} method=${entry.method} (0=STORED, 8=DEFLATED)",
					)

					// Stream the entry to codeCacheDir/rclone. Use a temp file + atomic rename
					// so a partial write never leaves a corrupt rclone in codeCacheDir.
					val tmpFile = java.io.File(execDir, "rclone.tmp.${System.currentTimeMillis()}")
					try {
						zf.getInputStream(entry).use { input ->
							java.io.FileOutputStream(tmpFile).use { output ->
								input.copyTo(output)
							}
						}
						diag("extractBinaryFromApkZip: wrote tmp file, size=${tmpFile.length()}")
					} catch (e: Exception) {
						diag("extractBinaryFromApkZip: tmp write FAILED: ${e.javaClass.name}: ${e.message}", e)
						tmpFile.delete()
						return null
					}

					// Atomic rename over any existing rclone.
					if (!tmpFile.renameTo(execFile)) {
						diag("extractBinaryFromApkZip: rename FAILED — falling back to copy+delete")
						try {
							tmpFile.copyTo(execFile, overwrite = true)
							tmpFile.delete()
						} catch (e: Exception) {
							diag("extractBinaryFromApkZip: fallback copy FAILED: ${e.javaClass.name}: ${e.message}", e)
							tmpFile.delete()
							return null
						}
					}
					diag("extractBinaryFromApkZip: execFile in place, size=${execFile.length()}")

					// Set executable bit.
					if (!execFile.canExecute()) {
						val ok = execFile.setExecutable(true, true)
						diag("extractBinaryFromApkZip: setExecutable result=$ok canExecute=${execFile.canExecute()}")
						if (!ok) {
							diag("extractBinaryFromApkZip: WARNING — setExecutable returned false; exec may fail with EACCES")
						}
					}

					diag("extractBinaryFromApkZip: DONE, returning ${execFile.absolutePath}")
					execFile
				}
			} catch (e: Exception) {
				diag("extractBinaryFromApkZip: ZipFile open/read FAILED: ${e.javaClass.name}: ${e.message}", e)
				null
			}
		}

		private suspend fun ping(): Result<Unit> =
			withContext(Dispatchers.IO) {
				runCatching {
					val p = process ?: return@withContext Result.failure(IllegalStateException("no process"))
					if (!p.isAlive) return@withContext Result.failure(IllegalStateException("process dead"))

					val url = URL("http://127.0.0.1:$port/rc/noop")
					val conn =
						(url.openConnection() as HttpURLConnection).apply {
							connectTimeout = PING_TIMEOUT_MS.toInt()
							readTimeout = PING_TIMEOUT_MS.toInt()
							requestMethod = "POST"
							setRequestProperty("Authorization", basicAuthHeader())
							setRequestProperty("Content-Type", "application/json")
						}
					try {
						conn.outputStream.use { it.write("{}".toByteArray()) }
						val code = conn.responseCode
						if (code != 200) {
							throw IOException("rcd ping HTTP $code")
						}
					} finally {
						conn.disconnect()
					}
				}
			}

		private suspend fun invokeRc(
			op: String,
			params: JsonObject,
		): Result<JsonObject> =
			withContext(Dispatchers.IO) {
				runCatching {
					val p = process ?: throw IllegalStateException("no rcd process")
					if (!p.isAlive) throw IOException("rcd process died")

					val url = URL("http://127.0.0.1:$port/$op")
					val conn =
						(url.openConnection() as HttpURLConnection).apply {
							connectTimeout = RC_CALL_CONNECT_TIMEOUT_MS.toInt()
							readTimeout = RC_CALL_READ_TIMEOUT_MS.toInt()
							requestMethod = "POST"
							setRequestProperty("Authorization", basicAuthHeader())
							setRequestProperty("Content-Type", "application/json")
						}
					val respText: String
					val code: Int
					try {
						val body = json.encodeToString(JsonObject.serializer(), params).toByteArray()
						conn.outputStream.use { it.write(body) }
						code = conn.responseCode
						val respStream = if (code in 200..299) conn.inputStream else conn.errorStream
						respText = respStream?.bufferedReader()?.use { it.readText() }
							?: ""
					} finally {
						conn.disconnect()
					}

					// ─── DIAGNOSTIC LOGGING ───────────────────────────────────────────
					// Log every rc call to sync_log.txt so we can diagnose false-success
					// without logcat. Never logs config contents — only op name, params
					// (which are paths, not credentials), HTTP status, and response body.
					SyncLogger.logRcCall(op, params, code, respText)

					// ─── ERROR CHECKING (Bug B fix) ───────────────────────────────────
					// rclone rcd may return HTTP 200 with an {"error": "..."} field for
					// certain failure modes (async-style errors, backend auth failures,
					// unreachable remotes). We must check the error field REGARDLESS of
					// HTTP status — not just on non-2xx.
					if (code !in 200..299) {
						throw IOException("rclone $op failed: HTTP $code — $respText")
					}

					if (respText.isBlank()) {
						JsonObject(emptyMap())
					} else {
						val parsed = json.parseToJsonElement(respText)
						val jsonObj =
							parsed as? JsonObject
								?: throw IOException("rclone $op returned non-object: $respText")

						// Check for rclone's error field (present on failure even with HTTP 200)
						val errorElement = jsonObj["error"]
						if (errorElement != null && errorElement !is kotlinx.serialization.json.JsonNull) {
							val errorMsg = errorElement.toString()
							throw IOException("rclone $op returned error: $errorMsg (full response: $respText)")
						}

						jsonObj
					}
				}
			}

		private fun basicAuthHeader(): String {
			val raw = "$authUser:$authToken"
			return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
		}

		private fun pickFreePort(): Int =
			ServerSocket().use { s ->
				s.bind(InetSocketAddress("127.0.0.1", 0))
				s.localPort
			}

		private fun generateToken(): String {
			val bytes = ByteArray(32)
			SecureRandom().nextBytes(bytes)
			return Base64.encodeToString(bytes, Base64.NO_WRAP)
		}

		enum class RcdState { STOPPED, STARTING, READY, DEAD }

		/**
		 * File info returned by [listRemote]. Simple name + size pair.
		 * @since PR1 sync — mandatory repo setup
		 */
		data class RemoteFileInfo(
			val name: String,
			val size: Long,
		)

		/**
		 * Thrown when the rclone backend doesn't support the requested hash type (e.g. Koofr
		 * + sha256). The caller should treat this as "hash verification skipped" and rely on
		 * [verifyFileExists] (size check) instead.
		 */
		class HashNotSupportedException(
			message: String,
		) : Exception(message)

		companion object {
			private const val BINARY_NAME = "rclone"
			private const val BINARY_LIB_NAME = "librclone.so"
			private const val RCD_READY_TIMEOUT_MS = 15_000L
			private const val POLL_INTERVAL_MS = 300L
			private const val PING_TIMEOUT_MS = 2_000L
			private const val RC_CALL_CONNECT_TIMEOUT_MS = 30_000L
			private const val RC_CALL_READ_TIMEOUT_MS = 600_000L

			// Polling interval for uploadFileWithProgress()'s size-based estimate
			// loop. 200ms = ~5 ticks/sec — fast enough to feel responsive, slow
			// enough to avoid UI/notification thrash. The caller's notification
			// rate-limiter (PhotoSyncWorker.updateNotification) further collapses
			// ticks to max ~2/sec on the notification side.
			//
			// @since v8 — stable upload notification with real progress percentage
			// @since real-upload-progress feature — lowered from 500ms to 200ms
			//   for a smoother progress bar with the size-based estimate
			private const val PROGRESS_POLL_INTERVAL_MS = 200L

			// Assumed upload speed for the size-based progress estimate in
			// uploadFileWithProgress(). 5000 bytes/ms = 5 MB/s = 40 Mbps —
			// a deliberately conservative value (slower than most home Wi-Fi
			// upload speeds, but faster than a weak cellular connection).
			//
			// The estimate under-promises on fast networks (the bar jumps to
			// 100% before the estimate would have reached it) and slightly
			// over-promises on very slow networks (the bar sits at 95% for a
			// while before the real upload finishes). Both failure modes are
			// acceptable — the alternative is an indeterminate spinner, which
			// gives the user zero feedback.
			//
			// @since real-upload-progress feature
			private const val ASSUMED_SPEED_BYTES_PER_MS = 5000L
		}
	}
