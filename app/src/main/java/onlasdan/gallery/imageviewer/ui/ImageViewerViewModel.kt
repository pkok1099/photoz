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

package onlasdan.gallery.imageviewer.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import onlasdan.gallery.encryption.domain.SessionRepository
import onlasdan.gallery.gallery.albums.domain.AlbumRepository
import onlasdan.gallery.model.database.entity.Photo
import onlasdan.gallery.model.repositories.PhotoRepository
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.sort.domain.SortConfig
import onlasdan.gallery.sort.domain.SortRepository
import onlasdan.gallery.sync.work.SyncRestorer
import onlasdan.gallery.transcoding.data.AesCbcRandomAccessDataSource
import onlasdan.gallery.uicomponnets.bindings.ObservableViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

sealed interface ImageViewerUiEvent {
    data class ConfirmDelete(val item: ImageViewerItem) : ImageViewerUiEvent
    data class ConfirmExport(
        val item: ImageViewerItem,
        val target: Uri,
    ) : ImageViewerUiEvent
    data class SetPinned(
        val photoUuid: String,
        val pinned: Boolean,
    ) : ImageViewerUiEvent
    data class UpdateLoopVideos(val newValue: Boolean) : ImageViewerUiEvent
    data class UpdateVideoPlaybackSpeed(val newValue: Float) : ImageViewerUiEvent
    data class UpdateShowControls(val newValue: Boolean) : ImageViewerUiEvent
    data object ToggleShowControls : ImageViewerUiEvent
    data object ToggleMuteVideoPlayer : ImageViewerUiEvent
    data class UpdateCurrentDialog(val newValue: ImageViewerUiState.Dialog?) : ImageViewerUiEvent
}

/**
 * State of an on-demand video download (remote-only video being fetched back
 * to local storage so ExoPlayer can play it).
 *
 * The viewer's shutter shows a determinate progress bar while the download is
 * in flight, then hands off to ExoPlayer once the file is local.
 *
 * @since video-loading-indicator feature — on-demand video download with
 *   visible progress
 */
sealed interface VideoDownloadState {
    /** No download needed (the local file is already present). */
    data object Idle : VideoDownloadState

    /**
     * Download is in flight. [progress] is 0f..100f (size-based estimate).
     * [lastUpdateMs] is the system time of the last progress update — used
     * by the rate-limiter in [ImageViewerViewModel.maybeStartVideoDownload]
     * to cap UI refreshes at ~5/sec.
     */
    data class Downloading(
        val progress: Float,
        val lastUpdateMs: Long = System.currentTimeMillis(),
    ) : VideoDownloadState

    /** Download completed successfully — ExoPlayer can now open the file. */
    data object Done : VideoDownloadState

    /** Download failed (network blip, vault locked, remote missing, etc.). */
    data class Failed(val message: String) : VideoDownloadState
}

data class ImageViewerUiState(
    val items: List<ImageViewerItem> = emptyList(),
    val albumUuid: String? = null,
    val pinnedPhotoIds: Set<String> = emptySet(),
    val loopVideos: Boolean = false,
    val muteVideoPlayer: Boolean = false,
    val videoPlaybackSpeed: Float = 1f,
    val inputs: Inputs = Inputs(),
    /**
     * Per-UUID on-demand video download state. Drives the "Downloading video…"
     * progress indicator in the viewer's shutter while a remote-only video is
     * being fetched back to local storage.
     *
     * @since video-loading-indicator feature
     */
    val videoDownloads: Map<String, VideoDownloadState> = emptyMap(),
) {
    data class Inputs(
        val showControls: Boolean = false,
        val currentDialog: Dialog? = null,
    )

    enum class Dialog {
        ConfirmDelete,
        ConfirmExport,
        MoreMenu,
        AlbumPicker,
        DetailsSheet
    }
}

const val ALBUM_UUID = "albumUuid"

