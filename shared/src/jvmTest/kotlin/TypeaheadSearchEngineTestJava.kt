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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

class TypeaheadSearchEngineTestJava {
    @Test
    fun `test memory consumption and performance for 10000 insertions`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>()
        val targetSize = 10_000

        // Generate a large synthetic dataset
        val largeDataset = List(targetSize) { index ->
            "Synthetic_Word_Data_${index}_${countries[index % countries.size]}"
        }

        // Suggest the JVM to run the garbage collector to stabilize memory footprint
        System.gc()
        Thread.sleep(200) // Brief pause to let GC complete

        val runtime = Runtime.getRuntime()
        val memoryBeforeBatch = runtime.totalMemory() - runtime.freeMemory()

        println("⏳ Starting insertion of $targetSize items...")
        val startTime = System.currentTimeMillis()

        // Perform the mass insertion utilizing the flatMapMerge bounded concurrency
        searchEngine.addAll(largeDataset)

        val endTime = System.currentTimeMillis()

        // Measure memory footprint after the vectors have been fully materialized
        System.gc()
        Thread.sleep(200)
        val memoryAfterBatch = runtime.totalMemory() - runtime.freeMemory()

        val timeTakenMs = endTime - startTime
        val memoryUsedBytes = memoryAfterBatch - memoryBeforeBatch
        val memoryUsedMb = memoryUsedBytes / (1024.0 * 1024.0)

        // Assertions to guarantee the engine processed everything
        assertEquals(targetSize, searchEngine.size, "Engine should contain exactly $targetSize items.")

        // Ensure the engine is actually searchable after a massive load
        val testResults = searchEngine.find("Synthetic_Word_Data_9999", maxResults = 1)
        assertTrue(testResults.isNotEmpty(), "Engine must be fully operational and return matches.")

        println("✅ Performance Report for $targetSize insertions:")
        println("   ⏱️ Time taken: $timeTakenMs ms")
        println("   💾 Memory consumed: ${String.format("%.2f", memoryUsedMb)} MB")

        // Optional: Fail the test if memory usage is astronomically high (e.g., > 150MB for 10k words)
        // This ensures future code changes don't introduce memory leaks on Edge devices.
        assertTrue(memoryUsedMb < 150.0, "Memory footprint ($memoryUsedMb MB) exceeded the 150MB strict limit for Edge devices.")
    }
}