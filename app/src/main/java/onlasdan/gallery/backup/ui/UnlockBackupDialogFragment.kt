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
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.BR
import onlasdan.gallery.R
import onlasdan.gallery.backup.data.BackupMetaData
import onlasdan.gallery.backup.domain.UnlockBackupUseCase
import onlasdan.gallery.databinding.DialogBackupUnlockBinding
import onlasdan.gallery.encryption.domain.models.Session
import onlasdan.gallery.other.extensions.hide
import onlasdan.gallery.other.extensions.show
import onlasdan.gallery.uicomponnets.bindings.BindableDialogFragment
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dialog for unlocking a backup.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
@AndroidEntryPoint
class UnlockBackupDialogFragment(
    private val uri: Uri,
    private val metaData: BackupMetaData,
    val onUnlockSuccess: (session: Session) -> Unit
) : BindableDialogFragment<DialogBackupUnlockBinding>(R.layout.dialog_backup_unlock) {

    private val viewModel: UnlockBackupViewModel by viewModels()

    @Inject
    lateinit var unlockBackupUseCase: UnlockBackupUseCase

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.addOnPropertyChange<String>(BR.password) {
            binding.unlockBackupWrongPasswordWarning.hide()
        }
    }

    fun onUnlock() {
        binding.unlockBackupWrongPasswordWarning.hide()

        lifecycleScope.launch {
            unlockBackupUseCase(uri, metaData, viewModel.password)
                .onSuccess { session ->
                    dismiss()
                    onUnlockSuccess(session)
                }
                .onFailure {
                    binding.unlockBackupWrongPasswordWarning.show()
                }
        }
    }

    override fun bind(binding: DialogBackupUnlockBinding) {
        super.bind(binding)
        binding.viewModel = viewModel
        binding.context = this
    }
}