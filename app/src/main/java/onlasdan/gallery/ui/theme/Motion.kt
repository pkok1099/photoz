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

package onlasdan.gallery.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring

/**
 * Sprint 5 / M9 — Material 3 Expressive motion constants.
 *
 * Centralizes the spring + easing curves so callers reference
 * `Motion.Spring.Default` etc. instead of hardcoding `spring()` calls
 * scattered across the codebase. The values follow Material 3 Expressive
 * (2025) motion guidance:
 *
 *  - **Springs** are preferred over duration-based tweens for organic,
 *    physics-driven motion. The default spring uses a moderate stiffness
 *    (400 = Spring.StiffnessMediumLow) and medium-bouncy damping (0.5) —
 *    bouncy enough to feel alive, damped enough not to feel jittery.
 *  - **Easings** are kept for cases where springs don't fit (e.g. fade
 *    transitions where physical motion isn't the metaphor). The standard
 *    Material 3 easing tokens (Emphasized, Standard, etc.) are exposed
 *    here so callers don't import CubicBezierEasing directly.
 *
 * Usage:
 * ```
 * AnimatedVisibility(
 *     visible = ...,
 *     enter = fadeIn(Motion.Tween.Emphasized) + slideInVertically(...),
 *     exit = fadeOut(Motion.Tween.Emphasized) + slideOutVertically(...),
 * )
 * ```
 *
 * Or for spring-based:
 * ```
 * val scale by animateFloatAsState(
 *     targetValue = if (selected) 1.2f else 1f,
 *     animationSpec = Motion.Spring.Default,
 * )
 * ```
 *
 * @since v12 — Sprint 5 / M9 Material 3 Expressive
 */
object Motion {
	/**
	 * Spring-based animation specs — preferred for organic motion.
	 *
	 * Uses literal stiffness/damping values instead of `Spring.StiffnessLow`
	 * etc. because some Compose BOM versions don't expose those constants
	 * as Kotlin-resolvable static fields on the `Spring` singleton. The
	 * literals match the canonical AndroidX values:
	 *   - StiffnessHigh = 10000f
	 *   - StiffnessMedium = 1500f
	 *   - StiffnessMediumLow = 400f
	 *   - StiffnessLow = 200f
	 *   - DampingRatioNoBouncy = 1f
	 *   - DampingRatioLowBouncy = 0.75f
	 *   - DampingRatioMediumBouncy = 0.5f
	 *   - DampingRatioHighBouncy = 0.6f (yes, lower than MediumBouncy —
	 *     "high bouncy" means more oscillation, which is LOWER damping)
	 */
	object Spring {
		/** Default spring: MediumLow stiffness (400), MediumBouncy damping (0.5). Use for most UI. */
		val Default =
			spring<Float>(
				dampingRatio = 0.5f,
				stiffness = 400f,
			)

		/** Snappy spring: high stiffness (10000), no bounce (1.0). Use for taps / presses. */
		val Snappy =
			spring<Float>(
				dampingRatio = 1.0f,
				stiffness = 10000f,
			)

		/** Bouncy spring: low stiffness (200), high bounce (0.6). Use for celebratory / playful UI. */
		val Bouncy =
			spring<Float>(
				dampingRatio = 0.6f,
				stiffness = 200f,
			)
	}

	/**
	 * Duration-based easing curves — use when springs don't fit (e.g. pure
	 * fades). These are the standard Material 3 motion tokens.
	 */
	object Tween {
		/** Emphasized easing — the default for most transitions. */
		val Emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

		/** Standard easing — for less-prominent transitions. */
		val Standard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

		/** Decelerate easing — for elements entering the screen. */
		val Decelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)

		/** Accelerate easing — for elements leaving the screen. */
		val Accelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
	}

	/**
	 * Standard Material 3 durations (in milliseconds).
	 */
	object Duration {
		const val Short = 150
		const val Medium = 300
		const val Long = 450
	}
}
