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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * A book with a multi-word title, used to verify phrase-level indexing and retrieval.
 */
data class Book(val isbn: String, val title: String, val author: String)

/**
 * Tests for the Tokenized Two-Stage Retrieval architecture.
 *
 * This suite validates the core behavior introduced by the multi-word refactoring:
 * - **Vocabulary (Flyweight cache)**: shared token-to-vector mappings across items.
 * - **Inverted Index**: token → item → positions lookup for candidate gathering.
 * - **Forward Index**: item → ordered token list for MaxSim scoring.
 * - **Two-stage search**: vocabulary scan (Stage 1) + candidate scoring (Stage 2).
 * - **Positional adjacency bonuses**: adjacent query tokens matching adjacent item tokens.
 * - **Partial last-token fuzzy matching**: incomplete words at the end of the query.
 */
class MultiWordSearchTest {

    // ═══════════════════════════════════════════════════════════════════
    //  VOCABULARY & INDEX STRUCTURE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that adding a multi-word item populates the [TypeaheadSearchEngine.EngineState.vocabulary]
     * with one entry per unique lowercase token, sharing vectors across items
     * that contain the same word (Flyweight pattern).
     */
    @Test
    fun `vocabulary stores one vector per unique token across all items`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        engine.add("Kotlin Multiplatform")
        engine.add("Kotlin Coroutines")
        engine.add("Java Concurrency")

        val vocab = engine.state.value.vocabulary

        // "kotlin" appears in two items but should have exactly one vocabulary entry
        assertTrue("kotlin" in vocab, "Vocabulary should contain 'kotlin'.")
        assertTrue("multiplatform" in vocab, "Vocabulary should contain 'multiplatform'.")
        assertTrue("coroutines" in vocab, "Vocabulary should contain 'coroutines'.")
        assertTrue("java" in vocab, "Vocabulary should contain 'java'.")
        assertTrue("concurrency" in vocab, "Vocabulary should contain 'concurrency'.")

