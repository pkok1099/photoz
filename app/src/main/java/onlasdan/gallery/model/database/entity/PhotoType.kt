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
	val mimeType: String,
	/**
	 * Canonical file extension (without leading dot) used when re-hydrating the
	 * plaintext file for external viewing. Empty for [UNDEFINED].
	 *
	 * @since file-upload feature — DOCUMENT / ARCHIVE / AUDIO types
	 */
	val fileExtension: String = String.empty,
) {
	UNDEFINED(0, String.empty),
	PNG(1, "image/png", "png"),
	JPEG(2, "image/jpeg", "jpg"),
	GIF(3, "image/gif", "gif"),
	MP4(4, "video/mp4", "mp4"),
	MPEG(5, "video/mpeg", "mpeg"),
	WEBM(6, "video/webm", "webm"),
	MOV(7, "video/quicktime", "mov"),
	WEBP(8, "image/webp", "webp"),
	MKV(9, "video/x-matroska", "mkv"),
	HEIC(10, "image/heic", "heic"),

	// ─── File upload feature — generic file types ───────────────────────────
	// These are NOT photo/video: they can't be rendered by the in-app image
	// viewer. Tapping one in the gallery decrypts it to a cache file and hands
	// it off to Android via `Intent.ACTION_VIEW` so the user can open it with
	// whatever external app they have installed for that mime type.
	//
	// The `isFile` property is the runtime discriminator: gallery UI uses it
	// to route taps to the external viewer instead of the in-app image viewer,
	// and the gallery filter chip uses it to split "Photos" vs "Files" views.
	//
	// @since file-upload feature
	DOCUMENT(11, "application/pdf", "pdf"),
	ARCHIVE(12, "application/zip", "zip"),
	AUDIO(13, "audio/*", "mp3"),
	;

	val isVideo: Boolean
		get() =
			when (value) {
				4, 5, 6, 7, 9 -> true
				else -> false
			}

	/**
	 * `true` for non-photo/non-video file types (DOCUMENT, ARCHIVE, AUDIO).
	 * These types are stored and encrypted exactly like photos, but the in-app
	 * image viewer can't render them — tapping one routes to an external viewer
	 * via [Intent.ACTION_VIEW].
	 *
	 * @since file-upload feature
	 */
	val isFile: Boolean
		get() =
			when (value) {
				11, 12, 13 -> true
				else -> false
			}

	companion object {
		/**
		 * Create a [PhotoType] from its Int value.
		 * Used in converters.
		 */
		fun fromValue(value: Int) = entries.first { it.value == value }

		fun fromMimeType(mimeType: String?): PhotoType =
			when (mimeType) {
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
				DOCUMENT.mimeType -> DOCUMENT
				ARCHIVE.mimeType -> ARCHIVE
				// Audio — accept any `audio/*` mime type, normalizing to the single
				// AUDIO enum entry. The original mime type is preserved on the
				// Photo via the stored `type` (which is AUDIO) and re-expanded to a
				// real mime type at view-time by inspecting the file extension.
				else -> {
					if (mimeType != null && mimeType.startsWith("audio/")) {
						AUDIO
					} else {
						UNDEFINED
					}
				}
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
