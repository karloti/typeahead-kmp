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

@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class, ExperimentalSerializationApi::class)

package io.github.karloti.typeahead

import io.github.karloti.cpq.ConcurrentPriorityQueue
import io.github.karloti.typeahead.TypeaheadRecord.TypeaheadMetadata
import io.github.karloti.typeahead.TypeaheadRecord.TypeaheadPayload
import io.github.karloti.typeahead.TypeaheadSearchEngine.Companion.EPSILON_FLOAT
import io.github.karloti.typeahead.TypeaheadSearchEngine.Companion.FIND_BATCH_SIZE
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.writeString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.decodeSourceToSequence
import kotlinx.serialization.serializer
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
 * @param textSelector A lambda function that extracts the searchable textual representation from your object [T]. Defaults to `toString()`.
 * @param keySelector A lambda function that extracts a unique key for each element [T]. Defaults to `it`.
 */

class TypeaheadSearchEngine<T, K>(
    @PublishedApi
    internal val textSelector: (T) -> String,
    /**
     * The active configuration of this engine.
     *
     * Reflects all weight parameters, N-gram size, and result limits. When
     * [importFromSource] is called with `clearExisting = true`, this property is
     * atomically replaced by the [TypeaheadMetadata] embedded in the import stream,
     * so the engine's configuration is always consistent with its indexed vectors.
     */
    metadata: TypeaheadMetadata = TypeaheadMetadata(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    @PublishedApi
    internal val keySelector: (T) -> K,
) : TypeaheadSearch<T, K> {

    @PublishedApi
    internal var typeaheadMetadata: TypeaheadMetadata = metadata
    val metadata: TypeaheadMetadata
        get() = typeaheadMetadata

    /**
     * Atomic wrapper that keeps embeddings and their text-key index in lock-step.
     * Both fields are always updated together inside a single CAS operation so there
     * is never a window where one is ahead of the other under concurrent [add]/[remove].
     */
    @PublishedApi
    internal data class EngineState<T>(
        val embeddings: PersistentMap<String, PersistentMap<T, SparseVector>> = persistentHashMapOf(),
        val keys: PersistentSet<String> = persistentSetOf(),
    )

    @PublishedApi
    internal val _state: MutableStateFlow<EngineState<T>> = MutableStateFlow(EngineState())
    private val _results = MutableStateFlow<List<Pair<T, Float>>>(emptyList())
    override val results: StateFlow<List<Pair<T, Float>>> = _results.asStateFlow()
    private val _highlightedResults = MutableStateFlow<List<HighlightedMatch<T>>>(emptyList())
    override val highlightedResults: StateFlow<List<HighlightedMatch<T>>> = _highlightedResults.asStateFlow()

    /**
     * Finds the top matching elements for the given query using cosine similarity.
     *
     * The query string is vectorized into a [SparseVector] and compared against all
     * indexed embeddings via dot-product (equivalent to cosine similarity for L2-normalized
     * vectors). A bounded priority queue retains only the top [TypeaheadMetadata.maxResults] matches.
     *
     * Upon completion, both [results] and [highlightedResults] `StateFlow`s are updated
     * with the new result set. Returns an empty list for blank queries.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(listOf("Kotlin", "Java", "Koka"), textSelector = { it })
     * val matches = engine.find("Kot")
     * // matches[0] == ("Kotlin" to 0.95f)  — highest cosine similarity
     * ```
     *
     * @param query The user's input string to search for.
     * @return A descending-score list of pairs containing the matched object and its similarity score `[0.0, 1.0]`.
     * @throws CancellationException if the coroutine scope is canceled during execution.
     */
    override suspend fun find(
        query: String,
    ): List<Pair<T, Float>> = withContext(defaultDispatcher) {
        if (query.isBlank()) return@withContext emptyList()

        val queryVector = query.toPositionalEmbedding()

        val topResultsQueue = ConcurrentPriorityQueue(
            maxSize = typeaheadMetadata.maxResults,
            dispatcher = defaultDispatcher,
            comparator = compareByDescending { it.second },
            keySelector = { it: Pair<T, Float> -> keySelector(it.first) }
        )

        topResultsQueue.addAll(
            elements = _state.value.embeddings.values
                .asSequence()
                .flatMap { it.entries.asSequence() }
                .asFlow(),
            transform = { (item, targetVector) ->
                val score = queryVector dotProduct targetVector
                item to score
            },
        )

        _results.value = topResultsQueue.items.value
        _highlightedResults.value = topResultsQueue.items.value.map { match ->
            val targetText = textSelector(match.first)
            val heatmap = query.computeHeatmap(
                targetStr = targetText,
                ignoreCase = typeaheadMetadata.ignoreCase,
            )
            HighlightedMatch(
                item = match.first,
                score = match.second,
                heatmap = heatmap,
            )
        }

        topResultsQueue.items.value
    }

    /**
     * Adds a single element to the search engine.
     *
     * The item is tokenized via [textSelector] and its vector embedding is computed
     * and stored in the index. Deduplication is based on the composite key
     * `textSelector(item) + keySelector(item)`: if an item with the same composite
     * key already exists, the new item is silently rejected (first-write wins).
     *
     * For bulk insertions, prefer [addAll] which utilizes concurrent processing
     * and batch updates for significantly better throughput.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine<String, String>(textSelector = { it }, keySelector = { it })
     * engine.add("Kotlin")
     * engine.add("Java")
     * engine.size // 2
     * ```
     *
     * @param item The domain object to index.
     */
    override suspend fun add(item: T): Unit = withContext(defaultDispatcher) {
        val str = textSelector(item)
        val key = str + keySelector(item)
        // Fast pre-check: avoids expensive vectorization for obvious duplicates.
        if (key in _state.value.keys) return@withContext
        val vector = str.toPositionalEmbedding()
        // Re-check inside CAS to guarantee correctness under concurrent adds.
        _state.update { state: EngineState<T> ->
            if (key in state.keys) {
                state
            } else {
                val existingMap: PersistentMap<T, SparseVector> = state.embeddings[str] ?: persistentHashMapOf()
                state.copy(
                    embeddings = state.embeddings.put(str, existingMap.put(item, vector)),
                    keys = state.keys.add(key)
                )
            }
        }
    }

    /**
     * Indexes multiple elements from a [Sequence] into the engine concurrently.
     *
     * Delegates to the [Flow]-based [addAll] overload with back-pressured concurrency.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine<String, String>(textSelector = { it }, keySelector = { it })
     * engine.addAll(sequenceOf("Kotlin", "Java", "Scala"))
     * ```
     *
     * @param items The sequence of domain objects to index.
     */
    override suspend fun addAll(
        items: Sequence<T>,
    ) = addAll(items.asFlow()) { it }

    /**
     * Indexes multiple elements from an [Iterable] into the engine concurrently.
     *
     * Delegates to the [Flow]-based [addAll] overload with back-pressured concurrency.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine<String, String>(textSelector = { it }, keySelector = { it })
     * engine.addAll(listOf("Kotlin", "Java", "Scala"))
     * ```
     *
     * @param items The collection of domain objects to index.
     */
    override suspend fun addAll(
        items: Iterable<T>,
    ) = addAll(items.asFlow()) { it }

    /**
     * Indexes multiple elements from a [Flow] into the engine with controlled concurrency.
     *
     * This operation uses a channel-based fan-out pattern with [DEFAULT_CONCURRENCY] workers
     * to achieve back-pressured parallelism, avoiding coroutine explosion (OOM) on large datasets.
     * Each worker drains batches of [FIND_BATCH_SIZE] items and vectorizes them concurrently.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine<String, String>(textSelector = { it }, keySelector = { it })
     * val itemsFlow: Flow<MyDto> = repository.streamAll()
     * engine.addAll(itemsFlow) { dto -> dto.name }
     * ```
     *
     * @param S The source element type emitted by the flow.
     * @param items The flow of source elements to index.
     * @param transform A suspend function that converts each source element [S] into a domain object [T].
     */
    override suspend fun <S> addAll(
        items: Flow<S>,
        transform: suspend (S) -> T
    ) = withContext(defaultDispatcher) {

        val batchSize = FIND_BATCH_SIZE
        val sourceChannel = Channel<S>(capacity = Channel.BUFFERED)

        coroutineScope {
            launch {
                items.collect { sourceChannel.send(it) }
                sourceChannel.close()
            }

            repeat(DEFAULT_CONCURRENCY) {
                launch {
                    while (true) {
                        val first = sourceChannel.receiveCatching().getOrNull() ?: break
                        val batch = ArrayList<S>(batchSize)
                        batch.add(first)
                        while (batch.size < batchSize) {
                            sourceChannel.tryReceive().getOrNull()?.let { batch.add(it) } ?: break
                        }

                        if (batch.size <= DEFAULT_CONCURRENCY) {
                            batch.forEach {
                                add(transform(it))
                            }
                        } else {
                            coroutineScope {
                                batch.forEach {
                                    launch {
                                        add(transform(it))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes a specific element and its vector embedding from the search engine.
     *
     * Only the given [item] is removed from the inner map for its text key.
     * If other items share the same text key, they are preserved. The outer
     * text key entry is removed only when its inner map becomes empty.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(listOf("Kotlin", "Java"))
     * engine.remove("Kotlin")
     * "Kotlin" in engine // false
     * ```
     *
     * @param item The element to remove.
     */
    override fun remove(item: T) {
        val str = textSelector(item)
        val key = str + keySelector(item)
        _state.update { state ->
            if (key !in state.keys) state
            else {
                val existing = state.embeddings[str] ?: return@update state
                val updated = existing.remove(item)
                state.copy(
                    embeddings = if (updated.isEmpty()) {
                        state.embeddings.remove(str)
                    } else {
                        state.embeddings.put(str, updated)
                    },
                    keys = state.keys.remove(key),
                )
            }
        }
    }

    /**
     * Clears the entire vector space and removes all indexed elements.
     *
     * After calling this method, [size] returns 0 and [getAllItems] returns an empty list.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(listOf("Apple", "Banana"))
     * engine.clear()
     * engine.size // 0
     * ```
     */
    override fun clear() {
        _state.value = EngineState()
    }

    /**
     * Returns the number of distinct composite keys currently indexed in the vector space.
     *
     * Each composite key is formed as `textSelector(item) + keySelector(item)`. Items sharing
     * the same text but with different keys are counted separately.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(listOf("Kotlin", "Java"), textSelector = { it })
     * engine.size // 2
     * ```
     */
    override val size: Int
        get() = _state.value.keys.size

    /**
     * Checks if an item with the same composite key is currently indexed in the engine.
     *
     * The composite key is formed as `textSelector(item) + keySelector(item)`. Both the text
     * and the key must match for this to return `true`. Supports the idiomatic `in` operator syntax.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(listOf("Kotlin"), textSelector = { it })
     * "Kotlin" in engine  // true
     * "Java" in engine    // false
     * ```
     *
     * @param item The element to check.
     * @return `true` if an item with the same composite key exists in the index, `false` otherwise.
     */
    override operator fun contains(item: T): Boolean {
        return (textSelector(item) + keySelector(item)) in _state.value.keys
    }

    /**
     * Returns a snapshot list of all items currently indexed in the engine.
     *
     * Useful for displaying the initial dataset when the search query is empty.
     * The returned list is a point-in-time snapshot; concurrent modifications
     * are not reflected.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(listOf("Kotlin", "Java"), textSelector = { it })
     * engine.getAllItems() // ["Kotlin", "Java"]
     * ```
     *
     * @return A read-only list of all domain objects.
     */
    override fun getAllItems(): List<T> {
        return _state.value.embeddings.values.flatMap { it.keys }
    }

    /**
     * Inserts a deserialized [TypeaheadPayload] into the mutable import buffer.
     *
     * The text key is derived via [textSelector]. If the buffer already contains
     * entries for the same text key, the new payload is merged into the inner map.
     *
     * @param buffer The accumulating mutable map used during [importFromSource].
     * @param payload The deserialized payload record to insert.
     */
    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun addRecordToBuffer2(
        buffer: MutableMap<String, PersistentMap<T, SparseVector>>,
        payload: TypeaheadPayload<T>
    ) {
        val stringKey = textSelector(payload.item)
        val existingMap = buffer[stringKey] ?: persistentHashMapOf()
        buffer[stringKey] = existingMap.put(payload.item, payload.vector)
    }

    /**
     * Generates an L2-normalized hybrid positional N-gram embedding from the receiver string.
     *
     * Constructs a [SparseVector] with 32-bit floating-point precision by extracting
     * 8 feature categories: P0 anchors, length buckets, gestalt anchors, strict prefixes,
     * fuzzy prefixes, skip-grams, and floating N-grams. The resulting vector is L2-normalized
     * (magnitude = 1.0f), enabling direct cosine similarity via [SparseVector.dotProduct].
     *
     * This is a cooperative suspend function that yields periodically during the prefix
     * and N-gram loops to remain cancellation-responsive on large strings.
     *
     * @receiver The input string to embed (lowercased internally).
     * @return An L2-normalized [SparseVector], or an empty vector if the string is blank
     *         or the computed magnitude is below [EPSILON_FLOAT].
     */
    private suspend fun String.toPositionalEmbedding(): SparseVector {
        val vector = mutableMapOf<String, Float>()
        val word = this.lowercase()
        val length = word.length

        if (length == 0) return SparseVector(emptyArray(), FloatArray(0))

        // 1. P0 Anchor (Absolute foundation)
        // The first letter is highly reliable in typeahead scenarios.
        vector["A_${word[0]}"] = typeaheadMetadata.anchorWeight

        // 2. Length Bucket (Length synchronizer)
        // Elevates words with the exact same length during typographical errors.
        vector["L_$length"] = typeaheadMetadata.lengthWeight

        // 3. Typoglycemia Gestalt Anchor
        // Captures perfectly 1-character typos where the length, first, and last characters match.
        if (length > 1) {
            vector["G_${word[0]}_${length}_${word.last()}"] = typeaheadMetadata.gestaltWeight
        }

        // 4. Strict & Fuzzy Prefixes (The core of Typeahead)
        for (i in 1..min(length, 8)) {
            val prefix = word.substring(0, i)

            // Strict Prefix: Rewards perfect typing sequence.
            vector["P_$prefix"] = i * typeaheadMetadata.prefixWeight

            if (i >= 2) {
                // Fuzzy Prefix: Mitigates transpositions (e.g., "Cna" vs "Canada").
                // Anchors the first character and sorts the remainder of the prefix.
                val sortedRest = prefix.substring(1).toCharArray().apply { sort() }.joinToString("")
                vector["F_${word[0]}_$sortedRest"] = i * typeaheadMetadata.fuzzyWeight
            }
            yield()
        }

        // 5. Skip-Grams (Bridge for deletions and insertions)
        for (i in 0 until length - 2) {
            val skipKey = "${word[i]}${word[i + 2]}"
            vector[skipKey] = (vector[skipKey] ?: 0.0f) + typeaheadMetadata.skipWeight
        }

        // 6. Floating N-Grams (The structural skeleton)
        for (i in 0 until length) {
            for (n in 2..typeaheadMetadata.maxNgramSize) {
                if (i + n <= length) {
                    val ngram = word.substring(i, i + n)
                    // Linear progression for weights avoids complex branching operations.
                    val weight = n * typeaheadMetadata.floatingWeight
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
        const val FIND_BATCH_SIZE = 512

        /**
         * A high-performance, in-memory fuzzy search engine designed for typeahead capabilities.
         *
         * It utilizes L2-normalized sparse vector embeddings and Cosine Similarity to provide
         * instant, typo-tolerant search results (handling transpositions, deletions, and insertions).
         * It behaves like a mutable concurrent collection, allowing you to add, remove, and find elements.
         *
         * @param T The type of elements held in this engine.
         * @param defaultDispatcher The coroutine dispatcher used for heavy computational vectorization. Defaults to [Dispatchers.Default].
         * @param textSelector A lambda function that extracts the searchable textual representation from your object [T]. Defaults to `toString()`.
         * @param keySelector A lambda function that extracts a unique key for each element [T]. Defaults to `it`.
         */
        operator fun <T> invoke(
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            keySelector: (T) -> T = { it },
            textSelector: (T) -> String = { it.toString() },
        ): TypeaheadSearchEngine<T, T> {
            return TypeaheadSearchEngine(
                textSelector = textSelector,
                metadata = metadata,
                defaultDispatcher = defaultDispatcher,
                keySelector = keySelector
            )
        }

        /**
         * Creates a [TypeaheadSearchEngine] with explicit text and key selectors, populated from an [Iterable].
         *
         * ```kotlin
         * data class Country(val id: Int, val name: String)
         * val engine = TypeaheadSearchEngine(
         *     items = listOf(Country(1, "Bulgaria"), Country(2, "Brazil")),
         *     textSelector = { it.name },
         *     keySelector = { it.id }
         * )
         * ```
         *
         * @param items Initial elements to populate the engine. Defaults to an empty list.
         * @param defaultDispatcher The coroutine dispatcher used for background processing.
         * @param metadata Configuration for weights, N-gram size, and result limits.
         * @param textSelector Extracts the searchable text representation from each element [T].
         * @param keySelector Extracts a unique identity key from each element [T].
         */
        suspend operator fun <T, K> invoke(
            items: Iterable<T>,
            textSelector: (T) -> String = { it.toString() },
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            keySelector: (T) -> K
        ): TypeaheadSearchEngine<T, K> {
            return TypeaheadSearchEngine(
                textSelector = textSelector,
                metadata = metadata,
                defaultDispatcher = defaultDispatcher,
                keySelector = keySelector
            ).apply { addAll(items) }
        }

        /**
         * Creates a [TypeaheadSearchEngine] where each element serves as its own unique key,
         * populated from an [Iterable].
         *
         * ```kotlin
         * val engine = TypeaheadSearchEngine(items = listOf("Kotlin", "Java", "Scala"))
         * val results = engine.find("Kot") // [("Kotlin", 0.95)]
         * ```
         *
         * @param items Initial elements to populate the engine. Defaults to an empty list.
         * @param textSelector Extracts searchable text from each element [T]. Defaults to [toString].
         */
        suspend operator fun <T> invoke(
            items: Iterable<T>,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            textSelector: (T) -> String = { it.toString() }
        ): TypeaheadSearchEngine<T, T> {
            return TypeaheadSearchEngine(
                textSelector = textSelector,
                metadata = metadata,
                defaultDispatcher = defaultDispatcher,
                keySelector = { it }
            ).apply { addAll(items) }
        }

        /**
         * Creates a [TypeaheadSearchEngine] with explicit selectors, asynchronously populated from a [Flow].
         *
         * ```kotlin
         * val engine = TypeaheadSearchEngine(
         *     items = repository.streamCountries(),
         *     textSelector = { it.name },
         *     uniqueKeySelector = { it.id }
         * )
         * ```
         *
         * @param items A flow of initial elements to populate the engine concurrently.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
         * @param metadata Configuration for weights, N-gram size, and result limits.
         * @param textSelector Extracts searchable text from each element [T].
         * @param uniqueKeySelector Extracts a unique identity key from each element [T].
         */
        suspend operator fun <T, K> invoke(
            items: Flow<T>,
            textSelector: (T) -> String,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            uniqueKeySelector: (T) -> K
        ): TypeaheadSearchEngine<T, K> {
            return TypeaheadSearchEngine(
                textSelector = textSelector,
                metadata = metadata,
                defaultDispatcher = defaultDispatcher,
                keySelector = uniqueKeySelector
            ).apply { addAll(items) { it } }
        }

        /**
         * Creates a [TypeaheadSearchEngine] where each element serves as its own unique key,
         * asynchronously populated from a [Flow].
         *
         * ```kotlin
         * val engine = TypeaheadSearchEngine(items = flowOf("Kotlin", "Java", "Scala"))
         * ```
         *
         * @param items A flow of initial elements to populate the engine concurrently.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
         * @param metadata Configuration for weights, N-gram size, and result limits.
         * @param textSelector Extracts searchable text from each element [T]. Defaults to [toString].
         */
        suspend operator fun <T> invoke(
            items: Flow<T>,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            textSelector: (T) -> String = { it.toString() }
        ): TypeaheadSearchEngine<T, T> {
            return TypeaheadSearchEngine(
                textSelector = textSelector,
                metadata = metadata,
                defaultDispatcher = defaultDispatcher,
                keySelector = { it }
            ).apply { addAll(items) { it } }
        }

        /**
         * Creates a [TypeaheadSearchEngine] by restoring its complete state — configuration
         * and pre-computed vectors — from a [Source] produced by [exportToSink].
         *
         * The engine's [TypeaheadMetadata] (weights, N-gram size, result limit) is read
         * from the stream's first element, so no separate metadata parameter is needed.
         * All subsequent elements are [TypeaheadPayload] records loaded directly into the
         * index without re-vectorization, making this ~50× faster than re-inserting items.
         *
         * ```kotlin
         * data class Product(val id: Int, val name: String)
         *
         * val engine = TypeaheadSearchEngine(
         *     source = fileSystem.source(snapshotPath),
         *     itemSerializer = Product.serializer(),
         *     textSelector = { it.name },
         *     keySelector = { it.id }
         * )
         * // engine.metadata reflects the configuration stored in the snapshot.
         * val results = engine.find("kotlin") // instant — vectors already loaded
         * ```
         *
         * @param T The type of elements held in the engine.
         * @param K The type of unique key used to identify each element.
         * @param source The [Source] to restore from, as produced by [exportToSink].
         * @param itemSerializer The [KSerializer] for deserializing items of type [T].
         * @param textSelector Extracts searchable text from each element [T].
         * @param keySelector Extracts a unique identity key from each element [T].
         * @param defaultDispatcher The coroutine dispatcher used for search computations.
         * @param json The [Json] instance used for deserialization.
         * @return A fully restored [TypeaheadSearchEngine] with [typeaheadMetadata] set from the stream.
         */
        inline operator fun <reified T, reified K> invoke(
            source: Source,
            noinline textSelector: (T) -> String,
            itemSerializer: KSerializer<T> = serializer<T>(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
            noinline keySelector: (T) -> K,
        ): TypeaheadSearchEngine<T, K> {
            return TypeaheadSearchEngine(
                textSelector = textSelector,
                metadata = TypeaheadMetadata(),
                defaultDispatcher = defaultDispatcher,
                keySelector = keySelector,
            ).apply {
                importFromSource(
                    source = source,
                    itemSerializer = itemSerializer,
                    clearExisting = true,
                    json = json
                )
            }
        }

        /**
         * Creates a [TypeaheadSearchEngine] where each element serves as its own unique key,
         * restoring complete state — configuration and pre-computed vectors — from a [Source]
         * produced by [exportToSink].
         *
         * ```kotlin
         * val engine = TypeaheadSearchEngine(
         *     source = fileSystem.source(snapshotPath),
         *     itemSerializer = serializer<String>()
         * )
         * // engine.metadata reflects the configuration stored in the snapshot.
         * ```
         *
         * @param T The type of elements held in the engine, used as its own key.
         * @param source The [Source] to restore from, as produced by [exportToSink].
         * @param itemSerializer The [KSerializer] for deserializing items of type [T].
         * @param textSelector Extracts searchable text from each element [T]. Defaults to [toString].
         * @param defaultDispatcher The coroutine dispatcher used for search computations.
         * @param json The [Json] instance used for deserialization.
         * @return A fully restored [TypeaheadSearchEngine] with [typeaheadMetadata] set from the stream.
         */
        inline operator fun <reified T> invoke(
            source: Source,
            itemSerializer: KSerializer<T> = serializer<T>(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
            noinline textSelector: (T) -> String = { it.toString() },
        ): TypeaheadSearchEngine<T, T> {
            return TypeaheadSearchEngine(
                textSelector = textSelector,
                metadata = TypeaheadMetadata(),
                defaultDispatcher = defaultDispatcher,
                keySelector = { it }
            ).apply {
                importFromSource(
                    source = source,
                    itemSerializer = itemSerializer,
                    clearExisting = true,
                    json = json
                )
            }
        }
    }
}

/**
 * Exports the current engine state as a streaming JSON array to the given [Sink].
 *
 * The output is a JSON array where the first element is a [TypeaheadMetadata] header
 * and all subsequent elements are [TypeaheadPayload] records. The streaming approach
 * ensures constant memory usage regardless of the dataset size.
 *
 * The exported data can be restored via [importFromSource].
 *
 * ```kotlin
 * val engine = TypeaheadSearchEngine(listOf("Kotlin", "Java"), textSelector = { it })
 * fileSystem.sink(path).use { sink ->
 *     engine.exportToSink(sink, serializer<String>())
 * }
 * ```
 *
 * @param sink The [Sink] to write the JSON array to.
 * @param itemSerializer The [KSerializer] for serializing items of type [T].
 * @param json The [Json] instance used for serialization.
 */
inline fun <reified T, K> TypeaheadSearchEngine<T, K>.exportToSink(
    sink: Sink,
    itemSerializer: KSerializer<T> = serializer<T>(),
    json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) {
    val polymorphicSerializer = TypeaheadRecord.serializer(itemSerializer)

    sink.buffered().use { bufferedSink ->
        // Open JSON array
        bufferedSink.writeString("[\n")

        // 1. Write metadata as the first element
        val metaString = json.encodeToString(polymorphicSerializer, typeaheadMetadata)
        bufferedSink.writeString("  $metaString")

        // 2. Lazily iterate and write each payload record
        _state.value.embeddings.values
            .asSequence()
            .flatMap { it.entries }
            .forEach { (item, vector) ->
                bufferedSink.writeString(",\n")
                val payload = TypeaheadPayload(item = item, vector = vector)
                val payloadString = json.encodeToString(polymorphicSerializer, payload)
                bufferedSink.writeString("  $payloadString")
            }

        // Close JSON array
        bufferedSink.writeString("\n]")
    }
}


/**
 * Restores the engine state by streaming records from a [Source], bypassing vectorization.
 *
 * Deserializes a JSON array previously produced by [exportToSink]. The first element is
 * expected to be a [TypeaheadMetadata] header; all subsequent elements are [TypeaheadPayload]
 * records whose pre-computed vectors are loaded directly into the index.
 *
 * **Metadata handling:**
 * - `clearExisting = true` (default): [typeaheadMetadata] is replaced by the value read from the
 *   stream, so the engine's configuration is always consistent with its indexed vectors.
 * - `clearExisting = false`: the imported [TypeaheadMetadata.maxNgramSize] must equal the
 *   engine's current value; merging vectors produced with a different N-gram size would
 *   corrupt cosine-similarity scores.
 *
 * ```kotlin
 * val engine = TypeaheadSearchEngine<String, String>(
 *     textSelector = { it },
 *     keySelector = { it }
 * )
 * fileSystem.source(path).use { source ->
 *     engine.importFromSource(source, serializer<String>())
 * }
 * // engine.metadata now reflects the configuration stored in the file.
 * ```
 *
 * @param T The type of the serialized items (must be assignment-compatible with [T] at runtime).
 * @param source The [Source] to read the JSON array from.
 * @param itemSerializer The [KSerializer] for item deserialization.
 * @param clearExisting If `true`, replaces the current index and [typeaheadMetadata]; if `false`, merges records.
 * @param json The [Json] instance used for deserialization.
 * @throws IllegalStateException if [clearExisting] is `false` and the imported
 *         [TypeaheadMetadata.maxNgramSize] differs from the engine's current value.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T, K> TypeaheadSearchEngine<T, K>.importFromSource(
    source: Source,
    itemSerializer: KSerializer<T> = serializer<T>(),
    clearExisting: Boolean = true,
    json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) {
    source.buffered().use { bufferedSource: Source ->
        val sequence = json.decodeSourceToSequence(
            source = bufferedSource,
            deserializer = TypeaheadRecord.serializer(itemSerializer),
            format = DecodeSequenceMode.ARRAY_WRAPPED
        )

        if (clearExisting) _state.value = TypeaheadSearchEngine.EngineState()

        _state.update { (_embeddings: PersistentMap<String, PersistentMap<T, SparseVector>>, _keys: PersistentSet<String>) ->
            var meta = typeaheadMetadata
            var embeddings = _embeddings
            var keys = _keys
            sequence.forEach { item ->
                when (item) {
                    is TypeaheadMetadata -> {
                        if (clearExisting) {
                            meta = item
                        } else {
                            check(item == meta) {
                                """Imported metadata does not match engine metadata. To merge records, both must have identical weights and N-gram sizes to ensure consistent scoring. Use clearExisting = true to overwrite the current engine state with the imported one."""
                            }
                        }
                    }

                    is TypeaheadPayload -> {
                        val existing = embeddings[textSelector(item.item)] ?: persistentHashMapOf()
                        embeddings = embeddings.put(textSelector(item.item), existing.put(item.item, item.vector))
                        keys = keys.add(textSelector(item.item) + keySelector(item.item))
                    }
                }
            }
            typeaheadMetadata = meta
            TypeaheadSearchEngine.EngineState(embeddings, keys)
        }
    }
}
