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

package onlasdan.gallery.encryption.domain.handlers

import onlasdan.gallery.encryption.domain.models.CreateRequest
import onlasdan.gallery.encryption.domain.models.UnlockRequest
import onlasdan.gallery.encryption.domain.models.VaultProtection
import javax.crypto.SecretKey

interface VaultProtectionHandler<URT : UnlockRequest, CRT : CreateRequest> {
	suspend fun unlock(
		request: URT,
		protection: VaultProtection,
	): SecretKey

	suspend fun create(request: CRT): VaultProtection

	suspend fun canMigrate(): Boolean

	suspend fun migrate(request: URT): VaultProtection

	fun onMigrationPersisted() {
		// No-op by default; handlers with migration side-effects override this.
	}

	suspend fun reset()
}
