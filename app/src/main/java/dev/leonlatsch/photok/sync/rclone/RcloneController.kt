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
                            put("srcLeaf", localFile.name)
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
                            put("srcLeaf", remoteRelPath.removePrefix("/"))
                            put("dstFs", localFile.parentFile?.absolutePath ?: "")
                            put("dstRemote", localFile.name)
                        },
                    ).getOrThrow()
                }
                Unit
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

                    val arr = resp["hashsum"] as? JsonArray
                        ?: throw IOException("Malformed hashsum response: $resp")
                    val firstEntry = arr.firstOrNull() as? JsonObject
                        ?: throw IOException("Empty hashsum response")
                    val actualHash = firstEntry.keys.firstOrNull()
                        ?: throw IOException("Hashsum response missing hash key: $firstEntry")
                    actualHash.equals(expectedSha256, ignoreCase = true)
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
                try {
                    val body = json.encodeToString(JsonObject.serializer(), params).toByteArray()
                    conn.outputStream.use { it.write(body) }
                    val code = conn.responseCode
                    val respStream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val respText = respStream?.bufferedReader()?.use { it.readText() }
                        ?: ""
                    if (code !in 200..299) {
                        throw IOException("rclone $op failed: HTTP $code — $respText")
                    }
                    if (respText.isBlank()) {
                        JsonObject(emptyMap())
                    } else {
                        json.parseToJsonElement(respText) as? JsonObject
                            ?: throw IOException("rclone $op returned non-object: $respText")
                    }
                } finally {
                    conn.disconnect()
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
