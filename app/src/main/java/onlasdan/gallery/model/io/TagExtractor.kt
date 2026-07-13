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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprint 9 / L6 — On-device semantic tag extraction.
 *
 * Runs a TFLite image classification model (MobileNet / EfficientNet) on
 * each imported photo to generate semantic tags like "beach", "sunset",
 * "person", "dog". Tags are stored in the [Photo.aiTags] column and
 * searched via the `tag:` prefix in the gallery search bar.
 *
 * ## Current status: SCAFFOLD
 *
 * The actual TFLite inference is NOT implemented yet — this is the
 * interface + a stub that returns null tags. To activate:
 *
 *  1. Add a TFLite model to `app/src/main/assets/mobilenet_v2.tflite`
 *     (~5MB, downloadable from TensorFlow Hub)
 *  2. Add the TFLite dependency: `implementation("org.tensorflow:tensorflow-lite:2.14.0")`
 *  3. Implement [TfliteTagExtractor] that loads the model, runs inference
 *     on the photo's thumbnail bytes, and maps the top-K predictions to
 *     human-readable tags
 *  4. Wire [extractTags] to call the TFLite extractor when
 *     `config.semanticSearchEnabled` is true
 *
 * The scaffold is here so the schema, search parser, and Config setting
 * are all in place — activating the model is a self-contained follow-up
 * that doesn't require schema changes.
 *
 * ## Privacy
 *
 * All inference runs ON-DEVICE. No photo bytes ever leave the phone.
 * Tags are stored in plaintext (not encrypted with VMK) — see
 * [Photo.aiTags] doc for the rationale (search speed vs. encryption).
 *
 * @since v14 — Sprint 9 / L6 on-device semantic search
 */
fun interface TagExtractor {
	/**
	 * Extract semantic tags from a photo.
	 *
	 * @param context needed to open the URI stream.
	 * @param uri the source photo URI.
	 * @return comma-separated tags (e.g. "beach,sunset,ocean"), or null
	 *   when extraction fails or is disabled.
	 */
	fun extractTags(
		context: Context,
		uri: Uri,
	): String?
}

/**
 * Stub implementation — returns null (no tags extracted).
 *
 * Replace with [TfliteTagExtractor] once the model file is bundled.
 *
 * @since v14 — Sprint 9 / L6 on-device semantic search
 */
@Singleton
class StubTagExtractor
	@Inject
	constructor() : TagExtractor {
		override fun extractTags(
			context: Context,
			uri: Uri,
		): String? {
			Timber.d("StubTagExtractor: semantic search not yet active — returning null for %s", uri)
			return null
		}
	}
