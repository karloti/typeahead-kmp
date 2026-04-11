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

import kotlinx.coroutines.flow.Flow
import org.w3c.files.File

expect fun streamLocalFile(file: File, chunkSize: Int = 1024 * 1024): Flow<String>
expect fun pickLocalFileAndStream(onStreamReady: (Flow<String>) -> Unit)
expect fun streamDemoFile(filePath: String): Flow<String>