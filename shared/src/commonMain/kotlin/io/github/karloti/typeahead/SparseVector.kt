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

import kotlinx.serialization.Serializable

/**
 * A highly memory-optimized representation of a mathematical sparse vector.
 * * This structure utilizes parallel arrays to eliminate object allocation overhead.
 * By utilizing a [FloatArray] instead of a DoubleArray, the memory footprint for
 * the vector weights is strictly halved. The 32-bit floating-point precision is
 * mathematically sufficient for relative cosine similarity ranking in search engines.
 *
 * The [features] array is strictly sorted alphabetically to enable highly
 * efficient two-pointer intersection algorithms during dot product computations.
 *
 * @property features A sorted array of vector keys representing linguistic N-grams.
 * @property weights A primitive array of L2-normalized weights, corresponding
 * exactly to the indices in [features].
 */
@Serializable
data class SparseVector(
    val features: Array<String>,
    val weights: FloatArray
) {
    /**
     * Computes the dot product (Cosine Similarity) between this vector and [other].
     * Uses a highly efficient two-pointer approach over the sorted feature arrays.
     *
     * @param other The target vector to compare against.
     * @return The similarity score [0.0f - 1.0f].
     */
    infix fun dotProduct(other: SparseVector): Float {
        var score = 0.0f
        var i = 0
        var j = 0

        val thisFeatures = this.features
        val otherFeatures = other.features
        val thisWeights = this.weights
        val otherWeights = other.weights

        val thisSize = thisFeatures.size
        val otherSize = otherFeatures.size

        while (i < thisSize && j < otherSize) {
            val cmp = thisFeatures[i].compareTo(otherFeatures[j])
            when {
                cmp == 0 -> {
                    score += thisWeights[i] * otherWeights[j]
                    i++
                    j++
                }
                cmp < 0 -> i++
                else -> j++
            }
        }

        return score
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SparseVector

        if (!features.contentEquals(other.features)) return false
        if (!weights.contentEquals(other.weights)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = features.contentHashCode()
        result = 31 * result + weights.contentHashCode()
        return result
    }
}