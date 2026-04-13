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

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.SearchBarDefaults.InputField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    modifier: Modifier = Modifier
) {
    val viewModel = viewModel<SearchViewModel>()
    val results by viewModel.results.collectAsState()

    var selectedIndex by remember { mutableStateOf(-1) }
    val options = listOf("Countries", "Movies", "Import File").withIndex().toList()

    val listState = rememberLazyListState()
    val searchBarState = rememberSearchBarState()
    val scope = rememberCoroutineScope()

    var searchBarPlaceholder by remember { mutableStateOf("") }

    LaunchedEffect(results) {
        launch { listState.scrollToItem(0) }
    }

    remember(viewModel.isLoading) {
        searchBarPlaceholder = if (viewModel.isLoading)
            "Search is live — results update as data loads"
        else
            "Search indexed data..."

    }

    val inputField: @Composable () -> Unit = {
        InputField(
            modifier = Modifier.fillMaxWidth(),
            textFieldState = viewModel.queryState,
            searchBarState = searchBarState,
            onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
            placeholder = { Text(searchBarPlaceholder) },
            leadingIcon = {
                if (searchBarState.currentValue == SearchBarValue.Expanded)
                    IconButton(onClick = {
                        scope.launch { searchBarState.animateToCollapsed() }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                else {
                    Icon(Icons.Default.Search, contentDescription = null)
                }

            },
            trailingIcon = {
                if (viewModel.queryState.text.isNotEmpty()) {
                    IconButton(onClick = {
                        viewModel.clearSearch()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            },
            enabled = viewModel.linesCount > 0
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Typeahead Demo", fontWeight = FontWeight.SemiBold)

                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                onClick = { viewModel.toggleInfo() }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = colorScheme.primary
                        )
                        Text(
                            text = "Engine Documentation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        val rotation by animateFloatAsState(if (viewModel.infoExpanded) 180f else 0f)
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.rotate(rotation)
                        )
                    }

                    if (viewModel.infoExpanded) {
                        Text(
                            text = "This engine is optimized for high-performance searching. It indexes data in real-time, allowing you to search even while a file is still loading.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Data Requirements:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary
                        )
                        Text(
                            text = "Each line must begin with a UNIQUE KEY, followed by a separator (SPACE or TAB). Compatible with common TSV (Tab-Separated Values) files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEach { (index, label) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        ),
                        onClick = {
                            selectedIndex = index
                            when (index) {
                                0 -> viewModel.loadSource(
                                    streamDemoFile("/country.tsv"),
                                    expectedLines = 194
                                )

                                1 -> viewModel.loadSource(
                                    streamDemoFile("/movies.tsv"),
                                    expectedLines = 86_000
                                )

                                2 -> pickLocalFileAndStream { viewModel.loadSource(it) }
                            }
                        },
                        selected = index == selectedIndex,
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        enabled = !viewModel.isLoading
                    )
                }
            }

            AnimatedVisibility(
                visible = viewModel.isLoading || viewModel.linesCount > 0,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (viewModel.isLoading) {
                        val progress = viewModel.loadProgress
                        val animatedProgress by animateFloatAsState(
                            targetValue = progress ?: 0f
                        )
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val progressText = if (progress != null) {
                                val percent = (progress * 100).toInt()
                                "Indexing: ${viewModel.linesCount} records ($percent%)"
                            } else {
                                "Indexing: ${viewModel.linesCount} records..."
                            }
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.stopLoading() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop loading",
                                    tint = colorScheme.error
                                )
                            }
                        }
                    } else {
                        Text(
                            "Ready: ${viewModel.linesCount} records indexed",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            SearchBar(
                modifier = Modifier,
                state = searchBarState,
                inputField = inputField
            )

            ExpandedFullScreenSearchBar(
                state = searchBarState,
                inputField = inputField,
                modifier = Modifier.fillMaxSize(),
                content = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        if (results.isEmpty() && viewModel.queryState.text.isNotEmpty()) {
                            item {
                                Text(
                                    "No results found for \"${viewModel.queryState.text}\"",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        } else {
                            items(
                                items = results,
                                key = { it.first }
                            ) { (result, score) ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.animateItem().clickable(
                                        onClick = {
                                            viewModel.onQueryChanged(result.second)
                                            scope.launch { searchBarState.animateToCollapsed() }
                                        }
                                    ),
                                    headlineContent = {
                                        val heatmap = viewModel.getHeatmap(result)
                                        if (heatmap != null) {
                                            Heatmap(heatmap)
                                        } else {
                                            Text(result.second)
                                        }
                                    },
                                    trailingContent = {
                                        Text("$score".take(5))
                                    },
                                    supportingContent = {
                                        Text(
                                            fontStyle = MaterialTheme.typography.bodySmall.fontStyle,
                                            text = "Index: ${result.first}"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            )

            if (searchBarState.currentValue == SearchBarValue.Collapsed && viewModel.linesCount > 0) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Powered by Kotlin Multiplatform",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}