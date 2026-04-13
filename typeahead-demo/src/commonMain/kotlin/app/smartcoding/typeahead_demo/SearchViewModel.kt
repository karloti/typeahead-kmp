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

package app.smartcoding.typeahead_demo

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clean Architecture: ViewModel to manage state and search engine logic.
 */
class SearchViewModel(
    private val engine: TypeaheadSearchEngine<Pair<String, String>, String>,
): ViewModel() {
    val queryState = TextFieldState()

    var linesCount by mutableStateOf(0)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var loadProgress by mutableStateOf<Float?>(null)
        private set

    var infoExpanded by mutableStateOf(true)

    val results: StateFlow<List<Pair<Pair<String, String>, Float>>> =
        engine.storeResults ?: MutableStateFlow(emptyList())

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            snapshotFlow { queryState.text.toString() }
                .drop(1)
                .collectLatest { newQuery ->
                    if (newQuery.isNotEmpty()) {
                        infoExpanded = false
                        engine.find(newQuery)
                    }
                }
        }
    }

    fun onQueryChanged(newQuery: String) {
        queryState.setTextAndPlaceCursorAtEnd(newQuery)
    }

    fun loadSource(sourceFlow: Flow<String>, expectedLines: Int? = null) {
        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            isLoading = true
            infoExpanded = false
            loadProgress = if (expectedLines != null) 0f else null
            engine.clear()
            linesCount = 0

            withContext(Dispatchers.Default) {
                sourceFlow.parse(
                    typeaheadSearchEngine = engine,
                    loadedLinesCount = { count ->
                        viewModelScope.launch(Dispatchers.Main) {
                            linesCount = count
                            loadProgress = expectedLines?.let {
                                (count.toFloat() / it).coerceAtMost(1f)
                            }
                        }
                    },
                    isFinished = {
                        viewModelScope.launch(Dispatchers.Main) {
                            isLoading = false
                            loadProgress = null
                        }
                    }
                )
            }
        }
    }

    fun stopLoading() {
        loadJob?.cancel()
        isLoading = false
        loadProgress = null
    }

    fun clearSearch() {
        queryState.clearText()
    }

    fun toggleInfo() {
        infoExpanded = !infoExpanded
    }

    fun getHeatmap(item: Pair<String, String>) =
        engine.heatmap(engine.docIdFor(item))?.toImmutableList()
}
