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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for a high-performance, in-memory fuzzy search engine with typeahead capabilities.
 *
 * Implementations use L2-normalized sparse vector embeddings and cosine similarity to provide
 * instant, typo-tolerant search results (handling transpositions, deletions, and insertions).
 * The engine behaves like a mutable concurrent collection, allowing callers to add, remove,
 * and search elements.
 *
 * ```kotlin
 * val engine: TypeaheadSearch<String, String> = TypeaheadSearchEngine(
 *     items = listOf("Kotlin", "Java", "Scala"),
 *     textSelector = { it },
 *     uniqueKeySelector = { it }
 * )
 * val results = engine.find("Kot") // [("Kotlin", 0.95f)]
 * ```
 *
 * @param T The type of elements held in this engine.
 * @param K The type of unique key used to identify each element.
 * @see TypeaheadSearchEngine
 */
interface TypeaheadSearch<T, K> {

    /**
     * A [StateFlow] containing the latest search results as `(item, score)` pairs,
     * updated after each [find] call.
     */
    val results: StateFlow<List<Pair<T, Float>>>

    /**
     * A [StateFlow] containing the latest search results with character-level heatmaps
     * for UI highlighting, updated after each [find] call.
     */
    val highlightedResults: StateFlow<List<HighlightedMatch<T>>>

    /**
     * The number of distinct text keys currently indexed in the vector space.
     *
     * Note: this counts unique text representations (as produced by the text selector),
     * not individual item objects. Multiple items sharing the same text key are counted once.
     */
    val size: Int

    /**
     * Finds the top matching elements for the given query using cosine similarity.
     *
     * The query string is vectorized into a [SparseVector] and compared against all
     * indexed embeddings via dot-product. A bounded priority queue retains only the
     * top results. Both [results] and [highlightedResults] are updated upon completion.
     *
     * Returns an empty list for blank queries.
     *
     * ```kotlin
     * val matches = engine.find("Kot")
     * // matches[0] == ("Kotlin" to 0.95f)
     * ```
     *
     * @param query The user's input string to search for.
     * @return A descending-score list of pairs containing the matched object and its
     *         similarity score `[0.0, 1.0]`.
     * @throws kotlinx.coroutines.CancellationException if the coroutine scope is cancelled.
     */
    suspend fun find(query: String): List<Pair<T, Float>>

    /**
     * Adds a single element to the search engine.
     *
     * The item is tokenized and its vector embedding is computed and stored in the index.
     * If an item with the same text key already exists, the new item is merged into the
     * existing inner map rather than replacing it.
     *
     * For bulk insertions, prefer [addAll] for significantly better throughput.
     *
     * ```kotlin
     * engine.add("Kotlin")
     * ```
     *
     * @param item The domain object to index.
     */
    suspend fun add(item: T)

    /**
     * Indexes multiple elements from a [Sequence] into the engine concurrently.
     *
     * ```kotlin
     * engine.addAll(sequenceOf("Kotlin", "Java", "Scala"))
     * ```
     *
     * @param items The sequence of domain objects to index.
     */
    suspend fun addAll(items: Sequence<T>)

    /**
     * Indexes multiple elements from an [Iterable] into the engine concurrently.
     *
     * ```kotlin
     * engine.addAll(listOf("Kotlin", "Java", "Scala"))
     * ```
     *
     * @param items The collection of domain objects to index.
     */
    suspend fun addAll(items: Iterable<T>)

    /**
     * Indexes multiple elements from a [Flow] into the engine with controlled concurrency.
     *
     * A transform function converts each source element [S] into a domain object [T]
     * before vectorization.
     *
     * ```kotlin
     * engine.addAll(dtoFlow) { dto -> dto.name }
     * ```
     *
     * @param S The source element type emitted by the flow.
     * @param items The flow of source elements to index.
     * @param transform A suspend function that converts each source element [S] into [T].
     */
    suspend fun <S> addAll(items: Flow<S>, transform: suspend (S) -> T)

    /**
     * Removes a specific element and its vector embedding from the search engine.
     *
     * Only the given [item] is removed. If other items share the same text key,
     * they are preserved. The text key entry is removed only when its inner map becomes empty.
     *
     * ```kotlin
     * engine.remove("Kotlin")
     * ```
     *
     * @param item The element to remove.
     */
    fun remove(item: T)

    /**
     * Clears the entire vector space and removes all indexed elements.
     *
     * After calling this method, [size] returns 0 and [getAllItems] returns an empty list.
     *
     * ```kotlin
     * engine.clear()
     * engine.size // 0
     * ```
     */
    fun clear()

    /**
     * Checks if an item with the same text key is currently indexed in the engine.
     *
     * Supports the idiomatic `in` operator syntax.
     *
     * ```kotlin
     * "Kotlin" in engine // true
     * ```
     *
     * @param item The element to check.
     * @return `true` if an item with the same text key exists, `false` otherwise.
     */
    operator fun contains(item: T): Boolean

    /**
     * Returns a snapshot list of all items currently indexed in the engine.
     *
     * Useful for displaying the initial dataset when the search query is empty.
     *
     * ```kotlin
     * val all = engine.getAllItems() // ["Kotlin", "Java"]
     * ```
     *
     * @return A read-only list of all domain objects.
     */
    fun getAllItems(): List<T>
}