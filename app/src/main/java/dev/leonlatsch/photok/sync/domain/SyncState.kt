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

package dev.leonlatsch.photok.sync.domain

enum class SyncState(val storageKey: String) {
    LOCAL_ONLY("LOCAL_ONLY"),
    UPLOAD_PENDING("UPLOAD_PENDING"),
    UPLOADED("UPLOADED"),
    UPLOAD_FAILED("UPLOAD_FAILED");

    companion object {
        fun fromStorageKey(key: String?): SyncState =
            entries.firstOrNull { it.storageKey == key } ?: LOCAL_ONLY
    }
}
