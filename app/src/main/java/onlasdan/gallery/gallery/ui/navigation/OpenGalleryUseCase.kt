/*
 *   Copyright 2020-2026 PhotoZ
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

package onlasdan.gallery.gallery.ui.navigation

import androidx.navigation.NavController
import onlasdan.gallery.R
import onlasdan.gallery.settings.data.Config
import onlasdan.gallery.settings.domain.models.StartPage
import timber.log.Timber
import javax.inject.Inject

class NavigateToGallery @Inject constructor(
    private val config: Config,
) {
    operator fun invoke(navController: NavController) {
        // ─── GATE: no gallery access without a confirmed repo session ───────
        // rclone remote + repo setup is MANDATORY. If the repo hasn't been confirmed,
        // redirect to RepoSetupFragment instead of the gallery. This is the gate that
        // makes the gallery unreachable until the user completes repo setup.
        // @since PR1 sync — mandatory repo setup
        if (!config.repoConfirmed) {
            try {
                navController.navigate(R.id.repoSetupFragment)
            } catch (e: Exception) {
                Timber.e(e)
            }
            return
        }

        val dest = when (config.galleryStartPage) {
            StartPage.AllFiles -> R.id.action_global_galleryFragment
            StartPage.Albums -> R.id.action_global_albumsFragment
        }

        try {
            navController.navigate(dest)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
}