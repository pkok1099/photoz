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

package onlasdan.gallery.other.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/**
 * Schedule a InputStream to be closed by the [Dispatchers.IO]
 * For use in suspend fun.
 *
 * F-WARN-005: @OptIn(DelicateCoroutinesApi) — GlobalScope is acceptable here
 * because lazyClose() fires-and-forgets a stream close on a background dispatcher.
 * The stream is already consumed by the time this is called; closing it is a
 * cleanup operation that should not block the caller.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun InputStream.lazyClose() =
	GlobalScope.launch(Dispatchers.IO) {
		close()
	}

/**
 * Schedule a OutputStream to be closed by the [Dispatchers.IO].
 * For use in suspend fun.
 *
 * F-WARN-005: @OptIn(DelicateCoroutinesApi) — see [InputStream.lazyClose] above.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun OutputStream.lazyClose() =
	GlobalScope.launch(Dispatchers.IO) {
		close()
	}
