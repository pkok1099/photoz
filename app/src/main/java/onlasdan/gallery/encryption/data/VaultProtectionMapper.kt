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

import onlasdan.gallery.encryption.domain.models.VaultProtection

fun VaultProtectionTable.toDomain(): VaultProtection {
    return VaultProtection(
        id = id,
        type = type,
        wrappedVMK = wrappedVMK,
        params = params,
    )
}

fun VaultProtection.toData(): VaultProtectionTable {
    return VaultProtectionTable(
        id = id,
        type = type,
        wrappedVMK = wrappedVMK,
        params = params,
    )
}