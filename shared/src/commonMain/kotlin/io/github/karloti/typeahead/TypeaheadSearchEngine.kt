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

import ConcurrentPriorityQueue
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A high-performance, in-memory fuzzy search engine designed for typeahead capabilities.
 *
 * It utilizes L2-normalized sparse vector embeddings and Cosine Similarity to provide
 * instant, typo-tolerant search results (handling transpositions, deletions, and insertions).
 * It behaves like a mutable concurrent collection, allowing you to add, remove, and find elements.
 *
 * @param T The type of elements held in this engine.
 * @param K The type of unique key used to identify each element.
 * @param defaultDispatcher The coroutine dispatcher used for heavy computational vectorization. Defaults to [Dispatchers.Default].
 * @param ignoreCase Whether to ignore character casing during search and tokenization. Defaults to `true`.
 * @param maxNgramSize The maximum size of N-grams to extract for floating positional matching. Defaults to 4.
 * @param anchorWeight The weight applied to the first character match (P0 Anchor). Defaults to 10.0.
 * @param lengthWeight The weight applied to the exact length bucket match. Defaults to 8.0.
 * @param gestaltWeight The weight for the Typoglycemia Gestalt anchor (matching first, last, and length). Defaults to 15.0.
 * @param prefixWeight The weight for strict prefix matching. Defaults to 6.0.
 * @param fuzzyWeight The weight for fuzzy prefix matching (transposition tolerant). Defaults to 5.0.
 * @param skipWeight The weight for skip-gram matching (insertion/deletion tolerant). Defaults to 4.0.
 * @param floatingWeight The weight for floating N-gram matching. Defaults to 2.5.
 * @param textSelector A lambda function that extracts the searchable textual representation from your object [T]. Defaults to `toString()`.
 * @param uniqueKeySelector A lambda function that extracts a unique key for each element [T]. Defaults to `it`.
 */
