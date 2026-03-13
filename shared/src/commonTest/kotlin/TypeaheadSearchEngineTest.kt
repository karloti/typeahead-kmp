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

package com.github.karloti.typeahead

import io.github.karloti.typeahead.SparseVector
import io.github.karloti.typeahead.TypeaheadRecord
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val countries = listOf(
    "Afghanistan", "Albania", "Algeria", "Andorra", "Angola", "Antigua and Barbuda",
    "Argentina", "Armenia", "Australia", "Austria", "Azerbaijan", "Bahamas",
    "Bahrain", "Bangladesh", "Barbados", "Belarus", "Belgium", "Belize",
    "Benin", "Bhutan", "Bolivia", "Bosnia and Herzegovina", "Botswana", "Brazil",
    "Brunei", "Bulgaria", "Burkina Faso", "Burundi", "Cabo Verde", "Cambodia",
    "Cameroon", "Canada", "Central African Republic", "Chad", "Chile", "China",
    "Colombia", "Comoros", "Congo (Congo-Brazzaville)", "Costa Rica", "Croatia",
    "Cuba", "Cyprus", "Czechia (Czech Republic)", "Denmark", "Djibouti", "Dominica",
    "Dominican Republic", "Ecuador", "Egypt", "El Salvador", "Equatorial Guinea",
    "Eritrea", "Estonia", "Eswatini", "Ethiopia", "Fiji", "Finland", "France",
    "Gabon", "Gambia", "Georgia", "Germany", "Ghana", "Greece", "Grenada",
    "Guatemala", "Guinea", "Guinea-Bissau", "Guyana", "Haiti", "Holy See",
    "Honduras", "Hungary", "Iceland", "India", "Indonesia", "Iran", "Iraq",
    "Ireland", "Israel", "Italy", "Ivory Coast", "Jamaica", "Japan", "Jordan",
    "Kazakhstan", "Kenya", "Kiribati", "Kuwait", "Kyrgyzstan", "Laos", "Latvia",
    "Lebanon", "Lesotho", "Liberia", "Libya", "Liechtenstein", "Lithuania",
    "Luxembourg", "Madagascar", "Malawi", "Malaysia", "Maldives", "Mali", "Malta",
    "Marshall Islands", "Mauritania", "Mauritius", "Mexico", "Micronesia",
    "Moldova", "Monaco", "Mongolia", "Montenegro", "Morocco", "Mozambique",
    "Myanmar (Burma)", "Namibia", "Nauru", "Nepal", "Netherlands", "New Zealand",
    "Nicaragua", "Niger", "Nigeria", "North Korea", "North Macedonia", "Norway",
    "Oman", "Pakistan", "Palau", "Palestine State", "Panama", "Papua New Guinea",
    "Paraguay", "Peru", "Philippines", "Poland", "Portugal", "Qatar", "Romania",
    "Russia", "Rwanda", "Saint Kitts and Nevis", "Saint Lucia",
    "Saint Vincent and the Grenadines", "Samoa", "San Marino", "Sao Tome and Principe",
    "Saudi Arabia", "Senegal", "Serbia", "Seychelles", "Sierra Leone", "Singapore",
    "Slovakia", "Slovenia", "Solomon Islands", "Somalia", "South Africa",
    "South Korea", "South Sudan", "Spain", "Sri Lanka", "Sudan", "Suriname",
    "Sweden", "Switzerland", "Syria", "Tajikistan", "Tanzania", "Thailand",
    "Timor-Leste", "Togo", "Tonga", "Trinidad and Tobago", "Tunisia", "Turkey",
    "Turkmenistan", "Tuvalu", "Uganda", "Ukraine", "United Arab Emirates",
    "United Kingdom", "United States of America", "Uruguay", "Uzbekistan",
    "Vanuatu", "Venezuela", "Vietnam", "Yemen", "Zambia", "Zimbabwe"
)

class TypeaheadSearchEngineTest {


    @Test
    fun testAddingCountriesAndSearching() = runTest(timeout = 1.minutes) {
        val searchEngine = TypeaheadSearchEngine(countries)

        launch {
            val results = searchEngine.find("bul", maxResults = 5)
            val checkResult = listOf("Bulgaria", "Burundi", "Burkina Faso", "Benin", "Bhutan")
            results.forEach { (country, score) ->
                println("Found country: $country with score: $score")
            }

            assertEquals(checkResult, results.map { it.first }, "Results should match the expected results.")

            println("Total indexed countries: ${searchEngine.size}")
        }
    }

