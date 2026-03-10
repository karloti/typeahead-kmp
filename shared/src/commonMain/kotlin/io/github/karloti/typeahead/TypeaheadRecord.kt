package io.github.karloti.typeahead

import kotlinx.serialization.Serializable

/**
 * Represents a single serialized record of the search engine's state.
 * This Data Transfer Object (DTO) bridges the internal vector space with external storage.
 *
 * @param T The type of the user-defined element.
 * @param item The original element stored in the engine.
 * @param vector The pre-computed, L2-normalized sparse vector for this element.
 */
@Serializable
data class TypeaheadRecord<T>(
    val item: T,
    val vector: Map<String, Double>
)