class TypeaheadSearchEngine<T, K>(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ignoreCase: Boolean = DEFAULT_IGNORE_CASE,
    private val maxNgramSize: Int = DEFAULT_MAX_NGRAM_SIZE,
    private val anchorWeight: Float = DEFAULT_ANCHOR_WEIGHT,
    private val lengthWeight: Float = DEFAULT_LENGTH_WEIGHT,
    private val gestaltWeight: Float = DEFAULT_GESTALT_WEIGHT,
    private val prefixWeight: Float = DEFAULT_PREFIX_WEIGHT,
    private val fuzzyWeight: Float = DEFAULT_FUZZY_WEIGHT,
    private val skipWeight: Float = DEFAULT_SKIP_WEIGHT,
    private val floatingWeight: Float = DEFAULT_FLOATING_WEIGHT,
    private val textSelector: (T) -> String = { it.toString() },
    private val uniqueKeySelector: (T) -> K,
) {

    // Holds the dataset mapped to their pre-computed, normalized spatial vectors
    private val _embeddings = atomic<PersistentMap<String, PersistentMap<T, SparseVector>>>(persistentMapOf())
    private val _results = MutableStateFlow<List<Pair<T, Float>>>(emptyList())
    val results: StateFlow<List<Pair<T, Float>>> = _results.asStateFlow()
    private val _highlightedResults = MutableStateFlow< List<HighlightedMatch<T>>>(emptyList())
    val highlightedResults: StateFlow<List<HighlightedMatch<T>>> = _highlightedResults.asStateFlow()

    /**
     * Finds the top matching elements for the given query using Cosine Similarity.
     * This operation yields the thread cooperatively during heavy vector dot-product
     * computations to prevent blocking the UI thread on resource-constrained devices.
     *
     * @param query The user's input string to search for.
     * @param maxResults The maximum number of top results to return. Defaults to 5.
     * @return A sorted list of pairs containing the matched object and its similarity score [0.0 - 1.0].
     */
    suspend fun find(query: String, maxResults: Int = DEFAULT_MAX_RESULTS): List<Pair<T, Float>> {
        if (query.isBlank()) return emptyList()

        return withContext(defaultDispatcher) {
            val queryVector = query.toPositionalEmbedding()

            val topResultsQueue = ConcurrentPriorityQueue<Pair<T, Float>, K>(
                maxSize = maxResults,
                comparator = compareByDescending { it.second },
                uniqueKeySelector = { uniqueKeySelector(it.first) }
            )
            _embeddings.value.values.forEach { entries ->
                entries.forEach { (item, targetVector) ->
                    val score = queryVector dotProduct targetVector
                    if (score > 0.0) topResultsQueue.add(item to score)
                    yield()
                }
            }

            // TODO: check for race condition
            _highlightedResults.value = topResultsQueue.items.value.map { match ->
                val targetText = textSelector(match.first)
                val heatmap = query.computeHeatmap(
                    targetStr = targetText,
                    ignoreCase = ignoreCase,
                )

                HighlightedMatch(
                    item = match.first,
                    score = match.second,
                    heatmap = heatmap,
                )
            }

            _results.value = topResultsQueue.items.value

            topResultsQueue.items.value
        }
    }

    /**
     * Adds a single element to the search engine.
     * The item is tokenized and its vector embedding is computed and stored in the index.
     *
     * Note: If adding multiple elements, use [addAll] for significantly better performance
     * as it utilizes concurrent processing and batch updates.
     *
     * @param item The domain object to index.
     */
    suspend fun add(item: T) = withContext(defaultDispatcher) {
        val stringToVectorize = textSelector(item)
        val vector = stringToVectorize.toPositionalEmbedding()

        _embeddings.update { currentMap ->
            currentMap.put(stringToVectorize, persistentMapOf(item to vector))
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
        items: Sequence<T>,
        concurrencyLevel: Int = DEFAULT_CONCURRENCY
    ) = addAll(items.asFlow(), concurrencyLevel)

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
    ) = addAll(items.asFlow(), concurrencyLevel)

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
        items: Flow<T>,
        concurrencyLevel: Int = DEFAULT_CONCURRENCY
    ) = withContext(defaultDispatcher) {

        val computedEntries = mutableMapOf<String, PersistentMap<T, SparseVector>>()

        items.flatMapMerge(concurrency = concurrencyLevel) { item ->
            flow {
                val string = textSelector(item)
                val embedding = string.toPositionalEmbedding()
                emit(string to (item to embedding))
            }
        }.collect { (stringKey, vectorPair) ->
            computedEntries[stringKey] = persistentMapOf(vectorPair.first to vectorPair.second)
        }

        // 3. Perform a lightning-fast, atomic state update in O(1) time.
        _embeddings.update { currentMap ->
            currentMap.putAll(computedEntries)
        }
    }

    /**
     * Removes a specific element and its vector embedding from the search engine.
     * The removal is based on the string representation of the item extracted via the `textSelector`.
     *
     * @param item The element to remove.
     */
    fun remove(item: T) {
        _embeddings.update { currentMap ->
            currentMap.remove(textSelector(item))
        }
    }

    /**
     * Clears the entire vector space and removes all indexed elements.
     */
    fun clear() {
        _embeddings.value.clear()
        _embeddings.value = persistentMapOf()
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
        return _embeddings.value.containsKey(textSelector(item))
    }

    /**
     * Returns a snapshot list of all items currently indexed in the engine.
     * Useful for displaying the initial list when the search query is empty.
     *
     * @return A read-only list of all domain objects.
     */
    fun getAllItems(): List<T> {
        return _embeddings.value.values.flatMap { it.keys }
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
        return _embeddings
            .value
            .values
            .asSequence()
            .flatMap { it.entries }
            .map { entry -> TypeaheadRecord(item = entry.key, vector = entry.value) }
    }

    /**
     * Restores or merges the search engine's state from a streamed [Sequence] of records.
     * * This bypasses the heavy mathematical vectorization process by loading pre-computed
     * embeddings directly into memory.
     *
     * @param records A lazy sequence of [TypeaheadRecord] elements to be loaded.
     * @param clearExisting If true, completely replaces the current state. If false, merges with existing data.
     */
    fun importFromSequence(records: Sequence<TypeaheadRecord<T>>, clearExisting: Boolean = true) {
        val bufferMap = mutableMapOf<String, PersistentMap<T, SparseVector>>()

        records.forEach { record ->
            val stringKey = textSelector(record.item)
            bufferMap[stringKey] = persistentMapOf(record.item to record.vector)
        }

        if (clearExisting) {
            _embeddings.value.clear()
            _embeddings.value = bufferMap.toPersistentMap()
        } else {
            _embeddings.update { currentMap -> currentMap.putAll(bufferMap) }
        }
    }

    /**
     * Generates an L2-normalized hybrid positional N-gram embedding from the given string.
     *
     * This function constructs a sparse vector utilizing 32-bit floating-point precision
     * to maximize memory efficiency on Edge devices. The resulting vector is L2-normalized,
     * ensuring its magnitude is 1.0f, which allows direct cosine similarity calculation
     * via the dot product.
     *
     * @return A highly optimized [SparseVector]. Returns an empty vector if the string is empty.
     */
    private suspend fun String.toPositionalEmbedding(): SparseVector {
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

    companion object {
        internal const val TIER_NONE = -1
        internal const val TIER_PRIMARY = 0
        internal const val TIER_SECONDARY = 1

        internal const val TIER_TERTIARY = 2
        internal const val EPSILON_FLOAT = 1e-7f
        internal const val TOLERANCE_FLOAT = 1e-6f

        const val DEFAULT_CONCURRENCY = 16
        const val DEFAULT_IGNORE_CASE = true
        const val DEFAULT_MAX_NGRAM_SIZE = 4
        const val DEFAULT_ANCHOR_WEIGHT = 10.0f
        const val DEFAULT_LENGTH_WEIGHT = 8.0f
        const val DEFAULT_GESTALT_WEIGHT = 15.0f
        const val DEFAULT_PREFIX_WEIGHT = 6.0f
        const val DEFAULT_FUZZY_WEIGHT = 5.0f
        const val DEFAULT_SKIP_WEIGHT = 4.0f
        const val DEFAULT_FLOATING_WEIGHT = 2.5f
        const val DEFAULT_MAX_RESULTS = 5

        /**
         * Creates a [TypeaheadSearchEngine] providing full control over the element type,
         * its textual representation, and its unique identity key, initialized from an [Iterable].
         *
         * @param items Initial elements to populate the engine. Defaults to an empty list.
         * @param defaultDispatcher The coroutine dispatcher used for background processing.
         * @param ignoreCase Whether the search should be case-insensitive.
         * @param maxNgramSize The maximum size of N-grams to generate for fuzzy matching.
         * @param anchorWeight The weight applied to exact anchor matches.
         * @param lengthWeight The weight applied to length similarity.
         * @param gestaltWeight The weight for the Typoglycemia Gestalt anchor.
         * @param prefixWeight The weight for strict prefix matching.
         * @param fuzzyWeight The weight for fuzzy prefix matching.
         * @param skipWeight The weight for skip-gram matching.
         * @param floatingWeight The weight for floating N-gram matching.
         * @param textSelector A lambda function that converts an element [T] into a searchable string.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        suspend operator fun <T, K> invoke(
            items: Iterable<T> = emptyList(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            ignoreCase: Boolean = DEFAULT_IGNORE_CASE,
            maxNgramSize: Int = DEFAULT_MAX_NGRAM_SIZE,
            anchorWeight: Float = DEFAULT_ANCHOR_WEIGHT,
            lengthWeight: Float = DEFAULT_LENGTH_WEIGHT,
            gestaltWeight: Float = DEFAULT_GESTALT_WEIGHT,
            prefixWeight: Float = DEFAULT_PREFIX_WEIGHT,
            fuzzyWeight: Float = DEFAULT_FUZZY_WEIGHT,
            skipWeight: Float = DEFAULT_SKIP_WEIGHT,
            floatingWeight: Float = DEFAULT_FLOATING_WEIGHT,
            textSelector: (T) -> String,
            uniqueKeySelector: (T) -> K
        ): TypeaheadSearchEngine<T, K> = TypeaheadSearchEngine(
            defaultDispatcher = defaultDispatcher,
            ignoreCase = ignoreCase,
            maxNgramSize = maxNgramSize,
            anchorWeight = anchorWeight,
            lengthWeight = lengthWeight,
            gestaltWeight = gestaltWeight,
            prefixWeight = prefixWeight,
            fuzzyWeight = fuzzyWeight,
            skipWeight = skipWeight,
            floatingWeight = floatingWeight,
            uniqueKeySelector = uniqueKeySelector,
            textSelector = textSelector
        ).apply { addAll(items) }

        /**
         * Creates a [TypeaheadSearchEngine] where the elements themselves act as their own unique identity keys,
         * initialized from an [Iterable].
         *
         * @param items Initial elements to populate the engine. Defaults to an empty list.
         * @param textSelector A lambda function that converts an element [T] into a searchable string. Defaults to calling `toString()`.
         */
        suspend operator fun <T> invoke(
            items: Iterable<T> = emptyList(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            ignoreCase: Boolean = DEFAULT_IGNORE_CASE,
            maxNgramSize: Int = DEFAULT_MAX_NGRAM_SIZE,
            anchorWeight: Float = DEFAULT_ANCHOR_WEIGHT,
            lengthWeight: Float = DEFAULT_LENGTH_WEIGHT,
            gestaltWeight: Float = DEFAULT_GESTALT_WEIGHT,
            prefixWeight: Float = DEFAULT_PREFIX_WEIGHT,
            fuzzyWeight: Float = DEFAULT_FUZZY_WEIGHT,
            skipWeight: Float = DEFAULT_SKIP_WEIGHT,
            floatingWeight: Float = DEFAULT_FLOATING_WEIGHT,
            textSelector: (T) -> String = { it.toString() }
        ): TypeaheadSearchEngine<T, T> = invoke(
            items = items,
            defaultDispatcher = defaultDispatcher,
            ignoreCase = ignoreCase,
            maxNgramSize = maxNgramSize,
            anchorWeight = anchorWeight,
            lengthWeight = lengthWeight,
            gestaltWeight = gestaltWeight,
            prefixWeight = prefixWeight,
            fuzzyWeight = fuzzyWeight,
            skipWeight = skipWeight,
            floatingWeight = floatingWeight,
            textSelector = textSelector,
            uniqueKeySelector = { it }
        )

        /**
         * Creates a [TypeaheadSearchEngine] providing full control over the element type,
         * its textual representation, and its unique identity key, asynchronously initialized from a [Flow].
         *
         * @param items A stream of initial elements to populate the engine concurrently.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
         * @param ignoreCase Whether to ignore character casing during search.
         * @param maxNgramSize The maximum size of N-grams to extract.
         * @param anchorWeight The weight applied to the first character match.
         * @param lengthWeight The weight applied to length similarity.
         * @param gestaltWeight The weight for the Typoglycemia Gestalt anchor.
         * @param prefixWeight The weight for strict prefix matching.
         * @param fuzzyWeight The weight for fuzzy prefix matching.
         * @param skipWeight The weight for skip-gram matching.
         * @param floatingWeight The weight for floating N-gram matching.
         * @param textSelector Lambda to extract searchable text from the items.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        suspend operator fun <T, K> invoke(
            items: Flow<T>,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            ignoreCase: Boolean = DEFAULT_IGNORE_CASE,
            maxNgramSize: Int = DEFAULT_MAX_NGRAM_SIZE,
            anchorWeight: Float = DEFAULT_ANCHOR_WEIGHT,
            lengthWeight: Float = DEFAULT_LENGTH_WEIGHT,
            gestaltWeight: Float = DEFAULT_GESTALT_WEIGHT,
            prefixWeight: Float = DEFAULT_PREFIX_WEIGHT,
            fuzzyWeight: Float = DEFAULT_FUZZY_WEIGHT,
            skipWeight: Float = DEFAULT_SKIP_WEIGHT,
            floatingWeight: Float = DEFAULT_FLOATING_WEIGHT,
            textSelector: (T) -> String,
            uniqueKeySelector: (T) -> K
        ): TypeaheadSearchEngine<T, K> = TypeaheadSearchEngine(
            defaultDispatcher = defaultDispatcher,
            ignoreCase = ignoreCase,
            maxNgramSize = maxNgramSize,
            anchorWeight = anchorWeight,
            lengthWeight = lengthWeight,
            gestaltWeight = gestaltWeight,
            prefixWeight = prefixWeight,
            fuzzyWeight = fuzzyWeight,
            skipWeight = skipWeight,
            floatingWeight = floatingWeight,
            uniqueKeySelector = uniqueKeySelector,
            textSelector = textSelector,
        ).apply { addAll(items) }

        /**
         * Creates a [TypeaheadSearchEngine] where the elements themselves act as their own unique identity keys,
         * asynchronously initialized from a [Flow].
         *
         * @param items A stream of initial elements to populate the engine concurrently.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
         * @param ignoreCase Whether to ignore character casing during search.
         * @param maxNgramSize The maximum size of N-grams to extract.
         * @param anchorWeight The weight applied to the first character match.
         * @param lengthWeight The weight applied to length similarity.
         * @param gestaltWeight The weight for the Typoglycemia Gestalt anchor.
         * @param prefixWeight The weight for strict prefix matching.
         * @param fuzzyWeight The weight for fuzzy prefix matching.
         * @param skipWeight The weight for skip-gram matching.
         * @param floatingWeight The weight for floating N-gram matching.
         * @param textSelector Lambda to extract searchable text from the items. Defaults to calling `toString()`.
         */
        suspend operator fun <T> invoke(
            items: Flow<T>,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            ignoreCase: Boolean = DEFAULT_IGNORE_CASE,
            maxNgramSize: Int = DEFAULT_MAX_NGRAM_SIZE,
            anchorWeight: Float = DEFAULT_ANCHOR_WEIGHT,
            lengthWeight: Float = DEFAULT_LENGTH_WEIGHT,
            gestaltWeight: Float = DEFAULT_GESTALT_WEIGHT,
            prefixWeight: Float = DEFAULT_PREFIX_WEIGHT,
            fuzzyWeight: Float = DEFAULT_FUZZY_WEIGHT,
            skipWeight: Float = DEFAULT_SKIP_WEIGHT,
            floatingWeight: Float = DEFAULT_FLOATING_WEIGHT,
            textSelector: (T) -> String = { it.toString() }
        ): TypeaheadSearchEngine<T, T> = invoke(
            items = items,
            defaultDispatcher = defaultDispatcher,
            ignoreCase = ignoreCase,
            maxNgramSize = maxNgramSize,
            anchorWeight = anchorWeight,
            lengthWeight = lengthWeight,
            gestaltWeight = gestaltWeight,
            prefixWeight = prefixWeight,
            fuzzyWeight = fuzzyWeight,
            skipWeight = skipWeight,
            floatingWeight = floatingWeight,
            textSelector = textSelector,
            uniqueKeySelector = { it }
        )

        /**
         * Factory function to instantiate a [TypeaheadSearchEngine] and immediately
         * populate it with an initial dataset.
         *
         * @param items The initial dataset to index.
         * @param textSelector Lambda to extract searchable text from the items. Defaults to `toString()`.
         * @param uniqueKeySelector Lambda to extract a unique key for each element.
         */
        suspend operator fun <T, K> invoke(
            items: Iterable<T>,
            textSelector: (T) -> String = { it.toString() },
            uniqueKeySelector: (T) -> K,
        ): TypeaheadSearchEngine<T, K> = invoke(
            items = items,
            textSelector = textSelector,
            uniqueKeySelector = uniqueKeySelector,
            defaultDispatcher = Dispatchers.Default
        )
    }
}