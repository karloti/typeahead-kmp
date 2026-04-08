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

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.HorizontalRule
import com.github.ajalt.mordant.widgets.Text
import io.github.karloti.typeahead.TypeaheadSearchEngine
import io.github.karloti.typeahead.toHeatmap
import io.github.karloti.typeahead.toHighlightedString
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.measureTime

private val visitedPaths = atomic(persistentSetOf<String>())
private val foundResults = atomic(persistentListOf<Path>())
private val activeTasks = atomic(1) // Starts at 1 for the initial seed path

fun main(args: Array<String>) = runBlocking {
    val t = Terminal(
        interactive = true,
        ansiLevel = AnsiLevel.TRUECOLOR
    )

    val parser = ArgParser("findme")

    val targetName by parser.option(
        type = ArgType.String,
        shortName = "n",
        fullName = "name",
        description = "The exact or partial name of the file/directory to search for"
    ).required()

    val startPathStr by parser.option(
        type = ArgType.String,
        shortName = "p",
        fullName = "path",
        description = "The starting directory path for the search"
    ).default(".")

    parser.parse(args)

    val infoStyle = cyan
    val successStyle = (brightGreen + TextStyles.bold)
    val pathStyle = gray
    val headerStyle = (brightYellow + TextStyles.bold)

    val searchEngine = TypeaheadSearchEngine<Path> { it.name }
    val startPath = startPathStr.toPath(normalize = true)

    t.println(HorizontalRule(title = Text(headerStyle(" SEARCH ")), ruleStyle = brightYellow))
    t.println(infoStyle("Search target: ") + white(targetName))
    t.println(infoStyle("Start path:    ") + pathStyle(startPath.toString()))
    t.println()

    val duration = measureTime {
        val workQueue = Channel<Path>(Channel.UNLIMITED)

        // Increased worker count to saturate disk I/O queues.
        // Because these threads will block on Okio operations, a higher number is required.
        val workerCount = 64

        val workers = List(workerCount) {
            // CRITICAL: Must use Dispatchers.IO to prevent starvation from blocking Okio calls
            launch(Dispatchers.IO) {
                for (dir in workQueue) {
                    try {
                        val canonicalPath = FileSystem.SYSTEM.canonicalize(dir)
                        val pathString = canonicalPath.toString()

                        // OPTIMIZATION: Lock-free fast-path check to avoid CAS contention
                        // If we already visited this, skip entirely without atomic updates.
                        if (visitedPaths.value.contains(pathString)) continue

                        // If not visited, safely update the persistent set
                        visitedPaths.update { it.add(pathString) }

                        val contents = FileSystem.SYSTEM.list(canonicalPath)
                        val filesToProcess = mutableListOf<Path>()
                        val dirsToProcess = mutableListOf<Path>()

                        // Okio's metadata is a blocking stat() system call.
                        // Having 64 workers on Dispatchers.IO handles this concurrently across directories.
                        for (item in contents) {
                            val isDir = FileSystem.SYSTEM.metadataOrNull(item)?.isDirectory == true
                            if (isDir) {
                                dirsToProcess.add(item)
                            } else {
                                filesToProcess.add(item)
                            }
                        }

                        // Add discovered directories to the queue
                        if (dirsToProcess.isNotEmpty()) {
                            activeTasks.addAndGet(dirsToProcess.size)
                            dirsToProcess.forEach { workQueue.trySend(it) }
                        }

                        // OPTIMIZATION: Inline pure CPU processing. No extra coroutine launch needed.
                        if (filesToProcess.isNotEmpty()) {
                            val matches = filesToProcess.filter { it.name == targetName }

                            if (matches.isNotEmpty()) {
                                foundResults.update { it.addAll(matches) }
                            } else if (foundResults.value.isEmpty()) {
                                searchEngine.addAll(filesToProcess)
                            }
                        }

                    } catch (e: IOException) {
                        // Silently ignore access denied or broken symlinks
                    } finally {
                        // Accurately track termination inside the finally block
                        if (activeTasks.decrementAndGet() == 0) {
                            workQueue.close()
                        }
                    }
                }
            }
        }

        // Seed the initial path
        workQueue.trySend(startPath)
        workers.joinAll()
    }

    val results = foundResults.value

    t.println(HorizontalRule(ruleStyle = gray))
    t.println(infoStyle("Elapsed time:        ") + white(duration.toString()))
    t.println(infoStyle("Scanned directories: ") + white(visitedPaths.value.size.toString()))
    t.println()

    if (results.isNotEmpty()) {
        t.println(successStyle("Exact matches found: ${results.size}"))
        results.forEach { path ->
            t.println(successStyle(" -> ") + white(path.toString()))
        }
    } else {
        searchEngine.find(targetName)
        val matches = searchEngine.results.value
        t.println(yellow("No exact match found. Possible suggestions (${matches.size}):"))
        t.println()

        t.println(TextStyles.bold("Match Legend:"))
        t.println("  " + brightYellow("■") + " Exact prefix/solid match")
        t.println("  " + yellow("■") + " Floating N-gram match")
        t.println("  " + cyan("■") + " Skip-gram / Fuzzy bridge")
        t.println("  " + gray("■") + " Unmatched character")
        t.println()

        matches.forEach { (path, score) ->
            val highlightedName = targetName.toHeatmap(path.name).toHighlightedString(path.name)
            t.println("  " + highlightedName + TextStyles.dim(" (score: ${score})"))
            t.println(pathStyle("  └─ $path"))
            t.println()
        }

        t.println(HorizontalRule(ruleStyle = gray))
        t.println(TextStyles.italic("Tip: If you are looking for a specific file, use its full name."))
    }
}