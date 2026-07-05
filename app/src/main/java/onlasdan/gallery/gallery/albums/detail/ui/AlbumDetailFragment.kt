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

package onlasdan.gallery.gallery.albums.detail.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.gallery.albums.detail.ui.compose.AlbumDetailScreen
import onlasdan.gallery.gallery.ui.navigation.PhotoActionsNavigator
import onlasdan.gallery.other.extensions.assistedViewModel
import onlasdan.gallery.other.extensions.launchLifecycleAwareJob
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.settings.ui.compose.LocalConfig
import onlasdan.gallery.transcoding.compose.LocalEncryptedImageLoader
import onlasdan.gallery.transcoding.di.EncryptedImageLoader
import onlasdan.gallery.ui.theme.AppTheme
import javax.inject.Inject

@AndroidEntryPoint
class AlbumDetailFragment : Fragment() {
	private val args: AlbumDetailFragmentArgs by navArgs()

	private val viewModel by assistedViewModel<AlbumDetailViewModel.Factory, AlbumDetailViewModel> {
		it.create(args.albumUuid)
	}

	@Inject
	lateinit var photoActionsNavigator: PhotoActionsNavigator

	@Inject
	lateinit var albumDetailNavigator: AlbumDetailNavigator

	@Inject
	lateinit var config: Config

	@EncryptedImageLoader
	@Inject
	lateinit var encryptedImageLoader: ImageLoader

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		ComposeView(requireContext()).apply {
			setContent {
				AppTheme {
					CompositionLocalProvider(
						LocalEncryptedImageLoader provides encryptedImageLoader,
						LocalConfig provides config,
					) {
						AlbumDetailScreen(viewModel, findNavController())
					}
				}
			}
		}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		launchLifecycleAwareJob {
			viewModel.photoActions.collect { action ->
				photoActionsNavigator.navigate(action, findNavController(), this)
			}
		}

		launchLifecycleAwareJob {
			viewModel.navEvents.collect { event ->
				albumDetailNavigator.navigate(event, this)
			}
		}
	}
}
