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

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package app.smartcoding.typeahead_demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    typeaheadSearchEngine: TypeaheadSearchEngine<Pair<String, String>, String>,
    modifier: Modifier = Modifier
) {
    val filteredResults by typeaheadSearchEngine.results.collectAsState()

    var choice: Pair<String, String>? by remember {
        mutableStateOf(null)
    }

    var linesCount by remember { mutableStateOf(0) }
    var flow: Flow<String>? by remember { mutableStateOf(null) }
    if (flow != null) LaunchedEffect(flow) {
        typeaheadSearchEngine.clear()
        flow?.parse(
            typeaheadSearchEngine = typeaheadSearchEngine,
            loadedLinesCount = {
                linesCount = it
            }
        ) { flow = null }
    }

    var query by remember { mutableStateOf("") }
    val cornerRadius = 16f
    val customContainerColor = false
    val containerColor = MaterialTheme.colorScheme.primaryContainer

    var expanded by remember { mutableStateOf(false) }

    val effectiveContainerColor = if (customContainerColor) containerColor else MaterialTheme.colorScheme.surface

    LaunchedEffect(query) {
        if (query.isNotEmpty()) typeaheadSearchEngine.find(query)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp),//.width(380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Typeahead Demo",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedCard(
            ) {
                Text(
                    text = """
                    This demo showcases a high-performance Typeahead engine capable of loading large text files into memory for instant searching. 
                    
                    The file format requires each line to start with a unique key, followed by a space and the searchable content.
                    """.trimIndent(),
                    modifier = modifier.padding(16.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = {
                        flow = streamDemoFile("/movies.txt")
                    },
                    enabled = flow == null
                ) {
                    Text("movies 86k")
                }
                Button(
                    onClick = {
                        flow = streamDemoFile("/movies_1_5M.txt")
                    },
                    enabled = flow == null
                ) {
                    Text("movies 1500k")
                }
                Button(
                    onClick = {
                        pickLocalFileAndStream { customFileFlow -> flow = customFileFlow }
                    },
                    enabled = flow == null
                ) {
                    Text("Import file")
                }
            }

            Text("Прочетени: $linesCount")

            DockedSearchBar(
                inputField = {
                    TextField(
                        value = query,
                        onValueChange = {
                            query = it
                            if (it.isNotEmpty() && !expanded) expanded = true
                        },
                        placeholder = {
                            Text("Search...", color = MaterialTheme.colorScheme.onSurface)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search Icon"
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        query = ""
                                        expanded = false
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search"
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                shape = RoundedCornerShape(cornerRadius.dp),
                colors = SearchBarDefaults.colors(containerColor = effectiveContainerColor),
                tonalElevation = 4f.dp,
                shadowElevation = 8f.dp,
                modifier = Modifier.fillMaxWidth(),
                content = {
                    LazyColumn {
                        if (filteredResults.isEmpty() && query.isNotEmpty()) {
                            item {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            "No results found for \"$query\"",
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                )
                            }
                        } else {
                            items(filteredResults) { (result, score) ->
                                ListItem(
                                    headlineContent = {
                                        val heatmap = typeaheadSearchEngine.heatmap(result.second) ?: return@ListItem
                                        heatmap.HeatMap(
                                            code = result.second,
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null
                                        )
                                    },
                                    trailingContent = {
                                        Text(
                                            text = score.toString().take(5),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Search for something",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

    }
}