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

package onlasdan.gallery.gallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import onlasdan.gallery.R
import onlasdan.gallery.ui.theme.Colors

/**
 * Sprint 3 / M10 — "No folder info" chip shown on the Photo Picker import row.
 *
 * Signals to the user that importing via the Photo Picker will NOT preserve
 * the original folder path (because the picker URI doesn't expose
 * `RELATIVE_PATH`). The user will be asked to pick an album after import.
 */
@Composable
fun NoFolderInfoChip(modifier: Modifier = Modifier) {
    AssistChip(
        modifier = modifier,
        onClick = { /* informational — no-op */ },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
            )
        },
        label = {
            Text(
                text = stringResource(R.string.import_menu_photo_picker_chip_no_path),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            leadingIconContentColor = Colors.Warning,
            labelColor = Colors.Warning,
        ),
    )
}

/**
 * Sprint 3 / M10 — Path Maker dialog (v1: one-shot "all photos to one album").
 *
 * Shown after the Photo Picker returns URIs. Lets the user pick which album
 * the imported photos should go into. Three options:
 *
 *  1. **Default "Picker" album** — hardcoded name, one-tap. Good for users
 *     who don't care about organization and just want the photos in.
 *  2. **Existing album** — pick from a dropdown of albums that already exist
 *     in the current vault. (v1 only passes the current album if imported
 *     from an album detail screen; full dropdown is v2.)
 *  3. **Create new album** — user types a new album name.
 *
 * The dialog shows a warning banner explaining that Photo Picker doesn't
 * preserve folder info — this is the M10 design note from ROADMAP.md.
 *
 * v2 (Sprint 4+) will add per-photo album assignment (long-press individual
 * photo to override the batch default). v3 (Sprint 6) will add smart
 * suggestions based on EXIF date/location.
 *
 * @param photoCount number of photos the user selected in the picker.
 * @param existingAlbums albums the user can pick from (v1: just the current
 *   album if imported from album detail, otherwise empty).
 * @param onConfirm called with the chosen album name when the user taps
 *   "Import to album".
 * @param onDismiss called when the user cancels (back button or "Cancel").
 *
 * @since v11 — Sprint 3 / M10
 */
@Composable
fun PathMakerDialog(
    photoCount: Int,
    existingAlbums: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Default selection: the hardcoded "Picker" album.
    var selectedMode by remember { mutableStateOf(PathMakerMode.DEFAULT) }
    var newAlbumName by remember { mutableStateOf("") }
    var selectedExisting by remember { mutableStateOf(existingAlbums.firstOrNull() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.path_maker_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.path_maker_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = stringResource(R.string.path_maker_count, photoCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                // ─── Warning banner ────────────────────────────────────────
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.path_maker_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                // ─── Default "Picker" album option ────────────────────────
                PathMakerOption(
                    label = stringResource(R.string.path_maker_album_default),
                    selected = selectedMode == PathMakerMode.DEFAULT,
                    onSelect = { selectedMode = PathMakerMode.DEFAULT },
                )

                // ─── Existing album option (only if there are existing albums) ──
                if (existingAlbums.isNotEmpty()) {
                    existingAlbums.forEach { album ->
                        PathMakerOption(
                            label = stringResource(R.string.path_maker_album_existing, album),
                            selected = selectedMode == PathMakerMode.EXISTING && selectedExisting == album,
                            onSelect = {
                                selectedMode = PathMakerMode.EXISTING
                                selectedExisting = album
                            },
                        )
                    }
                }

                // ─── Create new album option ──────────────────────────────
                PathMakerOption(
                    label = stringResource(R.string.path_maker_album_new),
                    selected = selectedMode == PathMakerMode.NEW,
                    onSelect = { selectedMode = PathMakerMode.NEW },
                )
                if (selectedMode == PathMakerMode.NEW) {
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it.trim() },
                        label = { Text(stringResource(R.string.path_maker_album_new_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = when (selectedMode) {
                        PathMakerMode.DEFAULT -> "Picker"
                        PathMakerMode.EXISTING -> selectedExisting
                        PathMakerMode.NEW -> newAlbumName.ifBlank { "Picker" }
                    }
                    onConfirm(chosen)
                },
                enabled = selectedMode != PathMakerMode.NEW || newAlbumName.isNotBlank(),
            ) {
                Text(stringResource(R.string.path_maker_button_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.path_maker_button_cancel))
            }
        },
    )
}

private enum class PathMakerMode {
    DEFAULT,
    EXISTING,
    NEW,
}

@Composable
private fun PathMakerOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        onClick = onSelect,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(12.dp),
        )
    }
}
