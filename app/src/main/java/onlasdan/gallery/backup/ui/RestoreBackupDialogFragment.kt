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

package onlasdan.gallery.backup.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.R
import onlasdan.gallery.review.InAppReview
import onlasdan.gallery.review.ReviewTrigger
import onlasdan.gallery.ui.theme.AppTheme
import javax.inject.Inject

/**
 * Sprint 10+ / M1 — RestoreBackupDialogFragment migrated to Compose.
 *
 * Previously extended [BindableDialogFragment] with XML layout
 * (dialog_restore_backup.xml) + ViewBinding. Now extends [DialogFragment]
 * and hosts a [ComposeView] that renders an [AlertDialog] whose content
 * switches based on [RestoreBackupViewModel.restoreState].
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@AndroidEntryPoint
class RestoreBackupDialogFragment : DialogFragment() {
	private val viewModel: RestoreBackupViewModel by viewModels()

	@Inject
	lateinit var inAppReview: InAppReview

	private val uri: Uri by lazy(LazyThreadSafetyMode.NONE) {
		@Suppress("DEPRECATION")
		requireArguments().getParcelable(ARG_URI)!!
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		ComposeView(requireContext()).apply {
			setContent {
				AppTheme {
					val state by viewModel.restoreState.collectAsStateWithLifecycle()

					LaunchedEffect(Unit) {
						viewModel.zipFileName = getString(R.string.backup_restore_validating)
						viewModel.loadAndValidateBackup(uri)
					}

					RestoreBackupDialogContent(
						state = state,
						zipFileName = viewModel.zipFileName,
						onRestore = { onRestoreAndUnlock() },
						onClose = {
							if (state == RestoreState.FINISHED) {
								inAppReview.requestInAppReview(requireActivity(), ReviewTrigger.BackupRestored)
							}
							dismiss()
						},
					)
				}
			}
		}

	fun onRestoreAndUnlock() {
		val unlockDialog =
			UnlockBackupDialogFragment(
				uri = uri,
				metaData = viewModel.metaData!!,
			) { session ->
				viewModel.restoreBackup(session)
			}
		unlockDialog.show(requireActivity().supportFragmentManager, "unlock_backup")
	}

	companion object {
		private const val ARG_URI = "uri"

		fun newInstance(uri: Uri): RestoreBackupDialogFragment =
			RestoreBackupDialogFragment().apply {
				arguments =
					Bundle().apply {
						putParcelable(ARG_URI, uri)
					}
			}
	}
}

@Composable
private fun RestoreBackupDialogContent(
	state: RestoreState,
	zipFileName: String,
	onRestore: () -> Unit,
	onClose: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onClose,
		title = { Text(stringResource(R.string.backup_restore_title)) },
		text = {
			when (state) {
				RestoreState.INITIALIZE -> {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						CircularProgressIndicator()
						Text(
							text = stringResource(R.string.backup_restore_validating),
							modifier = Modifier.padding(top = 12.dp),
						)
					}
				}
				RestoreState.FILE_VALID -> {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(
							text = zipFileName,
							style = MaterialTheme.typography.bodyLarge,
						)
						Text(
							text = stringResource(R.string.backup_restore_details_created_at),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
				RestoreState.FILE_INVALID -> {
					Text(
						text = stringResource(R.string.backup_restore_invalid_file),
						color = MaterialTheme.colorScheme.error,
					)
				}
				RestoreState.RESTORING -> {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						CircularProgressIndicator()
						Text(
							text = stringResource(R.string.backup_restore_processing),
							modifier = Modifier.padding(top = 12.dp),
						)
					}
				}
				RestoreState.FINISHED -> {
					Text(text = stringResource(R.string.process_finished))
				}
				RestoreState.FINISHED_WITH_ERRORS -> {
					Text(
						text = stringResource(R.string.process_failures_occurred),
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
		},
		confirmButton = {
			when (state) {
				RestoreState.FILE_VALID -> {
					TextButton(onClick = onRestore) {
						Text(stringResource(R.string.backup_restore_button))
					}
				}
				RestoreState.FILE_INVALID,
				RestoreState.FINISHED,
				RestoreState.FINISHED_WITH_ERRORS,
				-> {
					TextButton(onClick = onClose) {
						Text(stringResource(R.string.process_close))
					}
				}
				else -> {} // INITIALIZE + RESTORING: no button
			}
		},
	)
}
