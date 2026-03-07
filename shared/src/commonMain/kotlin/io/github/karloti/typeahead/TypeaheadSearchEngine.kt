package io.github.karloti.typeahead

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * A high-performance, in-memory fuzzy search engine designed for typeahead capabilities.
 * It behaves like a mutable collection, allowing you to add, remove, and find elements.
 * * @param T The type of elements held in this engine.
 * @param textSelector A lambda function that extracts the searchable String from your object [T].
 * @param defaultDispatcher The coroutine dispatcher used for heavy vectorization. Defaults to [Dispatchers.Default].
 */
class TypeaheadSearchEngine<T>(
    private val textSelector: (T) -> String,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // Държим данните в StateFlow. Това гарантира 100% Lock-Free (без блокиране)
    // четене по време на търсене, дори ако друга нишка добавя елементи в същия момент!
    private val _embeddings = MutableStateFlow<Map<T, Map<String, Double>>>(emptyMap())

    /**
     * Finds the best matching elements for the given query.
     * Yields the thread cooperatively during heavy computations.
     *
     * @param query The user's input string.
     * @param maxResults The maximum number of top results to return.
     * @return A sorted list of pairs containing the matched object and its similarity score.
     */
    suspend fun find(query: String, maxResults: Int = 20): List<Pair<T, Double>> {
        if (query.isBlank()) return emptyList()

        return withContext(defaultDispatcher) {
            val queryVector = query.toPositionalEmbedding()

            val topResultsQueue = BoundedConcurrentPriorityQueue<Pair<T, Double>>(
                maxSize = maxResults,
                comparator = compareByDescending { it.second }
            )

            // Взимаме моментното състояние (Snapshot).
            // Ако друга нишка извика add() или remove(), това търсене няма да гръмне
            // с ConcurrentModificationException, защото работи върху неизменимо копие!
            val currentMap = _embeddings.value

            for ((item, targetVector) in currentMap) {
                val score = queryVector dotProduct targetVector
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
     * Note: If adding multiple elements, prefer [addAll] for much better performance.
     */
    suspend fun add(item: T) = withContext(defaultDispatcher) {
        val stringToVectorize = textSelector(item)
        val vector = stringToVectorize.toPositionalEmbedding()

        // Атомарно обновяване на речника (Compare-And-Swap)
        _embeddings.update { currentMap ->
            currentMap + (item to vector)
        }
    }

    /**
     * Batches multiple elements into the engine.
     * This distributes the vectorization workload across all available CPU cores!
     */
    suspend fun addAll(items: Iterable<T>) = withContext(defaultDispatcher) {
        // Векторизираме всичко паралелно, точно както в стария Фабричен метод
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
     * Removes an element from the engine.
     */
    fun remove(item: T) {
        _embeddings.update { currentMap ->
            currentMap - item
        }
    }

    /**
     * Clears all elements from the engine.
     */
    fun clear() {
        _embeddings.value = emptyMap()
    }

    /**
     * Returns the current number of indexed elements.
     */
    val size: Int
        get() = _embeddings.value.size
}