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
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentHashMap
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
 * @param uniqueKeySelector A lambda function that extracts a unique key for each element [T]. Defaults to `it`.
 */

class TypeaheadSearchEngine<T, K> @PublishedApi internal constructor(
    private val defaultDispatcher: CoroutineDispatcher,
    private val metadata: TypeaheadMetadata,
    @PublishedApi internal val textSelector: (T) -> String,
    private val uniqueKeySelector: (T) -> K,
) {
    // Holds the dataset mapped to their pre-computed, normalized spatial vectors
    @PublishedApi
    internal val _embeddings =
        MutableStateFlow<PersistentMap<String, PersistentMap<T, SparseVector>>>(persistentHashMapOf())
    private val _results = MutableStateFlow<List<Pair<T, Float>>>(emptyList())
    val results: StateFlow<List<Pair<T, Float>>> = _results.asStateFlow()
    private val _highlightedResults = MutableStateFlow<List<HighlightedMatch<T>>>(emptyList())
    val highlightedResults: StateFlow<List<HighlightedMatch<T>>> = _highlightedResults.asStateFlow()

    /**
     * Finds the top matching elements for the given query using Cosine Similarity.
     *
     * This method vectorizes the query string and performs a high-performance dot-product
     * calculation against all indexed embeddings. It maintains a fixed-size priority queue
     * to efficiently track the top results based on their similarity scores.
     *
     * Upon completion, it updates the [results] and [highlightedResults] StateFlows.
     *
     * @param query The user's input string to search for.
     * @throws CancellationException if the coroutine scope is cancelled during execution.
     * @return A sorted list of pairs containing the matched object and its similarity score [0.0 - 1.0].
     */
    suspend fun find(
        query: String,
    ): List<Pair<T, Float>> = withContext(defaultDispatcher) {
        if (query.isBlank()) return@withContext emptyList()

        val queryVector = query.toPositionalEmbedding()

        val topResultsQueue = ConcurrentPriorityQueue<Pair<T, Float>, K>(
            maxSize = metadata.maxResults,
            comparator = compareByDescending { it.second },
            uniqueKeySelector = { uniqueKeySelector(it.first) }
        )

        topResultsQueue.addAll(
            elements = _embeddings.value.values
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
                ignoreCase = metadata.ignoreCase,
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
     * The item is tokenized and its vector embedding is computed and stored in the index.
     *
     * Note: If adding multiple elements, use [addAll] for significantly better performance
     * as it utilizes concurrent processing and batch updates.
     *
     * @param item The domain object to index.
     */
    suspend fun add(item: T): Unit = withContext(defaultDispatcher) {
        val stringToVectorize = textSelector(item)
        val vector = stringToVectorize.toPositionalEmbedding()

        _embeddings.update { currentMap ->
            currentMap.put(stringToVectorize, persistentHashMapOf(item to vector))
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
     * Defaults to [DEFAULT_CONCURRENCY] (typically 16), which provides excellent
     * CPU saturation on mobile devices without context-switching overhead.
     */
    suspend fun addAll(
        items: Sequence<T>,
    ) = addAll(items.asFlow()) { it }

    /**
     * Batches multiple elements into the search engine's spatial index concurrently.
     * * This operation is heavily optimized for Edge devices and memory-constrained environments
     * across all Kotlin Multiplatform targets. It strictly separates the heavy mathematical
     * vectorization from the state mutation phase to prevent computational thrashing.
     * * To avoid Coroutine Explosion (OOM errors) associated with unbounded [async] builders,
     * this method utilizes [flatMapMerge] to achieve controlled, backpressured concurrency.
     *
     * @param items The collection of domain objects to be indexed.
     * Defaults to [DEFAULT_CONCURRENCY] (typically 16), which provides excellent
     * CPU saturation on mobile devices without context-switching overhead.
     */
    suspend fun addAll(
        items: Iterable<T>,
    ) = addAll(items.asFlow()) { it }

    /**
     * Batches multiple elements into the search engine's spatial index concurrently.
     * * This operation is heavily optimized for Edge devices and memory-constrained environments
     * across all Kotlin Multiplatform targets. It strictly separates the heavy mathematical
     * vectorization from the state mutation phase to prevent computational thrashing.
     * * To avoid Coroutine Explosion (OOM errors) associated with unbounded [async] builders,
     * this method utilizes [flatMapMerge] to achieve controlled, backpressured concurrency.
     *
     * @param items The collection of domain objects to be indexed.
     * Defaults to [DEFAULT_CONCURRENCY] (typically 16), which provides excellent
     * CPU saturation on mobile devices without context-switching overhead.
     */

    suspend fun <S> addAll(
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
        _embeddings.value = persistentHashMapOf()
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
     * Извлича текущото състояние на търсачката в поточен формат към [Sink].
     * Записът е базиран на стрийминг, което гарантира нулев риск от OutOfMemory грешки.
     */
    fun exportToSink(
        sink: Sink,
        itemSerializer: KSerializer<T>,
        json: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    ) {
        // Създаваме полиморфния сериализатор на базата на интерфейса
        val polymorphicSerializer = TypeaheadRecord.serializer(itemSerializer)

        sink.buffered().use { bufferedSink ->
            // Отваряме JSON масива
            bufferedSink.writeString("[\n")

            // 1. Записваме метаданните като първи елемент
            val metadata = TypeaheadMetadata(
                ignoreCase = metadata.ignoreCase,
                maxNgramSize = metadata.maxNgramSize,
                anchorWeight = metadata.anchorWeight,
                lengthWeight = metadata.lengthWeight,
                gestaltWeight = metadata.gestaltWeight,
                prefixWeight = metadata.prefixWeight,
                fuzzyWeight = metadata.fuzzyWeight,
                skipWeight = metadata.skipWeight,
                floatingWeight = metadata.floatingWeight,
                maxResults = metadata.maxResults
            )
            val metaString = json.encodeToString(polymorphicSerializer, metadata)
            bufferedSink.writeString("  $metaString")

            // 2. Итерираме и записваме всички записи един по един (мързеливо)
            _embeddings.value.values
                .asSequence()
                .flatMap { it.entries }
                .forEach { entry ->
                    bufferedSink.writeString(",\n")
                    val payload = TypeaheadPayload(item = entry.key, vector = entry.value)
                    val payloadString = json.encodeToString(polymorphicSerializer, payload)
                    bufferedSink.writeString("  $payloadString")
                }

            // Затваряме JSON масива
            bufferedSink.writeString("\n]")
        }
    }

    /**
     * Възстановява състоянието на търсачката чрез мързеливо четене от [Source].
     * Байпасва математическата векторизация.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified A> importFromSource(
        source: Source,
        itemSerializer: KSerializer<A> = serializer<A>(),
        clearExisting: Boolean = true,
        json: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    ) {
        val polymorphicSerializer = TypeaheadRecord.serializer(itemSerializer)
        val bufferMap = mutableMapOf<String, PersistentMap<A, SparseVector>>()

        source.buffered().use { bufferedSource ->
            val sequence = json.decodeSourceToSequence(
                source = bufferedSource,
                deserializer = polymorphicSerializer,
                format = DecodeSequenceMode.ARRAY_WRAPPED
            )

            val iterator = sequence.iterator()

            // 1. КОНСУМИРАНЕ НА ПЪРВИЯ ЗАПИС (Метаданни)
            if (iterator.hasNext()) {
                when (val firstItem = iterator.next()) {
                    is TypeaheadMetadata -> {
                        // Тук може да се добави логика за валидация - например да се хвърли
                        // грешка или предупреждение, ако firstItem.maxNgramSize се различава
                        // от текущите настройки на енджина, тъй като това би счупило търсенето.
                    }

                    is TypeaheadPayload -> {
                        addRecordToBuffer(bufferMap, firstItem)
                    }
                }
            }

            // 2. КОНСУМИРАНЕ НА ОСТАНАЛИТЕ ЗАПИСИ (Полезен товар)
            iterator.forEach { item ->
                if (item is TypeaheadPayload) {
                    addRecordToBuffer(bufferMap, item)
                }
            }
        }

        val persistentBuffer = bufferMap.toPersistentHashMap()

        if (clearExisting) {
            _embeddings.value.clear()
            _embeddings.value = persistentBuffer as PersistentMap<String, PersistentMap<T, SparseVector>>
        } else {
            _embeddings.update { currentMap ->
                var mergedMap = currentMap
                persistentBuffer.forEach { (key, innerMap) ->
                    val existingInner = mergedMap[key] ?: persistentHashMapOf()
                    mergedMap = mergedMap.put(key, existingInner.putAll(innerMap as PersistentMap<T, SparseVector>))
                }
                mergedMap
            }
        }
    }

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal inline fun <reified A> addRecordToBuffer(
        buffer: MutableMap<String, PersistentMap<A, SparseVector>>,
        payload: TypeaheadPayload<A>
    ) {
        val stringKey = textSelector(payload.item as T)
        val existingMap = buffer[stringKey] ?: persistentHashMapOf()
        buffer[stringKey] = existingMap.put(payload.item, payload.vector)
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
        vector["A_${word[0]}"] = metadata.anchorWeight

        // 2. Length Bucket (Length synchronizer)
        // Elevates words with the exact same length during typographical errors.
        vector["L_$length"] = metadata.lengthWeight

        // 3. Typoglycemia Gestalt Anchor
        // Captures perfectly 1-character typos where the length, first, and last characters match.
        if (length > 1) {
            vector["G_${word[0]}_${length}_${word.last()}"] = metadata.gestaltWeight
        }

        // 4. Strict & Fuzzy Prefixes (The core of Typeahead)
        for (i in 1..min(length, 8)) {
            val prefix = word.substring(0, i)

            // Strict Prefix: Rewards perfect typing sequence.
            vector["P_$prefix"] = i * metadata.prefixWeight

            if (i >= 2) {
                // Fuzzy Prefix: Mitigates transpositions (e.g., "Cna" vs "Canada").
                // Anchors the first character and sorts the remainder of the prefix.
                val sortedRest = prefix.substring(1).toCharArray().apply { sort() }.joinToString("")
                vector["F_${word[0]}_$sortedRest"] = i * metadata.fuzzyWeight
            }
            yield()
        }

        // 5. Skip-Grams (Bridge for deletions and insertions)
        for (i in 0 until length - 2) {
            val skipKey = "${word[i]}${word[i + 2]}"
            vector[skipKey] = (vector[skipKey] ?: 0.0f) + metadata.skipWeight
        }

        // 6. Floating N-Grams (The structural skeleton)
        for (i in 0 until length) {
            for (n in 2..metadata.maxNgramSize) {
                if (i + n <= length) {
                    val ngram = word.substring(i, i + n)
                    // Linear progression for weights avoids complex branching operations.
                    val weight = n * metadata.floatingWeight
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
         * Creates a [TypeaheadSearchEngine] providing full control over the element type,
         * its textual representation, and its unique identity key, initialized from an [Iterable].
         *
         * @param items Initial elements to populate the engine. Defaults to an empty list.
         * @param defaultDispatcher The coroutine dispatcher used for background processing.
         * @param textSelector A lambda function that converts an element [T] into a searchable string.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        suspend inline operator fun <reified T, reified K> invoke(
            items: Iterable<T> = emptyList(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            noinline textSelector: (T) -> String,
            noinline uniqueKeySelector: (T) -> K
        ): TypeaheadSearchEngine<T, K> = TypeaheadSearchEngine(
            defaultDispatcher = defaultDispatcher,
            metadata = metadata,
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
        suspend inline operator fun <reified T> invoke(
            items: Iterable<T> = emptyList(),
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            noinline textSelector: (T) -> String = { it.toString() }
        ): TypeaheadSearchEngine<T, T> = invoke(
            items = items,
            defaultDispatcher = defaultDispatcher,
            metadata = metadata,
            textSelector = textSelector,
            uniqueKeySelector = { it }
        )

        /**
         * Creates a [TypeaheadSearchEngine] providing full control over the element type,
         * its textual representation, and its unique identity key, asynchronously initialized from a [Flow].
         *
         * @param items A stream of initial elements to populate the engine concurrently.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
         * @param textSelector Lambda to extract searchable text from the items.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        suspend inline operator fun <reified T, reified K> invoke(
            items: Flow<T>,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            noinline textSelector: (T) -> String,
            noinline uniqueKeySelector: (T) -> K
        ): TypeaheadSearchEngine<T, K> = TypeaheadSearchEngine(
            defaultDispatcher = defaultDispatcher,
            metadata = metadata,
            uniqueKeySelector = uniqueKeySelector,
            textSelector = textSelector,
        ).apply { addAll(items) { it } }

        /**
         * Creates a [TypeaheadSearchEngine] where the elements themselves act as their own unique identity keys,
         * asynchronously initialized from a [Flow].
         *
         * @param items A stream of initial elements to populate the engine concurrently.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
         * @param textSelector Lambda to extract searchable text from the items. Defaults to calling `toString()`.
         */
        suspend inline operator fun <reified T> invoke(
            items: Flow<T>,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            noinline textSelector: (T) -> String = { it.toString() }
        ): TypeaheadSearchEngine<T, T> = invoke(
            items = items,
            defaultDispatcher = defaultDispatcher,
            metadata = metadata,
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
        suspend inline operator fun <reified T, reified K> invoke(
            items: Iterable<T>,
            metadata: TypeaheadMetadata = TypeaheadMetadata(),
            noinline textSelector: (T) -> String = { it.toString() },
            noinline uniqueKeySelector: (T) -> K,
        ): TypeaheadSearchEngine<T, K> = invoke(
            items = items,
            metadata = metadata,
            textSelector = textSelector,
            uniqueKeySelector = uniqueKeySelector,
            defaultDispatcher = Dispatchers.Default
        )
    }
}