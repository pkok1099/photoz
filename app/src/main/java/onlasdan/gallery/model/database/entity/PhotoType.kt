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

package onlasdan.gallery.model.database.entity

import onlasdan.gallery.other.extensions.empty

/**
 * Enum for [Photo.type].
 * Internal value is an [Int].
 *
 * @since 1.0.0
 * @author PhotoZ
 */
enum class PhotoType(
    val value: Int,
    val mimeType: String)
{
    UNDEFINED(0, String.empty),
    PNG(1, "image/png"),
    JPEG(2, "image/jpeg"),
    GIF(3, "image/gif"),
    MP4(4, "video/mp4"),
    MPEG(5, "video/mpeg"),
    WEBM(6, "video/webm"),
    MOV(7, "video/quicktime"),
    WEBP(8, "image/webp"),
    MKV(9, "video/x-matroska"),
    HEIC(10, "image/heic");

    val isVideo: Boolean
        get() = when (value) {
            4, 5, 6, 7, 9 -> true
            else -> false
        }

    companion object {
        /**
         * Create a [PhotoType] from its Int value.
         * Used in converters.
         */
        fun fromValue(value: Int) = entries.first { it.value == value }

        fun fromMimeType(mimeType: String?): PhotoType = when (mimeType) {
            PNG.mimeType -> PNG
            JPEG.mimeType -> JPEG
            GIF.mimeType -> GIF
            MP4.mimeType -> MP4
            MPEG.mimeType -> MPEG
            WEBM.mimeType -> WEBM
            MOV.mimeType -> MOV
            WEBP.mimeType -> WEBP
            MKV.mimeType -> MKV
            HEIC.mimeType -> HEIC
            else -> UNDEFINED
        }

        /**
         * Parse a [PhotoType] from its name (e.g. `"JPEG"`, `"MP4"`), as
         * stored in the dedup registry's per-hash `type` field. Falls back
         * to [JPEG] for unknown values — the registry is hand-rolled and
         * older entries may use slightly different naming, but JPEG is by
         * far the most common photo type so it's a safe default for the
         * gallery's "show me something" path.
         *
         * @since v9 followup — backfill Photo metadata from registry
         */
        fun fromName(name: String?): PhotoType {
            if (name.isNullOrBlank()) return JPEG
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: JPEG
        }
    }

}