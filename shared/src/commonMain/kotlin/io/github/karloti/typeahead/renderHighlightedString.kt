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
 * Renders the highlighted match in the console using ANSI color codes.
 * This provides a visual representation of the L2 vector matching heatmap.
 *
 * Each character in the original [text] is wrapped with an ANSI color code
 * corresponding to its tier in the heatmap (the receiver [IntArray]).
 *
 * Tiers mapping to colors:
 * - [TypeaheadSearchEngine.TIER_PRIMARY] (0) -> [brightYellow] (Exact prefix/solid match)
 * - [TypeaheadSearchEngine.TIER_SECONDARY] (1) -> [standardYellow] (Floating N-gram match)
 * - [TypeaheadSearchEngine.TIER_TERTIARY] (2) -> [cyan] (Skip-gram / Fuzzy bridge)
 * - [TypeaheadSearchEngine.TIER_NONE] (-1) -> [dimGray] (Unmatched character)
 *
 * @param text The original string to be formatted.
 * @param reset The ANSI escape sequence to reset colors. Defaults to `\u001B[0m`.
 * @param brightYellow The ANSI escape sequence for bright yellow. Defaults to `\u001B[93m`.
 * @param standardYellow The ANSI escape sequence for standard yellow. Defaults to `\u001B[33m`.
 * @param cyan The ANSI escape sequence for cyan. Defaults to `\u001B[36m`.
 * @param dimGray The ANSI escape sequence for dim gray. Defaults to `\u001B[90m`.
 * @return A visually colored string ready for console output.
 */
fun IntArray.renderHighlightedString(
    text: String,
    reset:String = "\u001B[0m",
    brightYellow:String = "\u001B[93m",
    standardYellow:String = "\u001B[33m",
    cyan:String = "\u001B[36m",
    dimGray:String = "\u001B[90m",
): String = text.mapIndexed { index, char ->
    val color = when (this[index]) {
        TypeaheadSearchEngine.TIER_PRIMARY -> brightYellow
        TypeaheadSearchEngine.TIER_SECONDARY -> standardYellow
        TypeaheadSearchEngine.TIER_TERTIARY -> cyan
        else -> dimGray
    }
    "$color$char$reset"
}.joinToString("")