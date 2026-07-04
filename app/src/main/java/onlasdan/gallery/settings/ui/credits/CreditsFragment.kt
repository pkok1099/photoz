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

package onlasdan.gallery.settings.ui.credits

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import onlasdan.gallery.ui.theme.AppTheme

/**
 * Sprint 10+ / M1 — CreditsFragment migrated to Compose.
 *
 * Previously extended [BindableFragment] with XML layout (fragment_credits.xml)
 * + RecyclerView + CreditsAdapter. Now extends plain [Fragment] and hosts a
 * [ComposeView] that renders [CreditsScreen].
 *
 * The contributors.json asset is still parsed via Gson (unchanged) — only
 * the rendering layer switched from RecyclerView to Compose LazyColumn.
 *
 * @since v14 — Sprint 10+ / M1 Compose migration
 */
@AndroidEntryPoint
class CreditsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    val entries = loadContributors()
                    CreditsScreen(
                        entries = entries,
                        onBack = { findNavController().navigateUp() },
                    )
                }
            }
        }
    }

    private fun loadContributors(): ArrayList<CreditEntry> {
        return try {
            val json = String(requireActivity().assets.open(CONTRIBUTORS_FILE).readBytes())
            val listType = object : TypeToken<ArrayList<CreditEntry??>?>() {}.type
            val entries: ArrayList<CreditEntry> = Gson().fromJson(json, listType)
            entries.add(0, CreditEntry.createHeader())
            entries.add(CreditEntry.createFooter())
            entries
        } catch (e: Exception) {
            ArrayList()
        }
    }

    companion object {
        const val CONTRIBUTORS_FILE = "contributors.json"
    }
}
