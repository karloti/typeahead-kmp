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

import com.github.karloti.typeahead.countries
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
     * 
     * This test validates the complete lifecycle of serializing and deserializing a search engine
     * containing 10,000 product records. It measures:
     * - Memory consumption during insertion, export, file I/O, and import operations
     * - Disk space used by the serialized JSON file
     * - Execution time for each major operation
     * - Data integrity through comparison of search results before and after serialization
     */
    @Test
    fun `test import and export with json file serialization`() = runTest {
        val targetSize = 10_000

        // Generate a large synthetic dataset of products
        val productCategories = listOf("Electronics", "Clothing", "Food", "Books", "Toys", "Sports", "Home", "Beauty")
        val productBrands = listOf("BrandA", "BrandB", "BrandC", "BrandD", "BrandE", "BrandF", "BrandG", "BrandH")

        val products = List(targetSize) { index ->
            Product(
                id = index,
                name = "Product_${countries[index % countries.size]}_Item_$index",
                category = productCategories[index % productCategories.size],
                brand = productBrands[index % productBrands.size],
                price = (10.0 + (index % 1000) * 0.99)
            )
        }

        val searchEngine = TypeaheadSearchEngine<Product> { product ->
            product.name + " " + product.category + " " + product.brand
        }

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
        val resultsBeforeExport = searchEngine.find("Product", maxResults = 100)

        // Export the search engine
        println("⏳ Starting export of search engine state...")
        runtime.gc()
        Thread.sleep(100)

        val memoryBeforeExport = runtime.totalMemory() - runtime.freeMemory()
        val exportStartTime = System.currentTimeMillis()

        val exportedRecords = searchEngine.exportAsSequence().toList()

        val exportEndTime = System.currentTimeMillis()
        val memoryAfterExport = runtime.totalMemory() - runtime.freeMemory()

        val exportTime = exportEndTime - exportStartTime
        val exportMemoryUsed = memoryAfterExport - memoryBeforeExport

        println("✅ Export completed in $exportTime ms")
        println("📊 Memory used for export: ${exportMemoryUsed / 1024 / 1024} MB")

        assertEquals(targetSize, exportedRecords.size, "Exported records should contain exactly $targetSize items.")

        // Serialize to JSON and write to file
        println("⏳ Serializing records to JSON and writing to file...")
        val json = Json { prettyPrint = true }

        val serializationStartTime = System.currentTimeMillis()
        val jsonString = json.encodeToString(exportedRecords)
        val serializationEndTime = System.currentTimeMillis()

        val tempFile = File.createTempFile("typeahead_export_", ".json")

        val writeStartTime = System.currentTimeMillis()
        tempFile.writeText(jsonString)
        val writeEndTime = System.currentTimeMillis()

        val fileSize = tempFile.length()

        println("✅ Written to file: ${tempFile.absolutePath}")
        println("✅ Serialization completed in ${serializationEndTime - serializationStartTime} ms")
        println("✅ File write completed in ${writeEndTime - writeStartTime} ms")
        println("💾 File size on disk: ${fileSize / 1024 / 1024} MB (${fileSize / 1024} KB)")

        // Read from file and deserialize
        println("⏳ Reading from file and deserializing JSON...")
        runtime.gc()
        Thread.sleep(100)

        val memoryBeforeRead = runtime.totalMemory() - runtime.freeMemory()
        val readStartTime = System.currentTimeMillis()

        val jsonFromFile = tempFile.readText()

        val readEndTime = System.currentTimeMillis()

        val deserializationStartTime = System.currentTimeMillis()
        val deserializedRecords =
            json.decodeFromString<List<io.github.karloti.typeahead.TypeaheadRecord<Product>>>(jsonFromFile)
        val deserializationEndTime = System.currentTimeMillis()

        val memoryAfterRead = runtime.totalMemory() - runtime.freeMemory()

        val readTime = readEndTime - readStartTime
        val deserializationTime = deserializationEndTime - deserializationStartTime
        val readMemoryUsed = memoryAfterRead - memoryBeforeRead

        println("✅ File read completed in $readTime ms")
        println("✅ Deserialization completed in $deserializationTime ms")
        println("📊 Memory used for read and deserialization: ${readMemoryUsed / 1024 / 1024} MB")

        // Clean up temp file
        tempFile.delete()

        // Import into a new search engine
        val newSearchEngine = TypeaheadSearchEngine<Product> { product ->
            product.name + " " + product.category + " " + product.brand
        }

        println("⏳ Starting import of $targetSize records into new search engine...")
        runtime.gc()
        Thread.sleep(100)

        val memoryBeforeImport = runtime.totalMemory() - runtime.freeMemory()
        val importStartTime = System.currentTimeMillis()

        newSearchEngine.importFromSequence(deserializedRecords.asSequence(),)

        val importEndTime = System.currentTimeMillis()
        val memoryAfterImport = runtime.totalMemory() - runtime.freeMemory()

        val importTime = importEndTime - importStartTime
        val importMemoryUsed = memoryAfterImport - memoryBeforeImport

        println("✅ Import completed in $importTime ms")
        println("📊 Memory used for import: ${importMemoryUsed / 1024 / 1024} MB")

        assertEquals(targetSize, newSearchEngine.size, "Imported engine should contain exactly $targetSize products.")

        // Verify the imported engine produces identical results
        val resultsAfterImport = newSearchEngine.find("Product", maxResults = 100)

        assertEquals(
            resultsBeforeExport.size,
            resultsAfterImport.size,
            "Results count should match before and after import."
        )

        resultsBeforeExport.zip(resultsAfterImport).forEachIndexed { index, (before, after) ->
            assertEquals(
                before.first.id,
                after.first.id,
                "Product ID at index $index should match before and after import."
            )
            assertEquals(
                before.second,
                after.second,
                "Score at index $index should match before and after import."
            )
        }

        println("✅ Import verification completed successfully - all results are identical!")

        println("\n" + "=".repeat(80))
        println("PERFORMANCE SUMMARY")
        println("=".repeat(80))
        println("Insertion:        $insertionTime ms | Memory: ${insertionMemoryUsed / 1024 / 1024} MB")
        println("Export:           $exportTime ms | Memory: ${exportMemoryUsed / 1024 / 1024} MB")
        println("Serialization:    ${serializationEndTime - serializationStartTime} ms")
        println("File Write:       ${writeEndTime - writeStartTime} ms | Disk: ${fileSize / 1024 / 1024} MB")
        println("File Read:        $readTime ms")
        println("Deserialization:  $deserializationTime ms | Memory: ${readMemoryUsed / 1024 / 1024} MB")
        println("Import:           $importTime ms | Memory: ${importMemoryUsed / 1024 / 1024} MB")
        println("=".repeat(80))
        println("Total Time:       ${insertionTime + exportTime + (serializationEndTime - serializationStartTime) + (writeEndTime - writeStartTime) + readTime + deserializationTime + importTime} ms")
        println("=".repeat(80))
    }
}