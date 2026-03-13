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

const val EPSILON_FLOAT = 1e-7f
const val TOLERANCE_FLOAT = 1e-6f

/**
 * Generates an L2-normalized hybrid positional N-gram embedding from the given string.
 *
 * This function constructs a sparse vector utilizing 32-bit floating-point precision
 * to maximize memory efficiency on Edge devices. The resulting vector is L2-normalized,
 * ensuring its magnitude is 1.0f, which allows direct cosine similarity calculation
 * via the dot product.
 *
 * @param maxNgramSize The maximum length of the sliding N-grams.
 * @param anchorWeight Weight for the first character, crucial for typeahead reliability.
 * @param lengthWeight Weight for the word length, used to synchronize candidates of similar size.
 * @param gestaltWeight Weight for the "Gestalt" feature.
 * @param prefixWeight Base multiplier for strict prefix matches.
 * @param fuzzyWeight Base multiplier for sorted prefixes.
 * @param skipWeight Weight for skip-grams.
 * @param floatingWeight Base weight for standard sliding-window N-grams.
 * @return A highly optimized [SparseVector]. Returns an empty vector if the string is empty.
 */
suspend fun String.toPositionalEmbedding(
    maxNgramSize: Int = 4,
    anchorWeight: Float = 10.0f,
    lengthWeight: Float = 8.0f,
    gestaltWeight: Float = 15.0f,
    prefixWeight: Float = 6.0f,
    fuzzyWeight: Float = 5.0f,
    skipWeight: Float = 4.0f,
    floatingWeight: Float = 2.5f,
): SparseVector {
    val vector = mutableMapOf<String, Float>()
    val word = this.lowercase()
    val length = word.length

    if (length == 0) return SparseVector(emptyArray(), FloatArray(0))

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
        vector[skipKey] = (vector[skipKey] ?: 0.0f) + skipWeight
    }

    // 6. Floating N-Grams (The structural skeleton)
    for (i in 0 until length) {
        for (n in 2..maxNgramSize) {
            if (i + n <= length) {
                val ngram = word.substring(i, i + n)
                // Linear progression for weights avoids complex branching operations.
                val weight = n * floatingWeight
                vector[ngram] = (vector[ngram] ?: 0.0f) + weight
            }
        }
        yield()
    }

    // --- L2 Normalization ---
    // Mathematically balances the vector, implicitly penalizing length disparities
    // unless compensated by substantial feature intersections.
    var sumOfSquares = 0.0f
    for (weight in vector.values) {
        sumOfSquares += weight * weight
    }

    val magnitude = sqrt(sumOfSquares.toDouble()).toFloat()

    if (magnitude < EPSILON_FLOAT) return SparseVector(emptyArray(), FloatArray(0))

    val isAlreadyNormalized = abs(magnitude - 1.0f) < TOLERANCE_FLOAT

    val sortedKeys = vector.keys.sorted().toTypedArray()
    val primitiveWeights = FloatArray(sortedKeys.size)

    for (i in sortedKeys.indices) {
        val key = sortedKeys[i]
        val rawWeight = vector[key]!!
        primitiveWeights[i] = if (isAlreadyNormalized) rawWeight else (rawWeight / magnitude)
    }

    return SparseVector(features = sortedKeys, weights = primitiveWeights)
}