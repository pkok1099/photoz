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

package onlasdan.gallery.encryption.data

import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.encryption.domain.models.VaultSession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(): SessionRepository {
    private var session: VaultSession? = null

    override fun set(session: VaultSession) {
        this.session = session
    }

    override fun get(): VaultSession? {
        return session
    }

    override fun require(): VaultSession {
        return session ?: error("Vault is locked")
    }

    override fun reset() {
        session = null
    }
}