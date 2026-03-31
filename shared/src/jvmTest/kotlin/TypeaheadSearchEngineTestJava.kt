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

import io.github.karloti.typeahead.TypeaheadRecord
import io.github.karloti.typeahead.TypeaheadSearchEngine
import io.github.karloti.typeahead.exportToSink
import io.github.karloti.typeahead.importFromSource
import kotlinx.coroutines.test.runTest
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

@Serializable
data class Product(
    val id: Int,
    val name: String,
    val category: String,
    val brand: String,
    val price: Double
)

class TypeaheadSearchEngineTestJava {
    /**
     * Performance and memory consumption test for TypeaheadSearchEngine import/export operations.
     * * This test validates the complete lifecycle of serializing and deserializing a search engine
     * using streaming file I/O. It measures:
     * - Memory consumption during insertion, streaming export, and streaming import operations
     * - Disk space used by the serialized JSON file
     * - Execution time for each major operation
     * - Data integrity through comparison of search results before and after serialization
     */
    @Test
    fun `test import and export with json file serialization`() = runTest(timeout = 1.minutes) {
        val targetSize = if (LOCAL) 10_000 else 1_000
        val maxResults = if (LOCAL) 40 else 5

        val query = "PQ82"

        // Generate a large synthetic dataset of products
        val productCategories = listOf("Electronics", "Clothing", "Food", "Books", "Toys", "Sports", "Home", "Beauty")
        val productBrands = listOf("BrandA", "BrandB", "BrandC", "BrandD", "BrandE", "BrandF", "BrandG", "BrandH")

        val products: Sequence<Product> = sequence {
            repeat(targetSize) { index ->
                yield(
                    Product(
                        id = index,
                        name = "Product_${countries[index % countries.size]}_Item_$index",
                        category = productCategories[index % productCategories.size],
                        brand = productBrands[index % productBrands.size],
                        price = (10.0 + (index % 1000) * 0.99)
                    )
                )
            }
        }

        val searchEngine =
            TypeaheadSearchEngine<Product>(
                metadata = TypeaheadRecord.TypeaheadMetadata(maxResults = maxResults)
            ) { product -> product.name }

        println("⏳ Starting insertion of $targetSize products...")
        val runtime = Runtime.getRuntime()
        runtime.gc() // Suggest garbage collection for more accurate measurement
        Thread.sleep(100) // Allow GC to complete

        val memoryBeforeInsertion = runtime.totalMemory() - runtime.freeMemory()
        val insertionStartTime = System.currentTimeMillis()

        searchEngine.addAll(products)

        val insertionEndTime = System.currentTimeMillis()
        val memoryAfterInsertion = runtime.totalMemory() - runtime.freeMemory()

        val insertionTime = insertionEndTime - insertionStartTime
        val insertionMemoryUsed = memoryAfterInsertion - memoryBeforeInsertion

        println("✅ Insertion completed in $insertionTime ms")
        println("📊 Memory used for insertion: ${insertionMemoryUsed / 1024 / 1024} MB")

        assertEquals(
            expected = targetSize,
            actual = searchEngine.size,
            message = "Engine should contain exactly $targetSize products after insertion."
        )

        // Capture search results before export for comparison
        val resultsBeforeExport = searchEngine.find(query)

        // ---------------------------------------------------------
        // STREAMING EXPORT
        // ---------------------------------------------------------
        println("⏳ Starting streaming export of search engine state to file...")
        val tempFile = File.createTempFile("typeahead_export_", ".json")

        runtime.gc()
        Thread.sleep(100)

        val memoryBeforeExport = runtime.totalMemory() - runtime.freeMemory()
        val exportStartTime = System.currentTimeMillis()

        // Създаваме Sink от файла и експортираме директно
        tempFile.outputStream().asSink().use { sink -> searchEngine.exportToSink(sink.buffered()) }

        val exportEndTime = System.currentTimeMillis()
        val memoryAfterExport = runtime.totalMemory() - runtime.freeMemory()

        val exportTime = exportEndTime - exportStartTime
        val exportMemoryUsed = memoryAfterExport - memoryBeforeExport
        val fileSize = tempFile.length()

        println("✅ Streaming export completed in $exportTime ms")
        println("✅ Written to file: ${tempFile.absolutePath}")
        println("💾 File size on disk: ${fileSize / 1024 / 1024} MB (${fileSize / 1024} KB)")
        println("📊 Memory used for streaming export: ${exportMemoryUsed / 1024 / 1024} MB")

        // ---------------------------------------------------------
        // STREAMING IMPORT
        // ---------------------------------------------------------
        val newSearchEngine = TypeaheadSearchEngine<Product>(
            metadata = TypeaheadRecord.TypeaheadMetadata(maxResults = maxResults)
        ) { product -> product.name }

        println("⏳ Starting streaming import from file into new search engine...")
        runtime.gc()
        Thread.sleep(100)

        val memoryBeforeImport = runtime.totalMemory() - runtime.freeMemory()
        val importStartTime = System.currentTimeMillis()

        // Създаваме Source от файла и импортираме директно
        tempFile.inputStream().asSource().use { source -> newSearchEngine.importFromSource(source.buffered()) }

        val importEndTime = System.currentTimeMillis()
        val memoryAfterImport = runtime.totalMemory() - runtime.freeMemory()

        val importTime = importEndTime - importStartTime
        val importMemoryUsed = memoryAfterImport - memoryBeforeImport

        println("✅ Streaming import completed in $importTime ms")
        println("📊 Memory used for streaming import: ${importMemoryUsed / 1024 / 1024} MB")

        // Clean up temp file
//        tempFile.delete()

        assertEquals(targetSize, newSearchEngine.size, "Imported engine should contain exactly $targetSize products.")

        // Verify the imported engine produces identical results
        val resultsAfterImport = newSearchEngine.find(query)

        assertEquals(
            resultsBeforeExport.size,
            resultsAfterImport.size,
            "Results count should match before and after import."
        )

        resultsBeforeExport.zip(resultsAfterImport).forEachIndexed { index, (before, after) ->
            assertEquals(
                before.second,
                after.second,
                "Score at index $index should match before and after import.\n before: ${before.second}\n  after: ${after.second}"
            )
            assertEquals(
                expected = before.first.id,
                actual = after.first.id,
                message = """
                    Product ID at index $index should match before and after import.
                    before: ${before.first} score: ${before.second}
                     after: ${after.first} score: ${after.second}
                     """.trimIndent()
            )
        }

        println("✅ Import verification completed successfully - all results are identical!")

        println("\n" + "=".repeat(80))
        println("PERFORMANCE SUMMARY (STREAMING)")
        println("=".repeat(80))
        println("Insertion:              $insertionTime ms | Memory: ${insertionMemoryUsed / 1024 / 1024} MB")
        println("Streaming Export (I/O): $exportTime ms | Memory: ${exportMemoryUsed / 1024 / 1024} MB | Disk: ${fileSize / 1024 / 1024} MB")
        println("Streaming Import (I/O): $importTime ms | Memory: ${importMemoryUsed / 1024 / 1024} MB")
        println("=".repeat(80))
        println("Total Time:             ${insertionTime + exportTime + importTime} ms")
        println("=".repeat(80))
    }
}