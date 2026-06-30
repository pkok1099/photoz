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

package dev.leonlatsch.photok.transcoding.compose.model

data class EncryptedImageRequestData(
    val internalFileName: String,
    val mimeType: String,
    val playAnimation: Boolean = false,

    /**
     * UUID of the photo this request refers to, used by on-demand cloud-sync restore.
     *
     * - **Thumbnails / album covers / video previews**: leave as `null`. These artifacts are
     *   always kept local; they never get evicted, so restore is a no-op.
     * - **Full-size originals in the image viewer**: set to the photo's UUID. If the local
     *   original is missing (because it was uploaded and the local copy was evicted),
     *   EncryptedImageFetcher will trigger a download from the rclone remote before reading.
     *
     * @since PR1 sync feature
     */
    val photoUuid: String? = null,
)
