package io.github.kotlin.fibonacci.typeahead

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.min

/**
 * A lock-free, thread-safe bounded priority queue optimized for Kotlin Multiplatform.
 * It utilizes atomic Compare-And-Swap (CAS) operations via [MutableStateFlow]
 * to maintain high performance across multiple concurrent writers and readers.
 *
 * @param T The type of elements held in this queue.
 * @param maxSize The maximum number of elements the queue can hold.
 * @param comparator The comparator used to order the elements.
 */
class BoundedConcurrentPriorityQueue<T>(
    private val maxSize: Int,
    private val comparator: Comparator<T>
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())

    /**
     * A reactive stream of the current items in the queue.
     * Can be observed directly by UI components.
     */
    val items: StateFlow<List<T>> = _items.asStateFlow()

    /**
     * Attempts to add an item to the bounded queue.
     * If the queue is at capacity and the item is evaluated as having lower priority
     * than the lowest item currently in the queue, it is immediately discarded.
     *
     * @param item The element to add.
     */
    fun add(item: T) {
        _items.update { currentList ->
            if (currentList.size >= maxSize && comparator.compare(item, currentList.last()) > 0) {
                return@update currentList
            }

            var index = currentList.binarySearch(item, comparator)
            if (index < 0) {
                index = -(index + 1)
            }

            val newList = ArrayList<T>(min(currentList.size + 1, maxSize))

            for (i in 0 until index) {
                newList.add(currentList[i])
            }

            if (newList.size < maxSize) {
                newList.add(item)
            }

            for (i in index until currentList.size) {
                if (newList.size >= maxSize) break
                newList.add(currentList[i])
            }

            newList
        }
    }
}