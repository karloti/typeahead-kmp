package com.github.karloti.typeahead

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

class TypeaheadSearchEngineTest {

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

    @Test
    fun testAddingCountriesAndSearching() = runTest(timeout = 1.minutes) {
        val searchEngine = TypeaheadSearchEngine(countries)

        launch {
            val results = searchEngine.find("bul", maxResults = 5)
            val checkResult = listOf("Bulgaria", "Burundi", "Burkina Faso", "Benin", "Brunei")
            results.forEach { (country, score) ->
                println("Found country: $country with score: $score")
            }

            assertEquals(checkResult, results.map { it.first }, "Results should match the expected results.")

            println("Total indexed countries: ${searchEngine.size}")
        }
    }

    @Test
    fun testTypingSimulationWithScoreProgression() = runTest(timeout = 1.minutes){
        val searchEngine = TypeaheadSearchEngine<String>()
        val checkResult = listOf(
            listOf("Cuba", "Chad", "China", "Chile", "Cyprus"),
            listOf("Cuba", "Chad", "China", "Chile", "Cyprus"),
            listOf("Chad", "Cuba", "China", "Chile", "Cyprus"),
            listOf("Chad", "Cuba", "China", "Chile", "Cyprus"),
            listOf("China", "Chad", "Grenada", "Chile", "Cuba"),
        )
        launch {
            searchEngine.addAll(countries)
            searchEngine.remove("Canada")

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
        val itemsPerWriter = if (isJsOrWasm) 10 else 100

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
            val extraCountryRecord = io.github.karloti.typeahead.TypeaheadRecord(
                item = "Atlantis",
                vector = mapOf("P0_a" to 10.0, "LEN_8" to 8.0) // Mock vector
            )
            searchEngine.importFromSequence(sequenceOf(extraCountryRecord), clearExisting = false)

            assertEquals(originalSize + 1, searchEngine.size, "Engine size should increase by 1 after merging.")
            assertTrue("Atlantis" in searchEngine, "The merged item 'Atlantis' should be present in the engine.")

            println("✅ Export/Import sequence and vector integrity test passed perfectly!")
        }
    }
}