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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

const val EPSILON = 1e-10
const val TOLERANCE = 1e-15


/**
 * Generates an L2-normalized hybrid positional N-gram embedding from the given string.
 *
 * This function constructs a sparse vector representing linguistic and structural
 * features of the string. It is optimized for typeahead and fuzzy matching by
 * combining anchors, prefixes (strict and fuzzy), skip-grams, and floating N-grams.
 *
 * The resulting vector is normalized using the L2 (Euclidean) norm. This ensures
 * that the magnitude of the vector is 1.0, allowing similarity to be calculated
 * via the dot product of two embeddings.
 *
 * @param maxNgramSize The maximum length of floating N-grams to extract. Larger values capture
 * more context but increase vector sparsity.
 * @param anchorWeight The weight assigned to the first character of the string. High values
 * prioritize matches that share the same starting letter.
 * @param lengthWeight The weight assigned to the length of the string. This helps filter
 * candidates by their physical size.
 * @param gestaltWeight The weight for the "Gestalt" feature (start char + length + end char).
 * Captures the overall visual "shape" of the word, resilient to internal typos.
 * @param prefixWeight The base multiplier for strict prefix matches. Weights grow linearly
 * with prefix length to reward deeper sequence matches.
 * @param fuzzyWeight The base multiplier for "fuzzy" prefixes. These features sort the characters
 * after the first letter, making the embedding resilient to character transpositions near the start.
 * @param skipWeight The weight for skip-grams (pairs of characters separated by one position).
 * This bridges gaps caused by single-character deletions or insertions.
 * @param floatingWeight The base weight for standard sliding-window N-grams ($2 \leq n \leq maxNgramSize$).
 * These form the structural backbone of the embedding.
 * * @return A [Map] representing a sparse vector where keys are feature identifiers and
 * values are their L2-normalized weights. Returns an empty map if the string is empty.
 * * @throws Exception Can yield during heavy loops to support non-blocking execution in coroutines.
 */
suspend fun String.toPositionalEmbedding(
    maxNgramSize: Int = 4,
    anchorWeight: Double = 10.0,
    lengthWeight: Double = 8.0,
    gestaltWeight: Double = 15.0,
    prefixWeight: Double = 6.0,
    fuzzyWeight: Double = 5.0,
    skipWeight: Double = 4.0,
    floatingWeight: Double = 2.5,
): Map<String, Double> {
    val vector = mutableMapOf<String, Double>()
    val word = this.lowercase()
    val length = word.length

    if (length == 0) return vector

    // 1. P0 Anchor (Absolute foundation)
    // The first letter is highly reliable in typeahead scenarios.
    vector["A_${word[0]}"] = anchorWeight

    // 2. Length Bucket (Length synchronizer)
    // Elevates words with the exact same length during typographical errors.
    vector["L_$length"] = lengthWeight

    // 3. Typoglycemia Gestalt Anchor
    // Captures perfectly 1-character typos where the length, first, and last characters match.
    if (length > 1) {
        vector["G_${word[0]}_${length}_${word.last()}"] = gestaltWeight
    }

    // 4. Strict & Fuzzy Prefixes (The core of Typeahead)
    for (i in 1..min(length, 8)) {
        val prefix = word.substring(0, i)

        // Strict Prefix: Rewards perfect typing sequence.
        vector["P_$prefix"] = i * prefixWeight

        if (i >= 2) {
            // Fuzzy Prefix: Mitigates transpositions (e.g., "Cna" vs "Canada").
            // Anchors the first character and sorts the remainder of the prefix.
            val sortedRest = prefix.substring(1).toCharArray().apply { sort() }.joinToString("")
            vector["F_${word[0]}_$sortedRest"] = i * fuzzyWeight
        }
        yield()
    }

    // 5. Skip-Grams (Bridge for deletions and insertions)
    for (i in 0 until length - 2) {
        val skipKey = "${word[i]}${word[i + 2]}"
        vector[skipKey] = (vector[skipKey] ?: 0.0) + skipWeight
    }

    // 6. Floating N-Grams (The structural skeleton)
    for (i in 0 until length) {
        for (n in 2..maxNgramSize) {
            if (i + n <= length) {
                val ngram = word.substring(i, i + n)
                // Linear progression for weights avoids complex branching operations.
                val weight = n * floatingWeight
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

    if (magnitude < EPSILON) return emptyMap()
    if (abs(magnitude - 1.0) < TOLERANCE) return vector

    val normalizedVector = mutableMapOf<String, Double>()
    for ((key, weight) in vector) {
        normalizedVector[key] = weight / magnitude
    }

    return normalizedVector
}