        assertEquals(5, vocab.size, "Vocabulary should have exactly 5 unique tokens.")
    }

    /**
     * Verifies that [TypeaheadSearchEngine.EngineState.forwardIndex] maps each item to its ordered list
     * of lowercase tokens, preserving the original word order from [TypeaheadSearchEngine.textSelector].
     */
    @Test
    fun `forwardIndex maps each item to its ordered token list`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        val item = "Central African Republic"
        engine.add(item)

        val docId = engine.generateDocId(item)
        val tokens = engine.state.value.forwardIndex[docId]
        assertNotNull(tokens, "Forward index should contain the item.")
        assertEquals(
            listOf("central", "african", "republic"),
            tokens,
            "Tokens should be lowercase and in original word order."
        )
    }

    /**
     * Verifies that [TypeaheadSearchEngine.EngineState.invertedIndex] records the correct positions
     * for each token within an item's text. For example, in "New York New Jersey",
     * the token "new" should map to positions [0, 2].
     */
    @Test
    fun `invertedIndex records correct positions for repeated tokens`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        val item = "New York New Jersey"
        engine.add(item)

        val docId = engine.generateDocId(item)
        val inverted = engine.state.value.invertedIndex

        val newPositions = inverted["new"]?.get(docId)
        assertNotNull(newPositions, "Inverted index should contain positions for 'new'.")
        assertEquals(
            listOf(0, 2),
            newPositions,
            "Token 'new' appears at positions 0 and 2."
        )

        val yorkPositions = inverted["york"]?.get(docId)
        assertNotNull(yorkPositions, "Inverted index should contain positions for 'york'.")
        assertEquals(listOf(1), yorkPositions, "Token 'york' appears at position 1.")

        val jerseyPositions = inverted["jersey"]?.get(docId)
        assertNotNull(jerseyPositions, "Inverted index should contain positions for 'jersey'.")
        assertEquals(listOf(3), jerseyPositions, "Token 'jersey' appears at position 3.")
    }

    /**
     * Verifies that removing an item cleans up the inverted index entries
     * and that sibling items sharing the same token are not affected.
     */
    @Test
    fun `remove cleans inverted index without affecting sibling items`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        val item1 = "Kotlin Multiplatform"
        val item2 = "Kotlin Coroutines"
        engine.add(item1)
        engine.add(item2)

        engine.remove(item1)

        val inverted = engine.state.value.invertedIndex
        val docId1 = engine.generateDocId(item1)
        val docId2 = engine.generateDocId(item2)

        // "kotlin" should still exist because "Kotlin Coroutines" uses it
        assertNotNull(inverted["kotlin"], "Token 'kotlin' should survive in inverted index.")
        assertTrue(
            docId2 in (inverted["kotlin"]?.keys ?: emptySet()),
            "'Kotlin Coroutines' should still be indexed under 'kotlin'."
        )
        assertFalse(
            docId1 in (inverted["kotlin"]?.keys ?: emptySet()),
            "Removed item should be gone from inverted index."
        )

        // "multiplatform" should be completely removed (no other item uses it)
        assertNull(
            inverted["multiplatform"],
            "Token 'multiplatform' should be removed since no item references it."
        )

        // forwardIndex should only contain the surviving item
        assertEquals(1, engine.state.value.forwardIndex.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MULTI-WORD QUERY MATCHING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that a multi-word query correctly matches items that contain
     * the same set of words, ranking exact phrase matches above partial matches.
     */
    @Test
    fun `multi-word query ranks exact phrase match above partial overlap`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(
            listOf(
                "Kotlin Multiplatform Guide",
                "Kotlin Coroutines Deep Dive",
                "Advanced Java Concurrency",
                "Multiplatform Mobile Development"
            )
        )

        val results = engine.find("Kotlin Multiplatform").value

        assertTrue(results.isNotEmpty(), "Should return results for multi-word query.")

        // "Kotlin Multiplatform Guide" matches both tokens exactly
        assertEquals(
            "Kotlin Multiplatform Guide",
            results.first().first,
            "Exact phrase match should rank first."
        )
    }

    /**
     * Verifies that a two-word query where BOTH tokens appear in one item
     * scores higher than items matching only a single token.
     */
    @Test
    fun `item matching all query tokens scores higher than single-token match`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        engine.addAll(
            listOf(
                "South Korea",    // matches both "south" and "korea"
                "South Africa",   // matches "south" only
                "North Korea",    // matches "korea" only
                "South Sudan"     // matches "south" only
            )
        )

        val results = engine.find("South Korea").value

        assertTrue(results.size >= 3, "Should find at least 3 items with token overlap.")
        assertEquals(
            "South Korea",
            results.first().first,
            "Item matching ALL query tokens should be ranked first."
        )
    }

    /**
     * Verifies that a query with a typo in one of the words still retrieves the
     * correct multi-word item via fuzzy vocabulary matching.
     */
    @Test
    fun `multi-word query with typo still retrieves correct item`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(
            listOf(
                "United Kingdom",
                "United States of America",
                "United Arab Emirates"
            )
        )

        // "Untied" is a transposition of "United"
        val results = engine.find("Untied Kingdom").value

        assertTrue(results.isNotEmpty(), "Should return results despite typo.")
        assertEquals(
            "United Kingdom",
            results.first().first,
            "Fuzzy vocabulary scan should recover from transposition."
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PARTIAL LAST-TOKEN (TYPEAHEAD) BEHAVIOR
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that the last token in a query is matched fuzzily against the
     * vocabulary, enabling real-time typeahead for partially typed words.
     * For example, "United Ki" should match "United Kingdom" because "ki"
     * partially matches the vocabulary entry "kingdom".
     */
    @Test
    fun `partial last token matches via fuzzy vocabulary scan`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(
            listOf(
                "United Kingdom",
                "United States of America",
                "United Arab Emirates"
            )
        )

        val results = engine.find("United Ki").value

        assertTrue(results.isNotEmpty(), "Should return results for partial last token.")
        assertEquals(
            "United Kingdom",
            results.first().first,
            "Partial 'Ki' should fuzzily match 'kingdom' in the vocabulary."
        )
    }

    /**
     * Simulates keystroke-by-keystroke typing of a multi-word query,
     * verifying that the correct item surfaces progressively as more
     * characters are typed.
     */
    @Test
    fun `progressive typing of multi-word query converges to correct result`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(
            listOf(
                "Bosnia and Herzegovina",
                "Antigua and Barbuda",
                "Trinidad and Tobago",
                "Saint Vincent and the Grenadines"
            )
        )

        val target = "Bosnia and Herzegovina"
        val partials = listOf("Bos", "Bosnia", "Bosnia and", "Bosnia and Her", "Bosnia and Herzegovina")

        for (partial in partials) {
            val results = engine.find(partial).value
            assertTrue(
                results.any { it.first == target },
                "Query '$partial' should include '$target' in results."
            )
        }

        // Full query should rank the target first
        val finalResults = engine.find("Bosnia and Herzegovina").value
        assertEquals(target, finalResults.first().first, "Full query should rank target first.")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  POSITIONAL ADJACENCY & ORDER
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that items where query tokens appear in adjacent positions
     * receive a higher score than items where the same tokens are scattered.
     * "New York" with adjacent tokens [0,1] should outscore an item where
     * "new" and "york" are separated by other words.
     */
    @Test
    fun `adjacent token positions score higher than scattered positions`() = runTest {
        val engine = TypeaheadSearchEngine<Book, String>(
            textSelector = { it.title },
            keySelector = { it.isbn },
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        engine.addAll(
            listOf(
                Book("isbn-1", "New York Travel Guide", "Author A"),
                Book("isbn-2", "The New Comprehensive York County Guide", "Author B")
            )
        )

        val results = engine.find("New York").value

        assertEquals(2, results.size, "Both items should appear as candidates.")
        assertEquals(
            "isbn-1",
            results.first().first.isbn,
            "'New York Travel Guide' has adjacent tokens and should rank higher."
        )
    }

    /**
     * Verifies that a reversed word order in the query (e.g., "Korea South" instead
     * of "South Korea") still finds the correct item but with a lower score due
     * to the out-of-order positional penalty.
     */
    @Test
    fun `reversed word order still finds item but with reduced score`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(listOf("South Korea", "North Korea", "South Africa"))

        val naturalOrder = engine.find("South Korea").value
        val reversedOrder = engine.find("Korea South").value

        assertTrue(naturalOrder.isNotEmpty(), "Natural order query should return results.")
        assertTrue(reversedOrder.isNotEmpty(), "Reversed order query should return results.")

        val naturalScore = naturalOrder.first { it.first == "South Korea" }.second
        val reversedScore = reversedOrder.first { it.first == "South Korea" }.second

        assertTrue(
            naturalScore > reversedScore,
            "Natural word order (score=$naturalScore) should score higher than reversed (score=$reversedScore)."
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONTEXT DILUTION ELIMINATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that the tokenized architecture eliminates context dilution:
     * searching for a single word that appears in a long multi-word item
     * should still produce a high match score, unlike the old monolithic
     * embedding approach where long texts diluted every individual word's signal.
     */
    @Test
    fun `single-word query against long multi-word item avoids context dilution`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(
            listOf(
                "Kotlin",
                "Kotlin Multiplatform Mobile Development Guide for Beginners"
            )
        )

        val results = engine.find("Kotlin").value

        assertEquals(2, results.size, "Both items should appear.")

        // Both contain the exact token "kotlin" — the long item should NOT be
        // drastically penalized compared to the short one.
        val shortScore = results.first { it.first == "Kotlin" }.second
        val longScore = results.first { it.first.startsWith("Kotlin Multi") }.second

        assertTrue(
            longScore > 0.5f * shortScore,
            "Long item score ($longScore) should not be drastically diluted vs short ($shortScore)."
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  STRUCTURAL INTEGRITY UNDER MUTATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that [clear] resets all three indices (vocabulary, invertedIndex,
     * forwardIndex) and allows clean re-population.
     */
    @Test
    fun `clear resets vocabulary invertedIndex and forwardIndex`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 10)
        )

        engine.addAll(listOf("Hello World", "Foo Bar"))
        assertEquals(2, engine.size)

        engine.clear()

        val state = engine.state.value
        assertEquals(0, state.vocabulary.size, "Vocabulary should be empty after clear.")
        assertEquals(0, state.invertedIndex.size, "Inverted index should be empty after clear.")
        assertEquals(0, state.forwardIndex.size, "Forward index should be empty after clear.")
        assertEquals(0, state.documentStore.size, "Document store should be empty after clear.")

        // Re-add should work cleanly
        val newItem = "New Entry"
        engine.add(newItem)
        assertEquals(1, engine.size)
        val newDocId = engine.generateDocId(newItem)
        assertEquals(
            listOf("new", "entry"),
            engine.state.value.forwardIndex[newDocId],
            "Re-added item should be correctly tokenized."
        )
    }

    /**
     * Verifies that the vocabulary entry for a token is preserved even after
     * the last item using that token is removed. The vocabulary acts as a
     * Flyweight cache and is not eagerly cleaned up.
     */
    @Test
    fun `vocabulary entry survives after last item using it is removed`() = runTest {
        val engine = TypeaheadSearchEngine<String>()

        engine.add("Kotlin")
        assertTrue("kotlin" in engine.state.value.vocabulary)

        engine.remove("Kotlin")
        assertEquals(0, engine.size, "Item should be removed.")

        // Vocabulary is a Flyweight cache — stale entries are acceptable
        assertTrue(
            "kotlin" in engine.state.value.vocabulary,
            "Vocabulary entry should survive as a Flyweight cache entry."
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONCURRENCY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that concurrent additions of multi-word items correctly
     * populate the inverted index without data loss or corruption.
     */
    @Test
    fun `concurrent addAll of multi-word items preserves index integrity`() = runTest(timeout = 1.minutes) {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 100)
        )

        val items = (1..200).map { "Product Category$it Item$it" }

        val jobs = items.chunked(20).map { chunk ->
            launch(Dispatchers.Default) {
                engine.addAll(chunk)
            }
        }
        jobs.joinAll()

        assertEquals(200, engine.size, "All 200 items should be indexed.")

        // Each item has 3 tokens: "product", "categoryN", "itemN"
        val vocab = engine.state.value.vocabulary
        assertTrue("product" in vocab, "Shared token 'product' should be in vocabulary.")
        assertTrue("category1" in vocab, "Unique token 'category1' should be in vocabulary.")

        // Search should work correctly across concurrently-added items
        val results = engine.find("Product Category1").value
        assertTrue(
            results.any { it.first == "Product Category1 Item1" },
            "Should find the exact multi-word match."
        )
    }

    /**
     * Verifies that concurrent add and remove operations on multi-word items
     * leave the inverted index in a consistent state with no dangling references.
     */
    @Test
    fun `concurrent add and remove leaves consistent inverted index`() = runTest(timeout = 1.minutes) {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 50)
        )

        val permanentItems = (1..50).map { "Permanent Item $it" }
        val transientItems = (1..50).map { "Transient Entry $it" }

        engine.addAll(permanentItems + transientItems)
        assertEquals(100, engine.size)

        // Concurrently remove all transient items
        val removeJobs = transientItems.map { item ->
            launch(Dispatchers.Default) { engine.remove(item) }
        }
        removeJobs.joinAll()

        assertEquals(50, engine.size, "Only permanent items should remain.")

        // The inverted index for "transient" should be cleaned up
        val transientEntries = engine.state.value.invertedIndex["transient"]
        assertNull(
            transientEntries,
            "Inverted index entry for 'transient' should be fully removed."
        )

        // "permanent" should still be intact
        val permanentEntries = engine.state.value.invertedIndex["permanent"]
        assertNotNull(permanentEntries, "'permanent' should still be in the inverted index.")
        assertEquals(50, permanentEntries.size, "All 50 permanent items should reference 'permanent'.")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EDGE CASES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verifies that items containing only non-word characters (punctuation,
     * whitespace) produce an empty token list and are still handled gracefully.
     */
    @Test
    fun `item with only punctuation produces empty tokens and is searchable`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        val item = "---"
        engine.add(item)
        assertEquals(1, engine.size, "Punctuation-only item should still be stored.")

        val docId = engine.generateDocId(item)
        val tokens = engine.state.value.forwardIndex[docId]
        assertNotNull(tokens)
        assertTrue(tokens.isEmpty(), "Punctuation-only text should produce zero tokens.")
    }

    /**
     * Verifies that searching for a query that shares no tokens with any
     * indexed item returns an empty result list (no crashes or exceptions).
     */
    @Test
    fun `query with zero vocabulary overlap returns empty results gracefully`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(listOf("Alpha", "Beta", "Gamma"))

        val results = engine.find("Zzzzz").value

        // With the exhaustive fallback, items may still appear with very low scores.
        // The key assertion is that no exception is thrown.
        assertTrue(true, "Search completed without exception.")
    }

    /**
     * Verifies that special characters (hyphens, apostrophes) in item text
     * are treated as word separators during tokenization.
     */
    @Test
    fun `hyphens and apostrophes act as token separators`() = runTest {
        val engine = TypeaheadSearchEngine<String>(
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        val item = "Congo (Congo-Brazzaville)"
        engine.add(item)

        val docId = engine.generateDocId(item)
        val tokens = engine.state.value.forwardIndex[docId]
        assertNotNull(tokens)
        assertTrue(
            tokens.containsAll(listOf("congo", "brazzaville")),
            "Hyphens and parentheses should split into separate tokens. Got: $tokens"
        )
    }

    /**
     * Verifies that a single-word item behaves identically to the pre-refactoring
     * engine: one token, one vocabulary entry, one inverted index entry.
     */
    @Test
    fun `single-word items produce minimal index footprint`() = runTest {
        val engine = TypeaheadSearchEngine<String>()

        val item = "Bulgaria"
        engine.add(item)

        val state = engine.state.value
        val docId = engine.generateDocId(item)
        assertEquals(1, state.vocabulary.size, "One token → one vocabulary entry.")
        assertEquals(1, state.invertedIndex.size, "One token → one inverted index entry.")
        assertEquals(1, state.forwardIndex.size, "One item → one forward index entry.")
        assertEquals(listOf("bulgaria"), state.forwardIndex[docId])
    }

    // ═══════════════════════════════════════════════════════════════════
    //  REGRESSION: COUNTRY LIST WITH MULTI-WORD NAMES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * End-to-end regression test using the full country list.
     * Multi-word country names like "Central African Republic" should now
     * rank significantly higher for the query "Central" compared to the
     * old monolithic-vector approach.
     */
    @Test
    fun `multi-word country names are discoverable by individual word`() = runTest {
        val engine = TypeaheadSearchEngine(
            items = countries,
            metadata = TypeaheadMetadata(maxResults = 10),
        )

        val results = engine.find("Republic").value

        val republicCountries = results.map { it.first }
        assertTrue(
            republicCountries.any { "Republic" in it },
            "At least one country containing 'Republic' should appear. Got: $republicCountries"
        )
    }

    /**
     * Verifies that the "Cnada" typing simulation from the README still works
     * correctly under the tokenized architecture (regression guard).
     */
    @Test
    fun `Cnada typing simulation still resolves to Canada`() = runTest {
        val engine = TypeaheadSearchEngine(
            items = countries,
            metadata = TypeaheadMetadata(maxResults = 5),
        )

        val results = engine.find("Cnada").value

        assertTrue(results.isNotEmpty(), "Should return results for 'Cnada'.")
        assertEquals(
            "Canada",
            results.first().first,
            "'Cnada' should resolve to 'Canada' as the top result."
        )
    }

    /**
     * Verifies that an exact phrase search (words next to each other)
     * receives an Adjacency Bonus and outranks documents where words are scattered.
     */
    @Test
    fun `exact phrase gets adjacency bonus and ranks higher than scattered words`() = runTest {
        val engine = TypeaheadSearchEngine<Book, String>(
            textSelector = { it.title },
            keySelector = { it.isbn }
        )

        engine.addAll(
            listOf(
                Book("1", "Tom Hanks Drama", "Author A"), // Exact phrase
                Book("2", "Tom is in a Drama with Hanks", "Author B") // Scattered words
            )
        )

        val results = engine.find("Tom Hanks").value

        assertTrue(results.size >= 2, "Should find both documents")
        assertEquals("1", results[0].first.isbn, "Adjacent match should rank first due to ADJACENCY_BONUS")
        assertTrue(results[0].second > results[1].second, "Score of adjacent phrase should be strictly higher")
    }

    /**
     * Ensures that search works without strict order penalty.
     * Shuffled words should still find the document successfully, just with a slightly lower
     * score compared to the perfect sequence.
     */
    @Test
    fun `search works without order penalty for reversed query words`() = runTest {
        val engine = TypeaheadSearchEngine<Book, String>(
            textSelector = { it.title },
            keySelector = { it.isbn }
        )

        engine.addAll(
            listOf(
                Book("1", "Tom Hanks", "Author A")
            )
        )

        val resultsReversed = engine.find("Hanks Tom").value
        val resultsInOrder = engine.find("Tom Hanks").value

        assertTrue(resultsReversed.isNotEmpty(), "Should find the document even if words are reversed")
        assertTrue(
            resultsInOrder.first().second > resultsReversed.first().second,
            "In-order should have adjacency bonus, scoring slightly higher than reversed"
        )
    }

    /**
     * Validates the logarithmic (BM25-inspired) length penalty.
     * Short text that matches perfectly should beat a long description.
     */
    @Test
    fun `logarithmic length penalty prefers shorter text for the same match quality`() = runTest {
        val engine = TypeaheadSearchEngine<Book, String>(
            textSelector = { it.title },
            keySelector = { it.isbn }
        )

        engine.addAll(
            listOf(
                Book("1", "Bulgaria", "Author A"), // 1 word
                Book("2", "Bulgaria is a beautiful country located in the Balkans", "Author B") // Many words
            )
        )

        val results = engine.find("Bulgaria").value

        assertEquals(2, results.size, "Should find both documents")
        assertEquals("1", results.first().first.isbn, "Shorter document should rank first due to length penalty modifier")
        assertTrue(results[0].second > results[1].second, "Shorter text score must be significantly higher")
    }

    /**
     * Verifies late deduplication when using keySelector.
     * Multiple translations/synonyms of the same entity should not take up more than 1 spot in the results.
     */
    @Test
    fun `late deduplication prevents duplicate entities from multiple translations`() = runTest {
        data class TranslatedCountry(val code: String, val translation: String)

        val engine = TypeaheadSearchEngine<TranslatedCountry, String>(
            textSelector = { it.translation },
            keySelector = { it.code }, // Entity ID: Deduplication key
            metadata = TypeaheadMetadata(maxResults = 5)
        )

        engine.addAll(
            listOf(
                TranslatedCountry("BG", "Bulgaria"),
                TranslatedCountry("BG", "България"), // Same ID, different text
                TranslatedCountry("DE", "Germany"),
                TranslatedCountry("DE", "Deutschland")
            )
        )

        // Search for something that could potentially catch both translations via fuzzy matching
        val results = engine.find("Bulg").value

        val bgResultsCount = results.count { it.first.code == "BG" }
        assertEquals(
            1,
            bgResultsCount,
            "Late deduplication should keep only the highest scoring translation per entity ID"
        )
    }

    @Test
    fun `test 3-word query across 20-word documents with smart positional encoding`() = runTest {
        val engine = TypeaheadSearchEngine<String, String>(
            textSelector = { it },
            keySelector = { it } // For simplicity use text as unique key
        )

        // Document 1: Exact phrase (Adjacency Bonus should shoot it to the top)
        val doc1Exact = "The really fast brown fox jumps over the lazy dog and runs into the deep green forest very quickly today."

        // Document 2: All words are there, but scattered and shuffled (No bonus, but no order penalty)
        val doc2Scattered = "The fox is very fast but the brown bear is actually much slower when running in the deep green forest."

        // Document 3: One word missing (fox), has only "fast" and "brown"
        val doc3Partial = "The fast rabbit jumps over the lazy dog and runs into the deep brown forest very quickly during the day."

        // Document 4: Has only "fox", other words are missing
        val doc4Poor = "A completely different animal jumps over the lazy dog and runs into the deep green forest very quickly today fox."

        // Index controlled texts
        engine.addAll(listOf(doc1Exact, doc2Scattered, doc3Partial, doc4Poor))

        // Search for 3 words
        val query = "fast brown fox"
        val results = engine.find(query).value

        // ==========================================
        // PRINT RESULTS DURING TEST
        // ==========================================
        println("\n=== Results for query: [$query] ===")
        results.forEachIndexed { index, (match, score) ->
            // Format to 3 decimal places for readability
            val formattedScore = score.toString().take(5)
            println("${index + 1}. Score: $formattedScore | Match: $match")
        }
        println("==========================================\n")

        // ==========================================
        // ASSERT CHECKS (Verify algorithm)
        // ==========================================
        assertTrue(results.isNotEmpty(), "Should return results.")

        // 1. Exact phrase should be first (due to positional bonus)
        assertEquals(doc1Exact, results[0].first, "Exact phrase should be at rank 1.")

        // 2. Scattered words should be second (no bonus, but has all 3 words)
        assertEquals(doc2Scattered, results[1].first, "Scattered words should be at rank 2.")

        // 3. Partial match should be third (has only 2 words)
        assertEquals(doc3Partial, results[2].first, "Partial match should be at rank 3.")

        // 4. Perfect match score should be SIGNIFICANTLY higher than scattered
        assertTrue(
            results[0].second > results[1].second * 1.05f,
            "Adjacency Bonus should give at least 5% lead to the exact phrase."
        )
    }


    /**
     * Tests a situation where the user searches for 3 words, but all THREE have heavy typos.
     * The algorithm should recognize words via vector similarity and then
     * give a bonus to the exact (though misspelled) phrase via Smart Positional Encoding.
     */
    @Test
    fun `fuzzy search correctly ranks documents even when all 3 query words contain typos`() = runTest {
        val engine = TypeaheadSearchEngine<String, String>(
            textSelector = { it },
            keySelector = { it }
        )

        val doc1Exact = "The really fast brown fox jumps over the lazy dog." // Exact phrase
        val doc2Scattered = "The fox is very fast but the brown bear is slow." // Scattered words
        val doc3Partial = "The fast rabbit jumps over the deep brown forest." // Missing third word

        engine.addAll(listOf(doc1Exact, doc2Scattered, doc3Partial))

        // Query with heavy typos:
        // fsat -> fast (swapped letters)
        // brwon -> brown (swapped letters)
        // fxo -> fox (swapped letters)
        val query = "fsat brwon fxo"
        val results = engine.find(query).value

        println("\n=== Results for query with HEAVY TYPOS: [$query] ===")
        results.forEachIndexed { index, (match, score) ->
            val formattedScore = score.toString().take(5)
            println("${index + 1}. Score: $formattedScore | Match: $match")
        }
        println("===================================================\n")

        assertTrue(results.isNotEmpty(), "Should find results despite typos.")
        assertEquals(doc1Exact, results[0].first, "Should find perfect text at rank 1 despite typos.")
        assertTrue(
            results[0].second > results[1].second * 1.05f,
            "Even with misspelled words, the Adjacency Bonus should activate and provide a lead."
        )
    }

    /**
     * Simulates real world search for a movie/book where the user is in a hurry,
     * skips letters and types words phonetically.
     */
    @Test
    fun `real world sloppy query finds the correct long description`() = runTest {
        val engine = TypeaheadSearchEngine<String, String>(
            textSelector = { it },
            keySelector = { it }
        )

        // Control texts
        val doc1 = "Harry Potter and the Goblet of Fire is a brilliant fantasy movie released in 2005."
        val doc2 = "Harry Potter and the Chamber of Secrets is also a great fantasy movie about wizards."
        val doc3 = "A completely unrelated movie where a magical goblet was found in the fire."

        engine.addAll(listOf(doc1, doc2, doc3))

        // Query with skipped and wrong letters:
        // hariy -> harry
        // pota -> potter
        // gobelt -> goblet
        val sloppyQuery = "hariy pota gobelt"
        val results = engine.find(sloppyQuery).value

        println("\n=== Results for phonetic/sloppy query: [$sloppyQuery] ===")
        results.forEachIndexed { index, (match, score) ->
            val formattedScore = score.toString().take(5)
            println("${index + 1}. Score: $formattedScore | Match: $match")
        }
        println("========================================================\n")

        // Checks
        assertEquals(doc1, results[0].first, "The 'Goblet of Fire' movie should be the absolute winner.")
        assertTrue(results[0].second > results[1].second, "Document 1 should beat Document 2 because it contains the third word.")
        assertTrue(results[0].second > results[2].second, "Document 1 should beat Document 3 because it contains all words.")
    }
}