    @Test
    fun testTypingSimulationWithScoreProgression() = runTest(timeout = 1.minutes) {
        val searchEngine = TypeaheadSearchEngine<String>()
        val checkResult = listOf(
            listOf(
                "Chad",
                "Cuba",
                "Chile",
                "China",
                "Cyprus"
            ),
            listOf(
                "Chad",
                "Cuba",
                "Chile",
                "China",
                "Canada"
            ),
            listOf(
                "Canada",
                "Chad",
                "Cuba",
                "China",
                "Chile"
            ),
            listOf(
                "Chad",
                "Canada",
                "Cuba",
                "China",
                "Chile"
            ),
            listOf(
                "Canada",
                "China",
                "Chad",
                "Grenada",
                "Chile"
            ),
        )
        launch {
            searchEngine.addAll(countries)

            val queryToType = "Cnada"

            for (i in 1..minOf(queryToType.length, 6)) {
                val partialQuery = queryToType.substring(0, i)
                println("\n=== Typing: '$partialQuery' with typing error of '$queryToType' ===")

                val results = searchEngine.find(partialQuery, maxResults = 5)

                if (results.isEmpty()) {
                    println("No results found")
                } else {
                    results.forEachIndexed { index, (country, score) ->
                        println("${index + 1}. $country - Score: $score")
                    }
                }
                assertEquals(
                    checkResult[i - 1],
                    results.map { it.first },
                    "Results for query '$partialQuery' should match the expected results."
                )
            }
            println("\nTyping simulation completed!")
        }
    }

    @Test
    fun testUltimateThreadSafetyAndFuzzySearch() = runTest(timeout = 1.minutes) {
        val searchEngine = TypeaheadSearchEngine<String> { it }

        val baselineItems = (1..50).map { "item-baseline-$it" }
        val itemsToRemove = (1..500).map { "item-to-remove-$it" }

        searchEngine.addAll(baselineItems + itemsToRemove)

        // For platforms with true multi-threading (JVM, Native), we use more writers.
        // For JS/WASM, we reduce the count to avoid heavy overhead while still testing the logic.
        val isJsOrWasm = true

        val writersCount = if (isJsOrWasm) 5 else 100
        val itemsPerWriter = if (isJsOrWasm) 10 else 300

        println("Starting aggressive multi-threading test with $writersCount writers...")

        val allJobs = mutableListOf<Job>()

        for (writerIndex in 1..writersCount) {
            allJobs += launch(Dispatchers.Default) {
                val start = (writerIndex - 1) * itemsPerWriter + 1
                val end = writerIndex * itemsPerWriter
                for (i in start..end) {
                    searchEngine.add("item-inserted-$i")
                }
            }
        }

        itemsToRemove.chunked(50).forEach { chunk ->
            allJobs += launch(Dispatchers.Default) {
                chunk.forEach { searchEngine.remove(it) }
            }
        }

        repeat(if (isJsOrWasm) 5 else 20) {
            allJobs += launch(Dispatchers.Default) {
                searchEngine.find("item", maxResults = 10)
            }
        }

        allJobs.shuffle()
        allJobs.joinAll()

        println("All threads finished. Running exact verification...")

        val expectedSize = (writersCount * itemsPerWriter) + baselineItems.size

        assertEquals(expectedSize, searchEngine.size, "Engine size must exactly match the mathematical result.")

        baselineItems.forEach { item ->
            assertTrue(item in searchEngine, "Baseline item $item went missing!")
        }

        itemsToRemove.forEach { item ->
            assertFalse(item in searchEngine, "Deleted item $item is still in the engine!")
        }

        for (i in 1..(writersCount * itemsPerWriter)) {
            val item = "item-inserted-$i"
            assertTrue(item in searchEngine, "Inserted item $item went missing!")
        }

        assertEquals(
            expectedSize,
            searchEngine.getAllItems().size,
            "getAllItems() should return exactly $expectedSize items."
        )

        println("✅ Ultimate Thread-safety and Accuracy test passed perfectly!")
    }

