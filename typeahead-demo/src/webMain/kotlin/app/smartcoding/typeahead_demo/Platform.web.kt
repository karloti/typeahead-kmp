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

@file:OptIn(ExperimentalWasmJsInterop::class)

package app.smartcoding.typeahead_demo

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.browser.document
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

actual fun streamLocalFile(file: File, chunkSize: Int): Flow<String> = callbackFlow {
    var offset = 0
    val fileSize = file.size.toInt()
    var leftover = ""
    val reader = FileReader()

    reader.onload = { _ ->
        launch {
            val textChunk = reader.result.toString()
            val lines = (leftover + textChunk).split("\n")
            leftover = lines.last()

            for (i in 0 until lines.size - 1) {
                send(lines[i].trimEnd('\r'))
            }

            offset += chunkSize
            if (offset < fileSize) {
                val slice = file.slice(offset, offset + chunkSize)
                reader.readAsText(slice)
            } else {
                if (leftover.isNotEmpty()) send(leftover.trimEnd('\r'))
                close()
            }
        }
        Unit
    }

    reader.onerror = { close(Exception("Грешка при четене на локалния файл")) }

    // Стартираме четенето на първия чанк
    val firstSlice = file.slice(offset, offset + chunkSize)
    reader.readAsText(firstSlice)

    awaitClose { /* Изчистване при канселиране на корутината */ }
}


// 2. Функция за UI: Отваря диалог и връща Flow-а чрез колбек
actual fun pickLocalFileAndStream(onStreamReady: (Flow<String>) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.accept = ".txt,.json,.csv"

    input.onchange = {
        val file = input.files?.get(0)
        if (file != null) {
            onStreamReady(streamLocalFile(file))
        }
        null
    }
    input.click() // Симулираме клик за отваряне на диалога
}

actual fun streamDemoFile(filePath: String): Flow<String> = flow {
    val client = HttpClient() // Добре е този клиент да бъде сингълтън в реално приложение
    try {
        // prepareGet отваря връзката, без да сваля целия файл веднага
        client.prepareGet(filePath).execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()

            while (!channel.isClosedForRead) {
                // Изчакваме следващия ред, без да блокираме UI нишката
                val line = channel.readLine()
                if (line != null) {
                    emit(line.trimEnd('\r'))
                }
            }
        }
    } finally {
        client.close()
    }
}