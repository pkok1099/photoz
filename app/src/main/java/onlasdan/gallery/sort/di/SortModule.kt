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

package onlasdan.gallery.sort.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import onlasdan.gallery.model.database.PhotoZDatabase
import onlasdan.gallery.sort.data.SortRepositoryImpl
import onlasdan.gallery.sort.domain.SortRepository

@Module
@InstallIn(SingletonComponent::class)
class SortModule {
	@Provides
	fun provideSortDao(database: PhotoZDatabase) = database.getSortDao()
}

@Module
@InstallIn(SingletonComponent::class)
interface SortBindingModule {
	@Binds
	fun bindSortRepository(impl: SortRepositoryImpl): SortRepository
}
