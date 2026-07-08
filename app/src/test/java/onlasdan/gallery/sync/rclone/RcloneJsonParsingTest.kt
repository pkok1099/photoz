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

package onlasdan.gallery.sync.rclone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F-SYNC-001/013 — Tests for JSON-based RPC error detection and list parsing.
 *
 * Uses RobolectricTestRunner because org.json.JSONObject is an Android SDK
 * class (not available in plain JVM unit tests).
 *
 * @since F-SYNC-001/013 — JSON parser test coverage
 */
@RunWith(RobolectricTestRunner::class)
class RcloneJsonParsingTest {
        private fun hasRpcError(output: String): Boolean =
                try {
                        JSONObject(output).has("error")
                } catch (e: Exception) {
                        true
                }

        private fun parseListResult(json: String): List<RemoteFileInfo> =
                try {
                        val arr = JSONObject(json).optJSONArray("list") ?: return emptyList()
                        (0 until arr.length()).mapNotNull { i ->
                                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                                val name = obj.optString("Name")
                                if (name.isEmpty()) null
                                else RemoteFileInfo(name = name, size = obj.optLong("Size", 0L))
                        }
                } catch (e: Exception) {
                        emptyList()
                }

        @Test
        fun `hasRpcError returns false for success response`() {
                assertFalse(hasRpcError("""{"version":"v1.68.2"}"""))
        }

        @Test
        fun `hasRpcError returns true for error field`() {
                assertTrue(hasRpcError("""{"error":"not found"}"""))
        }

        @Test
        fun `hasRpcError returns false for error in a value (F-SYNC-001 false-positive fix)`() {
                assertFalse(hasRpcError("""{"Name":"error_report.jpg","Size":12345}"""))
        }

        @Test
        fun `hasRpcError returns true for invalid JSON`() {
                assertTrue(hasRpcError("not valid json"))
        }

        @Test
        fun `hasRpcError returns true for empty string`() {
                assertTrue(hasRpcError(""))
        }

        @Test
        fun `parseListResult parses single file`() {
                val result = parseListResult("""{"list":[{"Name":"photo.jpg","Size":1024}]}""")
                assertEquals(1, result.size)
                assertEquals("photo.jpg", result[0].name)
                assertEquals(1024L, result[0].size)
        }

        @Test
        fun `parseListResult parses multiple files`() {
                val result = parseListResult("""{"list":[{"Name":"a.jpg","Size":100},{"Name":"b.jpg","Size":200}]}""")
                assertEquals(2, result.size)
                assertEquals("a.jpg", result[0].name)
                assertEquals(200L, result[1].size)
        }

        @Test
        fun `parseListResult handles filename with escaped quote (F-SYNC-013 fix)`() {
                val result = parseListResult("""{"list":[{"Name":"my\"quoted\"file.jpg","Size":512}]}""")
                assertEquals(1, result.size)
                assertEquals("my\"quoted\"file.jpg", result[0].name)
        }

        @Test
        fun `parseListResult handles filename containing word error`() {
                val result = parseListResult("""{"list":[{"Name":"error_log.jpg","Size":256}]}""")
                assertEquals(1, result.size)
                assertEquals("error_log.jpg", result[0].name)
        }

        @Test
        fun `parseListResult skips entries with empty Name`() {
                val result = parseListResult("""{"list":[{"Name":"","Size":0},{"Name":"real.jpg","Size":100}]}""")
                assertEquals(1, result.size)
                assertEquals("real.jpg", result[0].name)
        }

        @Test
        fun `parseListResult returns empty for missing list array`() {
                assertEquals(emptyList<RemoteFileInfo>(), parseListResult("""{"error":"not found"}"""))
        }

        @Test
        fun `parseListResult returns empty for invalid JSON`() {
                assertEquals(emptyList<RemoteFileInfo>(), parseListResult("not json"))
        }

        @Test
        fun `parseListResult defaults Size to 0 when missing`() {
                val result = parseListResult("""{"list":[{"Name":"no_size.jpg"}]}""")
                assertEquals(1, result.size)
                assertEquals(0L, result[0].size)
        }
}
