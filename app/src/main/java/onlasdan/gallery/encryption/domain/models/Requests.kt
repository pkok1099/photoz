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

package onlasdan.gallery.encryption.domain.models

import androidx.fragment.app.Fragment
import onlasdan.gallery.encryption.domain.crypto.Bip39WordCount
import onlasdan.gallery.encryption.domain.models.RecoveryPhrase as Phrase

sealed interface UnlockRequest {
    val protectionType: VaultProtectionType

    data class Password(val password: String) : UnlockRequest {
        override val protectionType = VaultProtectionType.Password
    }

    data class Biometric(val fragment: Fragment) : UnlockRequest {
        override val protectionType = VaultProtectionType.Biometric
    }

    data class RecoveryPhrase(val phrase: Phrase) : UnlockRequest {
        override val protectionType = VaultProtectionType.RecoveryPhrase
    }
}

sealed interface CreateRequest {
    val protectionType: VaultProtectionType

    data class Password(val password: String) : CreateRequest {
        override val protectionType = VaultProtectionType.Password
    }

    data class Biometric(
        val session: VaultSession,
        val fragment: Fragment,
    ) : CreateRequest {
        override val protectionType = VaultProtectionType.Biometric
    }

    data class RecoveryPhrase(
        val session: VaultSession,
        val wordCount: Bip39WordCount,
    ) : CreateRequest {
        override val protectionType = VaultProtectionType.RecoveryPhrase
    }
}