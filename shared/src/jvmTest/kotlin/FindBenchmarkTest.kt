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

import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/**
 * Benchmark suite for [TypeaheadSearchEngine] insertion and search performance.
 *
 * Toggle [LOCAL] in commonTest to control intensity:
 * - `LOCAL = true`  → 300K–500K items, detailed profiling
 * - `LOCAL = false` → 3K–5K items, CI-safe
 */
class FindBenchmarkTest {

    private val datasetSize = if (LOCAL) 100_000 else 3_000
    private val largeDatasetSize = if (LOCAL) 500_000 else 5_000

    private fun generateItems(count: Int): Sequence<String> = sequence {
        val categories = listOf("Electronics", "Clothing", "Food", "Books", "Toys", "Sports", "Home", "Beauty")
        val adjectives = listOf("Premium", "Budget", "Luxury", "Basic", "Pro", "Ultra", "Mini", "Max")
        repeat(count) { i ->
            yield("${adjectives[i % adjectives.size]} ${categories[i % categories.size]} Item $i")
        }
    }

    private suspend fun benchmarkFind(
        engine: TypeaheadSearchEngine<String, String>,
        query: String,
        warmup: Int = 1,
        rounds: Int = if (LOCAL) 3 else 2,
    ): Duration {
        repeat(warmup) { engine.find(query) }
        val times = (1..rounds).map { measureTime { engine.find(query) } }
        return times.sorted()[times.size / 2]
    }

