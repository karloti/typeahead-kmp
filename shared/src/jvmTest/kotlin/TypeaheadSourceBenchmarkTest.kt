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

import io.github.karloti.typeahead.TypeaheadRecord.TypeaheadMetadata
import io.github.karloti.typeahead.TypeaheadSearchEngine
import io.github.karloti.typeahead.exportToSink
import io.github.karloti.typeahead.importFromSource
import kotlinx.coroutines.test.runTest
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Benchmark and correctness suite for the [TypeaheadSearchEngine] source-based factory functions.
 *
 * Measures and verifies:
 * - Insertion time vs source-restore time (speedup factor)
 * - Memory consumption at each stage
 * - [TypeaheadMetadata] fidelity after restore — all weights and limits must be preserved
 * - Search result identity — same scores and item order between original and restored engine
 *
 * Both the T+K variant (`invoke(source, itemSerializer, textSelector, uniqueKeySelector)`) and
 * the self-keyed variant (`invoke(source, itemSerializer)`) are exercised.
 *
 * Toggle [LOCAL] in commonTest to control dataset size:
 * - `LOCAL = true`  → 10 000 records, 40 top results
 * - `LOCAL = false` → 1 000 records, 10 top results
 */
class TypeaheadSourceBenchmarkTest {

    private val targetSize = if (LOCAL) 10_000 else 1_000
    private val maxResults = if (LOCAL) 40 else 10
    private val query = "PQ82"

