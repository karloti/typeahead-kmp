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
import kotlin.collections.iterator

/**
 * Computes the dot product of two sparse vectors.
 * Because the vectors are L2-normalized, this operation effectively yields the Cosine Similarity,
 * producing a score between 0.0 and 1.0.
 *
 * This function is highly optimized by iterating only over the smaller vector space.
 *
 * @param other The target vector to compare against.
 * @return The calculated similarity score [0.0 - 1.0]. Higher means more similar.
 */
infix fun Map<String, Double>.dotProduct(other: Map<String, Double>): Double {
    var score = 0.0
    // Always iterate over the smaller map to minimize lookups
    val (smaller, larger) = if (this.size < other.size) this to other else other to this

    for ((key, weight1) in smaller) {
        val weight2 = larger[key]
        if (weight2 != null) {
            score += weight1 * weight2
        }
//        yield()
    }

    return score
}