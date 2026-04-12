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
import io.github.karloti.typeahead.TypeaheadRecord.*
import io.github.karloti.typeahead.TypeaheadSearchEngine.Companion.EPSILON_FLOAT
import io.github.karloti.typeahead.TypeaheadSearchEngine.Companion.FIND_BATCH_SIZE
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentHashSet
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
import kotlinx.serialization.json.io.encodeToSink
import kotlinx.serialization.serializer
import kotlin.collections.forEachIndexed
import kotlin.collections.mutableMapOf

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

typealias Token = String
typealias DocId = String

data class TypeaheadResult(
    val docId: DocId,
    val tokens: PersistentSet<Token>,
    val score: Float
)

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
    val textSelector: (T) -> String,
    /**
     * The active configuration of this engine.
     *
     * Reflects all weight parameters, N-gram size, and result limits. When
     * [importFromSource] is called with `clearExisting = true`, this property is
     * atomically replaced by the [TypeaheadMetadata] embedded in the import stream,
     * so the engine's configuration is always consistent with its indexed vectors.
     */
    metadata: TypeaheadMetadata = TypeaheadMetadata(),
    val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val keySelector: (T) -> K,
) : TypeaheadSearch<T, K> {

    @PublishedApi
    internal var typeaheadMetadata: TypeaheadMetadata = metadata
    val metadata: TypeaheadMetadata
        get() = typeaheadMetadata

    /**
     * Atomic wrapper that keeps all indices in lock-step.
     * All fields are always updated together inside a single CAS operation so there
     * is never a window where one is ahead of the other under concurrent [add]/[remove].
     *
     * All inner maps are keyed by a String `docId` (produced by [generateDocId])
     * instead of the generic `T`. This avoids the **PersistentMap Key Trap**: complex
     * user objects with expensive `hashCode()`/`equals()` would otherwise multiply
     * into massive latency across the 100 000+ `put` operations that indexing triggers.
     * Strings cache their hash after the first call, making every subsequent
     * HAMT traversal O(1).
     *
     * @property tokenPool Canonicalization pool: maps a raw lowercase String to its canonical
     *           [Token] instance. Guarantees that every other field in this state references
     *           the **same** underlying String heap object for any given token value,
     *           eliminating String duplication across [embeddings], [index], and [tokens].
     * @property embeddings Flyweight cache: maps a unique lowercase token to its [SparseVector].
     * @property index Maps a vocabulary token → docId → earliest position in that doc.
     * @property tokens Maps a docId to the persistent set of tokens that compose its text.
     * @property store Maps a docId to the user's domain object [T], accessed only
     *           once per matching document when building the final result list.
     *           `null` when [TypeaheadMetadata.haveStore] is `false`.
     */
    @PublishedApi
    internal data class EngineState<T>(
        val tokenPool: PersistentMap<String, Token> = persistentHashMapOf(),
        val embeddings: PersistentMap<Token, SparseVector> = persistentHashMapOf(),
        val index: PersistentMap<Token, PersistentMap<DocId, Int>> = persistentHashMapOf(),
        val tokens: PersistentMap<DocId, PersistentSet<Token>> = persistentHashMapOf(),
        val store: PersistentMap<DocId, T>?,
    )

    @PublishedApi
    internal val state: MutableStateFlow<EngineState<T>> = MutableStateFlow(
        EngineState(store = if (metadata.haveStore) persistentHashMapOf() else null)
    )
    private val _results = MutableStateFlow<List<TypeaheadResult>>(emptyList())
    override val results: StateFlow<List<TypeaheadResult>> = _results.asStateFlow()

    private val _storeResults: MutableStateFlow<List<Pair<T, Float>>>? =
        if (metadata.haveStore) MutableStateFlow(emptyList()) else null
    override val storeResults: StateFlow<List<Pair<T, Float>>>? = _storeResults?.asStateFlow()

    private val _query = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> = _query.asStateFlow()

    /** Cached query-token vectors from the most recent [performBaseSearch]. */
    private var _lastQueryVectors: Map<Token, SparseVector> = emptyMap()

    private val scope = CoroutineScope(defaultDispatcher + SupervisorJob())

    init {
        scope.launch {
            state.collectLatest {
                val currentQuery = _query.value
                if (!currentQuery.isNullOrBlank()) {
                    updateSearch(currentQuery)
                }
            }
        }
    }

    private suspend fun updateSearch(query: String) {
        val snapshot = state.value
        val base = performBaseSearch(query, snapshot)
        _results.value = base
        val currentStore = snapshot.store
        if (currentStore != null) {
            _storeResults?.value = base.mapNotNull { br ->
                currentStore[br.docId]?.let { item -> item to br.score }
            }
        }
    }

    private suspend fun performBaseSearch(
        query: String,
        currentState: EngineState<T> = state.value,
    ): List<TypeaheadResult> = withContext(defaultDispatcher) {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return@withContext emptyList()

        // ── Phase 1: Vocabulary scan → candidate document set ──
        // For each query token, find the topK most similar vocabulary tokens,
        // then expand each into its inverted-index document list.
        // The UNION of all document IDs forms the candidate set.

        val candidateDocIds: Set<DocId> = queryTokens
            .asFlow()
            .map { queryToken ->
                val qVector = currentState.embeddings[queryToken] ?: embedding(queryToken)
                val cpq = ConcurrentPriorityQueue(
                    maxSize = typeaheadMetadata.topKVocab,
                    dispatcher = defaultDispatcher,
                    comparator = compareByDescending(Pair<Token, Float>::second),
                    keySelector = Pair<Token, Float>::first
                )
                cpq.addAll(
                    elements = currentState.embeddings.entries.asSequence().asFlow(),
                    transform = { (vocabWord, vocabVector) ->
                        vocabWord to (qVector dotProduct vocabVector)
                    }
                )
                cpq.items.value.map { it.first }.toSet()
            }
            .flatMapMerge { it.asFlow() }
            .flatMapMerge { vocabWord ->
                currentState.index[vocabWord]?.keys?.asFlow() ?: emptyFlow()
            }
            .toSet()

        if (candidateDocIds.isEmpty()) return@withContext emptyList()

        // Cache query vectors for Phase 2 scoring and for the heatmap function.
        val queryVectors: Map<Token, SparseVector> = buildMap(queryTokens.size) {
            for (qt in queryTokens) put(qt, currentState.embeddings[qt] ?: embedding(qt))
        }
        _lastQueryVectors = queryVectors

        // ── Phase 2: Score each candidate via full dotProduct + geometric mean ──
        // For every candidate document we re-compute the actual dotProduct between
        // each query vector and ALL document-token vectors.  This is critical:
        // a document may have been discovered through only ONE query token's topK,
        // yet it can still match the other query tokens well (e.g. "Harry Potter"
        // found via "harry" for q="hari" also contains "potter" for q="poter").

        val tokenCount = queryTokens.size

        val topResultsQueue = ConcurrentPriorityQueue(
            maxSize = typeaheadMetadata.maxResults,
            dispatcher = defaultDispatcher,
            comparator = compareByDescending(TypeaheadResult::score),
            keySelector = { it: TypeaheadResult -> it.docId.substringAfter('\u0000') }
        )

        topResultsQueue.addAll(
            elements = candidateDocIds.asSequence().asFlow(),
            transform = { docId ->
                val docTokens = currentState.tokens[docId]!!
                val matchScores = FloatArray(tokenCount)
                val matchPositions = mutableListOf<Int>()
                var idx = 0

                for (queryToken in queryTokens) {
                    val qVector = queryVectors[queryToken] ?: continue
                    var bestScore = 0.0f
                    var bestMatchToken: Token? = null

                    for (docToken in docTokens) {
                        val dVector = currentState.embeddings[docToken] ?: continue
                        val score = qVector dotProduct dVector
                        if (score > bestScore) {
                            bestScore = score
                            bestMatchToken = docToken
                        }
                    }

                    matchScores[idx] = bestScore
                    if (bestScore > 0f && bestMatchToken != null) {
                        val pos = currentState.index[bestMatchToken]?.get(docId) ?: -1
                        if (pos >= 0) matchPositions.add(pos)
                    }
                    idx++
                }

                // Harmonic mean: heavily penalises results where some query tokens
                // match poorly (e.g. prefix-only coincidence).  Unlike the geometric
                // mean, it ensures that a single high score cannot compensate for a
                // near-zero match on another token — the same principle behind F1-score.
                val baseScore = if (tokenCount == 1) {
                    matchScores[0]
                } else {
                    var reciprocalSum = 0.0
                    for (i in 0 until tokenCount) {
                        reciprocalSum += 1.0 / matchScores[i].toDouble().coerceAtLeast(1e-6)
                    }
                    (tokenCount.toDouble() / reciprocalSum).toFloat()
                }

                // Proximity with exponential decay 1/2^(gap+1).
                val proximityRatio: Float = when {
                    matchPositions.size >= 2 -> {
                        matchPositions.sort()
                        var totalDecay = 0.0f
                        for (i in 0 until matchPositions.size - 1) {
                            val gap = (matchPositions[i + 1] - matchPositions[i] - 1)
                                .coerceAtLeast(0)
                            totalDecay += 1.0f / (1 shl (gap + 1)) // 1/2^(gap+1)
                        }
                        val maxDecay = (matchPositions.size - 1) * 0.5f // all adjacent
                        totalDecay / maxDecay // normalised to (0, 1]
                    }
                    matchPositions.size == 1 && tokenCount > 1 -> 0f
                    else -> 1.0f // single token → proximity n/a
                }

                val proximityFactor =
                    1.0f - typeaheadMetadata.adjacencyBonus * (1.0f - proximityRatio)

                // BM25-inspired document-length penalty.
                val logDocLength = log10(docTokens.size.toFloat().coerceAtLeast(1f))
                val lengthPenalty = (1.0f - 0.1f * logDocLength).coerceAtLeast(0.5f)

                val finalScore = (baseScore * proximityFactor * lengthPenalty).coerceIn(0f, 1f)

                TypeaheadResult(docId = docId, tokens = docTokens, score = finalScore)
            }
        )

        return@withContext topResultsQueue.items.value
    }

    /**
     * Updates the active [query] and performs a two-stage retrieval search, storing
     * the ranked matches in the [results] `StateFlow`.
     *
     * **Stage 1 (Vocabulary Scan):** Each query token is vectorized and matched against
     * the shared vocabulary via a top-K fuzzy scan. The resulting vocabulary hits are
     * expanded through the inverted index into per-query-token document maps that
     * carry both the cosine similarity and the token position inside each document.
     *
     * **Stage 2 (Vocab-Aware Scoring):** Candidate documents are scored using:
     * - **Harmonic mean** of per-query-token cosine scores (heavily penalises partial
     *   coverage — a single low-scoring token drives the aggregate toward zero,
     *   following the same principle as F1-score in information retrieval),
     * - **Proximity decay** `1/2^(gap+1)` between consecutive matched positions
     *   (rewards tokens that appear close together in the document), and
     * - **BM25-inspired length penalty** on the document token count.
     *
     * A bounded [ConcurrentPriorityQueue] retains only the top
     * [TypeaheadMetadata.maxResults] matches. Blank queries clear the result set
     * immediately.
     *
     * Because the engine also observes [state] changes, the [results] flow
     * is automatically refreshed when items are added or removed while a non-blank query
     * is active — no additional [find] call is needed.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(items = listOf("Kotlin", "Java", "Koka"))
     * val results: StateFlow<List<Pair<String, Float>>> = engine.find("Kot")
     * // results.value[0] == ("Kotlin" to 0.95f)  — highest cosine similarity
     * ```
     *
     * @param query The user's input string to search for.
     * @return The [results] `StateFlow` containing descending-score pairs of matched
     *         objects and their similarity scores `[0.0, 1.0]`.
     */
    override suspend fun find(
        query: String,
    ): StateFlow<List<TypeaheadResult>> {
        _query.value = query
        if (query.isBlank()) {
            _results.value = emptyList()
            _storeResults?.value = emptyList()
        } else {
            updateSearch(query)
        }
        return results
    }

    /**
     * Computes a character-level heatmap for a document identified by [docId] against
     * the current [query], using the **tokenized multi-word matching** algorithm.
     *
     * The algorithm works in three phases:
     *
     * 1. **Exact intersection** — tokens present in both the query and the document
     *    receive a score of `1.0f` immediately (no embedding lookup needed because
     *    L2-normalised vectors have `dotProduct == 1.0` with themselves).
     *
     * 2. **Fuzzy matching** — remaining query tokens are matched against remaining
     *    document tokens via [SparseVector.dotProduct] on the cached embeddings.
     *    Each query token keeps only its best match; the matched document token is
     *    removed from the candidate set so it cannot be reused.
     *
     * 3. **Per-word colouring** — for every matched `(queryToken, docToken)` pair
     *    the existing [String.toHeatmap] is called on the original-case word span,
     *    and the resulting tier values are written into the correct character offsets.
     *
     * @param docId The composite document identifier (as stored in [TypeaheadResult.docId]).
     * @param ignoreCase Forwarded to the per-word [String.toHeatmap] calls.
     * @return A [List] of `(Char, tier)` pairs with exactly as many entries as the
     *         human-readable portion of [docId], or `null` if no query is active or
     *         the document is not indexed.
     */
    fun heatmap(
        docId: DocId,
        ignoreCase: Boolean = typeaheadMetadata.ignoreCase
    ): List<Pair<Char, Int>>? {
        val currentQuery = _query.value ?: return null
        val snapshot = state.value
        val targetText = docId.substringBefore('\u0000')
        val querySet = tokenize(currentQuery)
        val targetSet = snapshot.tokens[docId] ?: return null

        // Fast path: single token on both sides → delegate to the classic character-level algorithm
        if (querySet.size == 1 && targetSet.size == 1) {
            return currentQuery.toHeatmap(targetStr = targetText, ignoreCase = ignoreCase)
        }

        // ── Phase 1: Exact matches via set intersection (score = 1.0f) ──
        val exactMatches = (querySet intersect targetSet)
            .map { (it to it) to 1.0f }

        // ── Phase 2: Fuzzy matches via dotProduct ──
        val toScoreSet = querySet - targetSet
        val remainingTargets = (targetSet - querySet).toMutableSet()
        val queryEmbeddings = _lastQueryVectors

        val fuzzyMatches = mutableListOf<Pair<Pair<Token, Token>, Float>>()
        for (qToken in toScoreSet) {
            val qVec = queryEmbeddings[qToken] ?: continue
            var bestTarget: Token? = null
            var bestScore = 0f
            for (tToken in remainingTargets) {
                val dVec = snapshot.embeddings[tToken] ?: continue
                val score = qVec dotProduct dVec
                if (score > bestScore) {
                    bestScore = score
                    bestTarget = tToken
                }
            }
            if (bestTarget != null && bestScore > 0f) {
                fuzzyMatches.add((qToken to bestTarget) to bestScore)
                remainingTargets.remove(bestTarget)
            }
        }

        // ── Phase 3: Assemble and colour ──
        val allMatches = exactMatches + fuzzyMatches.sortedByDescending { it.second }
        val targetPositionedTokens = tokenizeWithOffsets(targetText, typeaheadMetadata.tokenizeRegexString)

        return toTokenizedHeatmap(
            matchedPairs = allMatches,
            targetText = targetText,
            targetTokensWithOffsets = targetPositionedTokens,
            ignoreCase = ignoreCase
        )
    }

    /**
     * Computes the [DocId] for the given [item] using the engine's
     * [textSelector] and [keySelector].
     */
    fun docIdFor(item: T): DocId = generateDocId(item)

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
        val docId = generateDocId(item)
        val rawTokens = tokenize(textSelector(item))

        // Precompute embeddings outside the CAS — `embedding` suspends and must not be re-entered on retry.
        val precomputedVocab: Map<Token, SparseVector> = buildMap(rawTokens.size) {
            for (t in rawTokens) put(t, state.value.embeddings[t] ?: embedding(t))
        }

        state.update { current ->
            if (current.tokens.containsKey(docId)) return@update current

            // Canonicalize each raw token against the pool so every downstream field
            // references the same underlying String heap object for a given token value.
            var pool = current.tokenPool
            val canonical = LinkedHashSet<Token>(rawTokens.size)
            for (t in rawTokens) {
                val existing = pool[t]
                if (existing != null) {
                    canonical.add(existing)
                } else {
                    canonical.add(t)
                    pool = pool.put(t, t)
                }
            }

            // Insert any new embeddings keyed by the canonical Token reference.
            var embeddings = current.embeddings
            for (t in canonical) {
                if (!embeddings.containsKey(t)) {
                    val vec = precomputedVocab[t] ?: continue
                    embeddings = embeddings.put(t, vec)
                }
            }

            // Update only the inverted-index entries touched by this doc's tokens,
            // preserving all other buckets via structural sharing.
            var index = current.index
            for ((position, t) in canonical.withIndex()) {
                val bucket = index[t] ?: persistentHashMapOf()
                val existing = bucket[docId]
                val newPos = if (existing == null) position else min(existing, position)
                index = index.put(t, bucket.put(docId, newPos))
            }

            current.copy(
                tokenPool = pool,
                embeddings = embeddings,
                index = index,
                tokens = current.tokens.put(docId, canonical.toPersistentHashSet()),
                store = current.store?.put(docId, item),
            )
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
     * Removes a specific element from the search engine.
     *
     * The item's document ID is computed via [generateDocId] and removed from the
     * document store, forward index, and inverted index. Vocabulary tokens that are
     * still referenced by other documents are preserved; inverted index entries are
     * pruned only when no documents reference them.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(items = listOf("Kotlin", "Java"))
     * engine.remove("Kotlin")
     * "Kotlin" in engine // false
     * ```
     *
     * @param item The element to remove.
     */
    override fun remove(item: T) {
        val docId = generateDocId(item)
        state.update { current ->
            val currentStore = current.store
            val presentInStore = currentStore != null && docId in currentStore
            val docTokens = current.tokens[docId]
            if (!presentInStore && docTokens == null) return@update current

            var inverted = current.index
            if (docTokens != null) {
                for (token in docTokens) {
                    val existing = inverted[token] ?: continue
                    val updated = existing.remove(docId)
                    inverted = if (updated.isEmpty()) {
                        inverted.remove(token)
                    } else {
                        inverted.put(token, updated)
                    }
                }
            }

            current.copy(
                index = inverted,
                tokens = current.tokens.remove(docId),
                store = currentStore?.remove(docId),
            )
        }
    }

    /**
     * Clears the entire vector space and removes all indexed elements.
     *
     * After calling this method, [size] returns 0 and [getAllItems] returns an empty list.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(items = listOf("Apple", "Banana"))
     * engine.clear()
     * engine.size // 0
     * ```
     */
    override fun clear() {
        state.value = EngineState(
            store = if (typeaheadMetadata.haveStore) persistentHashMapOf() else null
        )
    }

    /**
     * Returns the number of distinct composite keys currently indexed in the vector space.
     *
     * Each composite key is formed as `textSelector(item) + keySelector(item)`. Items sharing
     * the same text but with different keys are counted separately.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(items = listOf("Kotlin", "Java"))
     * engine.size // 2
     * ```
     */
    override val size: Int
        get() = state.value.let { it.store?.size ?: it.tokens.size }

    /**
     * Checks if an item with the same composite key is currently indexed in the engine.
     *
     * The composite key is formed as `textSelector(item) + keySelector(item)`. Both the text
     * and the key must match for this to return `true`. Supports the idiomatic `in` operator syntax.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(items = listOf("Kotlin"))
     * "Kotlin" in engine  // true
     * "Java" in engine    // false
     * ```
     *
     * @param item The element to check.
     * @return `true` if an item with the same composite key exists in the index, `false` otherwise.
     */
    override operator fun contains(item: T): Boolean {
        val docId = generateDocId(item)
        val snapshot = state.value
        return (snapshot.store?.containsKey(docId)) ?: snapshot.tokens.containsKey(docId)
    }

    /**
     * Returns a snapshot list of all items currently indexed in the engine.
     *
     * Useful for displaying the initial dataset when the search query is empty.
     * The returned list is a point-in-time snapshot; concurrent modifications
     * are not reflected.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine(items = listOf("Kotlin", "Java"))
     * engine.getAllItems() // ["Kotlin", "Java"]
     * ```
     *
     * @return A read-only list of all domain objects.
     */
    override fun getAllItems(): List<T> {
        return state.value.store?.values?.toList() ?: emptyList()
    }

    /**
     * Generates an L2-normalized hybrid positional N-gram embedding from the given string.
     *
     * Constructs a [SparseVector] with 32-bit floating-point precision by extracting
     * 8 feature categories: P0 anchors, length buckets, gestalt anchors, strict prefixes,
     * fuzzy prefixes, skip-grams, and floating N-grams. The resulting vector is L2-normalized
     * (magnitude = 1.0f), enabling direct cosine similarity via [SparseVector.dotProduct].
     *
     * This is a cooperative suspend function that yields periodically during the prefix
     * and N-gram loops to remain cancellation-responsive on large strings.
     *
     * ```kotlin
     * val engine = TypeaheadSearchEngine<String, String>(textSelector = { it }, keySelector = { it })
     * val vector: SparseVector = engine.embedding("Kotlin")
     * // vector is L2-normalized and ready for dot-product similarity
     * ```
     *
     * @param text The input string to embed (lowercased internally).
     * @return An L2-normalized [SparseVector], or an empty vector if the string is blank
     *         or the computed magnitude is below [EPSILON_FLOAT].
     */
    suspend fun embedding(text: Token): SparseVector {
        val vector = mutableMapOf<String, Float>()
        val word = text.lowercase()
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

    /**
     * Generates a collision-safe String document ID for the given [item].
     *
     * Uses `\u0000` (null character) as separator to guarantee that
     * `textSelector="AB", keySelector="C"` is never confused with
     * `textSelector="A", keySelector="BC"`.
     *
     * The resulting String caches its hash after the first call, making
     * all subsequent PersistentMap (HAMT) traversals O(1).
     */
    @PublishedApi
    internal fun generateDocId(item: T): DocId =
        "${textSelector(item)}\u0000${keySelector(item)}"

    /**
     * Splits the input text into lowercase word tokens, filtering out blanks.
     */
    fun tokenize(text: String): Set<Token> {
        val tokenizeRegex = Regex(typeaheadMetadata.tokenizeRegexString)
        return text
            .split(tokenizeRegex)
            .asSequence()
            .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            .toSet()
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
        const val DEFAULT_LENGTH_WEIGHT = 3.0f
        const val DEFAULT_GESTALT_WEIGHT = 4.0f
        const val DEFAULT_PREFIX_WEIGHT = 6.0f
        const val DEFAULT_FUZZY_WEIGHT = 5.0f
        const val DEFAULT_SKIP_WEIGHT = 2.0f
        const val DEFAULT_FLOATING_WEIGHT = 1.0f
        const val DEFAULT_MAX_RESULTS = 5
        const val FIND_BATCH_SIZE = 512
        const val DEFAULT_TOP_K_VOCAB = 30
        const val DEFAULT_ADJACENCY_BONUS = 0.5f
        const val DEFAULT_TOKENIZE_REGEX_STRING = """[^\p{L}\d]+"""
        const val DEFAULT_HAVE_STORE = false

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
         * @param items A [Flow] of initial elements to populate the engine concurrently.
         * @param textSelector Extracts searchable text from each element [T].
         * @param metadata Configuration for weights, N-gram size, and result limits.
         * @param defaultDispatcher The coroutine dispatcher for background tasks.
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
         * The stream also contains [TypeaheadVocabularyEntry] records with pre-computed
         * vectors and [TypeaheadPayload] records with token lists, loaded directly into the
         * index without re-vectorization.
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
         * @return A fully restored [TypeaheadSearchEngine] with [metadata] set from the stream.
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
         * @return A fully restored [TypeaheadSearchEngine] with [metadata] set from the stream.
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
 * Exports the current engine state as newline-delimited JSON (JSONL) to the given [Sink].
 *
 * Each line is a standalone [TypeaheadRecord]: the first line is a [TypeaheadMetadata]
 * header, followed by [TypeaheadVocabularyEntry] records with pre-computed vectors,
 * and then [TypeaheadPayload] records with token lists. The streaming approach
 * ensures constant memory usage regardless of the dataset size.
 *
 * The exported data can be restored via [importFromSource].
 *
 * ```kotlin
 * val engine = TypeaheadSearchEngine(items = listOf("Kotlin", "Java"))
 * fileSystem.sink(path).use { sink ->
 *     engine.exportToSink(sink, serializer<String>())
 * }
 * ```
 *
 * @param sink The [Sink] to write the JSONL stream to.
 * @param itemSerializer The [KSerializer] for serializing items of type [T].
 * @param json The [Json] instance used for serialization.
 */
inline fun <reified T, K> TypeaheadSearchEngine<T, K>.exportToSink(
    sink: Sink,
    itemSerializer: KSerializer<T> = serializer<T>(),
    json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) {
    val polymorphicSerializer = TypeaheadRecord.serializer(itemSerializer)
    val currentState = state.value
    val records: Sequence<TypeaheadRecord<T>> = sequence {
        yield(typeaheadMetadata)
        // Emit vocabulary entries (Flyweight cache)
        currentState.embeddings.forEach { (token, vector) ->
            yield(TypeaheadVocabularyEntry(token = token, vector = vector))
        }
        // Emit item payloads with their token lists
        currentState.tokens.forEach { (docId, tokens) ->
            val item = currentState.store?.get(docId)
            if (item != null) {
                yield(TypeaheadPayload(item = item, tokens = tokens.toList()))
            } else {
                yield(TypeaheadTokenPayload(docId = docId, tokens = tokens.toList()))
            }
        }
    }
    sink.buffered().use { bufferedSink ->
        records.forEach { record ->
            json.encodeToSink(serializer = polymorphicSerializer, value = record, sink = bufferedSink)
            bufferedSink.writeString("\n")
        }
    }
}

/**
 * Restores the engine state by streaming records from a [Source], bypassing vectorization.
 *
 * Deserializes a JSONL (or JSON-array) stream previously produced by [exportToSink].
 * The stream contains three record types: a [TypeaheadMetadata] header,
 * [TypeaheadVocabularyEntry] records with pre-computed vectors, and [TypeaheadPayload]
 * entries with token lists. This bypasses re-vectorization entirely.
 * The format is auto-detected via [DecodeSequenceMode.AUTO_DETECT].
 *
 * **Metadata handling:**
 * - `clearExisting = true` (default): [metadata] is replaced by the value read from the
 *   stream, so the engine's configuration is always consistent with its indexed vectors.
 * - `clearExisting = false`: the imported metadata must exactly match the engine's current
 *   metadata; merging vectors produced with different weights or N-gram sizes would
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
 * @param source The [Source] to read the JSONL stream from.
 * @param itemSerializer The [KSerializer] for item deserialization.
 * @param clearExisting If `true`, replaces the current index and [metadata]; if `false`, merges records.
 * @param json The [Json] instance used for deserialization.
 * @throws IllegalStateException if [clearExisting] is `false` and the imported
 *         metadata differs from the engine's current metadata.
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
            format = DecodeSequenceMode.AUTO_DETECT
        )

        if (clearExisting) state.value = TypeaheadSearchEngine.EngineState(
            store = if (typeaheadMetadata.haveStore) persistentHashMapOf() else null
        )

        state.update { s ->
            var meta = typeaheadMetadata
            var pool = s.tokenPool
            var vocabulary = s.embeddings
            var invertedIndex = s.index
            var forwardIndex = s.tokens
            var documentStore = s.store

            sequence.forEach { record ->
                when (record) {
                    is TypeaheadMetadata -> {
                        if (clearExisting) {
                            meta = record
                        } else {
                            check(record == meta) {
                                """Imported metadata does not match engine metadata. To merge records, both must have identical weights and N-gram sizes to ensure consistent scoring. Use clearExisting = true to overwrite the current engine state with the imported one."""
                            }
                        }
                    }

                    is TypeaheadVocabularyEntry -> {
                        val raw = record.token
                        val token = pool[raw] ?: raw.also { pool = pool.put(raw, it) }
                        vocabulary = vocabulary.put(token, record.vector)
                    }

                    is TypeaheadPayload -> {
                        val item = record.item
                        val docId = generateDocId(item)

                        val canonicalTokens = LinkedHashSet<Token>(record.tokens.size)
                        record.tokens.forEachIndexed { position, raw ->
                            val token = pool[raw] ?: raw.also { pool = pool.put(raw, it) }
                            canonicalTokens.add(token)
                            val bucket = invertedIndex[token] ?: persistentHashMapOf()
                            val existingPos = bucket[docId]
                            val newPos = if (existingPos == null) position else min(existingPos, position)
                            invertedIndex = invertedIndex.put(token, bucket.put(docId, newPos))
                        }
                        forwardIndex = forwardIndex.put(docId, canonicalTokens.toPersistentHashSet())
                        documentStore = documentStore?.put(docId, item)
                    }

                    is TypeaheadTokenPayload -> {
                        val docId = record.docId
                        val canonicalTokens = LinkedHashSet<Token>(record.tokens.size)
                        record.tokens.forEachIndexed { position, raw ->
                            val token = pool[raw] ?: raw.also { pool = pool.put(raw, it) }
                            canonicalTokens.add(token)
                            val bucket = invertedIndex[token] ?: persistentHashMapOf()
                            val existingPos = bucket[docId]
                            val newPos = if (existingPos == null) position else min(existingPos, position)
                            invertedIndex = invertedIndex.put(token, bucket.put(docId, newPos))
                        }
                        forwardIndex = forwardIndex.put(docId, canonicalTokens.toPersistentHashSet())
                    }
                }
            }
            typeaheadMetadata = meta
            TypeaheadSearchEngine.EngineState(
                tokenPool = pool,
                embeddings = vocabulary,
                index = invertedIndex,
                tokens = forwardIndex,
                store = documentStore,
            )
        }
    }
}