@OptIn(UnstableApi::class)
@HiltViewModel(assistedFactory = ImageViewerViewModel.Factory::class)
class ImageViewerViewModel @AssistedInject constructor(
    @Assisted(ALBUM_UUID) private val albumUuid: String?,
    private val app: Application,
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val sortRepository: SortRepository,
    private val config: Config,
    private val sessionRepository: SessionRepository,
    private val syncRestorer: SyncRestorer,
) : ObservableViewModel(app) {

    private val inputs = MutableStateFlow(ImageViewerUiState.Inputs())

    // @since video-loading-indicator feature — per-UUID on-demand video
    //  download state. Drives the "Downloading video…" progress bar in the
    //  viewer's shutter while a remote-only video is being fetched back to
    //  local storage before ExoPlayer can play it.
    private val videoDownloadsFlow = MutableStateFlow<Map<String, VideoDownloadState>>(emptyMap())

    // Track which UUIDs we've already kicked off a download for, so we don't
    // re-launch the download coroutine on every flow emission (the photos
    // flow re-emits on any DB change, but we only want ONE download per UUID
    // per viewer session).
    private val inflightDownloads = mutableSetOf<String>()

    val uiState = combine(
        createPhotosFlow(),
        createPinnedPhotoIdsFlow(),
        config.valuesFlow,
        inputs,
        videoDownloadsFlow,
    ) { photos, pinnedPhotoIds, configValues, inputs, videoDownloads ->
        ImageViewerUiState(
            items = photos.map { photo ->
                if (photo.type.isVideo) {
                    // For video originals, trigger an async restore BEFORE ExoPlayer tries to
                    // open the local file. ExoPlayer will see the file once download completes;
                    // until then, the player shows a loading state. (PR1 on-demand restore.)
                    //
                    // @since video-loading-indicator feature — track per-UUID
                    //   download state so the viewer's shutter shows a
                    //   determinate "Downloading video…" progress bar instead
                    //   of just an indeterminate spinner. The download itself
                    //   is fired from [maybeStartVideoDownload] (below) which
                    //   is called from [handleUiEvent] when the user opens a
                    //   video, NOT from this flow — this flow only LOOKS UP
                    //   the current state for each video UUID.
                    ImageViewerItem.Video(
                        photo = photo,
                        mediaItem = createMediaItem(photo)
                    )
                } else {
                    ImageViewerItem.Image(
                        photo = photo
                    )
                }
            },
            albumUuid = albumUuid,
            pinnedPhotoIds = pinnedPhotoIds,
            loopVideos = configValues.getOrDefault(Config.IMAGE_VIEWER_LOOP_VIDEO, false) as Boolean,
            muteVideoPlayer = configValues.getOrDefault(Config.IMAGE_VIEWER_MUTE_VIDEO_PLAYER, false) as Boolean,
            videoPlaybackSpeed = configValues.getOrDefault(Config.IMAGE_VIEWER_PLAYBACK_SPEED, 1f) as Float,
            inputs = inputs,
            videoDownloads = videoDownloads,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ImageViewerUiState())

    /**
     * Kick off (or look up) the on-demand download for a remote-only video so
     * ExoPlayer can play it. Called from the viewer's pager settle effect when
     * the user swipes to a video page.
     *
     * Idempotent: if a download is already in flight or has completed for this
     * UUID, the call is a no-op. Progress is published to [videoDownloadsFlow]
     * which the viewer's shutter observes.
     *
     * @since video-loading-indicator feature
     */
    fun maybeStartVideoDownload(photo: Photo) {
        if (!photo.type.isVideo) return
        val uuid = photo.uuid
        synchronized(inflightDownloads) {
            if (uuid in inflightDownloads) return
            inflightDownloads.add(uuid)
        }
        // If the local file already exists, mark Done immediately — no
        // network round-trip needed.
        val localFile = app.getFileStreamPath(photo.internalFileName)
        if (localFile.exists() && localFile.length() > 0) {
            videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Done) }
            return
        }
        if (photo.syncState != onlasdan.gallery.sync.domain.SyncState.UPLOADED) {
            // Not uploaded — can't restore from remote. Mark Failed so the
            // UI shows an error instead of an infinite spinner.
            videoDownloadsFlow.update {
                it + (uuid to VideoDownloadState.Failed("Video not uploaded — cannot restore from cloud"))
            }
            return
        }
        // Mark Downloading at 0% immediately so the spinner swaps to a
        // determinate bar without delay.
        videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Downloading(0f)) }
        viewModelScope.launch {
            val result = runCatching {
                syncRestorer.ensureLocalOriginalWithProgress(uuid) { progress ->
                    // Rate-limit updates to ~5/sec (every 200ms) — the same
                    // cadence uploadFileWithProgress uses. State-change to 100%
                    // always goes through.
                    val current = videoDownloadsFlow.value[uuid]
                    if (current is VideoDownloadState.Downloading) {
                        val now = System.currentTimeMillis()
                        if (progress >= 100f || now - current.lastUpdateMs >= 200L) {
                            videoDownloadsFlow.update {
                                it + (uuid to VideoDownloadState.Downloading(progress, now))
                            }
                        }
                    } else if (current == null) {
                        // First update — always emit.
                        videoDownloadsFlow.update {
                            it + (uuid to VideoDownloadState.Downloading(progress))
                        }
                    }
                }
            }
            if (result.isSuccess) {
                videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Done) }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Download failed"
                videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Failed(msg)) }
            }
        }
    }

    fun handleUiEvent(event: ImageViewerUiEvent) {
        when (event) {
            is ImageViewerUiEvent.ConfirmDelete -> viewModelScope.launch {
                photoRepository.safeDeletePhoto(event.item.photo)
            }

            is ImageViewerUiEvent.ConfirmExport -> viewModelScope.launch {
                photoRepository.exportPhoto(event.item.photo, event.target)
            }

            is ImageViewerUiEvent.SetPinned -> viewModelScope.launch {
                albumUuid ?: return@launch
                albumRepository.setPinned(
                    photoUUIDs = listOf(event.photoUuid),
                    albumUUID = albumUuid,
                    pinned = event.pinned,
                )
            }

            is ImageViewerUiEvent.UpdateLoopVideos -> viewModelScope.launch {
                config.imageViewerLoopVideos = event.newValue
            }

            is ImageViewerUiEvent.UpdateShowControls -> inputs.update {
                it.copy(showControls = event.newValue)
            }

            is ImageViewerUiEvent.ToggleShowControls -> inputs.update { old ->
                old.copy(showControls = !old.showControls)
            }

            is ImageViewerUiEvent.ToggleMuteVideoPlayer -> viewModelScope.launch {
                config.imageViewerMuteVideoPlayer = !uiState.value.muteVideoPlayer
            }

            is ImageViewerUiEvent.UpdateCurrentDialog -> inputs.update {
                it.copy(currentDialog = event.newValue)
            }

            is ImageViewerUiEvent.UpdateVideoPlaybackSpeed -> viewModelScope.launch {
                config.imageViewerPlaybackSpeed = event.newValue
            }
        }
    }

    private fun createMediaItem(photo: Photo): MediaItem {
        val uri = Uri.fromFile(app.getFileStreamPath(photo.internalFileName).canonicalFile)

        return MediaItem.Builder()
            .setMimeType(photo.type.mimeType)
            .setUri(uri)
            .build()
    }

    val mediaSourceFactory: MediaSource.Factory by lazy {
        val factory = DataSource.Factory {
            AesCbcRandomAccessDataSource(
                sessionRepository = sessionRepository,
            )
        }

        ProgressiveMediaSource.Factory(factory)
    }

    private fun createPhotosFlow(): Flow<List<Photo>> {
        val sort = runBlocking {
            sortRepository.getSortForAlbum(albumUuid) ?: SortConfig.defaultFor(albumUuid)
        }

        return if (albumUuid == null) {
            photoRepository.observeAll(sort)
        } else {
            albumRepository.observeAlbumWithPhotos(
                uuid = albumUuid,
                sort = sort
            ).map { it.files }
        }
    }

    private fun createPinnedPhotoIdsFlow(): Flow<Set<String>> =
        albumUuid?.let(albumRepository::observePinnedPhotoUUIDs) ?: flowOf(emptySet())

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted(ALBUM_UUID) albumUuid: String?
        ): ImageViewerViewModel
    }
}