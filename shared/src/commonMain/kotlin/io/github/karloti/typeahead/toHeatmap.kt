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

import kotlin.math.min

/**
 * Computes a character-level heatmap that visually represents how well this query string
 * matches against a target string. Each character in the target is paired with a tier value
 * indicating match quality. The heatmap uses a **multi-tier matching algorithm** inspired by
 * human cognitive pattern recognition during typeahead scenarios.
 *
 * This function implements a **greedy, three-phase alignment strategy**:
 *
 * 1. **Exact Positional Matches (TIER_PRIMARY / Bright Yellow)**:
 *    - Locks in characters that appear in the exact same position in both the query and target.
 *    - This represents the "anchor points" of perfect typing.
 *
 * 2. **Contiguous N-Grams (TIER_SECONDARY / Dark Yellow)**:
 *    - Finds the longest remaining sequences (2+ characters) that match consecutively.
 *    - Emulates how humans recognize "blocks" of correct text even if misplaced.
 *
 * 3. **Scattered Skip-Grams (TIER_TERTIARY / Cyan)**:
 *    - Matches individual remaining characters in a left-to-right greedy manner.
 *    - Handles deletions, insertions, and severe transpositions.
 *
 * 4. **Unmatched Characters (TIER_NONE / Dim Gray)**:
 *    - Any character in the target that couldn't be matched remains at the default tier.
 *
 * The algorithm uses **primitive boolean arrays** as "consumed character pools" to achieve
 * `O(1)` tracking without allocating Lists or Objects, making it highly memory-efficient
 * for mobile and wasm environments.
 *
 * ### Example:
 * ```kotlin
 * val query = "Sfoia"
 * val target = "Sofia"
 * val heatmap = query.toHeatmap(target)
 * // heatmap = [('S', 0), ('f', 1), ('o', 1), ('i', 1), ('a', 0)]
 * // Tier 0: 'S' and 'a' are exact positional matches
 * // Tier 1: 'f', 'o', 'i' form a contiguous block match
 * ```
 *
 * @receiver The query string typed by the user (e.g., "Sfoia").
 * @param targetStr The candidate string to match against (e.g., "Sofia").
 * @param ignoreCase Whether to perform case-insensitive matching. Defaults to `true`.
 * @return A [List] of [Pair]s where each entry maps a character from [targetStr] to its
 *         match tier:
 *         - [TypeaheadSearchEngine.Companion.TIER_PRIMARY] (0): Exact positional match.
 *         - [TypeaheadSearchEngine.Companion.TIER_SECONDARY] (1): Part of a contiguous N-gram block.
 *         - [TypeaheadSearchEngine.Companion.TIER_TERTIARY] (2): Scattered single-character match.
 *         - [TypeaheadSearchEngine.Companion.TIER_NONE] (-1): Unmatched character (default).
 */
fun String.toHeatmap(
    targetStr: String,
    ignoreCase: Boolean = true
): List<Pair<Char, Int>> {
    val q = this
    val queryLen = q.length
    val targetLen = targetStr.length

    // The final heatmap array to return
    val heatmapInts = IntArray(targetLen) { TypeaheadSearchEngine.TIER_NONE }

    // Primitive boolean arrays to act as our "remaining pool".
    // True means the character has been matched and removed from the pool.
    // This provides O(1) tracking without allocating Lists or Objects.
    val qConsumed = BooleanArray(queryLen)
    val tConsumed = BooleanArray(targetLen)

    // --- STEP 1: Exact Positional Matches (Tier 0 / Bright Yellow) ---
    // Emulates human logic: "Lock in the characters that are in the exact right spot."
    val minLen = min(queryLen, targetLen)
    for (i in 0 until minLen) {
        if (q[i].equals(targetStr[i], ignoreCase)) {
            heatmapInts[i] = TypeaheadSearchEngine.TIER_PRIMARY
            qConsumed[i] = true
            tConsumed[i] = true
        }
    }

    // --- STEP 2: Contiguous N-Grams (Tier 1 / Dark Yellow) ---
    // Emulates human logic: "Find the longest remaining blocks of text."
    // We look for any unconsumed sequence of length >= 2.
    for (i in 0 until queryLen) {
        if (qConsumed[i]) continue

        var bestTargetIdx = -1
        var bestMatchLen = 0

        for (j in 0 until targetLen) {
            if (tConsumed[j]) continue

            var currentLen = 0
            // Keep checking forward as long as characters match and are unconsumed
            while (i + currentLen < queryLen &&
                j + currentLen < targetLen &&
                !qConsumed[i + currentLen] &&
                !tConsumed[j + currentLen] &&
                q[i + currentLen].equals(targetStr[j + currentLen], ignoreCase)
            ) {
                currentLen++
            }

            // Greedily remember the longest contiguous block found
            if (currentLen > bestMatchLen) {
                bestMatchLen = currentLen
                bestTargetIdx = j
            }
        }

        // If we found a valid N-gram block (2 or more characters)
        if (bestMatchLen >= 2) {
            for (k in 0 until bestMatchLen) {
                heatmapInts[bestTargetIdx + k] = TypeaheadSearchEngine.TIER_SECONDARY
                qConsumed[i + k] = true
                tConsumed[bestTargetIdx + k] = true
            }
        }
    }

    // --- STEP 3: Scattered Skip-Grams (Tier 2 / Cyan) ---
    // Emulates human logic: "Connect whatever individual letters are left over."
    for (i in 0 until queryLen) {
        if (qConsumed[i]) continue

        for (j in 0 until targetLen) {
            if (!tConsumed[j] && q[i].equals(targetStr[j], ignoreCase)) {
                heatmapInts[j] = TypeaheadSearchEngine.TIER_TERTIARY

                // Mark as consumed so it cannot be reused
                qConsumed[i] = true
                tConsumed[j] = true
                break // Move to the next query character immediately
            }
        }
    }
    // Step 4 is implicit: Anything left unconsumed in the target remains TIER_NONE (Dim Gray).
    return targetStr.mapIndexed { index, ch -> ch to heatmapInts[index] }
}