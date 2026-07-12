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

package onlasdan.gallery.gallery.ui

import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.io.formatDateTakenForSearch
import java.util.Calendar
import java.util.Locale

/**
 * Sprint 6 / M4 — Parsed search query for the gallery.
 *
 * The gallery search bar accepts plain text (filename contains, case-insensitive)
 * OR prefixed queries that target EXIF metadata:
 *
 *  - `date:2024` — photos taken in 2024 (matches EXIF DateTimeOriginal year)
 *  - `date:2024-01` — photos taken in January 2024
 *  - `date:2024-01-15` — photos taken on January 15, 2024
 *  - `camera:Canon` — photos taken with a Canon camera (case-insensitive contains
 *    on the EXIF Make+Model field)
 *  - `location:-6.2,106.8` — photos taken near the given lat,lon (within ~50km —
 *    a coarse match; precise radius search is a future enhancement)
 *
 * Multiple prefixes can be combined: `date:2024-01 camera:Canon beach` matches
 * photos taken in January 2024 with a Canon camera whose filename contains
 * "beach". Plain-text tokens (no prefix) are treated as filename-contains.
 *
 * Backwards compatible: a query with no recognized prefixes falls back to the
 * pre-M4 behavior (filename contains, case-insensitive).
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
data class SearchQuery(
	/** Plain-text tokens (no prefix) — matched against filename, case-insensitive contains. */
	val textTokens: List<String> = emptyList(),
	/** Date prefix (e.g. "2024-01"). Empty string means no date filter. */
	val datePrefix: String = "",
	/** Camera prefix (e.g. "Canon"). Empty string means no camera filter. */
	val cameraPrefix: String = "",
	/** Location prefix as (lat, lon). Null means no location filter. */
	val location: Pair<Double, Double>? = null,
	/**
	 * Sprint 9 / L6 — AI tag prefix (e.g. "beach").
	 * Empty string means no tag filter.
	 * @since v14 — Sprint 9 / L6 semantic search
	 */
	val tagPrefix: String = "",
) {
	/** True when the query has no filters at all (empty search bar). */
	val isEmpty: Boolean get() =
		textTokens.isEmpty() &&
			datePrefix.isEmpty() &&
			cameraPrefix.isEmpty() &&
			location == null &&
			tagPrefix.isEmpty()
}

