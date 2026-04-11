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

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize
import io.github.karloti.typeahead.TypeaheadRecord
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.Dispatchers

val firebaseOptions = FirebaseOptions(
    apiKey = "AIzaSyBaFhBAaLW7gg89nyVfTcJ5Xav5i-12vX0",         // от JS: apiKey
    authDomain = "typeahead-kmp.firebaseapp.com",               // от JS: authDomain (Важно за Google Sign-In!)
    projectId = "typeahead-kmp",                                // от JS: projectId
    storageBucket = "typeahead-kmp.firebasestorage.app",        // от JS: storageBucket
    applicationId = "1:942294408844:web:1faa6dd9e0898fdeb665a9", // от JS: appId
    gaTrackingId = "G-7TF74DN0J2",                               // от JS: measurementId
    gcmSenderId = "942294408844"                               // от JS: messagingSenderId
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    Firebase.initialize(options = firebaseOptions)

    val typeaheadSearchEngine = TypeaheadSearchEngine(
        metadata = TypeaheadRecord.TypeaheadMetadata(
            ignoreCase = true,
            maxResults = 20,
            floatingWeight = 5f,

            prefixWeight = 1f,
            anchorWeight = 1.0f,
            fuzzyWeight = 2.0f

        ),
        textSelector = Pair<String, String>::second,
        keySelector = Pair<String, String>::first,
        defaultDispatcher = Dispatchers.Default,
    )

    ComposeViewport {
        viewModel { SearchViewModel(typeaheadSearchEngine) }
        App()
    }
}