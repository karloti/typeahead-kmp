/*
 * Copyright 2026 Kaloyan Karaivanov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.karloti.typeahead

/**
 * Renders this heatmap as an ANSI-colored string for terminal output.
 *
 * Each `(Char, Int)` pair in the receiver list is wrapped with the ANSI escape sequence
 * that corresponds to its match tier, producing a single string ready for `println()`.
 * Color parameters are fully customizable, so the same function can target any terminal
 * or be adapted to non-ANSI renderers by passing plain-text markers.
 *
 * Tier-to-color mapping:
 * - [TypeaheadSearchEngine.TIER_PRIMARY] (0) -> [brightYellow] — Exact positional match.
 * - [TypeaheadSearchEngine.TIER_SECONDARY] (1) -> [standardYellow] — Contiguous N-gram block.
 * - [TypeaheadSearchEngine.TIER_TERTIARY] (2) -> [cyan] — Scattered skip-gram match.
 * - [TypeaheadSearchEngine.TIER_NONE] (-1) -> [dimGray] — Unmatched character.
 *
 * ### Example:
 * ```kotlin
 * val engine = TypeaheadSearchEngine(listOf("Sofia", "Tokyo"), textSelector = { it })
 * engine.find("Sfoia")
 * val heatmap = engine.heatmap("Sofia")  // [('S', 0), ('o', 1), ('f', 1), ('i', 1), ('a', 0)]
 * println(heatmap?.toHighlightedString()) // prints "Sofia" with colored characters
 * ```
 *
 * @receiver A heatmap list produced by [TypeaheadSearchEngine.heatmap] or [String.toHeatmap].
 * @param reset The ANSI escape sequence to reset colors. Defaults to `\u001B[0m`.
 * @param brightYellow The ANSI escape sequence for bright yellow. Defaults to `\u001B[93m`.
 * @param standardYellow The ANSI escape sequence for standard yellow. Defaults to `\u001B[33m`.
 * @param cyan The ANSI escape sequence for cyan. Defaults to `\u001B[36m`.
 * @param dimGray The ANSI escape sequence for dim gray. Defaults to `\u001B[90m`.
 * @return A visually colored string ready for console output.
 */
fun List<Pair<Char, Int>>.toHighlightedString(
    reset:String = "\u001B[0m",
    brightYellow:String = "\u001B[93m",
    standardYellow:String = "\u001B[33m",
    cyan:String = "\u001B[36m",
    dimGray:String = "\u001B[90m",
): String = joinToString("") { (char, tier) ->
    val color = when (tier) {
        TypeaheadSearchEngine.TIER_PRIMARY -> brightYellow
        TypeaheadSearchEngine.TIER_SECONDARY -> standardYellow
        TypeaheadSearchEngine.TIER_TERTIARY -> cyan
        else -> dimGray
    }
    "$color$char$reset"
}