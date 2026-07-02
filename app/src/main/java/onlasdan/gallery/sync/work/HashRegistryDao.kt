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

package onlasdan.gallery.sync.work

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO for the local cache of the dedup registry ([HashRegistryEntry]).
 *
 * The remote-of-truth is the encrypted `registry.json.crypt` artifact; this
 * table is just a queryable cache so the upload worker can do fast
 * "is this hash already on the remote?" lookups without downloading +
 * decrypting the registry on every photo. It is fully rebuilt from the
 * remote on every successful login (see [HashRegistry.downloadAndCache]).
 *
 * @since v9 — dedup + encrypted GCM registry
 */
@Dao
interface HashRegistryDao {
    @Query("SELECT * FROM hash_registry WHERE content_hash = :hash AND deleted = 0 LIMIT 1")
    suspend fun findByHash(hash: String): HashRegistryEntry?

    @Query("SELECT * FROM hash_registry WHERE deleted = 0")
    suspend fun getAll(): List<HashRegistryEntry>

    @Upsert
    suspend fun upsert(entry: HashRegistryEntry)

    @Upsert
    suspend fun upsertAll(entries: List<HashRegistryEntry>)

    @Query("SELECT COUNT(*) FROM hash_registry WHERE deleted = 0")
    suspend fun count(): Int

    @Query("DELETE FROM hash_registry")
    suspend fun clear()
}
