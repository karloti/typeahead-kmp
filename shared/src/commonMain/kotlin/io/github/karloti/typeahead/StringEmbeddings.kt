package io.github.karloti.typeahead
import kotlinx.coroutines.yield
import kotlin.collections.iterator
import kotlin.math.min

/**
 * Generates a hybrid positional N-gram embedding from the given string.
 * This embedding captures exact positional matches, skip-grams (to bridge typographical errors),
 * and floating N-grams (to reward sequence momentum).
 *
 * @param maxNgramSize The maximum length of the floating N-grams. Default is 5.
 * @return A sparse vector represented as a Map where the key is the N-gram feature and the value is its weight.
 */
suspend fun String.toPositionalEmbedding(maxNgramSize: Int = 5): Map<String, Double> {
    val vector = mutableMapOf<String, Double>()
    val word = this.lowercase()
    val length = word.length

    if (length == 0) return vector

    // 1. Absolute Positional Anchors
    for (i in 0 until min(length, 8)) {
        val anchorKey = "P${i}_${word[i]}"
        vector[anchorKey] = 10.0 - i
        yield()
    }

    // 2. Skip-Grams (1-Gap Patterns)
    for (i in 0 until length - 2) {
        val skipKey = "S_${word[i]}_${word[i + 2]}"
        vector[skipKey] = (vector[skipKey] ?: 0.0) + 5.0
        yield()
    }

    // 3. Floating N-Grams (Sequence Momentum)
    for (i in 0 until length) {
        for (n in 2..maxNgramSize) {
            if (i + n <= length) {
                val ngram = word.substring(i, i + n)
                val weight = (n * n).toDouble()
                vector[ngram] = (vector[ngram] ?: 0.0) + weight
            }
        }
        yield()
    }

    return vector
}

/**
 * Computes the dot product of two sparse vectors.
 * This is highly optimized by iterating only over the smaller vector.
 *
 * @param other The target vector to compare against.
 * @return The calculated similarity score. Higher means more similar.
 */
suspend infix fun Map<String, Double>.dotProduct(other: Map<String, Double>): Double {
    var score = 0.0
    val (smaller, larger) = if (this.size < other.size) this to other else other to this

    for ((key, weight1) in smaller) {
        val weight2 = larger[key]
        if (weight2 != null) {
            score += weight1 * weight2
        }
        yield()
    }

    return score
}