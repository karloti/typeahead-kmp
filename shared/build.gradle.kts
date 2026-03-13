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

@file:OptIn(ExperimentalWasmDsl::class)
@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.karloti"
val projectVersion = "1.4.1"
version = projectVersion

kotlin {
    jvm()
    androidLibrary {
        namespace = "io.github.karloti.typeahead"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    js {
        browser {
            testTask {
                useMocha {
                    timeout = "60000"
                }
            }
        }
    }

    wasmJs {
        browser {
            testTask {
                useMocha {
                    timeout = "60000"
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "typeahead-kmp", projectVersion)

    pom {
        name = "Typeahead KMP"
        description = "A high-performance, lock-free, asynchronous fuzzy search engine for Kotlin Multiplatform."
        inceptionYear = "2026"
        url = "https://github.com/karloti/typeahead-kmp"
        licenses {
            license {
                name = "Apache License Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "karloti"
                name = "Kaloyan Karaivanov"
                url = "https://github.com/karloti"
            }
        }
        scm {
            url = "https://github.com/karloti/typeahead-kmp"
            connection = "scm:git:git://github.com/karloti/typeahead-kmp.git"
            developerConnection = "scm:git:ssh://github.com/karloti/typeahead-kmp.git"
        }
    }
}

/*
extensions.configure<SigningExtension> {
    useGpgCmd()
    sign(publishing.publications)
}*/
