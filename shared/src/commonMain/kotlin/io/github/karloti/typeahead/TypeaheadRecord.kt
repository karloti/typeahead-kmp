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
    val vector: SparseVector
)