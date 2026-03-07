# Typeahead KMP

A high-performance, asynchronous, and lock-free fuzzy search engine for Kotlin Multiplatform (KMP). 

Unlike standard Levenshtein distance algorithms that penalize length differences, or basic N-gram vectorizers that lose positional context, `typeahead-kmp` uses a **hybrid positional embedding algorithm**. It perfectly simulates human typing behavior by rewarding exact matches, bridging typographical errors (skip-grams), and boosting sequence momentum—all while searching in `O(1)` time per record.

## Features
* ⚡ **Lightning Fast:** Precomputes string embeddings for `O(1)` lookup performance.
* 🛡️ **Thread-Safe & Lock-Free:** Uses atomic Compare-And-Swap (CAS) operations via `StateFlow` for parallel querying without blocking threads.
* 🧠 **Human-Typing Aware:** Forgives common typos and emphasizes contiguous sequence matches (momentum).
* 🌍 **Kotlin Multiplatform:** 100% pure Kotlin. Works on Android, iOS, JVM, Desktop, and Web.

## Installation

Add the JitPack repository to your root `settings.gradle.kts` or `build.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("[https://jitpack.io](https://jitpack.io)")
    }
}
```

Add the dependency to your module:

```kotlin
dependencies {
    implementation("io.github.karloti:typeahead-kmp:1.0.0")
}
```

## Usage

The TypeaheadSearchEngine uses a suspend factory method to ensure background initialization without blocking the main thread.

```kotlin
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.launch

// 1. Initialize the engine with your data corpus
val corpus = listOf("Bulgaria", "Bolivia", "Belgium", "Burundi")

coroutineScope.launch {
    // This distributes the vectorization workload across available CPU cores
    val searchEngine = TypeaheadSearchEngine(corpus)
    
    // 2. Search for a query
    val results = searchEngine.search("bolgar", maxResults = 10)
    
    // Results will contain Pairs of String and their similarity score
    results.forEach { (text, score) ->
        println("$text : $score")
    }
}
```
## License
This project is licensed under the MIT License.


