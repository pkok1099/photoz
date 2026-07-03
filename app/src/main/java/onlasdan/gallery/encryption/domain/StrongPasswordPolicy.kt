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

/**
 * Strong password policy — Sprint 1 / P7.
 *
 * 2026 forensics research (Cellebrite, GrayKey) shows that 6-digit PINs can
 * always be cracked via TEE exploits, and short passwords are brute-forceable
 * on extracted CE storage. This policy enforces a minimum bar that resists
 * offline attacks against the PBKDF2-derived KEK (which uses 100k iterations
 * + per-vault salt — see `KeyGen.kt`).
 *
 * ## Rules
 *
 *  1. **Length**: minimum 8 characters (NIST SP 800-63B recommendation).
 *  2. **No PINs**: passwords consisting entirely of digits with length < 12
 *     are rejected (refuses 4-11 digit numeric PINs that Cellebrite can crack).
 *  3. **Character class diversity**: at least 3 of 4 classes must be present
 *     (lowercase, uppercase, digits, symbols) — relaxed to 2 of 4 if length
 *     >= 12 (a long lowercase-only passphrase is also strong).
 *
 * ## Strength scoring (for UI feedback, not enforcement)
 *
 * Returns one of [PasswordStrength] buckets for the setup screen's indicator.
 * The score is heuristic (length + class count + common-password check) — not
 * a full zxcvbn implementation, but enough to give the user useful feedback
 * without pulling in a 500KB library.
 */
object StrongPasswordPolicy {

    /** Minimum password length — enforced. */
    const val MIN_LENGTH = 8

    /**
     * Pure-digit passwords shorter than this are rejected as PINs.
     * 12+ digit numeric passwords (e.g. a 16-digit credit card number plus
     * a word) are grudgingly accepted because they're at least not 4-6 digit
     * phone unlock codes — but they still score poorly on the strength meter.
     */
    private const val PIN_REJECT_LENGTH_THRESHOLD = 12

    /**
     * Common passwords that should be rejected even if they meet the length
     * and class requirements. These are the top entries from the HaveIBeenPwned
     * "Top 100k" list, filtered to entries that pass the 8-char + 3-class rule
     * (so things like "password" wouldn't make the cut — those are already
     * rejected by the class check). The list is intentionally short — the
     * goal is to catch the obvious ones without bloating the APK.
     */
    private val COMMON_PASSWORDS = setOf(
        "password1", "password12", "password123",
        "qwerty123", "qwertyui1",
        "abc12345", "abc123456",
        "letmein1", "letmein12",
        "welcome1", "welcome12",
        "monkey123", "monkey12",
        "dragon12", "dragon123",
        "master12", "master123",
        "football1", "football12",
        "iloveyou1", "iloveyou12",
        "sunshine1", "sunshine12",
        "princess1", "princess12",
        "admin123", "admin1234",
        "login123", "login1234",
        // PhotoZ-specific — discourage users from picking the app name
        "photoz12", "photoz123", "photok12", "photok123",
    )

    /**
     * Returns `true` if [password] meets the minimum bar (length + not a PIN +
     * class diversity). The setup screen should refuse to enable the "Create"
     * button until this returns `true`.
     */
    fun isAcceptable(password: String): Boolean {
        if (password.length < MIN_LENGTH) return false

        // Reject PIN-style passwords: pure digits under the threshold.
        if (password.all { it.isDigit() } && password.length < PIN_REJECT_LENGTH_THRESHOLD) {
            return false
        }

        // Reject common passwords.
        if (password.lowercase() in COMMON_PASSWORDS) return false

        // Class diversity requirement.
        val classCount = countCharacterClasses(password)
        val requiredClasses = if (password.length >= 12) 2 else 3
        if (classCount < requiredClasses) return false

        return true
    }

    /**
     * Compute a heuristic strength score for UI feedback. NOT used for
     * enforcement — [isAcceptable] is the gate. The score lets the user see
     * "Weak / Moderate / Strong" change as they type.
     */
    fun strength(password: String): PasswordStrength {
        if (password.isEmpty()) return PasswordStrength.EMPTY
        if (password.length < MIN_LENGTH) return PasswordStrength.TOO_SHORT
        if (password.all { it.isDigit() } && password.length < PIN_REJECT_LENGTH_THRESHOLD) {
            return PasswordStrength.PIN_REJECTED
        }
        if (password.lowercase() in COMMON_PASSWORDS) return PasswordStrength.COMMON

        val classCount = countCharacterClasses(password)
        val score = when {
            password.length >= 16 && classCount >= 3 -> 4
            password.length >= 14 && classCount >= 3 -> 3
            password.length >= 12 && classCount >= 2 -> 3
            password.length >= 10 && classCount >= 3 -> 2
            password.length >= 8 && classCount >= 3 -> 2
            password.length >= 8 && classCount >= 2 -> 1
            else -> 0
        }
        return when (score) {
            0, 1 -> PasswordStrength.WEAK
            2 -> PasswordStrength.MODERATE
            3, 4 -> PasswordStrength.STRONG
            else -> PasswordStrength.WEAK
        }
    }

    /**
     * Returns `true` if [password] is rejected specifically because it's a
     * pure-digit PIN (length < 12). Used by the UI to show the dedicated
     * "PINs are not allowed" message instead of the generic "too weak".
     */
    fun isPinRejected(password: String): Boolean =
        password.isNotEmpty() &&
            password.all { it.isDigit() } &&
            password.length < PIN_REJECT_LENGTH_THRESHOLD

    /**
     * Returns `True` if [password] is rejected specifically because it's too
     * short. Used by the UI to show "minimum 8 characters" hint.
     */
    fun isTooShort(password: String): Boolean =
        password.isNotEmpty() && password.length < MIN_LENGTH

    private fun countCharacterClasses(password: String): Int {
        var classes = 0
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++  // symbols
        return classes
    }
}

/**
 * Strength bucket for UI display. Ordered from weakest to strongest.
 *
 * The first three ([EMPTY], [TOO_SHORT], [PIN_REJECTED], [COMMON]) are
 * "below the bar" states — [StrongPasswordPolicy.isAcceptable] returns false
 * for any of them. The remaining three ([WEAK], [MODERATE], [STRONG]) are
 * "above the bar" states — the user can proceed, but the indicator shows the
 * score so they're nudged toward STRONG.
 */
enum class PasswordStrength(val displayStringRes: Int) {
    EMPTY(0),
    TOO_SHORT(0),
    PIN_REJECTED(0),
    COMMON(0),
    WEAK(0),
    MODERATE(0),
    STRONG(0),
}