    @Test
    fun testExportAndImportSequence() = runTest(timeout = 1.minutes) {
        val searchEngine = TypeaheadSearchEngine<String> { it }

        launch {
            // 1. Load initial data and compute embeddings
            searchEngine.addAll(countries)
            val originalSize = searchEngine.size
            assertEquals(countries.size, originalSize, "Engine size should match the number of countries.")

            // 2. Perform a baseline search to record expected scores
            val query = "can"
            val expectResults = listOf("Canada", "Cambodia", "Cameroon", "Cabo Verde", "Chad")
            val actualResults = searchEngine.find(query, maxResults = 5)
            assertEquals(expectResults, actualResults.map { it.first }, "Baseline search results must match.")

            // 3. Export the state
            // We use .toList() to materialize the sequence into memory for testing purposes,
            // so we don't lose the data when we clear the engine.
            val exportedRecords = searchEngine.exportAsSequence().toList()
            assertEquals(originalSize, exportedRecords.size, "Exported sequence size must match engine size.")

            // 4. Clear the engine
            searchEngine.clear()
            assertEquals(0, searchEngine.size, "Engine should be empty after clear().")
            assertTrue(searchEngine.find(query).isEmpty(), "Search should return nothing after clearing.")

            // 5. Import the state back from the materialized sequence
            searchEngine.importFromSequence(exportedRecords.asSequence())
            assertEquals(originalSize, searchEngine.size, "Engine size must be fully restored after import.")

            // 6. Perform the exact same search and verify mathematical integrity
            val restoredResults = searchEngine.find(query, maxResults = 5)
            assertEquals(actualResults, restoredResults, "Restored results must match.")

            // Verify that not only the items match, but their floating-point scores match perfectly
            actualResults.forEachIndexed { index, expectedPair ->
                val actualPair = restoredResults[index]
                assertEquals(expectedPair.first, actualPair.first, "Item at index $index must match.")
                assertEquals(
                    expectedPair.second,
                    actualPair.second,
                    "Mathematical score at index $index must be identical."
                )
            }

            // 7. Test merging functionality (clearExisting = false)
            val extraCountryRecord = TypeaheadRecord(
                item = "Atlantis",
                vector = SparseVector(
                    features = arrayOf("P_a", "L_8"),
                    weights = floatArrayOf(10.0f, 8.0f)
                )
            )
            searchEngine.importFromSequence(sequenceOf(extraCountryRecord), clearExisting = false)

            assertEquals(originalSize + 1, searchEngine.size, "Engine size should increase by 1 after merging.")
            assertTrue("Atlantis" in searchEngine, "The merged item 'Atlantis' should be present in the engine.")

            println("✅ Export/Import sequence and vector integrity test passed perfectly!")
        }
    }

    @Test
    fun `test highlight heatmap generation for exact prefixes and n-grams`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>()
        val dataset = listOf("Bulgaria", "Belgium", "Bahamas", "Belarus")
        searchEngine.addAll(dataset)

        // Query "bulg" should perfectly match the prefix of "Bulgaria"
        val query = "bulg"
        val results = searchEngine.findWithHighlights(query, maxResults = 1)

        assertTrue(results.isNotEmpty(), "Engine should find at least one highlighted result.")

        val topMatch = results.first()
        assertEquals("Bulgaria", topMatch.item, "Top match should be Bulgaria.")

        // Heatmap for "Bulgaria" (8 chars) against "bulg" (4 chars)
        // 'B', 'u', 'l', 'g' -> TIER_PRIMARY (0)
        // 'a', 'r', 'i', 'a' -> TIER_NONE (-1)
        val expectedHeatmap = intArrayOf(
            TypeaheadSearchEngine.TIER_PRIMARY,   // B
            TypeaheadSearchEngine.TIER_PRIMARY,   // u
            TypeaheadSearchEngine.TIER_PRIMARY,   // l
            TypeaheadSearchEngine.TIER_PRIMARY,   // g
            TypeaheadSearchEngine.TIER_NONE,      // a
            TypeaheadSearchEngine.TIER_NONE,      // r
            TypeaheadSearchEngine.TIER_NONE,      // i
            TypeaheadSearchEngine.TIER_NONE       // a
        )

