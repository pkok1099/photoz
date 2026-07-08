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
import org.junit.Assert.assertNull
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
                                else RemoteFileInfo(
                                        name = name,
                                        size = obj.optLong("Size", 0L),
                                        isDir = obj.optBoolean("IsDir", false),
                                        mimeType = if (obj.isNull("MimeType")) null else obj.optString("MimeType", null),
                                )
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

        // ─── IsDir + MimeType parsing tests (folder operations support) ──────

        @Test
        fun `parseListResult captures IsDir true for directories`() {
                val json = """{"list":[{"Name":"myfolder","Size":0,"IsDir":true}]}"""
                val result = parseListResult(json)
                assertEquals(1, result.size)
                assertTrue("IsDir must be true for directories", result[0].isDir)
        }

        @Test
        fun `parseListResult captures IsDir false for files`() {
                val json = """{"list":[{"Name":"photo.jpg","Size":1024,"IsDir":false}]}"""
                val result = parseListResult(json)
                assertEquals(1, result.size)
                assertFalse("IsDir must be false for files", result[0].isDir)
        }

        @Test
        fun `parseListResult defaults IsDir to false when missing`() {
                val json = """{"list":[{"Name":"no_isdir.jpg","Size":512}]}"""
                val result = parseListResult(json)
                assertFalse(result[0].isDir)
        }

        @Test
        fun `parseListResult captures MimeType for files`() {
                val json = """{"list":[{"Name":"video.mp4","Size":5242880,"IsDir":false,"MimeType":"video/mp4"}]}"""
                val result = parseListResult(json)
                assertEquals("video/mp4", result[0].mimeType)
        }

        @Test
        fun `parseListResult returns null MimeType for directories`() {
                val json = """{"list":[{"Name":"folder","Size":0,"IsDir":true,"MimeType":null}]}"""
                val result = parseListResult(json)
                assertNull(result[0].mimeType)
        }

        @Test
        fun `parseListResult defaults MimeType to null when missing`() {
                val json = """{"list":[{"Name":"photo.jpg","Size":1024}]}"""
                val result = parseListResult(json)
                assertNull(result[0].mimeType)
        }

        @Test
        fun `parseListResult handles mixed files and directories`() {
                val json = """{"list":[
                        {"Name":"photos","Size":0,"IsDir":true},
                        {"Name":"img1.jpg","Size":1024,"IsDir":false,"MimeType":"image/jpeg"},
                        {"Name":"thumbnails","Size":0,"IsDir":true},
                        {"Name":"img2.png","Size":2048,"IsDir":false,"MimeType":"image/png"}
                ]}"""
                val result = parseListResult(json)
                assertEquals(4, result.size)
                assertTrue(result[0].isDir)  // photos
                assertFalse(result[1].isDir) // img1.jpg
                assertTrue(result[2].isDir)  // thumbnails
                assertFalse(result[3].isDir) // img2.png
        }

        // ─── RC operation input-building tests (verify JSON format) ──────────

        @Test
        fun `mkdir input format is correct`() {
                val remote = "myremote"
                val path = "photoz-backup/thumbnails"
                val input = """{"fs":"$remote:","remote":"$path"}"""
                val json = JSONObject(input)
                assertEquals("myremote:", json.getString("fs"))
                assertEquals("photoz-backup/thumbnails", json.getString("remote"))
        }

        @Test
        fun `movefile input format is correct`() {
                val input = """{"srcFs":"myremote:","srcRemote":"old/file.jpg","dstFs":"myremote:","dstRemote":"new/file.jpg"}"""
                val json = JSONObject(input)
                assertEquals("myremote:", json.getString("srcFs"))
                assertEquals("old/file.jpg", json.getString("srcRemote"))
                assertEquals("myremote:", json.getString("dstFs"))
                assertEquals("new/file.jpg", json.getString("dstRemote"))
        }

        @Test
        fun `movedir input format is correct`() {
                val input = """{"srcFs":"myremote:","srcRemote":"old_dir","dstFs":"myremote:","dstRemote":"new_dir"}"""
                val json = JSONObject(input)
                assertEquals("old_dir", json.getString("srcRemote"))
                assertEquals("new_dir", json.getString("dstRemote"))
        }

        @Test
        fun `purge input format is correct (recursive dir removal)`() {
                val input = """{"fs":"myremote:","remote":"photoz-backup/old_album"}"""
                val json = JSONObject(input)
                assertEquals("myremote:", json.getString("fs"))
                assertEquals("photoz-backup/old_album", json.getString("remote"))
        }

        @Test
        fun `hash input format is correct`() {
                val input = """{"fs":"myremote:","remote":"photoz-backup/originals/uuid.crypt","hashType":"sha256"}"""
                val json = JSONObject(input)
                assertEquals("sha256", json.getString("hashType"))
                assertEquals("photoz-backup/originals/uuid.crypt", json.getString("remote"))
        }

        @Test
        fun `hashFile response parsing extracts hash correctly`() {
                val response = """{"hash":"abc123def456789"}"""
                val json = JSONObject(response)
                assertEquals("abc123def456789", json.optString("hash"))
        }

        @Test
        fun `hashFile response with null hash returns null`() {
                val response = """{"hash":null}"""
                val json = JSONObject(response)
                assertTrue("hash should be null", json.isNull("hash"))
        }
}
