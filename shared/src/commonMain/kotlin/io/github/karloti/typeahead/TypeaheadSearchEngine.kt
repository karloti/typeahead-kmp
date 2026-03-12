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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.collections.iterator

/**
 * A high-performance, in-memory fuzzy search engine designed for typeahead capabilities.
 * It utilizes L2-normalized sparse vector embeddings and Cosine Similarity to provide
 * instant, typo-tolerant search results (handling transpositions, deletions, and insertions).
 * * It behaves like a mutable concurrent collection, allowing you to add, remove, and find elements.
 * * @param T The type of elements held in this engine.
 * @param textSelector A lambda function that extracts the searchable String from your object [T].
 * @param defaultDispatcher The coroutine dispatcher used for heavy vectorization. Defaults to [Dispatchers.Default].
 */
class TypeaheadSearchEngine<T>(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val textSelector: (T) -> String = { it.toString() }
) {

    // Holds the dataset mapped to their pre-computed, normalized spatial vectors
    private val _embeddings = MutableStateFlow<Map<T, Map<String, Double>>>(emptyMap())

    /**
     * Finds the best matching elements for the given query using Cosine Similarity.
     * Yields the thread cooperatively during heavy vector dot-product computations
     * to prevent UI blocking.
     *
     * @param query The user's input string.
     * @param maxResults The maximum number of top results to return.
     * @return A sorted list of pairs containing the matched object and its similarity score [0.0 - 1.0].
     */
    suspend fun find(query: String, maxResults: Int = 20): List<Pair<T, Double>> {
        if (query.isBlank()) return emptyList()

        return withContext(defaultDispatcher) {
            val queryVector = query.toPositionalEmbedding()

            val topResultsQueue = BoundedConcurrentPriorityQueue<Pair<T, Double>>(
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
     * Adds a single element to the engine.
     * The item will be tokenized and mathematically embedded into the vector space.
     * Note: If adding multiple elements, prefer [addAll] for significantly better performance.
     */
    suspend fun add(item: T) = withContext(defaultDispatcher) {
        val stringToVectorize = textSelector(item)
        val vector = stringToVectorize.toPositionalEmbedding()

        _embeddings.update { currentMap ->
            currentMap + (item to vector)
        }
    }

    /**
     * Batches multiple elements into the engine simultaneously.
     * This distributes the heavy mathematical vectorization workload concurrently
     * across all available CPU cores.
     */
    suspend fun addAll(items: Iterable<T>) = withContext(defaultDispatcher) {
        val newEmbeddings = items.map { item ->
            async {
                val stringToVectorize = textSelector(item)
                item to stringToVectorize.toPositionalEmbedding()
            }
        }.awaitAll()

        _embeddings.update { currentMap ->
            val mutableMap = currentMap.toMutableMap()
            newEmbeddings.forEach { (item, vector) ->
                mutableMap[item] = vector
            }
            mutableMap
        }
    }

    /**
     * Removes a specific element and its vector embedding from the engine.
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
     * Allows using the `in` operator (e.g., `item in searchEngine`).
     */
    operator fun contains(item: T): Boolean {
        return _embeddings.value.containsKey(item)
    }

    /**
     * Returns a snapshot list of all items currently in the engine.
     * Useful for displaying default lists when the search query is empty.
     */
    fun getAllItems(): List<T> {
        return _embeddings.value.keys.toList()
    }

    /**
     * Exports the current state of the search engine as a lazy [Sequence].
     * By utilizing a sequence, the export process is stream-based and highly memory-efficient,
     * preventing OutOfMemory errors when handling massive datasets.
     * * The consumer of this sequence is responsible for the actual byte-level serialization
     * (e.g., to JSON, ProtoBuf, or a Database).
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
     * This bypasses the heavy mathematical vectorization process, loading pre-computed
     * embeddings directly into memory.
     *
     * @param records A lazy sequence of [TypeaheadRecord] elements to be loaded.
     * @param clearExisting If true, completely replaces the current state. If false, merges with existing data.
     */
    suspend fun importFromSequence(
        records: Sequence<TypeaheadRecord<T>>,
        clearExisting: Boolean = true
    ) = withContext(defaultDispatcher) {

        // Use a local mutable map to build the state efficiently before pushing to StateFlow
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
        suspend operator fun <T> invoke(
            items: Iterable<T>,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            textSelector: (T) -> String = { it.toString() }
        ) = TypeaheadSearchEngine(defaultDispatcher, textSelector)
            .apply { addAll(items) }
    }
}