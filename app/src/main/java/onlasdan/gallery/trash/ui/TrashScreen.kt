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

package onlasdan.gallery.trash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import onlasdan.gallery.R
import onlasdan.gallery.model.database.entity.Photo
import java.text.DateFormat
import java.util.Date

/**
 * Trash screen — list of soft-deleted photos with restore / permanently-
 * delete / empty-trash actions.
 *
 * @since v10 recycle bin
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(viewModel: TrashViewModel) {
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDeletePhoto by remember { mutableStateOf<Photo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        // Navigate up via the LocalFragment's navController —
                        // the activity's back button also works.
                        // (Composable doesn't have direct NavController
                        // access here, so we rely on the system back button
                        // + the toolbar's nav icon being primarily visual.)
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.common_cancel),
                        )
                    }
                },
                actions = {
                    if (trash.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) {
                            Text(stringResource(R.string.trash_empty_button))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (trash.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(R.string.trash_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.trash_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
            ) {
                items(trash, key = { it.uuid }) { photo ->
                    TrashRow(
                        photo = photo,
                        onRestore = { viewModel.restore(photo.uuid) },
                        onPermanentlyDelete = { confirmDeletePhoto = photo },
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.trash_empty_confirm_title)) },
            text = { Text(stringResource(R.string.trash_empty_confirm_message)) },
            confirmButton = {
                Button(onClick = {
                    confirmEmpty = false
                    viewModel.emptyTrash()
                }) { Text(stringResource(R.string.common_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    confirmDeletePhoto?.let { photo ->
        AlertDialog(
            onDismissRequest = { confirmDeletePhoto = null },
            title = { Text(stringResource(R.string.trash_delete_permanent_confirm_title)) },
            text = { Text(stringResource(R.string.trash_delete_permanent_confirm_message, photo.fileName)) },
            confirmButton = {
                Button(onClick = {
                    confirmDeletePhoto = null
                    viewModel.permanentlyDelete(photo)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePhoto = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun TrashRow(
    photo: Photo,
    onRestore: () -> Unit,
    onPermanentlyDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Thumbnail placeholder — uses the ic_image drawable since
            // rendering the actual encrypted thumbnail would require the
            // EncryptedImageLoader composition local + a full Coil request
            // per row. Keep the row lightweight; the user just needs to
            // identify the photo by name + date to decide whether to
            // restore or delete it.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = photo.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val deleted = remember(photo.deletedAt) {
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT,
                    ).format(Date(photo.deletedAt))
                }
                Text(
                    text = stringResource(R.string.trash_row_deleted_at, deleted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            OutlinedButton(
                onClick = onRestore,
                content = { Text(stringResource(R.string.trash_restore_button)) },
            )
            OutlinedButton(
                onClick = onPermanentlyDelete,
                content = { Text(stringResource(R.string.trash_delete_permanent_button)) },
            )
        }
    }
}
