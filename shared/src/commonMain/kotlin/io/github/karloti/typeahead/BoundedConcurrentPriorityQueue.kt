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

@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

package io.github.karloti.typeahead

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A lock-free, thread-safe bounded priority queue that maintains a sorted list of top elements
 * while guaranteeing uniqueness based on a specific identity key.
 *
 * It utilizes atomic Compare-And-Swap (CAS) operations via [MutableStateFlow] to maintain
 * high performance across multiple concurrent writers and readers without blocking threads.
 *
 * @param T The type of elements held in this queue.
 * @param K The type of the unique key used to identify elements and prevent duplicates.
 * @property maxSize The maximum number of elements the queue can hold.
 * @property priorityComparator Defines the sorting order of the elements. Elements that sort smaller
 * (i.e., appear earlier in the order) are considered to have a higher priority.
 * @property uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
 * If an element with an identical key is already present in the queue, the new element is ignored.
 */
class BoundedConcurrentPriorityQueue<T, K>(
    private val maxSize: Int = 5,
    private val priorityComparator: Comparator<T>,
    private val uniqueKeySelector: (T) -> K,
) {
    /**
     * Represents the internal atomic state of the queue, keeping the sorted list
     * and the set of unique keys perfectly synchronized.
     */
    private data class QueueState<T, K>(
        val items: PersistentList<T> = persistentListOf(),
        val keys: PersistentSet<K> = persistentSetOf()
    )

    private val _state = MutableStateFlow(QueueState<T, K>())

    /**
     * A highly optimized, read-only [StateFlow] exposing the current top elements.
     * It strictly guarantees immutability for downstream consumers (e.g., UI components)
     * preventing accidental modifications or the need for defensive copying.
     */
    val items: StateFlow<ImmutableList<T>> = object : StateFlow<ImmutableList<T>> {
        override val replayCache: List<ImmutableList<T>> get() = listOf(_state.value.items)
        override val value: ImmutableList<T> get() = _state.value.items
        override suspend fun collect(collector: FlowCollector<ImmutableList<T>>): Nothing {
            _state.collect { collector.emit(it.items) }
        }
    }

    /**
     * Attempts to add a new item to the bounded priority queue concurrently.
     *
     * The insertion process follows these rules:
     * 1. If an item with the same unique key already exists, the new item is discarded.
     * 2. If the queue is at maximum capacity and the new item has a lower or equal priority
     * compared to the lowest-priority item currently in the queue, it is discarded.
     * 3. If the item qualifies, it is inserted at the correct sorted position. If this insertion
     * exceeds the [maxSize], the lowest-priority item is evicted, and its unique key is freed.
     *
     * @param item The element to be evaluated and potentially added to the queue.
     */
    fun add(item: T) {
        if (maxSize <= 0) return
        val itemKey = uniqueKeySelector(item)

        _state.update { currentState ->
            // Fast path: Ignore if an item with the exact same unique key is already in the queue
            if (currentState.keys.contains(itemKey)) {
                return@update currentState
            }

            // Fast path: Discard if the queue is full and the item is worse or equal to the worst item
            if (currentState.items.size >= maxSize && priorityComparator.compare(
                    item,
                    currentState.items.last()
                ) >= 0
            ) {
                return@update currentState
            }

            // Find the exact insertion index using binary search for O(log N) performance
            val searchResult = currentState.items.binarySearch(item, priorityComparator)
            val insertIndex = if (searchResult < 0) -(searchResult + 1) else searchResult

            var evictedItem: T? = null

            // Mutate the persistent list and set atomically
            val newItems = currentState.items.mutate { mutableList ->
                mutableList.add(insertIndex, item)
                if (mutableList.size > maxSize) {
                    evictedItem = mutableList.removeAt(mutableList.size - 1)
                }
            }

            val newKeys = currentState.keys.mutate { mutableSet ->
                mutableSet.add(itemKey)
                evictedItem?.let { evicted ->
                    mutableSet.remove(uniqueKeySelector(evicted))
                }
            }

            QueueState(newItems, newKeys)
        }
    }

    companion object {
        /**
         * Creates a [BoundedConcurrentPriorityQueue] providing full control over the element type, sorting, and its unique key.
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         * @param priorityComparator Defines the sorting order of the elements.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        operator fun <T, K> invoke(
            maxSize: Int = 5,
            priorityComparator: Comparator<T>,
            uniqueKeySelector: (T) -> K
        ): BoundedConcurrentPriorityQueue<T, K> {
            return BoundedConcurrentPriorityQueue(maxSize, priorityComparator, uniqueKeySelector)
        }

        /**
         * Creates a [BoundedConcurrentPriorityQueue] for [Comparable] types with a custom identity key.
         * Uses a descending sorting order by default.
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         * @param uniqueKeySelector A lambda function that extracts a unique identity key from an element [T].
         */
        operator fun <T : Comparable<T>, K> invoke(
            maxSize: Int = 5,
            uniqueKeySelector: (T) -> K
        ): BoundedConcurrentPriorityQueue<T, K> {
            return BoundedConcurrentPriorityQueue(maxSize, reverseOrder(), uniqueKeySelector)
        }

        /**
         * Creates a [BoundedConcurrentPriorityQueue] where the elements themselves act as their own unique identity keys.
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         * @param priorityComparator Defines the sorting order of the elements.
         */
        operator fun <T> invoke(
            maxSize: Int = 5,
            priorityComparator: Comparator<T>
        ): BoundedConcurrentPriorityQueue<T, T> {
            return BoundedConcurrentPriorityQueue(maxSize, priorityComparator) { it }
        }

        /**
         * Creates a [BoundedConcurrentPriorityQueue] for [Comparable] types where the elements themselves act as their own unique identity keys.
         * Uses a descending sorting order by default.
         *
         * @param maxSize The maximum capacity of the queue. Defaults to 5.
         */
        operator fun <T : Comparable<T>> invoke(
            maxSize: Int = 5
        ): BoundedConcurrentPriorityQueue<T, T> {
            return BoundedConcurrentPriorityQueue(maxSize, reverseOrder()) { it }
        }
    }
}