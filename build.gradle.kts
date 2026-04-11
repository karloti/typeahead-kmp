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

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.jetbrains.kotlinMultiplatform) apply  false
    alias(libs.plugins.jetbrains.serialization) apply  false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.android.application) apply false
//    alias(libs.plugins.android.kmpLibrary) apply false
    alias(libs.plugins.jetbrains.composeHotReload) apply false
    alias(libs.plugins.jetbrains.composeMultiplatform) apply false
    alias(libs.plugins.jetbrains.composeCompiler) apply false
    alias(libs.plugins.jetbrains.kotlinJvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.google.devtoolsKsp) apply false
//    alias(libs.plugins.stability.analyzer) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.google.firebase.crashlytics) apply false

}