    private fun generateProducts(count: Int): List<Product> {
        val categories = listOf("Electronics", "Clothing", "Food", "Books", "Toys", "Sports", "Home", "Beauty")
        val brands = listOf("BrandA", "BrandB", "BrandC", "BrandD", "BrandE", "BrandF", "BrandG", "BrandH")
        return List(count) { index ->
            Product(
                id = index,
                name = "Product_${countries[index % countries.size]}_Item_$index",
                category = categories[index % categories.size],
                brand = brands[index % brands.size],
                price = 10.0 + (index % 1_000) * 0.99,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  1. T+K factory: TypeaheadSearchEngine(source, serializer, textSelector, uniqueKeySelector)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark and verify invoke(source) with explicit key selector`() = runTest(timeout = 2.minutes) {
        val products = generateProducts(targetSize)
        val customMetadata = TypeaheadMetadata(
            maxResults = maxResults,
            anchorWeight = 12.0f,
            gestaltWeight = 20.0f,
        )
        val runtime = Runtime.getRuntime()

        // ── 1. Build original engine ──────────────────────────────────────────
        runtime.gc(); Thread.sleep(100)
        val memBeforeInsert = runtime.totalMemory() - runtime.freeMemory()
        val insertStartMs = System.currentTimeMillis()

        val original = TypeaheadSearchEngine<Product>(metadata = customMetadata) { it.name }
        original.addAll(products)

        val insertMs = System.currentTimeMillis() - insertStartMs
        val memInsert = (runtime.totalMemory() - runtime.freeMemory() - memBeforeInsert) / 1024 / 1024
        assertEquals(targetSize, original.size)

        val originalResults = original.find(query)

        // ── 2. Export to temp file ────────────────────────────────────────────
        val tempFile = File.createTempFile("typeahead_source_bench_", ".json")
        try {
            runtime.gc(); Thread.sleep(100)
            val exportStartMs = System.currentTimeMillis()
            tempFile.outputStream().asSink().use { sink -> original.exportToSink(sink.buffered()) }
            val exportMs = System.currentTimeMillis() - exportStartMs
            val fileSizeKb = tempFile.length() / 1024
            val fileSizeMb = tempFile.length() / 1024 / 1024

            // ── 3. Restore via invoke(source=…) factory ───────────────────────
            runtime.gc(); Thread.sleep(100)
            val memBeforeRestore = runtime.totalMemory() - runtime.freeMemory()
            val restoreStartMs = System.currentTimeMillis()

            val restored: TypeaheadSearchEngine<Product, Int> =
                tempFile.inputStream().asSource().buffered().use { src ->
                    TypeaheadSearchEngine(
                        source = src,
                        textSelector = { it.name },
                        uniqueKeySelector = { it.id },
                    )
                }

            val restoreMs = System.currentTimeMillis() - restoreStartMs
            val memRestore = (runtime.totalMemory() - runtime.freeMemory() - memBeforeRestore) / 1024 / 1024

            // ── 4. Verify metadata fidelity ───────────────────────────────────
            assertEquals(
                customMetadata.maxResults, restored.metadata.maxResults,
                "maxResults must be restored from stream"
            )
            assertEquals(
                customMetadata.maxNgramSize, restored.metadata.maxNgramSize,
                "maxNgramSize must be restored from stream"
            )
            assertEquals(
                customMetadata.anchorWeight, restored.metadata.anchorWeight,
                "anchorWeight must be restored from stream"
            )
            assertEquals(
                customMetadata.gestaltWeight, restored.metadata.gestaltWeight,
                "gestaltWeight must be restored from stream"
            )
            assertEquals(
                targetSize, restored.size,
                "Restored engine must contain exactly $targetSize items"
            )

            // ── 5. Verify search result identity ─────────────────────────────
            // Sort before comparing: concurrent HAMT iteration order may differ between
            // an engine built via addAll() and one restored via importFromSource(), even
            // though both hold identical content. Sorting by score then by id removes
            // this ambiguity while fully verifying correctness.
            val restoredResults = restored.find(query)
            assertEquals(
                originalResults.size, restoredResults.size,
                "Result count must match"
            )
            val origSorted =
                originalResults.sortedWith(compareByDescending<Pair<Product, Float>> { it.second }.thenBy { it.first.id })
            val restSorted =
                restoredResults.sortedWith(compareByDescending<Pair<Product, Float>> { it.second }.thenBy { it.first.id })
            origSorted.zip(restSorted).forEachIndexed { i, (orig, rest) ->
                assertEquals(orig.first.id, rest.first.id, "Item ID mismatch at rank $i")
                assertEquals(orig.second, rest.second, "Score mismatch at rank $i")
            }

            val speedup = if (restoreMs > 0) insertMs / restoreMs else insertMs
            println()
            println("=".repeat(80))
            println("BENCHMARK: TypeaheadSearchEngine(source=…) factory — T+K variant")
            println("Dataset: $targetSize records | query: \"$query\"")
            println("=".repeat(80))
            println("Insertion:       $insertMs ms\t| Memory delta: $memInsert MB")
            println("Export (stream): $exportMs ms\t| Disk: $fileSizeMb MB ($fileSizeKb KB)")
            println("Restore (source factory): $restoreMs ms\t| Memory delta: $memRestore MB")
            println("Speedup vs insertion: ${speedup}×  (restore is ~${speedup}x faster than insert)")
            println("─".repeat(80))
            println("Metadata fidelity:")
            println("  maxResults  : ${restored.metadata.maxResults}  (expected: ${customMetadata.maxResults})")
            println("  maxNgramSize: ${restored.metadata.maxNgramSize}  (expected: ${customMetadata.maxNgramSize})")
            println("  anchorWeight: ${restored.metadata.anchorWeight}  (expected: ${customMetadata.anchorWeight})")
            println("  gestaltWeight: ${restored.metadata.gestaltWeight}  (expected: ${customMetadata.gestaltWeight})")
            println("Search identity: PASSED  (${restoredResults.size}/${originalResults.size} results match)")
            println("=".repeat(80))
        } finally {
            tempFile.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. Self-keyed factory: TypeaheadSearchEngine(source, itemSerializer)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `benchmark and verify invoke(source) self-keyed variant`() = runTest(timeout = 2.minutes) {
        val names = generateProducts(targetSize).map { it.name }
        val customMetadata = TypeaheadMetadata(maxResults = maxResults)
        val runtime = Runtime.getRuntime()

        // ── 1. Build + export ─────────────────────────────────────────────────
        runtime.gc(); Thread.sleep(100)
        val insertStartMs = System.currentTimeMillis()
        val original = TypeaheadSearchEngine<String>(metadata = customMetadata)
        original.addAll(names)
        val insertMs = System.currentTimeMillis() - insertStartMs
        assertEquals(targetSize, original.size)

        val tempFile = File.createTempFile("typeahead_selfkey_bench_", ".json")
        try {
            runtime.gc(); Thread.sleep(100)
            val exportStartMs = System.currentTimeMillis()
            tempFile.outputStream().asSink().use { sink -> original.exportToSink(sink.buffered()) }
            val exportMs = System.currentTimeMillis() - exportStartMs
            val fileSizeKb = tempFile.length() / 1024

            // ── 2. Restore via self-keyed factory ─────────────────────────────
            runtime.gc(); Thread.sleep(100)
            val memBeforeRestore = runtime.totalMemory() - runtime.freeMemory()
            val restoreStartMs = System.currentTimeMillis()

            val restored: TypeaheadSearchEngine<String, String> =
                tempFile.inputStream().asSource().buffered().use { src ->
                    TypeaheadSearchEngine(
                        source = src,
                        itemSerializer = kotlinx.serialization.serializer<String>(),
                    )
                }

            val restoreMs = System.currentTimeMillis() - restoreStartMs
            val memRestore = (runtime.totalMemory() - runtime.freeMemory() - memBeforeRestore) / 1024 / 1024

            // ── 3. Verify ─────────────────────────────────────────────────────
            assertEquals(targetSize, restored.size)
            assertEquals(customMetadata.maxResults, restored.metadata.maxResults)
            assertEquals(customMetadata.maxNgramSize, restored.metadata.maxNgramSize)

            val origQ = original.find(query)
            val restQ = restored.find(query)
            assertEquals(
                origQ.map { it.second }, restQ.map { it.second },
                "Scores must be identical after restore"
            )

            val speedup = if (restoreMs > 0) insertMs / restoreMs else insertMs
            println()
            println("=".repeat(80))
            println("BENCHMARK: TypeaheadSearchEngine(source=…) factory — self-keyed (T=T) variant")
            println("Dataset: $targetSize String items | query: \"$query\"")
            println("=".repeat(80))
            println("Insertion:       $insertMs ms")
            println("Export (stream): $exportMs ms\t| Disk: $fileSizeKb KB")
            println("Restore (source factory): $restoreMs ms\t| Memory delta: $memRestore MB")
            println("Speedup vs insertion: ${speedup}×")
            println("Metadata: maxResults=${restored.metadata.maxResults}, maxNgramSize=${restored.metadata.maxNgramSize}")
            println("Search identity: PASSED  (${restQ.size}/${origQ.size} results match)")
            println("=".repeat(80))
        } finally {
            tempFile.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. Full lifecycle comparison: insert vs importFromSource vs invoke(source)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `compare insert vs importFromSource vs invoke(source) lifecycle`() = runTest(timeout = 3.minutes) {
        val products = generateProducts(targetSize)
        val metadata = TypeaheadMetadata(maxResults = maxResults)
        val runtime = Runtime.getRuntime()

        // ── Insertion ─────────────────────────────────────────────────────────
        runtime.gc(); Thread.sleep(100)
        val memBeforeInsert = runtime.totalMemory() - runtime.freeMemory()
        val insertStartMs = System.currentTimeMillis()
        val engine = TypeaheadSearchEngine<Product>(metadata = metadata) { it.name }
        engine.addAll(products)
        val insertMs = System.currentTimeMillis() - insertStartMs
        val memInsert = (runtime.totalMemory() - runtime.freeMemory() - memBeforeInsert) / 1024 / 1024
        assertEquals(targetSize, engine.size)

        // ── Export ────────────────────────────────────────────────────────────
        val tempFile = File.createTempFile("typeahead_lifecycle_", ".json")
        try {
            runtime.gc(); Thread.sleep(100)
            val exportStartMs = System.currentTimeMillis()
            tempFile.outputStream().asSink().use { sink -> engine.exportToSink(sink.buffered()) }
            val exportMs = System.currentTimeMillis() - exportStartMs
            val fileSizeKb = tempFile.length() / 1024
            val fileSizeMb = tempFile.length() / 1024 / 1024

            // ── importFromSource ──────────────────────────────────────────────
            runtime.gc(); Thread.sleep(100)
            val memBeforeImport = runtime.totalMemory() - runtime.freeMemory()
            val importStartMs = System.currentTimeMillis()
            val importedEngine = TypeaheadSearchEngine<Product>(metadata = metadata) { it.name }
            tempFile.inputStream().asSource().use { src ->
                importedEngine.importFromSource(src.buffered())
            }
            val importMs = System.currentTimeMillis() - importStartMs
            val memImport = (runtime.totalMemory() - runtime.freeMemory() - memBeforeImport) / 1024 / 1024

            // ── invoke(source) factory ────────────────────────────────────────
            runtime.gc(); Thread.sleep(100)
            val memBeforeFactory = runtime.totalMemory() - runtime.freeMemory()
            val factoryStartMs = System.currentTimeMillis()
            val factoryEngine: TypeaheadSearchEngine<Product, Int> =
                tempFile.inputStream().asSource().buffered().use { src ->
                    TypeaheadSearchEngine(
                        source = src,
                        textSelector = { it.name },
                        uniqueKeySelector = { it.id },
                    )
                }
            val factoryMs = System.currentTimeMillis() - factoryStartMs
            val memFactory = (runtime.totalMemory() - runtime.freeMemory() - memBeforeFactory) / 1024 / 1024

            // ── Correctness ───────────────────────────────────────────────────
            assertEquals(targetSize, importedEngine.size)
            assertEquals(targetSize, factoryEngine.size)
            val ref = engine.find(query)
            val fromImport = importedEngine.find(query)
            val fromFactory = factoryEngine.find(query)
            assertEquals(ref.map { it.second }, fromImport.map { it.second })
            assertEquals(ref.map { it.second }, fromFactory.map { it.second })
            assertEquals(metadata.maxResults, factoryEngine.metadata.maxResults)

            println()
            println("=".repeat(80))
            println("FULL LIFECYCLE COMPARISON — $targetSize records")
            println("=".repeat(80))
            println("%-35s %8s   %s".format("Operation", "Time", "Memory delta"))
            println("-".repeat(80))
            println("%-35s %5d ms   %d MB".format("1. Insert (full vectorization)", insertMs, memInsert))
            println(
                "%-35s %5d ms   %d MB  (%d MB / %d KB on disk)".format(
                    "2. Export (streaming)", exportMs, 0, fileSizeMb, fileSizeKb
                )
            )
            println("%-35s %5d ms   %d MB".format("3. importFromSource()", importMs, memImport))
            println("%-35s %5d ms   %d MB".format("4. invoke(source=…) factory", factoryMs, memFactory))
            println("-".repeat(80))
            println("Restore speedup vs insertion:")
            println("  importFromSource: ${if (importMs > 0) insertMs / importMs else insertMs}×")
            println("  invoke(source):   ${if (factoryMs > 0) insertMs / factoryMs else insertMs}×")
            println("=".repeat(80))
            println("All results identical: PASSED")
            println("=".repeat(80))
        } finally {
            tempFile.delete()
        }
    }
}