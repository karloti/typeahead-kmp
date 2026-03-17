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

import io.github.karloti.typeahead.BoundedConcurrentPriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.DefaultAsserter.assertEquals
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test suite for [io.github.karloti.typeahead.BoundedConcurrentPriorityQueue].
 * * Verifies the correctness of concurrent element additions, strict capacity bounding,
 * proper priority sorting, and the atomicity of the unique key deduplication mechanisms.
 */
class BoundedConcurrentPriorityQueueTest {

    /**
     * A mock data class used to simulate an incoming typeahead search result.
     */
    private data class SearchResultItem(val id: Int, val score: Int)

    /**
     * A mock data class used to test the default Comparable factory methods.
     */
    private data class ComparableItem(val id: Int, val score: Int) : Comparable<ComparableItem> {
        override fun compareTo(other: ComparableItem): Int = this.score.compareTo(other.score)
    }

    /**
     * Verifies that when an element with a previously registered unique key is added,
     * the queue ignores the new element and preserves the existing one.
     */
    @Test
    fun `test duplicate keys are correctly ignored`() {
        val queue = BoundedConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 3,
            priorityComparator = compareByDescending { it.score },
            uniqueKeySelector = { it.id }
        )

        queue.add(SearchResultItem(id = 1, score = 5))
        queue.add(SearchResultItem(id = 1, score = 10))
        queue.add(SearchResultItem(id = 2, score = 20))
        queue.add(SearchResultItem(id = 3, score = 3))
        queue.add(SearchResultItem(id = 4, score = 4))
        queue.add(SearchResultItem(id = 5, score = 5))


        val result = queue.items.value
        result.forEach { println("Result: $it") }

        queue.items.value.forEach { println("Result: $it") }

        assertEquals(3, result.size)
        assertEquals(20, result[0].score)
        assertEquals(10, result[1].score)
        assertEquals(5, result[2].score)
        assertEquals(2, result[0].id)
        assertEquals(1, result[1].id)
        assertEquals(5, result[2].id)


    }

    /**
     * Verifies the eviction edge case: when an element is removed from the queue due to
     * capacity constraints, its unique key must be freed. This allows a subsequent element
     * with the exact same key to re-enter the queue if its priority is high enough.
     */
    @Test
    fun `test evicted key can re-enter the queue`() {
        val queue = BoundedConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 2,
            priorityComparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        queue.add(SearchResultItem(id = 1, score = 50))
        queue.add(SearchResultItem(id = 2, score = 40))
        queue.add(SearchResultItem(id = 3, score = 30))
        queue.add(SearchResultItem(id = 1, score = 20))

        val result = queue.items.value
        assertEquals(2, result.size)
        assertEquals(1, result[0].id)
        assertEquals(3, result[1].id)
    }

    /**
     * A stress test executing thousands of concurrent additions across multiple coroutines.
     * Intentionally generates heavy key collisions to ensure the underling CAS operations
     * maintain absolute state consistency without race conditions or memory leaks.
     */
    @Test
    fun `test massive concurrent additions with heavy key collisions`() = runTest {
        val queue = BoundedConcurrentPriorityQueue<SearchResultItem, Int>(
            maxSize = 10,
            priorityComparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        val jobs = (1..100).map { i ->
            launch(Dispatchers.Default) {
                for (j in 1..100) {
                    val id = (i * j) % 500
                    val score = id
                    queue.add(SearchResultItem(id = id, score = score))
                }
            }
        }

        jobs.joinAll()

        val result = queue.items.value

        assertEquals(10, result.size)

        val uniqueIds = result.map { it.id }.toSet()
        assertEquals("Duplicate keys found in the final result state!", 10, uniqueIds.size)

        val isSorted = result.zipWithNext { a, b -> a.score <= b.score }.all { it }
        assertTrue("The resulting collection is not strictly sorted!", isSorted)

        assertEquals((0..9).toList(), result.map { it.id })
    }

    /**
     * Verifies that the parameterless factory method for [Comparable] types defaults
     * to a descending sort order while using the elements themselves as identity keys.
     */
    @Test
    fun `test factory default for Comparable types uses reverse order and self key`() {
        val queue = BoundedConcurrentPriorityQueue.Companion<Int>()

        queue.add(10)
        queue.add(50)
        queue.add(20)
        queue.add(30)
        queue.add(40)
        queue.add(60)
        queue.add(60)

        val result = queue.items.value
        assertEquals(5, result.size)
        assertEquals(listOf(60, 50, 40, 30, 20), result)
    }

    /**
     * Verifies the generic factory method applying a custom sorting comparator while
     * still utilizing the elements themselves as identity keys.
     */
    @Test
    fun `test factory with custom comparator and self key uses provided order`() {
        val queue = BoundedConcurrentPriorityQueue.Companion<Int>(
            maxSize = 3,
            priorityComparator = naturalOrder()
        )

        queue.add(10)
        queue.add(2)
        queue.add(5)
        queue.add(1)
        queue.add(2)

        val result = queue.items.value
        assertEquals(3, result.size)
        assertEquals(listOf(1, 2, 5), result)
    }

    /**
     * Verifies the factory method designed for [Comparable] types requiring a custom
     * key selector. Ensures the descending default order is correctly combined with the key logic.
     */
    @Test
    fun `test factory for Comparable with custom key selector uses reverse order`() {
        val queue = BoundedConcurrentPriorityQueue.Companion<ComparableItem, Int>(
            maxSize = 3,
            uniqueKeySelector = { it.id }
        )

        queue.add(ComparableItem(id = 1, score = 10))
        queue.add(ComparableItem(id = 2, score = 50))
        queue.add(ComparableItem(id = 3, score = 30))
        queue.add(ComparableItem(id = 4, score = 20))
        queue.add(ComparableItem(id = 2, score = 100))

        val result = queue.items.value
        assertEquals(3, result.size)
        assertEquals(listOf(100, 30, 20), result.map { it.score })
    }

    /**
     * Verifies the primary factory method successfully applies the default maximum
     * capacity when omitted, while utilizing the provided comparator and key selector.
     */
    @Test
    fun `test factory with full control applies default max size`() {
        val queue = BoundedConcurrentPriorityQueue<SearchResultItem, Int>(
            priorityComparator = compareBy { it.score },
            uniqueKeySelector = { it.id }
        )

        (1..6).forEach { queue.add(SearchResultItem(id = it, score = it * 10)) }

        val result = queue.items.value
        assertEquals(5, result.size)
        assertEquals(listOf(1, 2, 3, 4, 5), result.map { it.id })
    }
}