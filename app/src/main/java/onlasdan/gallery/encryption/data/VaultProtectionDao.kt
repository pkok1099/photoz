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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import onlasdan.gallery.encryption.domain.models.VaultProtectionType

@Dao
interface VaultProtectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(protection: VaultProtectionTable)

    @Query("DELETE FROM vault_protection WHERE type = :type")
    suspend fun delete(type: VaultProtectionType)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(protection: VaultProtectionTable)

    @Query("SELECT * FROM vault_protection WHERE type = :type")
    suspend fun getVaultProtection(type: VaultProtectionType): VaultProtectionTable?
}