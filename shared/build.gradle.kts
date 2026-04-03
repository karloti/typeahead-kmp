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

@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)
//@file:Suppress("UnstableApiUsage")

//import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
//import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.karloti"
val projectVersion = "1.7.3" // todo
version = projectVersion

kotlin {
    // JVM
    jvm()

    // Android
    android {
        namespace = "io.github.karloti.typeahead"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compileTaskProvider.configure {
            }
        }
    }

    // iOS
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // tvOS
    tvosArm64()
    tvosSimulatorArm64()

    // watchOS
    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()

    // macOS
    macosArm64()

    // Android Native
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    // Windows
    mingwX64 {
        binaries {
            executable {
                entryPoint = "main"
                runTask?.apply {
                    args(
                        listOf(
                            "--path",
                            "C:\\Users\\skarl\\AndroidStudioProjects\\RealRate",
                            "--name",
                            "shared.exe"
                        )

                    )
                }
            }
        }
    }

    // Linux
    linuxArm64()
    linuxX64()

    // JavaScript
    js {
        browser {
            testTask {
                useMocha {
                    timeout = "60000"
                }
            }
        }
    }

    // WebAssembly
    wasmJs {
        browser {
            testTask {
                useMocha {
                    timeout = "60000"
                }
            }
        }
//        nodejs()
    }

    wasmWasi {
        // To build distributions for and run tests use one or several of:
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.karloti.concurrent.priority.queue)
            implementation(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        mingwX64Main.dependencies {
            implementation(libs.squareup.okio)
            implementation("com.github.ajalt.mordant:mordant:3.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
        }

        linuxX64Main.dependencies {
            implementation(libs.squareup.okio)
            implementation("com.github.ajalt.mordant:mordant:3.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
        }
    }
}

mavenPublishing {    publishToMavenCentral(automaticRelease = true)


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

tasks.withType<Test> {
    maxHeapSize = "4g"
    testLogging {
        showStandardStreams = true
    }
}

/*
tasks.register("createMissingSourceDirs") {
    group = "setup"
    description = "Creates all missing source set directories for Kotlin Multiplatform"

    doLast {
        kotlin.sourceSets.forEach { sourceSet ->
            sourceSet.kotlin.srcDirs.forEach { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
        }
    }
}
*/
