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
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprint 8 / TODO #15 — TFLite-based semantic tag extraction.
 *
 * Uses MobileNet V2 to classify photos into semantic tags. The model file
 * is NOT bundled in the APK (saves ~5MB) — the user downloads it from
 * Settings → Semantic Search → Download Model.
 *
 * ## Model file
 * - Location: `app.filesDir/models/mobilenet_v2.tflite`
 * - Size: ~5MB
 * - Input: 224×224 RGB bitmap, normalized to [-1, 1]
 * - Output: 1001 class probabilities (ImageNet labels)
 *
 * ## Labels
 * A subset of ImageNet labels is bundled in assets (`mobilenet_labels.txt`).
 * Only the top-K most useful labels for photo search are kept (people,
 * animals, food, nature, objects) — the full 1001 labels include many
 * obscure categories that aren't useful for photo search.
 *
 * ## Privacy
 * All inference runs ON-DEVICE. No photo bytes leave the phone.
 *
 * @since Sprint 8 — TFLite activation
 */
@Singleton
class TfliteTagExtractor
	@Inject
	constructor() : TagExtractor {
		companion object {
			private const val MODEL_FILENAME = "models/mobilenet_v2.tflite"
			private const val INPUT_IMAGE_SIZE = 224
			private const val PIXEL_SIZE = 3 // RGB
			// Number of top predictions to keep as tags
			private const val TOP_K = 5
			// Minimum confidence threshold (0-1)
			private const val MIN_CONFIDENCE = 0.3f
		}

		private var interpreter: Interpreter? = null
		private var labels: List<String> = emptyList()

		/**
		 * Check if the TFLite model file exists in internal storage.
		 * Used by Settings UI to show download status.
		 */
		fun isModelAvailable(context: Context): Boolean {
			return File(context.filesDir, MODEL_FILENAME).exists()
		}

		/**
		 * Get the model file path (for download target).
		 */
		fun getModelFile(context: Context): File {
			return File(context.filesDir, MODEL_FILENAME)
		}

		@Synchronized
		private fun ensureInterpreter(context: Context): Boolean {
			if (interpreter != null) return true

			val modelFile = File(context.filesDir, MODEL_FILENAME)
			if (!modelFile.exists()) {
				Timber.d("TfliteTagExtractor: model file not found — returning null")
				return false
			}

			return try {
				val options = Interpreter.Options().setNumThreads(2)
				interpreter = Interpreter(modelFile, options)
				labels = loadLabels(context)
				Timber.d("TfliteTagExtractor: model loaded (${modelFile.length()} bytes, ${labels.size} labels)")
				true
			} catch (e: Exception) {
				Timber.e(e, "TfliteTagExtractor: failed to load model")
				false
			}
		}

		private fun loadLabels(context: Context): List<String> {
			return try {
				context.assets.open("mobilenet_labels.txt").bufferedReader().readLines()
					.filter { it.isNotBlank() }
			} catch (e: Exception) {
				Timber.w(e, "TfliteTagExtractor: failed to load labels — using empty list")
				emptyList()
			}
		}

		override fun extractTags(
			context: Context,
			uri: Uri,
		): String? {
			if (!ensureInterpreter(context)) return null

			val bitmap = try {
				loadBitmap(context, uri)
			} catch (e: Exception) {
				Timber.w(e, "TfliteTagExtractor: failed to load bitmap from %s", uri)
				return null
			}

			val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, true)
			val inputBuffer = bitmapToByteBuffer(scaledBitmap)

			val outputSize = labels.size.coerceAtLeast(1)
			val outputBuffer = Array(1) { FloatArray(outputSize) }

			try {
				interpreter?.run(inputBuffer, outputBuffer)
			} catch (e: Exception) {
				Timber.e(e, "TfliteTagExtractor: inference failed")
				return null
			}

			val predictions = outputBuffer[0]
			val taggedPredictions = predictions.mapIndexed { index, confidence ->
				index to confidence
			}.filter { it.second >= MIN_CONFIDENCE }
				.sortedByDescending { it.second }
				.take(TOP_K)

			val tags = taggedPredictions.mapNotNull { (index, _) ->
				labels.getOrNull(index)?.lowercase()?.replace(" ", "_")
			}

			Timber.d("TfliteTagExtractor: extracted %d tags from %s: %s", tags.size, uri, tags.joinToString(","))
			return if (tags.isNotEmpty()) tags.joinToString(",") else null
		}

		private fun loadBitmap(context: Context, uri: Uri): Bitmap {
			return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				val source = ImageDecoder.createSource(context.contentResolver, uri)
				ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
					decoder.setMutableRequired(true)
				}
			} else {
				@Suppress("DEPRECATION")
				MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
			}
		}

		private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
			val byteBuffer =
				ByteBuffer.allocateDirect(4 * INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE * PIXEL_SIZE)
			byteBuffer.order(ByteOrder.nativeOrder())

			val intValues = IntArray(INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE)
			bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

			var pixel = 0
			for (i in 0 until INPUT_IMAGE_SIZE) {
				for (j in 0 until INPUT_IMAGE_SIZE) {
					val pixelVal = intValues[pixel++]
					// Normalize to [-1, 1] (MobileNet V2 preprocessing)
					byteBuffer.putFloat(((pixelVal shr 16 and 0xFF) - 127.5f) / 127.5f)
					byteBuffer.putFloat(((pixelVal shr 8 and 0xFF) - 127.5f) / 127.5f)
					byteBuffer.putFloat(((pixelVal and 0xFF) - 127.5f) / 127.5f)
				}
			}
			return byteBuffer
		}

		/**
		 * Release the TFLite interpreter to free native memory.
		 * Called when the user disables semantic search or the app goes to background.
		 */
		@Synchronized
		fun release() {
			interpreter?.close()
			interpreter = null
			Timber.d("TfliteTagExtractor: interpreter released")
		}
	}
