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
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user-supplied `rclone.conf` for the sync feature.
 *
 * The **entire** rclone.conf file is copied byte-for-byte to app-private storage. We do NOT
 * filter, rewrite, or extract individual sections — critical for rclone remote types like
 * `union`, `crypt`, `combine`, `chunker` which reference OTHER remotes by name.
 *
 * The user's chosen remote name (the `[section]` they picked from the picker) is stored as
 * a single string in [Config.syncChosenRemote].
 *
 * Validation (relaxed): file must contain at least one `[section]` header. Per-section
 * `type=` presence is informational (surfaced in [RemoteInfo]), not a hard reject.
 */
@Singleton
class RcloneConfigManager
        @Inject
        constructor(
                private val app: Application,
                private val config: Config,
                private val rcloneController: RcloneController,
        ) {
                data class RemoteInfo(
                        val name: String,
                        val type: String?,
                )

                sealed class Status {
                        object NotConfigured : Status()

                        data class Configured(
                                val remoteName: String,
                        ) : Status()

                        data class AwaitingRemoteChoice(
                                val availableRemotes: List<RemoteInfo>,
                        ) : Status()

                        data class Invalid(
                                val reason: InvalidReason,
                        ) : Status()

                        enum class InvalidReason {
                                NO_SECTIONS,
                                UNREADABLE,
                        }
                }

                val configFile: File?
                        get() = targetFile().takeIf { it.exists() }

                /** Canonical config path, even before the file is imported. */
                val configFilePath: String
                        get() = targetFile().absolutePath

                val binDir: File
                        get() = File(app.filesDir, BIN_DIR_NAME).also { it.mkdirs() }

                suspend fun import(sourceUri: Uri): Result<Unit> =
                        withContext(Dispatchers.IO) {
                                runCatching {
                                        val targetFile = targetFile()
                                        targetFile.parentFile?.mkdirs()
                                        val tempFile = File(app.filesDir, CONFIG_TEMP_NAME)
                                        tempFile.parentFile?.mkdirs()

                                        val raw = readUriToBytes(sourceUri).getOrThrow()
                                        val parsed = parseConfig(String(raw, Charsets.UTF_8))
                                        validate(parsed)

                                        tempFile.outputStream().use { it.write(raw) }
                                        if (!tempFile.renameTo(targetFile)) {
                                                tempFile.delete()
                                                throw IOException("Failed to commit rclone.conf (rename failed)")
                                        }
                                        Timber.i("rclone.conf imported: ${parsed.sections.size} section(s): ${parsed.sections.keys}")
                                }
                        }

                suspend fun clear(): Result<Unit> =
                        withContext(Dispatchers.IO) {
                                runCatching {
                                        configFile?.delete()
                                        config.syncChosenRemote = null
                                        // F-SYNC-006: invalidate rclone's in-memory cached config so the
                                        // next operation re-reads the (possibly new) config file. Without
                                        // this, rclone keeps using the old config until process restart.
                                        rcloneController.invalidateConfigPath()
                                        Unit
                                }
                        }

                suspend fun isConfigured(): Boolean =
                        withContext(Dispatchers.IO) {
                                currentStatus() is Status.Configured
                        }

                suspend fun currentStatus(): Status =
                        withContext(Dispatchers.IO) {
                                val f = configFile ?: return@withContext Status.NotConfigured
                                val parsed =
                                        runCatching { parseConfig(f.readText()) }.getOrElse {
                                                return@withContext Status.Invalid(Status.InvalidReason.UNREADABLE)
                                        }
                                if (parsed.sections.isEmpty()) {
                                        return@withContext Status.Invalid(Status.InvalidReason.NO_SECTIONS)
                                }

                                val chosen = config.syncChosenRemote
                                if (chosen != null && parsed.sections.containsKey(chosen)) {
                                        Status.Configured(remoteName = chosen)
                                } else {
                                        Status.AwaitingRemoteChoice(availableRemotes = parsed.toRemoteInfos())
                                }
                        }

                suspend fun availableRemotes(): List<RemoteInfo> =
                        withContext(Dispatchers.IO) {
                                val f = configFile ?: return@withContext emptyList()
                                runCatching {
                                        parseConfig(f.readText()).toRemoteInfos()
                                }.getOrDefault(emptyList())
                        }

                suspend fun chooseRemote(name: String): Boolean =
                        withContext(Dispatchers.IO) {
                                val parsed = runCatching { parseConfig(configFile?.readText() ?: "") }.getOrNull()
                                if (parsed?.sections?.containsKey(name) != true) {
                                        return@withContext false
                                }
                                config.syncChosenRemote = name
                                true
                        }

                private fun targetFile(): File = File(app.filesDir, CONFIG_FILE_NAME)

                private suspend fun readUriToBytes(uri: Uri): Result<ByteArray> =
                        withContext(Dispatchers.IO) {
                                runCatching {
                                        app.contentResolver.openInputStream(uri).use { stream ->
                                                stream ?: throw IOException("Cannot open input stream for $uri")
                                                stream.readBytes()
                                        }
                                }
                        }

                fun toInvalidReason(e: Throwable): Status.InvalidReason =
                        when {
                                e is IOException -> Status.InvalidReason.UNREADABLE
                                e.message?.contains("no [section] headers", ignoreCase = true) == true ->
                                        Status.InvalidReason.NO_SECTIONS
                                else -> Status.InvalidReason.UNREADABLE
                        }

                private fun parseConfig(text: String): ParsedConfig {
                        val sections = LinkedHashMap<String, Map<String, String>>()
                        var currentName: String? = null
                        val current = LinkedHashMap<String, String>()

                        fun flush() {
                                currentName?.let { name ->
                                        sections[name] = LinkedHashMap(current)
                                }
                                current.clear()
                        }

                        text.lineSequence().forEach { rawLine ->
                                val line = rawLine.trim()
                                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                                        return@forEach
                                }
                                if (line.startsWith("[") && line.endsWith("]")) {
                                        flush()
                                        currentName = line.substring(1, line.length - 1).trim()
                                        return@forEach
                                }
                                val eq = line.indexOf('=')
                                if (eq > 0 && currentName != null) {
                                        val k = line.substring(0, eq).trim()
                                        val v = line.substring(eq + 1).trim()
                                        current[k] = v
                                }
                        }
                        flush()

                        return ParsedConfig(sections = sections)
                }

                private fun validate(parsed: ParsedConfig) {
                        require(parsed.sections.isNotEmpty()) {
                                "rclone.conf has no [section] headers"
                        }
                }

                private fun ParsedConfig.toRemoteInfos(): List<RemoteInfo> =
                        sections.entries.map { (name, kv) ->
                                RemoteInfo(name = name, type = kv["type"])
                        }

                private data class ParsedConfig(
                        val sections: Map<String, Map<String, String>>,
                )

                companion object {
                        private const val CONFIG_FILE_NAME = "rclone/rclone.conf"
                        private const val CONFIG_TEMP_NAME = "rclone/rclone.conf.tmp"
                        private const val BIN_DIR_NAME = "rclone/bin"
                }
        }
