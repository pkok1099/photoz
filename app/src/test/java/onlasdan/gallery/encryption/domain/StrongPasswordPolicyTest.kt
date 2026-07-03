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

package onlasdan.gallery.encryption.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StrongPasswordPolicy] — Sprint 1 / P7.
 *
 * Verifies the policy enforcement rules:
 *  - Minimum 8 characters
 *  - Rejects pure-digit PINs under 12 digits
 *  - Requires character class diversity (3 of 4, or 2 of 4 if length >= 12)
 *  - Rejects common passwords from the blacklist
 *  - Strength scoring buckets are correct
 */
class StrongPasswordPolicyTest {

    // ─── isAcceptable ──────────────────────────────────────────────────────

    @Test
    fun `empty password is not acceptable`() {
        assertFalse(StrongPasswordPolicy.isAcceptable(""))
    }

    @Test
    fun `password shorter than 8 chars is not acceptable`() {
        assertFalse(StrongPasswordPolicy.isAcceptable("Ab1!"))
        assertFalse(StrongPasswordPolicy.isAcceptable("Abc123"))
        assertFalse(StrongPasswordPolicy.isAcceptable("Abc123!"))
    }

    @Test
    fun `password exactly 8 chars with 3 classes is acceptable`() {
        assertTrue(StrongPasswordPolicy.isAcceptable("Abc12345"))
        // 8 chars, lowercase + uppercase + digits = 3 classes
    }

    @Test
    fun `8-char password with only 2 classes is not acceptable`() {
        assertFalse(StrongPasswordPolicy.isAcceptable("abc12345"))
        // 8 chars, lowercase + digits = 2 classes, needs 3
    }

    @Test
    fun `12-char lowercase-only passphrase is NOT acceptable (needs 2 classes)`() {
        // 12 chars, only lowercase = 1 class. Length >= 12 relaxes to 2 classes required,
        // but 1 < 2, so still rejected.
        assertFalse(StrongPasswordPolicy.isAcceptable("correcthorse"))
    }

    @Test
    fun `12-char password with 2 classes is acceptable`() {
        assertTrue(StrongPasswordPolicy.isAcceptable("correcthorse1"))
        // 13 chars, lowercase + digits = 2 classes, length >= 12 → 2 classes required → OK
    }

    @Test
    fun `numeric PIN under 12 digits is rejected even if 8+ chars`() {
        assertFalse(StrongPasswordPolicy.isAcceptable("12345678"))
        assertFalse(StrongPasswordPolicy.isAcceptable("1234567890"))
        assertFalse(StrongPasswordPolicy.isAcceptable("12345678901"))
    }

    @Test
    fun `pure 12-digit numeric string is NOT acceptable (1 class < 2 required)`() {
        // 12 digits, 1 class, length >= 12 relaxes to 2 classes required, but 1 < 2 → reject.
        // This is intentional — even though the PIN reject rule only fires for < 12 digits,
        // the class-diversity rule still catches a pure-digit 12-char password.
        assertFalse(StrongPasswordPolicy.isAcceptable("123456789012"))
    }

    @Test
    fun `numeric string of 12+ digits with letters is accepted`() {
        assertTrue(StrongPasswordPolicy.isAcceptable("123456789012a"))
        // 13 chars, digits + lowercase = 2 classes, length >= 12 → 2 classes required → OK
    }

    @Test
    fun `common passwords are rejected`() {
        assertFalse(StrongPasswordPolicy.isAcceptable("password1"))
        // "Password1" lowercase form is "password1" which is in the blacklist
        assertFalse(StrongPasswordPolicy.isAcceptable("Password1"))
        assertFalse(StrongPasswordPolicy.isAcceptable("qwerty123"))
        assertFalse(StrongPasswordPolicy.isAcceptable("admin1234"))
    }

    @Test
    fun `strong password with all 4 classes is acceptable`() {
        assertTrue(StrongPasswordPolicy.isAcceptable("Abc123!@#"))
        // 9 chars, lowercase + uppercase + digits + symbols = 4 classes
    }

    // ─── strength ──────────────────────────────────────────────────────────

    @Test
    fun `empty password returns EMPTY strength`() {
        assertEquals(PasswordStrength.EMPTY, StrongPasswordPolicy.strength(""))
    }

    @Test
    fun `short password returns TOO_SHORT strength`() {
        assertEquals(PasswordStrength.TOO_SHORT, StrongPasswordPolicy.strength("Ab1!"))
        assertEquals(PasswordStrength.TOO_SHORT, StrongPasswordPolicy.strength("Abc123"))
    }

    @Test
    fun `numeric PIN returns PIN_REJECTED strength`() {
        assertEquals(PasswordStrength.PIN_REJECTED, StrongPasswordPolicy.strength("1234"))
        assertEquals(PasswordStrength.PIN_REJECTED, StrongPasswordPolicy.strength("12345678"))
        assertEquals(PasswordStrength.PIN_REJECTED, StrongPasswordPolicy.strength("1234567890"))
    }

    @Test
    fun `common password returns COMMON strength`() {
        assertEquals(PasswordStrength.COMMON, StrongPasswordPolicy.strength("password1"))
        assertEquals(PasswordStrength.COMMON, StrongPasswordPolicy.strength("qwerty123"))
    }

    @Test
    fun `strong 16+ char password with 3+ classes returns STRONG`() {
        assertEquals(PasswordStrength.STRONG,
            StrongPasswordPolicy.strength("Abcdefgh12345678"))
        // 16 chars, lowercase + uppercase + digits = 3 classes, length 16 >= 16 → STRONG
    }

    // ─── isPinRejected / isTooShort ────────────────────────────────────────

    @Test
    fun `isPinRejected detects pure-digit PINs under 12 digits`() {
        assertTrue(StrongPasswordPolicy.isPinRejected("1234"))
        assertTrue(StrongPasswordPolicy.isPinRejected("12345678"))
        assertFalse(StrongPasswordPolicy.isPinRejected("123456789012"))
        assertFalse(StrongPasswordPolicy.isPinRejected("abc123"))
    }

    @Test
    fun `isTooShort detects passwords under 8 chars`() {
        assertTrue(StrongPasswordPolicy.isTooShort("Ab1!"))
        assertTrue(StrongPasswordPolicy.isTooShort("Abc123"))
        assertFalse(StrongPasswordPolicy.isTooShort("Abc12345"))
        assertFalse(StrongPasswordPolicy.isTooShort(""))
    }
}
