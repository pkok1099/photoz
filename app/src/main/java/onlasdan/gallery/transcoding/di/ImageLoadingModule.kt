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

package onlasdan.gallery.transcoding.di

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import onlasdan.gallery.BuildConfig
import onlasdan.gallery.transcoding.data.EncryptedImageFetcherFactory
import onlasdan.gallery.transcoding.data.ImageStorageImpl
import onlasdan.gallery.transcoding.domain.ImageStorage

@Module
@InstallIn(SingletonComponent::class)
object ImageLoadingModule {
	/**
	 * Encrypted image loader — decrypts vault files on the fly via [EncryptedImageFetcherFactory].
	 *
	 * F-PERF-001 (UI optimization, v1.0.2): enable **disk cache** for decrypted thumbnails.
	 *
	 * Previously, disk cache was DISABLED — every gallery scroll re-decrypted every thumbnail
	 * from AES-CBC/GCM, causing severe jank on long lists. Now the decrypted bitmap result is
	 * cached to app-private cacheDir (already sandboxed by Android), so subsequent scrolls hit
	 * the disk cache instead of repeating the AES decryption.
	 *
	 * Security note: the disk cache holds DECRYPTED thumbnails (low-res, ~512px, JPEG-quality 40).
	 * The cache lives in `cacheDir/image_cache/` which is app-private — no other app can read it
	 * without root. The original encrypted files in `filesDir/` are unaffected. If the user clears
	 * app cache, the disk cache is wiped and thumbnails re-decrypt on next access (correct behavior).
	 *
	 * Memory cache bumped from 25% → 30% of available heap for more in-memory thumbnail hits.
	 */
	@Provides
	@EncryptedImageLoader
	fun provideEncryptedImageLoader(
		@ApplicationContext context: Context,
		encryptedImageFetcherFactory: EncryptedImageFetcherFactory,
	): ImageLoader =
		ImageLoader
			.Builder(context)
			.components {
				add(encryptedImageFetcherFactory)
			}.diskCachePolicy(CachePolicy.ENABLED)
			.diskCache {
				DiskCache
					.Builder()
					.directory(context.cacheDir.resolve("image_cache"))
					.maxSizeBytes(100L * 1024 * 1024) // 100 MB
					.build()
			}.memoryCachePolicy(CachePolicy.ENABLED)
			.memoryCache {
				MemoryCache
					.Builder(context)
					.maxSizePercent(0.30)
					.build()
			}.apply {
				if (BuildConfig.DEBUG) {
					logger(DebugLogger())
				}
			}.build()

	/**
	 * Default image loader — used for non-encrypted images (e.g. onboarding illustrations).
	 *
	 * F-PERF-001: also enable disk + memory cache for the default loader. Previously both were
	 * DISABLED which forced re-decode on every render. The default loader only handles public
	 * resources / URIs, so caching is unambiguously safe.
	 */
	@Provides
	fun provideDefaultImageLoader(
		@ApplicationContext context: Context,
	): ImageLoader =
		ImageLoader
			.Builder(context)
			.components { add(VideoFrameDecoder.Factory()) }
			.diskCachePolicy(CachePolicy.ENABLED)
			.diskCache {
				DiskCache
					.Builder()
					.directory(context.cacheDir.resolve("image_cache_default"))
					.maxSizeBytes(50L * 1024 * 1024) // 50 MB
					.build()
			}.memoryCachePolicy(CachePolicy.ENABLED)
			.memoryCache {
				MemoryCache
					.Builder(context)
					.maxSizePercent(0.15)
					.build()
			}.build()
}

@Module
@InstallIn(SingletonComponent::class)
interface ImageLoadingBindingModule {
	@Binds
	fun bindImageStorage(impl: ImageStorageImpl): ImageStorage
}
