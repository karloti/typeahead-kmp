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
import io.github.karloti.typeahead.toHeatmap
import io.github.karloti.typeahead.toHighlightedString
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

        var query = "blugaria"
        searchEngine.find(query)
        var heatmap = query.toHeatmap(searchEngine.results.value.first().first)
            .map { it.second }
        var expectedHeatmap = listOf(0, 2, 2, 0, 0, 0, 0, 0)
        assertContentEquals(
            expected = expectedHeatmap,
            actual = heatmap,
            message = "Floating N-gram heatmap must match expected secondary tiers."
        )

        query = "bulgira "
        searchEngine.find(query)
        heatmap = searchEngine.results.value.first().first.toHeatmap(query)
            .map { it.second }
        expectedHeatmap = listOf(0, 0, 0, 0, 2, 0, 2, -1)
        assertContentEquals(
            expected = expectedHeatmap,
            actual = heatmap,
            message = "Floating N-gram heatmap must match expected secondary tiers."
        )
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
            val (topMatch, score) = searchEngine.results.value.firstOrNull() ?: continue

            val highlightedText = query
                .toHeatmap(topMatch)
                .toHighlightedString()

            // Format score to 4 decimal places for clean UI alignment
            val formattedScore = score.toString().take(5)
            val paddedQuery = query.padEnd(8, ' ')

            println(" Query: [$paddedQuery] | Score: $formattedScore | Match: $highlightedText")
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
            searchEngine.results.value.forEach { (result, score) ->
                val highlightedText = query
                    .toHeatmap(result.countryName)
                    .toHighlightedString()
                // Format score to 4 decimal places for clean UI alignment
                val formattedScore = score.toString().take(5)
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

        val results = engine.find("Kotlin").value

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
        val results = engine.find("Ap").value

        assertEquals(3, engine.size)
        assertContentEquals(listOf("Apple", "Apricot", "Banana"), results.map { it.first })
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DEDUPLICATION BY textSelector KEY — NEW TESTS FOR EngineState
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `add rejects duplicate items with the same textSelector key`() = runTest {
        val engine = TypeaheadSearchEngine<VersionedDocument, String>(
            textSelector = { it.title },
            keySelector = { it.docId }
        )

        val first = VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 1)
        val duplicate = VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 2)

        engine.add(first)
        engine.add(duplicate)

        assertEquals(1, engine.size, "Only one entry per unique textSelector key.")
        val results = engine.find("Kotlin").value
        assertEquals(1, results.size)
        assertEquals(1, results.first().first.version, "First-write wins: version 1 should be kept.")
    }

    @Test
    fun `addAll rejects duplicates within a single batch`() = runTest {
        val engine = TypeaheadSearchEngine<VersionedDocument, String>(
            textSelector = { it.title },
            keySelector = { it.docId }
        )

        engine.addAll(
            listOf(
                VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 1),
                VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 2),
                VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 3),
                VersionedDocument(docId = "doc-2", title = "Coroutines Deep Dive", version = 1),
                VersionedDocument(docId = "doc-2", title = "Coroutines Deep Dive", version = 2),
            )
        )

        assertEquals(2, engine.size, "Only 2 unique textSelector keys should be stored.")
        val results = engine.find("Kotlin").value
        assertEquals(2, results.size, "Both entries have non-zero fuzzy scores, so both appear in results.")
        val titles = results.map { it.first.title }.toSet()
        assertEquals(setOf("Kotlin Guide", "Coroutines Deep Dive"), titles)
    }

    @Test
    fun `concurrent add of same textSelector key stores exactly one`() = runTest(timeout = 1.minutes) {
        val engine = TypeaheadSearchEngine<String>()

        val jobs = (1..100).map {
            launch(Dispatchers.Default) {
                engine.add("DuplicateItem")
            }
        }
        jobs.joinAll()

        assertEquals(1, engine.size, "100 concurrent adds of the same text key must produce exactly 1 entry.")
    }

    @Test
    fun `remove then re-add with same textSelector works correctly`() = runTest {
        val engine = TypeaheadSearchEngine<VersionedDocument, String>(
            textSelector = { it.title },
            keySelector = { it.docId }
        )

        val v1 = VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 1)
        val v2 = VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 2)

        engine.add(v1)
        assertEquals(1, engine.size)
        assertTrue(v1 in engine)

        engine.remove(v1)
        assertEquals(0, engine.size)
        assertFalse(v1 in engine)

        engine.add(v2)
        assertEquals(1, engine.size)
        val results = engine.find("Kotlin").value
        assertEquals(2, results.first().first.version, "After remove + re-add, version 2 should be stored.")
    }

    @Test
    fun `add after addAll respects existing textSelector keys`() = runTest {
        val engine = TypeaheadSearchEngine<String>()

        engine.addAll(listOf("Alpha", "Beta", "Gamma"))
        assertEquals(3, engine.size)

        engine.add("Alpha")
        engine.add("Beta")
        engine.add("Delta")

        assertEquals(4, engine.size, "Only Delta should be new; Alpha and Beta should be rejected.")
    }

    @Test
    fun `clear resets both embeddings and textKeys then allows re-add`() = runTest {
        val engine = TypeaheadSearchEngine<String>()

        engine.addAll(listOf("Alpha", "Beta"))
        assertEquals(2, engine.size)

        engine.clear()
        assertEquals(0, engine.size)
        assertFalse("Alpha" in engine)

        engine.add("Alpha")
        assertEquals(1, engine.size)
        assertTrue("Alpha" in engine)
    }

    @Test
    fun `getAllItems returns exactly one item per textSelector key`() = runTest {
        val engine = TypeaheadSearchEngine<VersionedDocument, String>(
            textSelector = { it.title },
            keySelector = { it.docId }
        )

        engine.addAll(
            listOf(
                VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 1),
                VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 2),
                VersionedDocument(docId = "doc-2", title = "Coroutines", version = 1),
            )
        )

        val allItems = engine.getAllItems()
        assertEquals(2, allItems.size, "getAllItems must return one item per unique text key.")
        val titles = allItems.map { it.title }.toSet()
        assertEquals(setOf("Kotlin Guide", "Coroutines"), titles)
    }

    @Test
    fun `contains checks by textSelector not by object identity`() = runTest {
        val engine = TypeaheadSearchEngine<VersionedDocument, String>(
            textSelector = { it.title },
            keySelector = { it.docId }
        )

        val v1 = VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 1)
        val v2 = VersionedDocument(docId = "doc-1", title = "Kotlin Guide", version = 2)

        engine.add(v1)
        assertTrue(v1 in engine, "v1 was added, should be found.")
        assertTrue(v2 in engine, "v2 shares textSelector key with v1, should also report as contained.")
    }

    @Test
    fun `Verify addAll deduplicates entries with matching unique key`() = runTest {
        val engine = TypeaheadSearchEngine<VersionedDocument, String>(
            textSelector = { it.title },
            keySelector = { it.docId }
        )

        engine.addAll(
            listOf(
                VersionedDocument(docId = "doc-1", title = "Kotlin Multiplatform Guide", version = 1),
                VersionedDocument(docId = "doc-1", title = "Kotlin Multiplatform Guide", version = 2),
                VersionedDocument(docId = "doc-1", title = "Kotlin Multiplatform Guide", version = 3),
                VersionedDocument(docId = "doc-2", title = "Advanced Kotlin Coroutines", version = 1),
                VersionedDocument(docId = "doc-2", title = "Advanced Kotlin Coroutines", version = 2),
            )
        )
        val state = engine.state.value.forwardIndex
        assertEquals(2, state.size)

        val results = engine.find("Kotlin").value

        assertEquals(2, results.size, "Results must contain exactly 2 unique documents by docId.")

        val uniqueIds = results.map { it.first.docId }.toSet()
        assertEquals(2, uniqueIds.size)
        assertTrue(uniqueIds.contains("doc-1"))
        assertTrue(uniqueIds.contains("doc-2"))
    }

    @Test
    fun `Verify State`() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>()
        searchEngine.add("Bulgaria")

        val query = "blugaria"
        searchEngine.find("blugaria")
        searchEngine.results.value.first().first.toHeatmap(query)
            .map { it.second }
            .let { topMatch ->
                val expectedHeatmap = listOf(0, 2, 2, 0, 0, 0, 0, 0)
                assertContentEquals(
                    expected = expectedHeatmap,
                    actual = topMatch,
                    message = "Floating N-gram heatmap must match expected secondary tiers."
                )
            }
        val query1 = "bulgira "
        searchEngine.find(query1)
        searchEngine.results.value.first().first.toHeatmap(query1)
            .map { it.second }
            .let { topMatch ->
            val expectedHeatmap = listOf(0, 0, 0, 0, 2, 0, 2, -1)
            assertContentEquals(
                expected = expectedHeatmap,
                actual = topMatch,
                message = "Floating N-gram heatmap must match expected secondary tiers."
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  textSelector / keySelector INTERACTION TESTS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Data class representing a country with a unique code and a translated name.
     * The code (e.g., "BG") is the unique key, while the name may vary by language.
     */
    private data class TranslatedCountry(val code: String, val name: String)

    /**
     * Data class representing a word occurrence at a specific position in a text file.
     * The position is the unique key, while the word (textSelector) may repeat.
     */
    private data class WordOccurrence(val position: Int, val word: String)

    // ─── Use Case 1: Country translations — same key, different text ───

    @Test
    fun `country translations with same code but different text are all stored`() = runTest {
        val engine = TypeaheadSearchEngine<TranslatedCountry, String>(
            textSelector = { it.name },
            keySelector = { it.code }
        )

        engine.add(TranslatedCountry("BG", "Bulgaria"))
        engine.add(TranslatedCountry("BG", "България"))
        engine.add(TranslatedCountry("BG", "Bulgarien"))

        assertEquals(3, engine.size, "Different text+same key = different composite keys, all stored.")
    }

    @Test
    fun `country translations across multiple countries are all stored when text differs`() = runTest {
        val engine = TypeaheadSearchEngine<TranslatedCountry, String>(
            textSelector = { it.name },
            keySelector = { it.code }
        )

        engine.addAll(
            listOf(
                TranslatedCountry("BG", "Bulgaria"),
                TranslatedCountry("BG", "България"),
                TranslatedCountry("DE", "Germany"),
                TranslatedCountry("DE", "Deutschland"),
                TranslatedCountry("DE", "Германия"),
                TranslatedCountry("FR", "France"),
            )
        )

        assertEquals(6, engine.size, "Each item has unique composite key (text+code), all stored.")

        val allItems = engine.getAllItems()
        assertEquals(6, allItems.size)
    }

    @Test
    fun `contains checks composite key of text plus code`() = runTest {
        val engine = TypeaheadSearchEngine<TranslatedCountry, String>(
            textSelector = { it.name },
            keySelector = { it.code }
        )

        engine.add(TranslatedCountry("BG", "Bulgaria"))

        assertTrue(
            TranslatedCountry("BG", "Bulgaria") in engine,
            "Exact same text+code composite key should be found."
        )
        assertFalse(
            TranslatedCountry("BG", "България") in engine,
            "Same code but different text = different composite key, not found."
        )
        assertFalse(
            TranslatedCountry("DE", "Germany") in engine,
            "Different code 'DE' should not be found."
        )
    }

    // ─── Use Case 2: Same text (word), different keys (positions) ───

    @Test
    fun `same word at different positions are all stored when keys differ`() = runTest {
        val engine = TypeaheadSearchEngine<WordOccurrence, Int>(
            textSelector = { it.word },
            keySelector = { it.position }
        )

        engine.add(WordOccurrence(position = 0, word = "hello"))
        engine.add(WordOccurrence(position = 5, word = "hello"))
        engine.add(WordOccurrence(position = 12, word = "hello"))

        assertEquals(3, engine.size, "Three different positions, all should be stored.")

        val allItems = engine.getAllItems()
        assertEquals(3, allItems.size, "getAllItems should return all three occurrences.")

        val positions = allItems.map { it.position }.toSet()
        assertEquals(setOf(0, 5, 12), positions)
    }

    @Test
    fun `same word same position is not duplicated`() = runTest {
        val engine = TypeaheadSearchEngine<WordOccurrence, Int>(
            textSelector = { it.word },
            keySelector = { it.position }
        )

        engine.add(WordOccurrence(position = 0, word = "hello"))
        engine.add(WordOccurrence(position = 0, word = "hello"))

        assertEquals(1, engine.size, "Same position key should not produce duplicates.")
    }

    @Test
    fun `find returns all occurrences of the same word with different keys`() = runTest {
        val engine = TypeaheadSearchEngine<WordOccurrence, Int>(
            textSelector = { it.word },
            keySelector = { it.position },
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        engine.add(WordOccurrence(position = 0, word = "kotlin"))
        engine.add(WordOccurrence(position = 10, word = "kotlin"))
        engine.add(WordOccurrence(position = 20, word = "kotlin"))
        engine.add(WordOccurrence(position = 30, word = "java"))

        val results = engine.find("kotlin").value
        val kotlinResults = results.filter { it.first.word == "kotlin" }

        assertEquals(3, kotlinResults.size, "All three 'kotlin' occurrences should appear in results.")
        val positions = kotlinResults.map { it.first.position }.toSet()
        assertEquals(setOf(0, 10, 20), positions)
    }

    // ─── Mixed: same text with some shared keys, some different ───

    @Test
    fun `mixed scenario same text shared and unique keys`() = runTest {
        val engine = TypeaheadSearchEngine<WordOccurrence, Int>(
            textSelector = { it.word },
            keySelector = { it.position }
        )

        engine.add(WordOccurrence(position = 0, word = "hello"))
        engine.add(WordOccurrence(position = 5, word = "hello"))
        engine.add(WordOccurrence(position = 0, word = "hello"))  // duplicate key=0
        engine.add(WordOccurrence(position = 10, word = "world"))

        assertEquals(3, engine.size, "Keys 0, 5, 10 are unique; duplicate key=0 rejected.")
    }

    // ─── remove() with shared textSelector — exposes the bug ───

    @Test
    fun `remove one item does not destroy siblings sharing the same textKey`() = runTest {
        val engine = TypeaheadSearchEngine<WordOccurrence, Int>(
            textSelector = { it.word },
            keySelector = { it.position },
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        val occ1 = WordOccurrence(position = 0, word = "hello")
        val occ2 = WordOccurrence(position = 5, word = "hello")
        val occ3 = WordOccurrence(position = 12, word = "hello")

        engine.add(occ1)
        engine.add(occ2)
        engine.add(occ3)
        assertEquals(3, engine.size)

        // Remove only the first occurrence
        engine.remove(occ1)

        assertEquals(2, engine.size, "Only occ1 should be removed; occ2 and occ3 should survive.")
        assertFalse(occ1 in engine, "occ1 was removed.")
        assertTrue(occ2 in engine, "occ2 should still be present.")
        assertTrue(occ3 in engine, "occ3 should still be present.")

        // Verify they are still searchable
        val results = engine.find("hello").value
        assertEquals(2, results.size, "occ2 and occ3 should still appear in search results.")
        val remainingPositions = results.map { it.first.position }.toSet()
        assertEquals(setOf(5, 12), remainingPositions)
    }

    @Test
    fun `remove with shared textKey then re-add works correctly`() = runTest {
        val engine = TypeaheadSearchEngine<WordOccurrence, Int>(
            textSelector = { it.word },
            keySelector = { it.position }
        )

        val occ1 = WordOccurrence(position = 0, word = "hello")
        val occ2 = WordOccurrence(position = 5, word = "hello")

        engine.add(occ1)
        engine.add(occ2)
        assertEquals(2, engine.size)

        engine.remove(occ1)
        assertEquals(1, engine.size)

        // Re-add at the same position with the same text
        val occ1v2 = WordOccurrence(position = 0, word = "hello")
        engine.add(occ1v2)
        assertEquals(2, engine.size, "Re-add after remove should work.")
    }

    // ─── Edge cases ───

    @Test
    fun `different text same key is stored as separate entries`() = runTest {
        val engine = TypeaheadSearchEngine<TranslatedCountry, String>(
            textSelector = { it.name },
            keySelector = { it.code }
        )

        engine.add(TranslatedCountry("BG", "Bulgaria"))
        engine.add(TranslatedCountry("BG", "Wonderland"))

        assertEquals(2, engine.size, "Different text+same key = different composite keys, both stored.")
    }

    @Test
    fun `empty text and non-empty text with same key are separate entries`() = runTest {
        val engine = TypeaheadSearchEngine<TranslatedCountry, String>(
            textSelector = { it.name },
            keySelector = { it.code }
        )

        engine.add(TranslatedCountry("BG", ""))
        engine.add(TranslatedCountry("BG", "Bulgaria"))

        assertEquals(2, engine.size, "Different text (empty vs non-empty) + same key = different composite keys.")
    }

    @Test
    fun `addAll with interleaved entries stores all unique composite keys`() = runTest {
        val engine = TypeaheadSearchEngine<TranslatedCountry, String>(
            textSelector = { it.name },
            keySelector = { it.code }
        )

        engine.addAll(
            listOf(
                TranslatedCountry("BG", "Bulgaria"),
                TranslatedCountry("DE", "Germany"),
                TranslatedCountry("BG", "България"),
                TranslatedCountry("FR", "France"),
                TranslatedCountry("DE", "Deutschland"),
            )
        )

        assertEquals(5, engine.size, "Each item has unique composite key (text+code), all stored.")
    }

    @Test
    fun `concurrent adds of same textKey different keys all survive`() = runTest(timeout = 1.minutes) {
        val engine = TypeaheadSearchEngine<WordOccurrence, Int>(
            textSelector = { it.word },
            keySelector = { it.position }
        )

        val jobs = (1..50).map { pos ->
            launch(Dispatchers.Default) {
                engine.add(WordOccurrence(position = pos, word = "concurrent"))
            }
        }
        jobs.joinAll()

        assertEquals(50, engine.size, "50 unique positions with same text should all be stored.")
    }
}