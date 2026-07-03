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

import androidx.lifecycle.LiveData

/**
 * Utils to validate passwords.
 *
 * @since 1.0.0
 * @author PhotoZ
 */
object PasswordUtils {

    /**
     * Validates a password string.
     *
     * @since Sprint 1 / P7 — delegates to [StrongPasswordPolicy.isAcceptable]
     *   which enforces: min 8 chars, not a PIN, character class diversity,
     *   not in the common-password list.
     */
    fun validatePassword(password: String) = StrongPasswordPolicy.isAcceptable(password)

    /**
     * Indicates if two password equal.
     */
    fun passwordsNotEmptyAndEqual(password: String, confirmPassword: String) = password.isNotEmpty()
            && confirmPassword.isNotEmpty()
            && password == confirmPassword

    fun passwordsNotEmptyAndEqual(password: LiveData<String>, confirmPassword: LiveData<String>) =
        passwordsNotEmptyAndEqual(password.value!!, confirmPassword.value!!)

    /**
     * Indicates if two password are valid and equal.
     */
    fun validatePasswords(password: String, confirmPassword: String) = validatePassword(password)
            && validatePassword(confirmPassword)
            && passwordsNotEmptyAndEqual(password, confirmPassword)

    fun validatePasswords(password: LiveData<String>, confirmPassword: LiveData<String>) =
        validatePasswords(password.value!!, confirmPassword.value!!)
}