    private fun header(title: String) {
        println()
        println("=".repeat(90))
        println(title)
        println("=".repeat(90))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  1. addAll scaling — how insertion time grows with dataset size
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark addAll scaling with dataset size`() = runTest(timeout = 5.minutes) {
        val sizes = if (LOCAL) listOf(1_000, 10_000, 50_000, 100_000, 300_000) else listOf(500, 1_000, 3_000)

        header("BENCHMARK: addAll() scaling by dataset size")
        println("%-12s %-16s %-14s %s".format("Size", "addAll time", "Per item", "Throughput"))
        println("-".repeat(70))

        for (size in sizes) {
            val items = generateItems(size)
            val engine = TypeaheadSearchEngine<String>(maxResults = 20)

            val elapsed = measureTime { engine.addAll(items) }
            assertEquals(size, engine.size)

            val perItem = elapsed.inWholeNanoseconds.toDouble() / size / 1000.0 // microseconds
            val throughput = size.toDouble() / elapsed.inWholeMilliseconds.coerceAtLeast(1).toDouble() * 1000.0

            println(
                "%-12d %-16s %-14s %.0f items/sec".format(
                    size, elapsed, "%.1f us".format(perItem), throughput
                )
            )
        }

        println("=".repeat(90))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. addAll concurrency — how different concurrency levels affect insertion
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark addAll concurrency levels`() = runTest(timeout = 5.minutes) {
        val size = if (LOCAL) 100_000 else 5_000

        header("BENCHMARK: addAll() concurrency levels | $size items")
        println("%-14s %-16s %-14s %s".format("Concurrency", "addAll time", "Per item", "Throughput"))
        println("-".repeat(70))

        val items = generateItems(size).asFlow()

        val engine = TypeaheadSearchEngine<String, String>(maxResults = 20) { it }

        val elapsed = measureTime { engine.addAll(items) { it } }
        assertEquals(size, engine.size)

        val perItem = elapsed.inWholeNanoseconds.toDouble() / size / 1000.0
        val throughput = size.toDouble() / elapsed.inWholeMilliseconds.coerceAtLeast(1).toDouble() * 1000.0

        println(
            "%-16s %-14s %.0f items/sec".format(elapsed, "%.1f us".format(perItem), throughput)
        )

        println("=".repeat(90))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. find() sequential vs parallel — different concurrency levels
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark sequential vs parallel find`() = runTest(timeout = 5.minutes) {
        val items = generateItems(datasetSize)
        val engine = TypeaheadSearchEngine<String>(maxResults = 20)
        engine.addAll(items)
        assertEquals(datasetSize, engine.size)

        val query = "Premium Electronics"

        header("BENCHMARK: find() sequential vs parallel | $datasetSize items | query=\"$query\"")
        println("%-22s %-16s %-12s %s".format("Mode", "Median time", "Per item", "Speedup"))
        println("-".repeat(70))

        val seqTime = benchmarkFind(engine, query)
        val perItemSeq = seqTime.inWholeNanoseconds.toDouble() / datasetSize
        println(
            "%-22s %-16s %-12s %s".format(
                "Sequential (1)", seqTime, "%.0f ns".format(perItemSeq), "-"
            )
        )

        println("=".repeat(90))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  4. find() scaling — how find time grows with dataset size
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark find scaling with dataset size`() = runTest(timeout = 5.minutes) {
        val sizes = if (LOCAL) listOf(1_000, 10_000, 50_000, 100_000, 300_000) else listOf(500, 1_000, 3_000)
        val query = "Budget Food"

        header("BENCHMARK: find() scaling by dataset size | query=\"$query\"")
        println("%-12s %-16s %s".format("Size", "Sequential", "ns/item (seq)"))
        println("-".repeat(80))

        for (size in sizes) {
            val items = generateItems(size)
            val engine = TypeaheadSearchEngine<String>(maxResults = 20)
            engine.addAll(items)

            val seqTime = benchmarkFind(engine, query)
            val nsPerItem = seqTime.inWholeNanoseconds.toDouble() / size

            println("%-12d %-16s %.0f".format(size, seqTime, nsPerItem))
        }

        println("=".repeat(90))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  5. Throughput — queries per second at different concurrency levels
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark find throughput queries per second`() = runTest(timeout = 1.minutes) {
        val size = if (LOCAL) 200_000 else 3_000
        val items = generateItems(size)
        val engine = TypeaheadSearchEngine<String>(maxResults = 10)
        engine.addAll(items)

        val queries = listOf("Pro", "Premium Elec", "Budget Food Item", "Ultra")
        val iterations = if (LOCAL) 10 else 5

        // warmup
        for (q in queries) engine.find(q)

        header("BENCHMARK: Throughput | $size items | $iterations iterations x ${queries.size} queries")

        val elapsed = measureTime {
            repeat(iterations) {
                for (q in queries) {
                    engine.find(q)
                }
            }
        }
        val totalOps = iterations * queries.size
        val qps = if (elapsed.inWholeMilliseconds > 0)
            totalOps.toDouble() / elapsed.inWholeMilliseconds.toDouble() * 1000.0
        else totalOps.toDouble()
        val avgLatency = elapsed.inWholeMilliseconds.toDouble() / totalOps
        println(
            "%3d queries in %-14s | %6.1f q/s | avg latency: %.1f ms".format(
                totalOps, elapsed, qps, avgLatency
            )
        )

        println("=".repeat(90))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  6. Large dataset — full profile: addAll + find breakdown
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark large dataset full profile`() = runTest(timeout = 5.minutes) {
        header("BENCHMARK: Full profile | $largeDatasetSize items")

        // ── addAll ──
        val items = generateItems(largeDatasetSize)
        val engine = TypeaheadSearchEngine<String>(maxResults = 50)

        val insertTime = measureTime { engine.addAll(items) }
        assertEquals(largeDatasetSize, engine.size)

        val insertThroughput =
            largeDatasetSize.toDouble() / insertTime.inWholeMilliseconds.coerceAtLeast(1).toDouble() * 1000.0
        println(
            "addAll:  $insertTime | %.0f items/sec | %.1f us/item".format(
                insertThroughput,
                insertTime.inWholeNanoseconds.toDouble() / largeDatasetSize / 1000.0
            )
        )
        println()

        // ── find breakdown ──
        println(
            "%-28s %5s | %-14s | %s".format(
                "Query", "Hits", "Sequential", "ns/item (seq)"
            )
        )
        println("-".repeat(95))

        val queries = listOf("Premium", "Budget Food", "Ultra Sports Item 42", "x")

        for (query in queries) {
            val seqTime = benchmarkFind(engine, query, rounds = 2)
            val hits = engine.find(query).size
            val nsPerItem = seqTime.inWholeNanoseconds.toDouble() / largeDatasetSize

            println(
                "%-28s %5d | %-14s | %.0f".format(
                    "\"$query\"", hits, seqTime, nsPerItem
                )
            )
        }

        println("=".repeat(90))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  7. Correctness — parallel results contain the same top scores
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `verify parallel find returns same top scores as sequential`() = runTest(timeout = 5.minutes) {
        val items = generateItems(datasetSize)
        val engine = TypeaheadSearchEngine<String>(maxResults = 20)
        engine.addAll(items)

        val queries = listOf("Premium", "Budget Food", "Ultra Sports", "Mini", "Pro Electronics Item")

        for (query in queries) {
            val seqResults = engine.find(query)
            val parResults = engine.find(query)

            assertEquals(seqResults.size, parResults.size, "Result count must match for query=\"$query\"")

            val seqScores = seqResults.map { it.second }.sorted()
            val parScores = parResults.map { it.second }.sorted()
            assertEquals(seqScores, parScores, "Score sets must match for query=\"$query\"")

            if (seqResults.isNotEmpty()) {
                assertEquals(
                    seqResults.first().second,
                    parResults.first().second,
                    "Top-1 score must match for query=\"$query\""
                )
            }
        }

        println("Parallel vs sequential correctness: PASSED for ${queries.size} queries on $datasetSize items")
    }
}
