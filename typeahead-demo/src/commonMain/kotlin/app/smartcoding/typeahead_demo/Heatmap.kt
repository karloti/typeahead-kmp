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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import kotlinx.collections.immutable.ImmutableList

@Composable
fun Heatmap(
    heatmap: ImmutableList<Pair<Char, Int>>,
    modifier: Modifier = Modifier.Companion,
    fontFamily: FontFamily? = null,
) {
    val colors = listOf(
        MaterialTheme.colorScheme.onBackground,//.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.error,
    )

    val annotatedString = remember(heatmap, colors) {
        buildAnnotatedString {
            heatmap.forEach { (char, i) -> withStyle(SpanStyle(colors[i + 1])) { append(char) } }
        }
    }
    Row(
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Text(
            modifier = modifier,
            text = annotatedString,
            fontFamily = fontFamily,
        )
    }
}