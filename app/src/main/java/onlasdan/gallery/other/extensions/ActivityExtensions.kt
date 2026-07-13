package onlasdan.gallery.other.extensions

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import onlasdan.gallery.BaseApplication

/**
 * Get the "application" as [BaseApplication] from any activity.
 */
fun Activity.getBaseApplication(): BaseApplication = application as BaseApplication

/**
 * Compat method to hide the system ui.
 * Uses window insets from api 30 and higher.
 */

inline fun AppCompatActivity.launchLifecycleAwareJob(
	state: Lifecycle.State = Lifecycle.State.CREATED,
	crossinline block: suspend () -> Unit,
) = lifecycleScope.launch { repeatOnLifecycle(state) { block() } }