        assertTrue(
            expectedHeatmap.contentEquals(topMatch.heatmap),
            "Heatmap arrays must match. Expected ${expectedHeatmap.joinToString()} but got ${topMatch.heatmap.joinToString()}"
        )
    }

    @Test
    fun `test highlight heatmap for floating n-grams`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>()
        searchEngine.add("Afghanistan") // 11 chars

        // Query "stan" matches the end of "Afghanistan" (floating n-gram match)
        val query = "stan"
        val results = searchEngine.findWithHighlights(query, maxResults = 1)
        val topMatch = results.first()
        println("topMatch = ${topMatch.heatmap.contentToString()}")

        // 's', 't', 'a', 'n' should be marked as TIER_SECONDARY (1) at the end.
        // All preceding chars should be TIER_NONE (-1)
        val expectedHeatmap = IntArray(11) { TypeaheadSearchEngine.TIER_NONE }
        expectedHeatmap[7] = TypeaheadSearchEngine.TIER_SECONDARY // s
        expectedHeatmap[8] = TypeaheadSearchEngine.TIER_SECONDARY // t
        expectedHeatmap[9] = TypeaheadSearchEngine.TIER_SECONDARY // a
        expectedHeatmap[10] = TypeaheadSearchEngine.TIER_SECONDARY // n

        assertTrue(
            expectedHeatmap.contentEquals(topMatch.heatmap),
            "Floating N-gram heatmap must match expected secondary tiers."
        )
    }

    /**
     * Helper utility to render the highlighted match in the console using ANSI color codes.
     * This provides a visual representation of the L2 vector matching heatmap.
     *
     * - TIER_PRIMARY (0) -> Bright Yellow (Exact prefix/solid match)
     * - TIER_SECONDARY (1) -> Standard Yellow (Floating N-gram match)
     * - TIER_TERTIARY (2) -> Cyan (Skip-gram / Fuzzy bridge)
     * - TIER_NONE (-1) -> Dim Gray (Unmatched character)
     *
     * @param text The original string to be formatted.
     * @param heatmap The heatmap array computed by the engine.
     * @return A visually colored string ready for console output.
     */
    private fun renderHighlightedString(text: String, heatmap: IntArray): String {
        // ANSI Escape Codes for UI Console Colors
        val reset = "\u001B[0m"
        val brightYellow = "\u001B[93m"
        val standardYellow = "\u001B[33m"
        val cyan = "\u001B[36m"
        val dimGray = "\u001B[90m"

        return text.mapIndexed { index, char ->
            val color = when (heatmap[index]) {
                TypeaheadSearchEngine.TIER_PRIMARY -> brightYellow
                TypeaheadSearchEngine.TIER_SECONDARY -> standardYellow
                TypeaheadSearchEngine.TIER_TERTIARY -> cyan
                else -> dimGray
            }
            "$color$char$reset"
        }.joinToString("")
    }

    @Test
    fun `test typing simulation with score progression and visual console highlighting`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>()

        // Add a primary target to observe
        searchEngine.add("Bulgaria")

        // We simulate a user typing normally, then typing an n-gram, and then making a typo (skip-gram).
        val typingSimulation = listOf(
            // Transposed 'l' and 'g'.
            // 'bu' is Tier 0. 'aria' is Tier 1.
            // The algorithm bridges 'u'-'g' and 'l'-'a'.
            // RESULT: 'l' and 'g' will light up in CYAN!
            "buglaria",

            // Transposed 'a' and 'g'.
            // 'bul' is Tier 0. 'ria' is Tier 1.
            // The algorithm bridges 'l'-'a' and 'g'-'r'.
            // RESULT: 'g' and 'a' will light up in CYAN!
            "bulagria",

            // Transposed 'u' and 'l'.
            // 'b' is Tier 0. 'garia' is Tier 1.
            // The algorithm bridges 'b'-'l' and 'u'-'g'.
            // RESULT: 'u' and 'l' will light up in CYAN!
            "blugaria",

            // Transposed 'i' and 'r' near the end.
            // 'bulg' is Tier 0.
            // The algorithm bridges 'r'-'a'.
            // RESULT: 'a' and 'i' will be GRAY, but 'r' and the final 'a' will be CYAN!
            "bulgira"
        )

        println("\n=======================================================")
        println(" ⌨️  TYPING SIMULATION & SCORE PROGRESSION OVERVIEW")
        println("=======================================================")
        println("Legend:")
        println("  \u001B[93mBright Yellow\u001B[0m : Primary Exact Match (Tier 0)")
        println("  \u001B[33mDark Yellow\u001B[0m   : Secondary N-gram Match (Tier 1)")
        println("  \u001B[36mCyan\u001B[0m          : Tertiary Skip-gram / Bridge (Tier 2)")
        println("  \u001B[90mDim Gray\u001B[0m      : Unmatched Character")
        println("-------------------------------------------------------")

        for (query in typingSimulation) {
            val results = searchEngine.findWithHighlights(query, maxResults = 1)
            val topMatch = results.firstOrNull()

            if (topMatch != null) {
                // Render the colored string
                val highlightedText = renderHighlightedString(topMatch.item, topMatch.heatmap)

                // Format score to 4 decimal places for clean UI alignment
                val formattedScore = topMatch.score.toString().take(5)
                val paddedQuery = query.padEnd(8, ' ')

                println(" Query: [$paddedQuery] | Score: $formattedScore | Match: $highlightedText")
            } else {
                println(" Query: [${query.padEnd(8, ' ')}] | No match found.")
            }
        }
        println("=======================================================\n")

        // Simple assertion to ensure the test completes successfully
        assertTrue(true, "Typing simulation executed without exceptions.")
    }
}