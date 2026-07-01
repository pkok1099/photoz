/*
 *   Copyright 2020–2026 Leon Latsch
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

package dev.leonlatsch.photok.sync.rclone

import android.app.Application
import android.util.Base64
import dev.leonlatsch.photok.sync.debug.SyncLogger
import dev.leonlatsch.photok.sync.domain.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Photok artifacts against a remote configured via `rclone.conf`.
 */
@Singleton
class RcloneController @Inject constructor(
    private val app: Application,
    private val configManager: RcloneConfigManager,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(RcdState.STOPPED)
    val state = _state.asStateFlow()

    private val readinessMutex = Mutex()
    private val callMutex = Mutex()

    @Volatile private var process: Process? = null
    @Volatile private var port: Int = 0
    @Volatile private var authUser: String = "photok"
    @Volatile private var authToken: String = ""

    suspend fun uploadFile(localPath: String, remotePath: String): Result<Unit> =
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
                        params = buildJsonObject {
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

    suspend fun downloadFile(remotePath: String, localPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()

                awaitRcdReady().getOrThrow()
                callMutex.withLock {
                    invokeRc(
                        op = "operations/copyfile",
                        params = buildJsonObject {
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
     * Independent verification that a file exists on the remote at [remotePath], with the
     * expected [expectedSize] in bytes. Uses a SEPARATE `operations/list` rc call — not the
     * same channel as the upload — so a bug in upload params can't be masked by a bug in
     * verification.
     *
     * This is the restic-style "remote is source of truth" check: after uploadFile() reports
     * success, this method independently confirms the file is actually there by listing the
     * remote directory and checking for the file by name + size.
     *
     * @return `Result.success(true)` only if the file exists at the exact path AND its size
     *   matches. `Result.success(false)` if the file is absent. `Result.failure` on I/O error.
     *
     * @since PR1 sync — independent verification (Step 2 of false-success fix)
     */
    suspend fun verifyFileExists(remotePath: String, expectedSize: Long): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                awaitRcdReady().getOrThrow()
                callMutex.withLock {
                    val remoteName = remotePath.substringBefore(':')
                    val remoteRelPath = remotePath.substringAfter(':').removePrefix("/")
                    // The file's parent dir on the remote (e.g. "originals" for "originals/abc.crypt")
                    val parentDir = remoteRelPath.substringBeforeLast('/', "")
                    val fileName = remoteRelPath.substringAfterLast('/')

                    val resp = invokeRc(
                        op = "operations/list",
                        params = buildJsonObject {
                            put("fs", "$remoteName:")
                            put("remote", parentDir)
                        },
                    ).getOrThrow()

                    val list = resp["list"] as? JsonArray
                        ?: throw IOException("Malformed list response (no 'list' array): $resp")

                    val found = list.any { entry ->
                        val obj = entry as? JsonObject ?: return@any false
                        val name = (obj["Name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        val size = (obj["Size"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                        name == fileName && size == expectedSize
                    }

                    if (!found) {
                        throw IOException(
                            "File not found on remote after upload: " +
                                "$remoteName:$remoteRelPath (expected size=$expectedSize). " +
                                "Remote listing: $resp"
                        )
                    }
                    true
                }
            }
        }

    suspend fun verifyRemote(remotePath: String, expectedSha256: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                awaitRcdReady().getOrThrow()
                callMutex.withLock {
                    val remoteName = remotePath.substringBefore(':')
                    val remoteRelPath = remotePath.substringAfter(':').removePrefix("/")

                    val resp = invokeRc(
                        op = "operations/hashsum",
                        params = buildJsonObject {
                            put("fs", "$remoteName:")
                            put("remote", remoteRelPath)
                            put("hashType", "sha256")
                        },
                    ).getOrThrow()

                    // rclone's operations/hashsum returns:
                    //   {"hashsum": [{"<hash_value>": "<remote_path>"}]}
                    // where the hash IS the key and the path is the value.
                    // Some rclone versions may return {"hashsum": [{"hash": "<algo>", "hash_value": "<hex>"}]}.
                    // Handle both shapes. If neither matches, throw (never return true on parse failure).
                    val arr = resp["hashsum"] as? JsonArray
                        ?: throw IOException("Malformed hashsum response (no 'hashsum' array): $resp")
                    val firstEntry = arr.firstOrNull() as? JsonObject
                        ?: throw IOException("Empty hashsum response: $resp")

                    val actualHash: String = when {
                        // Shape 1: {"<64-char-hex>": "<path>"} — hash is the key
                        firstEntry.keys.any { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } } ->
                            firstEntry.keys.first { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } }

                        // Shape 2: {"hash": "<algo>", "hash_value": "<hex>"} — hash is a value
                        (firstEntry["hash_value"] as? kotlinx.serialization.json.JsonPrimitive)?.content != null ->
                            (firstEntry["hash_value"] as kotlinx.serialization.json.JsonPrimitive).content

                        // Shape 3: single key that looks like a hex hash of any length
                        firstEntry.keys.size == 1 && firstEntry.keys.first().all { c -> c in "0123456789abcdefABCDEF" } ->
                            firstEntry.keys.first()

                        else -> throw IOException(
                            "Could not extract hash from hashsum response: $firstEntry (full: $resp)"
                        )
                    }

                    val match = actualHash.equals(expectedSha256, ignoreCase = true)
                    if (!match) {
                        throw IOException(
                            "Hash mismatch: local=$expectedSha256 remote=$actualHash " +
                                "(response: $resp)"
                        )
                    }
                    match
                }
            }
        }

    suspend fun stop() = withContext(Dispatchers.IO) {
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
        if (_state.value == RcdState.READY && ping().isSuccess) {
            return Result.success(Unit)
        }

        return readinessMutex.withLock {
            if (_state.value == RcdState.READY && ping().isSuccess) {
                return@withLock Result.success(Unit)
            }

            process?.let { existing ->
                if (!existing.isAlive) {
                    process = null
                    _state.value = RcdState.DEAD
                } else if (!ping().isSuccess) {
                    runCatching { existing.destroyForcibly() }
                    process = null
                    _state.value = RcdState.DEAD
                }
            }

            startRcdProcess().getOrElse { err ->
                _state.value = RcdState.DEAD
                return@withLock Result.failure(err)
            }

            val deadline = System.currentTimeMillis() + RCD_READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (ping().isSuccess) {
                    _state.value = RcdState.READY
                    return@withLock Result.success(Unit)
                }
                delay(POLL_INTERVAL_MS)
            }

            _state.value = RcdState.DEAD
            Result.failure(IOException("rclone rcd did not become ready within ${RCD_READY_TIMEOUT_MS} ms"))
        }
    }

    private suspend fun startRcdProcess(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val binary = locateRcloneBinary()
            val conf = configManager.configFile
                ?: throw IllegalStateException(
                    "No rclone.conf imported. Call RcloneConfigManager.import() first."
                )

            port = pickFreePort()
            authToken = generateToken()
            authUser = "photok"

            val argv = mutableListOf(
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

            val p = try {
                pb.start()
            } catch (e: IOException) {
                throw IOException("Failed to spawn rclone binary at ${binary.absolutePath}", e)
            }
            process = p
            _state.value = RcdState.STARTING

            Thread({
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) Timber.tag("rclone").v(line)
                        }
                    }
                }
                _state.value = RcdState.DEAD
            }, "rclone-stdout-drain").apply {
                isDaemon = true
                start()
            }

            Unit
        }
    }

    private fun locateRcloneBinary(): File {
        val candidates = buildList {
            add(File(configManager.binDir, BINARY_NAME))
            val nativeLibDir = app.applicationInfo.nativeLibraryDir
            if (nativeLibDir.isNotBlank()) {
                add(File(nativeLibDir, BINARY_LIB_NAME))
                add(File(nativeLibDir, BINARY_NAME))
            }
        }

        val found = candidates.firstOrNull { it.exists() && it.isFile }
            ?: throw IllegalStateException(
                "rclone binary not found. Tried: ${candidates.joinToString { it.absolutePath }}. " +
                    "Run scripts/build-rclone.sh first; see README sync section."
            )

        if (!found.canExecute()) {
            found.setExecutable(true, true)
        }
        return found
    }

    private suspend fun ping(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = process ?: return@withContext Result.failure(IllegalStateException("no process"))
            if (!p.isAlive) return@withContext Result.failure(IllegalStateException("process dead"))

            val url = URL("http://127.0.0.1:$port/rc/noop")
            val conn = (url.openConnection() as HttpURLConnection).apply {
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

    private suspend fun invokeRc(op: String, params: JsonObject): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = process ?: throw IllegalStateException("no rcd process")
                if (!p.isAlive) throw IOException("rcd process died")

                val url = URL("http://127.0.0.1:$port/$op")
                val conn = (url.openConnection() as HttpURLConnection).apply {
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
                    val jsonObj = parsed as? JsonObject
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

    private fun pickFreePort(): Int = ServerSocket().use { s ->
        s.bind(InetSocketAddress("127.0.0.1", 0))
        s.localPort
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    enum class RcdState { STOPPED, STARTING, READY, DEAD }

    companion object {
        private const val BINARY_NAME = "rclone"
        private const val BINARY_LIB_NAME = "librclone.so"
        private const val RCD_READY_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 300L
        private const val PING_TIMEOUT_MS = 2_000L
        private const val RC_CALL_CONNECT_TIMEOUT_MS = 30_000L
        private const val RC_CALL_READ_TIMEOUT_MS = 600_000L
    }
}
