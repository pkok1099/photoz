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

package onlasdan.gallery.model.io

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sprint 6 / M4 — EXIF metadata extracted from a photo at import time.
 *
 * All fields are nullable because EXIF is optional — screenshots, PDFs, audio
 * files, and photos stripped of EXIF (e.g. by social media apps before sharing)
 * will have null fields. The gallery search parser treats null fields as
 * non-matching for EXIF-prefixed queries (`date:`, `camera:`, `location:`).
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
data class ExifMetadata(
	/** EXIF DateTimeOriginal as epoch-ms. Null when no EXIF date. */
	val dateTaken: Long? = null,
	/** GPS latitude in decimal degrees. Null when no GPS. */
	val gpsLat: Double? = null,
	/** GPS longitude in decimal degrees. Null when no GPS. */
	val gpsLon: Double? = null,
	/** Camera Make + Model concatenated (e.g. "Canon EOS R6"). Null when no EXIF. */
	val camera: String? = null,
)

/**
 * Extract EXIF metadata from a photo URI at import time.
 *
 * Opens the URI's input stream just long enough to parse EXIF — does NOT
 * read the full image bytes (the ExifInterface library reads only the EXIF
 * segment, typically the first few KB). The stream is closed before return.
 *
 * Non-fatal: any exception (corrupt EXIF, unsupported format, stream error)
 * returns an empty [ExifMetadata] — the import proceeds normally, the photo
 * just has no EXIF fields populated. The gallery search parser handles nulls
 * gracefully.
 *
 * @param context needed to open the URI's input stream via ContentResolver.
 * @param uri the source photo URI (MediaStore, SAF, or Photo Picker).
 * @return extracted metadata, or all-nulls if EXIF is unavailable.
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
fun extractExifMetadata(
	context: Context,
	uri: Uri,
): ExifMetadata =
	try {
		context.contentResolver.openInputStream(uri)?.use { stream ->
			parseExif(stream)
		} ?: ExifMetadata()
	} catch (e: Exception) {
		Timber.w(e, "EXIF extraction failed for %s (non-fatal)", uri)
		ExifMetadata()
	}

/**
 * Extract EXIF from an in-memory byte array. Used by the ZIP import path
 * where the source bytes are already in memory (no URI to stream from).
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
fun extractExifMetadata(bytes: ByteArray): ExifMetadata =
	try {
		bytes.inputStream().use { stream -> parseExif(stream) }
	} catch (e: Exception) {
		Timber.w(e, "EXIF extraction from bytes failed (non-fatal)")
		ExifMetadata()
	}

private fun parseExif(stream: InputStream): ExifMetadata {
	val exif = ExifInterface(stream)

	// ─── Date taken ───────────────────────────────────────────────────────
	// EXIF DateTimeOriginal format: "2024:01:15 12:34:56" (note the colons
	// between date components — EXIF is from 1990s and uses ':' not '-' or '/').
	// We parse it to epoch-ms so the search parser can do range comparisons
	// (e.g. `date:2024-01` matches any photo taken in January 2024).
	val dateTaken =
		exif
			.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
			?.let { dateString ->
				try {
					val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
					fmt.timeZone = TimeZone.getTimeZone("UTC")
					fmt.parse(dateString)?.time
				} catch (e: Exception) {
					null
				}
			}

	// ─── GPS coordinates ──────────────────────────────────────────────────
	// ExifInterface.getLatLong() (no-arg, added in 1.1.0) returns a
	// double[] of size 2 (lat, lon) when GPS is present, null otherwise.
	// The older float[] overload is deprecated and has a different signature.
	val latLon = exif.getLatLong()
	val gpsLat = latLon?.getOrNull(0)
	val gpsLon = latLon?.getOrNull(1)

	// ─── Camera (Make + Model) ────────────────────────────────────────────
	// Concatenated as "Make Model" (e.g. "Canon EOS R6", "Apple iPhone 15 Pro").
	// Trimmed and null-coalesced so a photo with only Make (no Model) still
	// shows up under `camera:Canon`.
	val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
	val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
	val camera =
		listOf(make, model)
			.filter { it.isNotEmpty() }
			.joinToString(" ")
			.ifBlank { null }

	return ExifMetadata(
		dateTaken = dateTaken,
		gpsLat = gpsLat,
		gpsLon = gpsLon,
		camera = camera,
	)
}

/**
 * Format an epoch-ms date taken as a searchable string.
 *
 * The search parser uses this to match `date:2024-01` queries against the
 * photo's EXIF date. Returns the date in "yyyy-MM-dd HH:mm" format so the
 * user can search by year (`date:2024`), year-month (`date:2024-01`), or
 * full date (`date:2024-01-15`).
 *
 * Returns empty string when [dateTaken] is null (no EXIF date) — the search
 * parser treats empty as non-matching.
 *
 * @since v13 — Sprint 6 / M4 EXIF search
 */
fun formatDateTakenForSearch(dateTaken: Long?): String {
	if (dateTaken == null) return ""
	return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
		.format(Date(dateTaken))
}
