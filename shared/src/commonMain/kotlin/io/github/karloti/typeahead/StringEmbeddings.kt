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

import kotlinx.coroutines.yield
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Generates an L2-normalized hybrid positional N-gram embedding from the given string.
 * This advanced embedding handles typeahead (prefix matching) and fuzzy spellchecking
 * (transpositions, insertions, deletions) within a unified sparse vector space.
 *
 * @param maxNgramSize The maximum length of the floating N-grams. Default is 4.
 * @return A sparse vector represented as a Map where the key is the feature and the value is its L2-normalized weight.
 */
suspend fun String.toPositionalEmbedding(maxNgramSize: Int = 4): Map<String, Double> {
    val vector = mutableMapOf<String, Double>()
    val word = this.lowercase()
    val length = word.length

    if (length == 0) return vector

    // 1. P0 Anchor (Absolute foundation)
    // The first letter is highly reliable in typeahead scenarios.
    vector["P0_${word[0]}"] = 10.0

    // 2. Length Bucket (Length synchronizer)
    // Elevates words with the exact same length during typographical errors.
    vector["LEN_$length"] = 8.0

    // 3. Typoglycemia Gestalt Anchor
    // Captures perfectly 1-character typos where the length, first, and last characters match.
    if (length > 1) {
        vector["GESTALT_${word[0]}_${length}_${word.last()}"] = 15.0
    }

    // 4. Strict & Fuzzy Prefixes (The core of Typeahead)
    for (i in 1..min(length, 8)) {
        val prefix = word.substring(0, i)

        // Strict Prefix: Rewards perfect typing sequence.
        vector["PR_$prefix"] = i * 6.0

        if (i >= 2) {
            // Fuzzy Prefix: Mitigates transpositions (e.g., "Cna" vs "Canada").
            // Anchors the first character and sorts the remainder of the prefix.
            val sortedRest = prefix.substring(1).toCharArray().apply { sort() }.joinToString("")
            vector["FPR_${word[0]}_$sortedRest"] = i * 5.0
        }
        yield()
    }

    // 5. Skip-Grams (Bridge for deletions and insertions)
    for (i in 0 until length - 2) {
        val skipKey = "${word[i]}${word[i + 2]}"
        vector[skipKey] = (vector[skipKey] ?: 0.0) + 4.0
    }

    // 6. Floating N-Grams (The structural skeleton)
    for (i in 0 until length) {
        for (n in 2..maxNgramSize) {
            if (i + n <= length) {
                val ngram = word.substring(i, i + n)
                // Linear progression for weights avoids complex branching operations.
                val weight = n * 2.5
                vector[ngram] = (vector[ngram] ?: 0.0) + weight
            }
        }
        yield()
    }

    // --- L2 Normalization ---
    // Mathematically balances the vector, implicitly penalizing length disparities
    // unless compensated by substantial feature intersections.
    var sumOfSquares = 0.0
    for (weight in vector.values) {
        sumOfSquares += weight * weight
    }

    val magnitude = sqrt(sumOfSquares)

    if (magnitude == 0.0) return emptyMap()

    val normalizedVector = mutableMapOf<String, Double>()
    for ((key, weight) in vector) {
        normalizedVector[key] = weight / magnitude
    }

    return normalizedVector
}
