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
 * A data transfer object representing a matched item along with its search score
 * and a character-level heatmap for UI highlighting.
 *
 * @param T The type of the user-defined element.
 * @param item The original matched element from the dataset.
 * @param score The cosine similarity score representing the match quality (0.0 to 1.0).
 * @param heatmap A primitive array of the same length as the string representation of the [item].
 * Each index corresponds to a highlight tier (e.g., 0 for exact match, 1 for n-gram, 2 for skip-gram, or -1 for no match).
 */
@Serializable
data class HighlightedMatch<T>(
    val item: T,
    val score: Float,
    val heatmap: IntArray,
) {
    /**
     * Custom equality check is required because Kotlin data classes do not
     * perform deep structural equality checks on primitive arrays by default.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as HighlightedMatch<*>

        if (score != other.score) return false
        if (item != other.item) return false
        if (!heatmap.contentEquals(other.heatmap)) return false

        return true
    }

    /**
     * Generates a hash code using deep content hashing for the array.
     */
    override fun hashCode(): Int {
        var result = score.hashCode()
        result = 31 * result + (item?.hashCode() ?: 0)
        result = 31 * result + heatmap.contentHashCode()
        return result
    }
}