/**
 * Parse a raw search string into a [SearchQuery].
 *
 * Tokenizes on whitespace, then classifies each token by prefix:
 *  - `date:` → date prefix (last one wins if multiple)
 *  - `camera:` → camera prefix (last one wins)
 *  - `location:` → lat,lon pair (last one wins)
 *  - anything else → plain-text token (filename contains)
 *
 * Prefix matching is case-insensitive (`DATE:2024` works the same as `date:2024`).
 * Tokens without a value after the prefix (e.g. bare `date:`) are ignored.
 *
 * Example:
 * ```
 * parseSearchQuery("beach date:2024-01 camera:canon")
 * // → SearchQuery(textTokens=["beach"], datePrefix="2024-01", cameraPrefix="canon")
 * ```
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
fun parseSearchQuery(raw: String): SearchQuery {
	if (raw.isBlank()) return SearchQuery()

	val textTokens = mutableListOf<String>()
	var datePrefix = ""
	var cameraPrefix = ""
	var location: Pair<Double, Double>? = null
	var tagPrefix = ""

	for (token in raw.trim().split(Regex("\\s+"))) {
		if (token.isEmpty()) continue
		val colonIdx = token.indexOf(':')
		if (colonIdx <= 0) {
			// No prefix — plain text token.
			textTokens.add(token)
			continue
		}
		val prefix = token.substring(0, colonIdx).lowercase(Locale.US)
		val value = token.substring(colonIdx + 1)
		if (value.isEmpty()) continue

		when (prefix) {
			"date" -> datePrefix = value
			"camera" -> cameraPrefix = value
			"tag" -> tagPrefix = value
			"location" -> {
				val parts = value.split(",")
				if (parts.size == 2) {
					val lat = parts[0].trim().toDoubleOrNull()
					val lon = parts[1].trim().toDoubleOrNull()
					if (lat != null && lon != null) {
						location = lat to lon
					}
				}
			}
			else -> {
				// Unknown prefix — treat the whole token as plain text so
				// the user doesn't lose their query if they typo a prefix.
				textTokens.add(token)
			}
		}
	}

	return SearchQuery(
		textTokens = textTokens,
		datePrefix = datePrefix,
		cameraPrefix = cameraPrefix,
		location = location,
		tagPrefix = tagPrefix,
	)
}

/**
 * Apply a parsed [SearchQuery] to a list of photos.
 *
 * Returns only the photos that match ALL active filters:
 *  - Each text token must be a case-insensitive substring of the filename.
 *  - The date prefix must match the photo's EXIF date (formatted as "yyyy-MM-dd").
 *  - The camera prefix must be a case-insensitive substring of the EXIF camera.
 *  - The location must be within ~50km of the photo's EXIF GPS coordinates.
 *
 * Photos with NULL EXIF fields are excluded from EXIF-prefixed matches (a
 * photo with no GPS won't match `location:...`). This is the intended
 * behavior — the user is explicitly searching by EXIF, so non-EXIF photos
 * aren't relevant.
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
fun List<Photo>.filterBySearchQuery(query: SearchQuery): List<Photo> {
	if (query.isEmpty) return this
	return this.filter { photo ->
		if (!photoMatchesTextTokens(photo, query.textTokens)) return@filter false
		if (!photoMatchesDatePrefix(photo, query.datePrefix)) return@filter false
		if (!photoMatchesCameraPrefix(photo, query.cameraPrefix)) return@filter false
		if (!photoMatchesLocation(photo, query.location)) return@filter false
		if (!photoMatchesTagPrefix(photo, query.tagPrefix)) return@filter false
		true
	}
}


private fun photoMatchesTextTokens(photo: Photo, textTokens: List<String>): Boolean {
	if (textTokens.isEmpty()) return true
	val filenameLower = photo.fileName.lowercase(Locale.US)
	return textTokens.all { token ->
		filenameLower.contains(token.lowercase(Locale.US))
	}
}

@Suppress("SameParameterValue")
private fun photoMatchesDatePrefix(photo: Photo, datePrefix: String): Boolean {
	if (datePrefix.isEmpty()) return true
	val dateStr = formatDateTakenForSearch(photo.exifDateTaken)
	return dateStr.startsWith(datePrefix)
}

private fun photoMatchesCameraPrefix(photo: Photo, cameraPrefix: String): Boolean {
	if (cameraPrefix.isEmpty()) return true
	val camera = photo.exifCamera ?: return false
	return camera.lowercase(Locale.US).contains(cameraPrefix.lowercase(Locale.US))
}

private fun photoMatchesTagPrefix(photo: Photo, tagPrefix: String): Boolean {
	if (tagPrefix.isEmpty()) return true
	val tags = photo.aiTags ?: return false
	val tagList = tags.split(",").map { it.trim().lowercase(Locale.US) }
	val needle = tagPrefix.lowercase(Locale.US)
	return tagList.any { it.contains(needle) }
}

private fun photoMatchesLocation(photo: Photo, location: Pair<Double, Double>?): Boolean {
	if (location == null) return true
	val lat = photo.exifGpsLat ?: return false
	val lon = photo.exifGpsLon ?: return false
	val (targetLat, targetLon) = location
	val distanceM = haversineDistance(lat, lon, targetLat, targetLon)
	return distanceM <= LOCATION_MATCH_RADIUS_M
}

private fun parseLocationToken(value: String): Pair<Double, Double>? {
	val parts = value.split(",")
	if (parts.size != 2) return null
	val lat = parts[0].trim().toDoubleOrNull() ?: return null
	val lon = parts[1].trim().toDoubleOrNull() ?: return null
	return lat to lon
}

/** ~50km radius for location: prefix matches. Coarse but useful for "city-level" search. */
private const val LOCATION_MATCH_RADIUS_M = 50_000.0

/**
 * Haversine distance between two lat/lon points, in meters.
 *
 * Standard formula — used by [filterBySearchQuery] for the `location:` prefix.
 * Accurate enough for ~50km radius matching; don't use for navigation. :)
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
private fun haversineDistance(
	lat1: Double,
	lon1: Double,
	lat2: Double,
	lon2: Double,
): Double {
	val r = 6_371_000.0 // Earth radius in meters
	val dLat = Math.toRadians(lat2 - lat1)
	val dLon = Math.toRadians(lon2 - lon1)
	val a =
		Math.sin(dLat / 2) * Math.sin(dLat / 2) +
			Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
			Math.sin(dLon / 2) * Math.sin(dLon / 2)
	val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
	return r * c
}

/**
 * Sprint 6 / M4 — Helper for the search bar's placeholder/hint text.
 *
 * Returns a human-readable description of the supported search prefixes, so
 * the user discovers the feature without reading docs.
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
fun searchHintText(): String {
	val currentYear = Calendar.getInstance().get(Calendar.YEAR)
	return "Search by name, or use date:$currentYear, camera:Canon, location:lat,lon"
}
