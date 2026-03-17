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
@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.karloti"
val projectVersion = "1.6.0"
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
    macosX64()
    macosArm64()
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
    linuxX64 {
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
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.squareup.okio)
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        mingwX64Main.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
        }

        linuxX64Main.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
        }
        nativeMain.dependencies {
            implementation("com.github.ajalt.mordant:mordant:3.0.2")
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
}*/
