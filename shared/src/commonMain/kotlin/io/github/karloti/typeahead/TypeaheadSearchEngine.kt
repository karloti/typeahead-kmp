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

@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package io.github.karloti.typeahead

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * A high-performance, in-memory fuzzy search engine designed for typeahead capabilities.
 * * It utilizes L2-normalized sparse vector embeddings and Cosine Similarity to provide
 * instant, typo-tolerant search results (handling transpositions, deletions, and insertions).
 * It behaves like a mutable concurrent collection, allowing you to add, remove, and find elements.
 * * @param T The type of elements held in this engine.
 * @param defaultDispatcher The coroutine dispatcher used for heavy computational vectorization. Defaults to [Dispatchers.Default].
 * @param textSelector A lambda function that extracts the searchable textual representation from your object [T].
 */
class TypeaheadSearchEngine<T>(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val textSelector: (T) -> String = { it.toString() }
) {

    // Holds the dataset mapped to their pre-computed, normalized spatial vectors
    private val _embeddings = MutableStateFlow<Map<T, SparseVector>>(emptyMap())

    /**
     * Finds the best matching elements for the given query using Cosine Similarity.
     * Yields the thread cooperatively during heavy vector dot-product computations
     * to prevent UI blocking on edge devices.
     *
     * @param query The user's input string.
     * @param maxResults The maximum number of top results to return.
     * @return A sorted list of pairs containing the matched object and its similarity score [0.0 - 1.0].
     */
    suspend fun find(query: String, maxResults: Int = 5): List<Pair<T, Float>> {
        if (query.isBlank()) return emptyList()

        return withContext(defaultDispatcher) {
            val queryVector = query.toPositionalEmbedding()

            val topResultsQueue = BoundedConcurrentPriorityQueue<Pair<T, Float>>(
                maxSize = maxResults,
                comparator = compareByDescending { it.second }
            )

            val currentMap = _embeddings.value

            for ((item, targetVector) in currentMap) {
                val score = queryVector dotProduct targetVector
                // Discard absolute zero matches immediately to save queue operations
                if (score > 0.0) {
                    topResultsQueue.add(item to score)
                }
                yield()
            }

            topResultsQueue.items.value
        }
    }

    /**
     * Executes a search and automatically applies a character-level highlighting alignment
     * to the resulting matches. This represents a highly optimized "Two-Phase" search pipeline.
     *
     * @param query The user's input string.
     * @param maxResults The maximum number of top results to return.
     * @return A list of [HighlightedMatch] containing the item, score, and the visual heatmap array.
     */
    suspend fun findWithHighlights(query: String, maxResults: Int = 5): List<HighlightedMatch<T>> {
        val results = find(query, maxResults)
        return results.map { match ->
            val targetText = textSelector(match.first)
            val heatmap = computeHeatmap(query, targetText)

            HighlightedMatch(
                item = match.first,
                score = match.second,
                heatmap = heatmap,
            )
        }
    }

    /**
     * Adds a single element to the engine.
     * The item will be tokenized and mathematically embedded into the vector space.
     * * Note: If adding multiple elements, prefer [addAll] for significantly better batch performance.
     *
     * @param item The domain object to index.
     */
    suspend fun add(item: T) = withContext(defaultDispatcher) {
        val stringToVectorize = textSelector(item)
        val vector = stringToVectorize.toPositionalEmbedding()

        _embeddings.update { currentMap ->
            currentMap + (item to vector)
        }
    }

    /**
     * Batches multiple elements into the search engine's spatial index concurrently.
     * * This operation is heavily optimized for Edge devices and memory-constrained environments
     * across all Kotlin Multiplatform targets. It strictly separates the heavy mathematical
     * vectorization from the state mutation phase to prevent computational thrashing.
     * * To avoid Coroutine Explosion (OOM errors) associated with unbounded [async] builders,
     * this method utilizes [flatMapMerge] to achieve controlled, backpressured concurrency.
     *
     * @param items The collection of domain objects to be indexed.
     * @param concurrencyLevel The maximum number of concurrent tokenization tasks.
     * Defaults to [DEFAULT_CONCURRENCY] (typically 16), which provides excellent
     * CPU saturation on mobile devices without context-switching overhead.
     */
    suspend fun addAll(
        items: Iterable<T>,
        concurrencyLevel: Int = DEFAULT_CONCURRENCY
    ) = withContext(defaultDispatcher) {

        // 1. Initialize a highly efficient mutable map with expected capacity
        // to prevent dynamic resizing overhead under the hood.
        val collectionSize = if (items is Collection<*>) items.size else 16
        val computedEntries = HashMap<T, SparseVector>(collectionSize)
        // 2. Process elements using controlled concurrency (Bounded Parallelism)
        items.asFlow()
            .flatMapMerge(concurrency = concurrencyLevel) { item ->
                flow {
                    val embedding = textSelector(item).toPositionalEmbedding()
                    emit(item to embedding)
                }
            }
            .collect { (item, vector) ->
                // Zero-allocation collection: directly populate the map
                computedEntries[item] = vector
            }

        // 3. Perform a lightning-fast, atomic state update in O(1) time.
        _embeddings.update { currentMap ->
            currentMap + computedEntries
        }
    }

    /**
     * Removes a specific element and its vector embedding from the engine.
     * * @param item The element to remove.
     */
    fun remove(item: T) {
        _embeddings.update { currentMap ->
            currentMap - item
        }
    }

    /**
     * Clears the entire vector space and removes all indexed elements.
     */
    fun clear() {
        _embeddings.value = emptyMap()
    }

    /**
     * Returns the current number of indexed elements in the vector space.
     */
    val size: Int
        get() = _embeddings.value.size

    /**
     * Checks if a specific item is currently indexed in the engine.
     * Allows using the intuitive `in` operator (e.g., `item in searchEngine`).
     * * @param item The element to check.
     * @return True if the item exists in the index, false otherwise.
     */
    operator fun contains(item: T): Boolean {
        return _embeddings.value.containsKey(item)
    }

    /**
     * Returns a snapshot list of all items currently in the engine.
     * Extremely useful for displaying default lists when the search query is empty.
     * * @return A read-only list of all domain objects.
     */
    fun getAllItems(): List<T> {
        return _embeddings.value.keys.toList()
    }

    /**
     * Exports the current state of the search engine as a lazy [Sequence].
     * * By utilizing a sequence, the export process is stream-based and highly memory-efficient,
     * preventing OutOfMemory errors when handling massive datasets. The consumer of this
     * sequence is responsible for the actual byte-level serialization (e.g., JSON, ProtoBuf).
     *
     * @return A lazy sequence containing [TypeaheadRecord] objects.
     */
    fun exportAsSequence(): Sequence<TypeaheadRecord<T>> {
        return _embeddings.value.asSequence().map { (item, vector) ->
            TypeaheadRecord(item = item, vector = vector)
        }
    }

    /**
     * Restores or merges the search engine's state from a streamed [Sequence] of records.
     * * This bypasses the heavy mathematical vectorization process by loading pre-computed
     * embeddings directly into memory.
     *
     * @param records A lazy sequence of [TypeaheadRecord] elements to be loaded.
     * @param clearExisting If true, completely replaces the current state. If false, merges with existing data.
     */
    suspend fun importFromSequence(
        records: Sequence<TypeaheadRecord<T>>,
        clearExisting: Boolean = true
    ) = withContext(defaultDispatcher) {

        val newMap = if (clearExisting) {
            mutableMapOf()
        } else {
            _embeddings.value.toMutableMap()
        }

        for (record in records) {
            newMap[record.item] = record.vector
            // Yield cooperatively to keep the thread responsive during massive imports
            yield()
        }

        // Atomic update of the entire vector space
        _embeddings.value = newMap
    }

    companion object {
        const val TIER_NONE = -1
        const val TIER_PRIMARY = 0
        const val TIER_SECONDARY = 1
        const val TIER_TERTIARY = 2

        /**
         * Factory function to instantiate a [TypeaheadSearchEngine] and immediately
         * populate it with an initial dataset.
         * * @param T The type of elements.
         * @param items The initial dataset to index.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
         * @param textSelector Lambda to extract searchable text from the items.
         */
        suspend operator fun <T> invoke(
            items: Iterable<T>,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            textSelector: (T) -> String = { it.toString() }
        ) = TypeaheadSearchEngine(defaultDispatcher, textSelector)
            .apply { addAll(items) }

        /**
         * Generates a spatial heatmap array representing highlight tiers for each character
         * in the [target] string based on the [query].
         *
         * This algorithm uses a "Longest Match First" approach. It tracks which parts
         * of the query have already been matched to prevent smaller N-grams from highlighting
         * visually confusing fragments if they belong to a larger continuous match.
         */
        fun computeHeatmap(query: String, target: String, maxNgramSize: Int = 4): IntArray {
            val q = query.lowercase()
            val t = target.lowercase()

            val targetLen = t.length
            val queryLen = q.length

            val heatmap = IntArray(targetLen) { TIER_NONE }

            if (queryLen == 0 || targetLen == 0) return heatmap

            // Array to track which characters of the query have been successfully
            // mapped to the target. Prevents smaller n-grams from "double-dipping".
            val queryCovered = BooleanArray(queryLen)

            // --- Step 1: Prefix / Primary Alignment (Tier 0) ---
            var prefixMatchLength = 0
            while (prefixMatchLength < queryLen && prefixMatchLength < targetLen
                && q[prefixMatchLength] == t[prefixMatchLength]
            ) {
                heatmap[prefixMatchLength] = TIER_PRIMARY
                queryCovered[prefixMatchLength] = true // Mark as consumed
                prefixMatchLength++
            }

            // --- Step 2: Floating N-grams (Tier 1) ---
            val maxPossibleNgram = minOf(maxNgramSize, queryLen)

            for (n in maxPossibleNgram downTo 2) {
                for (i in 0..queryLen - n) {

                    // Check if this specific n-gram from the query is already entirely
                    // part of a larger successful match. If so, skip it.
                    var isFullyCovered = true
                    for (k in 0 until n) {
                        if (!queryCovered[i + k]) {
                            isFullyCovered = false
                            break
                        }
                    }
                    if (isFullyCovered) continue

                    val ngramStart = i
                    var foundInTarget = false

                    for (j in 0..targetLen - n) {
                        if (t.regionMatches(j, q, ngramStart, n, ignoreCase = true)) {
                            foundInTarget = true
                            for (k in 0 until n) {
                                if (heatmap[j + k] > TIER_SECONDARY || heatmap[j + k] == TIER_NONE) {
                                    heatmap[j + k] = TIER_SECONDARY
                                }
                            }
                        }
                    }

                    // If we found a match in the target, mark these query characters as consumed
                    if (foundInTarget) {
                        for (k in 0 until n) {
                            queryCovered[i + k] = true
                        }
                    }
                }
            }

            // --- Step 3: Skip-Grams / Fuzzy Bridges (Tier 2) ---
            if (queryLen >= 2) {
                for (i in 0 until queryLen - 1) {
                    // Only apply skip-grams for parts of the query that failed to match as a solid block
                    if (queryCovered[i] && queryCovered[i + 1]) continue

                    val charA = q[i]
                    val charB = q[i + 1]

                    for (j in 0 until targetLen - 2) {
                        if (t[j] == charA && t[j + 2] == charB) {
                            if (heatmap[j] == TIER_NONE) heatmap[j] = TIER_TERTIARY
                            if (heatmap[j + 2] == TIER_NONE) heatmap[j + 2] = TIER_TERTIARY
                        }
                    }
                }
            }

            return heatmap
        }
    }
}