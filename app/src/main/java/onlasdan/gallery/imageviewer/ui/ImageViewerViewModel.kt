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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

/**
 * In-flight state for a progressive video download, shared between the
 * download coroutine (writer) and [AesCbcRandomAccessDataSource] (reader,
 * running on ExoPlayer's loading thread).
 *
 * - [availableBytes] is polled from the file system (the download's writer
 *   side updates it as `File.length()` grows). The DataSource reads it to
 *   know how many bytes are safe to read; reads past this point block
 *   (poll) until more bytes arrive.
 * - [downloadComplete] flips to `true` when the download coroutine exits
 *   (success OR failure). The DataSource treats this as "no more data is
 *   coming" and returns EOF on the next read past the available window.
 *
 * Keyed by [Photo.internalFileName] in [ImageViewerViewModel.videoStreamState]
 * so the DataSource — which only sees the file `Uri` — can look the state up
 * by extracting the filename from the URI path.
 *
 * @since progressive-video-streaming feature — playback starts while the
 *   encrypted file is still downloading.
 */
data class StreamState(
    val availableBytes: AtomicLong = AtomicLong(0L),
    val downloadComplete: AtomicBoolean = AtomicBoolean(false),
)

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

    // @since progressive-video-streaming — per-internalFileName stream state
    //  shared between the download coroutine (which updates availableBytes as
    //  the file grows) and the AesCbcRandomAccessDataSource (which blocks on
    //  reads past the available window). Entries are added when a progressive
    //  download starts and removed after it completes (success or failure) so
    //  the DataSource falls back to its default "fully available" path.
    //
    //  ConcurrentHashMap: the DataSource reads from ExoPlayer's loading thread
    //  while the ViewModel adds/removes entries from the main / IO dispatchers.
    private val videoStreamState = ConcurrentHashMap<String, StreamState>()

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
     * ## Progressive streaming
     *
     * Instead of waiting for the full download before ExoPlayer opens the
     * file (the pre-progressive behavior), this method:
     *  1. Registers a [StreamState] in [videoStreamState] keyed by the photo's
     *     `internalFileName` so [AesCbcRandomAccessDataSource] can find it by
     *     inspecting the file `Uri` ExoPlayer passes to `open()`.
     *  2. Launches the download coroutine (existing
     *     `ensureLocalOriginalWithProgress` — writes directly to the final
     *     `<uuid>.crypt` path).
     *  3. Launches a file-size monitor coroutine that polls
     *     `localFile.length()` every [STREAM_POLL_INTERVAL_MS] and advances
     *     `StreamState.availableBytes`. The DataSource blocks on reads past
     *     this watermark.
     *  4. When the download coroutine finishes (success OR failure), flips
     *     `StreamState.downloadComplete` so the DataSource stops blocking and
     *     returns EOF cleanly, then removes the entry so the DataSource falls
     *     back to its default "fully available" path.
     *
     * ExoPlayer starts playback immediately (the LaunchedEffect in
     * `ImageViewerScreen` calls `setMediaItem` + `prepare()` right after this
     * method returns). The DataSource's `open()` blocks on
     * `waitForBytesAvailable(1)` until rclone has written at least the version
     * byte, then proceeds to read — blocking as needed — while the download
     * continues in the background. The user sees the "Downloading video…"
     * progress bar in the shutter until ExoPlayer has enough buffered data to
     * start rendering frames.
     *
     * @since video-loading-indicator feature; progressive-video-streaming
     *   extension.
     */
    fun maybeStartVideoDownload(photo: Photo) {
        if (!photo.type.isVideo) return
        val uuid = photo.uuid
        synchronized(inflightDownloads) {
            if (uuid in inflightDownloads) return
            inflightDownloads.add(uuid)
        }
        // If the local file already exists, mark Done immediately — no
        // network round-trip needed. No StreamState is registered, so the
        // DataSource uses its default "fully available" path.
        val localFile = app.getFileStreamPath(photo.internalFileName)
        if (localFile.exists() && localFile.length() > 0) {
            videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Done) }
            // QC fix: the inflightDownloads set used to be append-only — the
            // UUID was added when a download started but NEVER removed, not
            // even on these fast-path early returns. That meant a user who
            // later cleared the local file (or whose syncState changed) could
            // never retry the download without leaving and re-entering the
            // viewer. Remove the UUID on every exit path so the set only
            // blocks genuinely concurrent duplicate launches, not retries.
            synchronized(inflightDownloads) { inflightDownloads.remove(uuid) }
            return
        }
        if (photo.syncState != onlasdan.gallery.sync.domain.SyncState.UPLOADED) {
            // Not uploaded — can't restore from remote. Mark Failed so the
            // UI shows an error instead of an infinite spinner.
            videoDownloadsFlow.update {
                it + (uuid to VideoDownloadState.Failed("Video not uploaded — cannot restore from cloud"))
            }
            // QC fix: same as above — allow retry if the photo is later
            // uploaded (e.g. after a manual sync run).
            synchronized(inflightDownloads) { inflightDownloads.remove(uuid) }
            return
        }

        // --- Progressive streaming setup -----------------------------------
        // Register a StreamState BEFORE launching the download so the
        // DataSource (which ExoPlayer will spin up momentarily) can find it
        // and block on reads past the available window.
        val streamState = StreamState()
        videoStreamState[photo.internalFileName] = streamState

        // Mark Downloading at 0% immediately so the spinner swaps to a
        // determinate bar without delay.
        videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Downloading(0f)) }

        // File-size monitor: poll localFile.length() and advance
        // availableBytes. rclone writes the file directly (no temp file), so
        // File.length() reflects real progress. Monotonic — only advances,
        // never goes backwards (File.length() can transiently read 0 if
        // rclone hasn't created the file yet on the very first poll).
        monitorFileSize(streamState, localFile)

        // Download coroutine — writes to localFile. When it finishes, flips
        // downloadComplete so the DataSource's blocking reads unblock.
        viewModelScope.launch {
            try {
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
                // Signal the DataSource that no more data is coming, regardless
                // of success/failure. This unblocks any waitForBytesAvailable /
                // BlockingInputStream reads that are parked at the end of the
                // available window.
                streamState.downloadComplete.set(true)
                // One final length sync — the monitor's loop may not have
                // ticked since the last write. On failure this captures the
                // partial file size; on success it captures the final size.
                streamState.availableBytes.set(localFile.length())

                if (result.isSuccess) {
                    videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Done) }
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Download failed"
                    videoDownloadsFlow.update { it + (uuid to VideoDownloadState.Failed(msg)) }
                }
            } finally {
                // Remove the StreamState entry. After downloadComplete is set,
                // the entry is no longer needed for blocking — the DataSource's
                // providers return defaults (-1, true) when the entry is
                // absent, which is the correct "fully available" (or "partial
                // but download finished") state. Removing also prevents the
                // map from growing unboundedly across many video opens.
                videoStreamState.remove(photo.internalFileName)
                // QC fix: ALWAYS remove the UUID from inflightDownloads when
                // the coroutine exits — success OR failure. Without this, a
                // failed download (network blip, vault locked, remote missing)
                // permanently blocked retries for that UUID for the lifetime
                // of the viewer session. The set's only purpose is to prevent
                // concurrent duplicate launches while a download is in flight,
                // NOT to permanently pin a UUID after the download resolves.
                synchronized(inflightDownloads) { inflightDownloads.remove(uuid) }
            }
        }
    }

    /**
     * Poll [file]'s length on the IO dispatcher and advance
     * [state.availableBytes] (monotonically) so [AesCbcRandomAccessDataSource]
     * knows how many bytes are safe to read. Stops when
     * [StreamState.downloadComplete] flips to true.
     *
     * Best-effort: swallowed exceptions don't fail the download (the download
     * coroutine is the source of truth for success/failure; this monitor only
     * feeds the DataSource's blocking-read watermark).
     *
     * @since progressive-video-streaming feature
     */
    private fun monitorFileSize(state: StreamState, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive && !state.downloadComplete.get()) {
                    val len = file.length()
                    // Monotonic — File.length() can transiently read 0 before
                    // rclone creates the file, and we never want to regress
                    // the watermark (the DataSource might already have
                    // unblocked a read at a higher offset).
                    state.availableBytes.updateAndGet { old -> if (len > old) len else old }
                    delay(STREAM_POLL_INTERVAL_MS)
                }
                // Final sync — capture the post-completion file size so any
                // DataSource read parked at the old watermark can proceed.
                state.availableBytes.set(file.length())
            } catch (e: Exception) {
                // Swallow — best-effort monitor. The download coroutine
                // handles failure signaling.
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
        // The DataSource.Factory creates a fresh AesCbcRandomAccessDataSource
        // per MediaSource. ExoPlayer calls DataSource.open(dataSpec) with the
        // file Uri, and the DataSource looks up the per-file StreamState (if
        // any) via the providers below. When a progressive download is in
        // flight, the DataSource blocks on reads past the available window;
        // when the file is fully local (no StreamState entry), the providers
        // return their defaults and the DataSource behaves exactly as before
        // — preserving the existing local-playback path.
        val factory = DataSource.Factory {
            AesCbcRandomAccessDataSource(
                sessionRepository = sessionRepository,
                availableBytesProvider = { uri ->
                    lookupStreamState(uri)?.availableBytes?.get() ?: -1L
                },
                downloadCompleteProvider = { uri ->
                    lookupStreamState(uri)?.downloadComplete?.get() ?: true
                },
            )
        }

        ProgressiveMediaSource.Factory(factory)
    }

    /**
     * Extract the internal filename (e.g. `<uuid>.crypt`) from [uri] and look
     * up the associated [StreamState] in [videoStreamState]. Returns `null`
     * when the file isn't being progressively downloaded (fully local or
     * download already complete) — the DataSource's providers translate that
     * to their "fully available" defaults.
     *
     * @since progressive-video-streaming feature
     */
    private fun lookupStreamState(uri: Uri): StreamState? {
        val path = uri.path ?: return null
        val name = File(path).name
        return videoStreamState[name]
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

    companion object {
        /**
         * Poll interval for the file-size monitor coroutine that feeds
         * [StreamState.availableBytes]. 100ms is responsive enough that the
         * DataSource's blocking reads unblock within ~1-2 frames of the data
         * being written, without burning CPU on `File.length()` calls during
         * a long download.
         */
        private const val STREAM_POLL_INTERVAL_MS = 100L
    }
}