# 6 UI Bugs Fix — Code Editing Subagent

**Date:** 2026 build run
**Scope:** Fix 6 UI/UX bugs in the PhotoZ (onlasdan.gallery) Android app, build, commit, push.

## Bugs Fixed

### Bug 1: Filter chips (All/Photos/Videos/Files) disappear when gallery is empty

**Files:**
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiState.kt`
  - Converted `GalleryUiState.Empty` from `data object` to `data class(filter, searchQuery)`.
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiStateFactory.kt`
  - Both empty-state branches now pass `filter` + `searchQuery` into `Empty(...)`.
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryContent.kt`
  - Removed the search bar + filter row rendering from inside `GalleryContent`.
  - Promoted `GallerySearchBar` and `GalleryFilterRow` from private to public top-level composables.
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryScreen.kt`
  - Rendered `GallerySearchBar` + `GalleryFilterRow` ABOVE the `when (state)` block in the Scaffold content lambda so they stay visible in BOTH Empty and Content states.
  - Used a local `val state = uiState` snapshot to allow Kotlin smart-cast through the `when` branches.
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryViewModel.kt`
  - Updated `stateIn(... GalleryUiState.Empty)` → `GalleryUiState.Empty()` since `Empty` is now a data class.

**Behavior change:** Picking the "Videos" filter with no videos imported no longer dead-ends the gallery — the user can switch back to "All" or "Photos".

### Bug 2: Large empty gap at top of gallery

**Files:**
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryScreen.kt`
  - Replaced `LargeTopAppBar` with the small `TopAppBar`.
  - Switched `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()` → `TopAppBarDefaults.pinnedScrollBehavior()` so the top bar stays at a single compact 64dp height with no expanded/collapsed state.
  - The empty expanded-title gap between the top bar and the thumbnails is gone.

### Bug 3: Restore menu icon too large / not responsive

**Files:**
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryScreen.kt`
  - Added explicit `Modifier.size(24.dp)` to the overflow `IconButton`'s `Icon` (ic_more_vert).
  - Added explicit `Modifier.size(24.dp)` to the `DropdownMenuItem` `leadingIcon` (ic_restore).
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryContent.kt`
  - Added explicit `Modifier.size(24.dp)` to all `DropdownMenuItem` leading icons (Add to album, Restore original).

### Bug 4: Single file / folder / all restore — per-photo Restore original

Added a granular per-photo restore action alongside the existing bulk "Restore from backup" overflow item.

**Files:**
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryUiEvent.kt`
  - New event: `data class OnRestoreOriginals(val uuids: List<String>)`.
- `app/src/main/java/onlasdan/gallery/gallery/ui/GalleryViewModel.kt`
  - New private `onRestoreOriginals(uuids)` handler.
  - Iterates the selected UUIDs and calls `syncRestorer.ensureLocalOriginal(uuid)` (idempotent — photos whose local original already exists are skipped).
  - Sends 4 toast variants: started, success, nothing-to-do, failed.
- `app/src/main/java/onlasdan/gallery/gallery/ui/compose/GalleryContent.kt`
  - Added a new "Restore original" `DropdownMenuItem` inside `additionalMultiSelectionActions`, with `ic_download` leading icon (24dp) and `R.string.menu_ms_restore_original` label.
- `app/src/main/res/values/strings.xml`
  - Added: `menu_ms_restore_original`, `menu_restore_original_toast_started`, `menu_restore_original_toast_success`, `menu_restore_original_toast_failed`, `menu_restore_original_toast_nothing`.

### Bug 5: Albums not auto-created from folder path (import + restore)

Photos imported from a folder (e.g. "Download") are now automatically grouped into an album of the same name. Same logic runs on restore from registry.

**Files:**
- `app/src/main/java/onlasdan/gallery/model/database/dao/AlbumDao.kt`
  - New query: `getByName(name: String): AlbumTable?` — find existing album by name to avoid duplicates.
- `app/src/main/java/onlasdan/gallery/model/repositories/PhotoRepository.kt`
  - New private helper `ensureAlbumForPhoto(photo)` — find-or-create album + link photo.
  - Called from `safeImportPhoto()` after `safeCreatePhoto` succeeds and the dedup check passes.
  - Skipped silently when `albumPath` is null/blank or equals the photo's filename (the SAF-picker fallback — would create useless one-item albums).
  - Wrapped in try/catch: failure is non-fatal (the photo is already imported; album grouping is a convenience).
- `app/src/main/java/onlasdan/gallery/sync/rclone/RepoManager.kt`
  - Injected `AlbumDao` into the constructor.
  - New private helper `ensureAlbumForRestoredPhoto(photo)` — mirrors the import-side logic.
  - Called from `ensurePhotoRowForRestoredEntry()` after `photoDao.insert(photo)` succeeds.
- `app/src/main/java/onlasdan/gallery/sync/di/SyncModule.kt`
  - Updated `provideRepoManager` to inject `AlbumDao` (already bound by `AppModule.provideAlbumDao`).

### Bug 6: Cleanup menu description misleading

**Files:**
- `app/src/main/res/values/strings.xml`
  - Title: "Clean cached originals" → **"Clean local originals cache"**
  - Summary: now reads **"Delete cached encrypted originals (.crypt) from local storage. Photos stay safe on the remote and can be re-downloaded on demand."**
  - (Removed the ambiguous previous wording that mentioned "safely backed up" without making it clear this is local-only.)

## Build

- `export JAVA_HOME=/home/z/jdk/jdk21 && export PATH=$JAVA_HOME/bin:$PATH`
- `./gradlew :app:assembleFossDebug --no-daemon --console=plain -Pandroid.injected.build.abi=arm64-v8a`
- Result: **BUILD SUCCESSFUL in 57s**, APK at `app/build/intermediates/apk/foss/debug/photok-1.0.0-foss-debug.apk`.

### Iteration notes

- First build attempt failed with smart-cast errors on the delegated `uiState` property inside `GalleryScreen.kt` (the new `when (uiState)` block tried to access `.filter` / `.searchQuery` on the Empty branch but Kotlin refused to smart-cast a delegated property).
- Fix: snapshotted `val state = uiState` to a local val before the `when`, then smart-cast against `state` instead.

## Commit & Push

Commit message: `fix: 6 UI bugs — filter chips always visible, gap fix, restore menu sizing, single restore, auto-album, cleanup text`
