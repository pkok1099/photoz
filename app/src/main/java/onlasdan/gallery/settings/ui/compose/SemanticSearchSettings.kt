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

package onlasdan.gallery.settings.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import onlasdan.gallery.settings.data.Config
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sprint 8 / TODO #15 — Semantic Search settings section.
 *
 * Shows:
 * - Toggle to enable/disable on-device TFLite tag inference
 * - Download button for the model file (~5MB, not bundled in APK)
 * - Status indicator (not downloaded / downloading / ready)
 *
 * @since Sprint 8 — TFLite activation
 */
@Composable
fun SemanticSearchSettings(
	config: Config,
	onToggleChanged: (Boolean) -> Unit,
) {
	val context = LocalContext.current
	val modelFile = remember { File(context.filesDir, "models/mobilenet_v2.tflite") }
	var modelAvailable by remember { mutableStateOf(modelFile.exists()) }
	var isDownloading by remember { mutableStateOf(config.tfliteModelStatus == 1) }
	var downloadError by remember { mutableStateOf<String?>(null) }

	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
		) {
			Text(
				text = "Semantic Search",
				style = MaterialTheme.typography.titleMedium,
			)
			Spacer(modifier = Modifier.height(8.dp))
			Text(
				text = "On-device AI tag extraction using MobileNet V2. " +
					"Generates tags like 'beach', 'sunset', 'dog' for each photo. " +
					"All processing happens on your device — no data leaves your phone.",
				fontSize = 14.sp,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(modifier = Modifier.height(16.dp))

			// Enable/disable toggle
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(text = "Enable semantic search")
				Switch(
					checked = config.semanticSearchEnabled,
					onCheckedChange = onToggleChanged,
					enabled = modelAvailable,
				)
			}

			if (!modelAvailable) {
				Text(
					text = "Download the AI model (~5MB) to enable semantic search.",
					fontSize = 13.sp,
					color = MaterialTheme.colorScheme.secondary,
					modifier = Modifier.padding(top = 4.dp),
				)
			}

			Spacer(modifier = Modifier.height(12.dp))

			// Download button / status
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				when {
					isDownloading -> {
						CircularProgressIndicator(modifier = Modifier.height(24.dp))
						Text(text = "Downloading model...", fontSize = 14.sp)
					}
					modelAvailable -> {
						Text(
							text = "✓ Model ready (${File(context.filesDir, "models/mobilenet_v2.tflite").length() / 1024 / 1024}MB)",
							fontSize = 14.sp,
							color = MaterialTheme.colorScheme.primary,
						)
					}
					else -> {
						Button(
							onClick = {
								isDownloading = true
								downloadError = null
								config.tfliteModelStatus = 1
								downloadModel(
									context = context,
									url = config.tfliteModelUrl,
									onSuccess = {
										isDownloading = false
										modelAvailable = true
										config.tfliteModelStatus = 2
										Timber.d("SemanticSearchSettings: model downloaded successfully")
									},
									onError = { error ->
										isDownloading = false
										downloadError = error
										config.tfliteModelStatus = 0
										Timber.e("SemanticSearchSettings: model download failed: $error")
									},
								)
							},
						) {
							Text("Download Model")
						}
					}
				}
			}

			downloadError?.let { error ->
				Text(
					text = "Download failed: $error",
					fontSize = 13.sp,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(top = 8.dp),
				)
			}
		}
	}
}

/**
 * Download the TFLite model file in a background thread.
 */
private fun downloadModel(
	context: Context,
	url: String,
	onSuccess: () -> Unit,
	onError: (String) -> Unit,
) {
	Thread {
		try {
			val modelFile = File(context.filesDir, "models/mobilenet_v2.tflite")
			modelFile.parentFile?.mkdirs()

			val connection = URL(url).openConnection() as HttpURLConnection
			connection.connectTimeout = 30000
			connection.readTimeout = 60000
			connection.connect()

			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				throw Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}")
			}

			modelFile.outputStream().use { output ->
				connection.inputStream.use { input ->
					input.copyTo(output)
				}
			}
			connection.disconnect()

			if (modelFile.exists() && modelFile.length() > 0) {
				onSuccess()
			} else {
				onError("Model file is empty")
			}
		} catch (e: Exception) {
			onError(e.message ?: "Unknown error")
		}
	}.start()
}
