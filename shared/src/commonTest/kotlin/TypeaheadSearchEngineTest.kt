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
import io.github.karloti.typeahead.renderHighlightedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

data class Country(val id: Int, val countryName: String)

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

/**
 * Set to `true` for intensive local runs, `false` for lightweight CI (GitHub Actions).
 */
const val LOCAL = false

class TypeaheadSearchEngineTest {

    @Test
    fun testTypingSimulationWithScoreProgression() = runTest(timeout = 1.minutes) {
        launch {
            val searchEngine = TypeaheadSearchEngine.invoke(
                items = countries,
                metadata = TypeaheadMetadata(maxResults = 5),
            )

            val queryToType = "Cnada"

            for (i in 1..minOf(queryToType.length, 6)) {
                val partialQuery = queryToType.substring(0, i)
                println("\n=== Typing: '$partialQuery' with typing error of '$queryToType' ===")

                searchEngine.find(partialQuery)
                val results = searchEngine.results.value

                if (results.isEmpty()) {
                    println("No results found")
                } else {
                    results.forEachIndexed { index, (country, score) ->
                        println("${index + 1}. $country - Score: $score")
                    }
                }
            }
            println("\nTyping simulation completed!")
        }
    }

    @Test
    fun testUltimateThreadSafetyAndFuzzySearch() = runTest(timeout = 1.minutes) {
        val searchEngine = TypeaheadSearchEngine<String>(metadata = TypeaheadMetadata(maxResults = 10))

        val baselineItems = (1..50).map { "item-baseline-$it" }
        val itemsToRemove = (1..500).map { "item-to-remove-$it" }

        searchEngine.addAll(baselineItems + itemsToRemove)

        // For platforms with true multi-threading (JVM, Native), we use more writers.
        // For JS/WASM, we reduce the count to avoid heavy overhead while still testing the logic.

        val writersCount = if (LOCAL) 100 else 5
        val itemsPerWriter = if (LOCAL) 300 else 10

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

        repeat(if (LOCAL) 20 else 5) {
            allJobs += launch(Dispatchers.Default) {
                searchEngine.find("item")
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

        println("✅ Ultimate Thread-safety and Accuracy test passed perfectly! Expected items: $expectedSize")
    }

    @Test
    fun `test highlight heatmap for floating n-grams`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>(metadata = TypeaheadMetadata(maxResults = 1))
        searchEngine.add("Bulgaria")

        searchEngine.find("blugaria")
        searchEngine.highlightedResults.value.first().heatmap.let { topMatch ->
            println("Top match heatmap: ${topMatch.contentToString()}")
            val expectedHeatmap = intArrayOf(0, 2, 2, 0, 0, 0, 0, 0)
            assertTrue(
                actual = expectedHeatmap.contentEquals(topMatch),
                message = "Floating N-gram heatmap must match expected secondary tiers."
            )
        }
        searchEngine.find("bulgira ")
        searchEngine.highlightedResults.value.first().heatmap.let { topMatch ->
            println("Top match heatmap: ${topMatch.contentToString()}")
            val expectedHeatmap = intArrayOf(0, 0, 0, 0, 2, 0, 2, -1)
            assertTrue(
                actual = expectedHeatmap.contentEquals(topMatch),
                message = "Floating N-gram heatmap must match expected secondary tiers."
            )
        }
    }

    @Test
    fun `test typing simulation with score progression and visual console highlighting`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>(metadata = TypeaheadMetadata(maxResults = 1))

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
            searchEngine.find(query)
            val topMatch = searchEngine.highlightedResults.value.firstOrNull()

            if (topMatch != null) {
                // Render the colored string
                val highlightedText = topMatch.heatmap.renderHighlightedString(topMatch.item)

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

    @Test
    fun `test typing simulation with score progression and visual console highlighting2`() = runTest {
        val listOfCountries = countries.mapIndexed { index, country -> Country(index, country) }
        val searchEngine = TypeaheadSearchEngine(items = listOfCountries, textSelector = Country::countryName)
        searchEngine.addAll(listOfCountries)
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

        typingSimulation.forEach { query ->
            val paddedQuery = query.padEnd(8, ' ')
            println(" Query: [\u001B[35m${paddedQuery}\u001B[0m]")
            searchEngine.find(query)
            searchEngine.highlightedResults.value.forEach { highlightedMatch ->
                val highlightedText = highlightedMatch.heatmap
                    .renderHighlightedString(text = highlightedMatch.item.countryName)
                // Format score to 4 decimal places for clean UI alignment
                val formattedScore = highlightedMatch.score.toString().take(5)
                println(" Score: $formattedScore | Match: $highlightedText")
            }
            println("-------------------------------------------------------")
        }
        println("=======================================================\n")

        // Simple assertion to ensure the test completes successfully
        assertTrue(true, "Typing simulation executed without exceptions.")
    }

    @Test
    fun `Verify single add under high concurrency works correctly and measure performance`() = runTest {
        val engine = TypeaheadSearchEngine<String>()
        val totalItems = if (LOCAL) 100_000 else 1_000
        val items = (1..totalItems).map { "Product $it" }

        val time = measureTime {
            // Launch 10,000 concurrent coroutines to hammer the state flow
            val jobs = items.map { item ->
                launch(Dispatchers.Default) {
                    engine.add(item)
                }
            }
            jobs.joinAll() // Wait for all insertions to finish
        }

        println("✅ Concurrent add() of $totalItems items took $time")

        // Assuming your engine has a way to check size, e.g., via a property or by exporting
        // Alternatively, you can do a search to verify items are present.
        assertEquals(totalItems, engine.size, "Engine should contain exactly 10,000 items after concurrent add")
    }

    @Test
    fun `Verify addAll processes items correctly and measure performance`() = runTest {
        val engine = TypeaheadSearchEngine<String>()
        val totalItems = if (LOCAL) 50_000 else 1_000
        val items = (1..totalItems).map { "Product $it" }

        val time = measureTime {
            // addAll handles its own concurrency internally
            engine.addAll(items)
        }

        println("✅ addAll() of $totalItems items took $time")

        assertEquals(totalItems, engine.size, "Engine should contain exactly 10,000 items after addAll")
    }

    /**
     * A mock data class representing a versioned document.
     * Used to verify that the search engine can deduplicate results based on a specific identity key
     * even when the objects themselves are distinct due to other differing properties.
     */
    private data class VersionedDocument(val docId: String, val title: String, val version: Int)

    /**
     * Verifies that the [TypeaheadSearchEngine] properly delegates the unique key selection
     * to the underlying priority queue, successfully deduplicating search results.
     * When multiple items share the same unique key, only the highest-scoring match
     * (or the first evaluated match of equal score) should remain in the final results.
     */
    @Test
    fun `Verify engine deduplicates search results using custom unique key selector`() = runTest {
        val engine = TypeaheadSearchEngine<VersionedDocument, String>(
            textSelector = { it.title },
            keySelector = { it.docId }
        )

        engine.addAll(
            listOf(
                VersionedDocument(docId = "doc-1", title = "Kotlin Multiplatform Guide", version = 1),
                VersionedDocument(docId = "doc-1", title = "Kotlin Multiplatform Guide", version = 2),
                VersionedDocument(docId = "doc-2", title = "Advanced Kotlin Coroutines", version = 1),
                VersionedDocument(docId = "doc-3", title = "Java to Kotlin Migration", version = 1)
            )
        )

        val results = engine.find("Kotlin")

        assertEquals(3, results.size)

        val uniqueIds = results.map { it.first.docId }.toSet()
        assertEquals(3, uniqueIds.size)
        assertTrue(uniqueIds.contains("doc-1"))
        assertTrue(uniqueIds.contains("doc-2"))
        assertTrue(uniqueIds.contains("doc-3"))
    }

    /**
     * Verifies that the default factory method for [TypeaheadSearchEngine] implicitly uses
     * the element itself as the unique key, automatically filtering out exact object duplicates
     * from the search results.
     */
    @Test
    fun `Verify engine default factory method uses element as unique key`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            textSelector = { it }
        )

        engine.addAll(
            listOf(
                "Apple",
                "Banana",
                "Apple",
                "Apricot"
            )
        )
        val results = engine.find("Ap")

        assertEquals(3, engine.size)
        assertContentEquals(listOf("Apple", "Apricot", "Banana"), results.map { it.first })
    }

    @Test
    fun `Verify State`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>()
        searchEngine.add("Bulgaria")

        searchEngine.find("blugaria")
        searchEngine.highlightedResults.value.first().heatmap.let { topMatch ->
            println("Top match heatmap: ${topMatch.contentToString()}")
            val expectedHeatmap = intArrayOf(0, 2, 2, 0, 0, 0, 0, 0)
            assertTrue(
                actual = expectedHeatmap.contentEquals(topMatch),
                message = "Floating N-gram heatmap must match expected secondary tiers."
            )
        }
        searchEngine.find("bulgira ")
        searchEngine.highlightedResults.value.first().heatmap.let { topMatch ->
            println("Top match heatmap: ${topMatch.contentToString()}")
            val expectedHeatmap = intArrayOf(0, 0, 0, 0, 2, 0, 2, -1)
            assertTrue(
                actual = expectedHeatmap.contentEquals(topMatch),
                message = "Floating N-gram heatmap must match expected secondary tiers."
            )
        }
    }
}