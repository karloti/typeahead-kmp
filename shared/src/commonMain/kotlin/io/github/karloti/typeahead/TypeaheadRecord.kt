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

package io.github.karloti.typeahead

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface TypeaheadRecord<out T> {

    /**
     * Represents a single serialized record of the search engine's state.
     * This Data Transfer Object (DTO) bridges the internal vector space with external storage.
     *
     * @param T The type of the user-defined element.
     * @property item The original element stored in the engine.
     * @property tokens The ordered list of tokens extracted from the item's text representation.
     */
    @Serializable
    @SerialName("payload")
    data class TypeaheadPayload<T>(
        val item: T,
        val tokens: List<String>
    ) : TypeaheadRecord<T>

    /**
     * A single entry from the shared vocabulary (Flyweight cache).
     * Maps a unique lowercase token to its pre-computed, L2-normalized sparse vector.
     *
     * @property token The lowercase token string.
     * @property vector The pre-computed [SparseVector] for this token.
     */
    @Serializable
    @SerialName("vocab")
    data class TypeaheadVocabularyEntry(
        val token: String,
        val vector: SparseVector
    ) : TypeaheadRecord<Nothing>

    /**
     * Configuration settings for the [TypeaheadSearchEngine] that control the behavior and weighting of the fuzzy search algorithm.
     *
     * @property ignoreCase Whether to ignore character casing during search and tokenization. Defaults to `true`.
     * @property maxNgramSize The maximum size of N-grams to extract for floating positional matching. Defaults to 4.
     * @property anchorWeight The weight applied to the first character match (P0 Anchor). Defaults to 10.0.
     * @property lengthWeight The weight applied to the exact length bucket match. Defaults to 8.0.
     * @property gestaltWeight The weight for the Typoglycemia Gestalt anchor (matching first, last, and length). Defaults to 8.0.
     * @property prefixWeight The weight for strict prefix matching. Defaults to 6.0.
     * @property fuzzyWeight The weight for fuzzy prefix matching (transposition tolerant). Defaults to 5.0.
     * @property skipWeight The weight for skip-gram matching (insertion/deletion tolerant). Defaults to 2.0.
     * @property floatingWeight The weight for floating N-gram matching. Defaults to 1.0.
     * @property maxResults The maximum number of results to return from a search query. Defaults to 5.
     * @property topKVocab The number of top vocabulary matches to consider per query token during the fuzzy vocabulary scan. Defaults to 10.
     * @property adjacencyBonus The multiplicative bonus applied when consecutive query tokens match adjacent positions in a document. Defaults to 0.1.
     * @property tokenizeRegexString The regular expression string used to split input text into tokens.
     */
    @Serializable
    @SerialName("metadata")
    data class TypeaheadMetadata(
        val ignoreCase: Boolean = TypeaheadSearchEngine.DEFAULT_IGNORE_CASE,
        val maxNgramSize: Int = TypeaheadSearchEngine.DEFAULT_MAX_NGRAM_SIZE,
        val anchorWeight: Float = TypeaheadSearchEngine.DEFAULT_ANCHOR_WEIGHT,
        val lengthWeight: Float = TypeaheadSearchEngine.DEFAULT_LENGTH_WEIGHT,
        val gestaltWeight: Float = TypeaheadSearchEngine.DEFAULT_GESTALT_WEIGHT,
        val prefixWeight: Float = TypeaheadSearchEngine.DEFAULT_PREFIX_WEIGHT,
        val fuzzyWeight: Float = TypeaheadSearchEngine.DEFAULT_FUZZY_WEIGHT,
        val skipWeight: Float = TypeaheadSearchEngine.DEFAULT_SKIP_WEIGHT,
        val floatingWeight: Float = TypeaheadSearchEngine.DEFAULT_FLOATING_WEIGHT,
        val maxResults: Int = TypeaheadSearchEngine.DEFAULT_MAX_RESULTS,
        val topKVocab: Int = TypeaheadSearchEngine.DEFAULT_TOP_K_VOCAB,
        val adjacencyBonus: Float = TypeaheadSearchEngine.DEFAULT_ADJACENCY_BONUS,
        val tokenizeRegexString: String = TypeaheadSearchEngine.DEFAULT_TOKENIZE_REGEX_STRING
    ): TypeaheadRecord<Nothing>

}