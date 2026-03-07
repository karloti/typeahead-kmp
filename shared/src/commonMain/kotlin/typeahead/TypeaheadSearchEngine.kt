package io.github.kotlin.fibonacci.typeahead

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * A high-performance, asynchronous fuzzy search engine designed for typeahead capabilities.
 * It precomputes string embeddings to allow O(1) matching time complexity per record.
 * * Instances of this class are fully immutable and thread-safe.
 */
class TypeaheadSearchEngine private constructor(
    private val precomputedEmbeddings: Map<String, Map<String, Double>>,
    private val defaultDispatcher: CoroutineDispatcher
) {

    companion object {
        /**
         * Factory method to construct and initialize the search engine.
         * The initialization distributes the vectorization workload across available CPU cores.
         *
         * @param corpus The initial list of strings to index.
         * @param dispatcher The coroutine dispatcher for background computation. Defaults to [Dispatchers.Default].
         * @return A fully initialized and ready-to-use [TypeaheadSearchEngine].
         */
        suspend operator fun invoke(
            corpus: List<String>,
            dispatcher: CoroutineDispatcher = Dispatchers.Default
        ): TypeaheadSearchEngine = withContext(dispatcher) {

            val embeddings = corpus.map { text ->
                async {
                    text to text.toPositionalEmbedding()
                }
            }.awaitAll().toMap()

            TypeaheadSearchEngine(embeddings, dispatcher)
        }
    }

    /**
     * Searches the precomputed corpus for the best matches against the provided query.
     * Yields the thread cooperatively during heavy computations.
     *
     * @param query The user's input string.
     * @param maxResults The maximum number of top results to return.
     * @return A sorted list of pairs containing the matched string and its similarity score.
     */
    suspend fun search(query: String, maxResults: Int = 20): List<Pair<String, Double>> {
        if (query.isBlank()) return emptyList()

        return withContext(defaultDispatcher) {
            val queryVector = query.toPositionalEmbedding()

            val topResultsQueue = BoundedConcurrentPriorityQueue<Pair<String, Double>>(
                maxSize = maxResults,
                comparator = compareByDescending { it.second }
            )

            for ((text, targetVector) in precomputedEmbeddings) {
                val score = queryVector dotProduct targetVector
                if (score > 0.0) {
                    topResultsQueue.add(text to score)
                }
                yield()
            }

            topResultsQueue.items.value
        }
    }
}