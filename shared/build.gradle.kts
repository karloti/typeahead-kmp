import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.karloti"
val projectVersion = "1.2.1"
//val projectVersion = project.findProperty("version")?.toString() ?: "1.0.2-SNAPSHOT"
version = projectVersion

kotlin {
    jvm()
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
        inceptionYear = "2024"
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
                name = "karloti"
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

extensions.configure<SigningExtension> {
    useGpgCmd()
    sign(publishing.publications)
}