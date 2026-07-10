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
			}.diskCachePolicy(CachePolicy.DISABLED)
			.diskCache(null)
			.memoryCachePolicy(CachePolicy.ENABLED)
			.memoryCache {
				MemoryCache
					.Builder(context)
					.maxSizePercent(0.25)
					.build()
			}.apply {
				if (BuildConfig.DEBUG) {
					logger(DebugLogger())
				}
			}.build()

	@Provides
	fun provideDefaultImageLoader(
		@ApplicationContext context: Context,
	): ImageLoader =
		ImageLoader
			.Builder(context)
			.components { add(VideoFrameDecoder.Factory()) }
			.diskCachePolicy(CachePolicy.DISABLED)
			.diskCache(null)
			.memoryCachePolicy(CachePolicy.DISABLED)
			.memoryCache(null)
			.build()
}

@Module
@InstallIn(SingletonComponent::class)
interface ImageLoadingBindingModule {
	@Binds
	fun bindImageStorage(impl: ImageStorageImpl): ImageStorage
}
