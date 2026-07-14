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

package onlasdan.gallery.encryption.domain.models

/**
 * Key Derivation Function types used by [onlasdan.gallery.encryption.domain.crypto.KeyGen].
 *
 * The KDF type is stored per-VaultProtection row in [VaultProtectionParams.kdf].
 * New vaults created after the Argon2id upgrade use [Argon2id]; old vaults
 * stay [PBKDF2WithHmacSHA256] (backwards compatible — version dispatch).
 *
 * @since v14 — Argon2id upgrade
 */
enum class Kdf(
	val value: String,
) {
	PBKDF2WithHmacSHA256("PBKDF2WithHmacSHA256"),

	/**
	 * (roadmap #3) — Argon2id: memory-hard KDF (2025 standard).
	 *
	 * Resistant to GPU/ASIC brute force (memory bandwidth bottleneck).
	 * PBKDF2 is only CPU-hard — GPUs can parallelize it efficiently.
	 *
	 * Parameters (stored in VaultProtectionParams):
	 * - kdfIterations = time cost (iterations)
	 * - kdfMemory = memory cost in KB (stored in the `iv` field as a
	 *   Base64-encoded 4-byte big-endian int — reuses the existing column
	 *   since Argon2 doesn't use an IV for key derivation, only for the
	 *   subsequent AES wrapping)
	 * - kdfParallelism = parallelism (fixed at 1 for Android — multi-thread
	 *   Argon2 doesn't help on mobile and adds complexity)
	 *
	 * @since v14
	 */
	Argon2id("Argon2id"),
}
