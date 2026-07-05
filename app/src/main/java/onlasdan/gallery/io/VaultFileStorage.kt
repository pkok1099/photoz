/*
 *   Copyright 2020-2026 PhotoZ
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

package onlasdan.gallery.io

import android.app.Application
import android.content.Context
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.crypto.CryptoEngine
import timber.log.Timber
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.inject.Inject

class VaultFileStorage @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val cryptoEngine: CryptoEngine,
    private val app: Application,
) {
    fun openEncryptedInput(filename: String): InputStream? = try {
        val session = sessionRepository.require()
        val input = app.openFileInput(filename)
        cryptoEngine.createDecryptStream(input, session)
    } catch (e: Exception) {
        Timber.e(e)
        null
    }

    fun openEncryptedOutput(
        fileName: String,
        useGcm: Boolean = true,
    ): OutputStream? = try {
        val session = sessionRepository.require()
        val output = app.openFileOutput(fileName, Context.MODE_PRIVATE)
        cryptoEngine.createEncryptStream(output, session, useGcm = useGcm)
    } catch (e: Exception) {
        Timber.e(e)
        null
    }

    fun deleteEncryptedFile(fileName: String): Boolean {
        val success = app.deleteFile(fileName)
        if (!success) {
            Timber.e("Error deleting internal file: $fileName")
        }

        return success
    }

    fun renameEncryptedFile(currentFileName: String, newFileName: String): Boolean {
        val currentFile = app.getFileStreamPath(currentFileName)
        val newFile = app.getFileStreamPath(newFileName)
        return currentFile.renameTo(newFile)
    }
}