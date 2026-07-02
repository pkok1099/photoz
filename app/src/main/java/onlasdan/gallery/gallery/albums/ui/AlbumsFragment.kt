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

package onlasdan.gallery.gallery.albums.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.gallery.albums.ui.compose.AlbumsScreen
import onlasdan.gallery.gallery.albums.ui.navigation.AlbumsNavigator
import onlasdan.gallery.other.extensions.finishOnBackWhileStarted
import onlasdan.gallery.other.extensions.launchLifecycleAwareJob
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.settings.domain.models.StartPage
import onlasdan.gallery.settings.ui.compose.LocalConfig
import onlasdan.gallery.transcoding.compose.LocalEncryptedImageLoader
import onlasdan.gallery.transcoding.di.EncryptedImageLoader
import onlasdan.gallery.ui.LocalFragment
import javax.inject.Inject

@AndroidEntryPoint
class AlbumsFragment : Fragment() {

    @Inject
    lateinit var albumsNavigator: AlbumsNavigator

    private val viewModel: AlbumsViewModel by viewModels()

    @EncryptedImageLoader
    @Inject
    lateinit var encryptedImageLoader: ImageLoader

    @Inject
    lateinit var config: Config

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setContent {
            CompositionLocalProvider(
                LocalEncryptedImageLoader provides encryptedImageLoader,
                LocalFragment provides this@AlbumsFragment,
                LocalConfig provides config,
            ) {
                AlbumsScreen(viewModel)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        finishOnBackWhileStarted(
            enabled = config.galleryStartPage == StartPage.Albums,
        )

        launchLifecycleAwareJob {
            viewModel.navEvent.collect { event ->
                albumsNavigator.navigate(event, findNavController())
            }
        }
    }
}