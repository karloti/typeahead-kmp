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

@file:OptIn(ExperimentalAtomicApi::class)

package app.smartcoding.typeahead_demo

import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

suspend fun Flow<String>.parse(
    typeaheadSearchEngine: TypeaheadSearchEngine<Pair<String, String>, String>,
    loadedLinesCount: (Int) -> Unit,
    isFinished: () -> Unit
) = withContext(Dispatchers.Default) {
    val loadedLinesCount = AtomicInt(0)

    typeaheadSearchEngine.addAll(this@parse) { line ->
        if (line.isNotBlank()) {
            val idStr = line.takeWhile { !it.isWhitespace() && it != ',' }
            if (idStr.isNotEmpty()) {
                val title = line.drop(idStr.length + 1).trim()
                loadedLinesCount.incrementAndFetch().let {
                    if (it % 1000 == 0) loadedLinesCount(it)
                }
                idStr to title
            } else "" to ""
        } else "" to ""
    }
    isFinished()
    loadedLinesCount(loadedLinesCount.load())
    isFinished()
}