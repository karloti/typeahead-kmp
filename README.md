# Typeahead KMP 🚀

A high-performance, asynchronous, and lock-free in-memory fuzzy search engine for Kotlin Multiplatform (KMP).

Unlike standard search algorithms that fail during real-time typing (typeahead), `typeahead-kmp` is specifically designed to understand the **"Blind Continuation" phenomenon**—where users make an early typo but intuitively continue typing the rest of the word correctly.

Powered by a custom **Hybrid Positional Embedding** algorithm, it acts as a highly optimized vector database that provides `O(1)` lookup times while gracefully handling skipped characters, swapped letters, and phonetic typos.

---

## 🛠️ The Evolution: Why standard algorithms fail

Building a perfect typeahead engine is notoriously difficult. During the development of this library, we evaluated and discarded several standard approaches because they fundamentally misalign with human typing behavior.

Here is a comprehensive comparison of how different algorithms perform in a typeahead context:

| Algorithm | Prefix Sensitivity | Typo Tolerance | Length Penalty | Blind Continuation | Performance |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Levenshtein Distance** | ❌ Poor | ✅ Good | ❌ Aggressive | ❌ Poor | `O(N*M)` |
| **Standard LCS** | ❌ Poor | ⚠️ Moderate | ✅ Minimal | ❌ Poor | `O(N*M)` |
| **Index-Weighted LCS** | ✅ Excellent | ❌ Poor | ✅ Minimal | ❌ Poor | `O(N*M)` |
| **Standard N-Grams** | ❌ Poor | ✅ Good | ✅ Minimal | ✅ Good | `O(1)` |
| **Typeahead KMP (Ours)**| **✅ Excellent** | **✅ Excellent** | **✅ Minimal** | **✅ Excellent** | **🚀 O(1)** |

### Approach 1: Levenshtein (Edit) Distance
Calculates the minimum number of single-character edits (insertions, deletions, substitutions) required to change one word into another.
* **The Flaw:** It heavily penalizes length differences. If a user types `"bu"`, the distance to `"Bulgaria"` is `6` (6 missing letters). The distance to `"Bux"` is only `1`. Consequently, irrelevant short words outrank highly relevant long words during the early stages of typing.

### Approach 2: Standard Longest Common Subsequence (LCS)
Finds the longest sequence of characters that appear left-to-right in both strings, ignoring gaps.
* **The Flaw:** It lacks positional awareness (Prefix Sensitivity). The sequence `"a"` is treated equally whether it appears at the beginning of `"Apple"` or at the end of `"Banana"`. It fails to prioritize words that *start* with the user's input.

### Approach 3: Index-Weighted LCS (Our Early Attempt)
To fix the prefix issue of standard LCS, we experimented with assigning weights based on the character's index (e.g., `Score = 100 + 100/index`). Matches at index `0` yielded massive points.
* **The Flaw (The Index Shift Problem):** It completely failed at handling insertions or early typos. If a user typed `"Buelgaria"` (inserting a wrong `e`), the absolute index of all subsequent correct characters (`l`, `g`, `a`, `r`) shifted by +1. This index misalignment destroyed the score for the rest of the word, penalizing the user for "blindly continuing" to type the correct letters.

### Approach 4: Standard Character N-grams
Breaks words into sub-strings (e.g., `"bul"` becomes `b`, `u`, `l`, `bu`, `ul`, `bul`).
* **The Flaw:** It is fast (`O(1)` using vector math) but completely loses global positional context. Typing `"bul"` will reward `"Bulgaria"` and `"Istanbul"` equally because the floating chunk exists in both.



---

## 💡 Our Solution: Hybrid Positional Embeddings

To simulate human cognition during typing and fix all the flaws mentioned above, `typeahead-kmp` converts every string into a mathematical **Sparse Vector** composed of three distinct types of features:

1. **Absolute Positional Anchors (Prefix Bonus):** Rewards characters that match the exact starting indices (e.g., `P0_b`, `P1_u`). This guarantees that words *starting* with the query always rank at the top, fixing the flaw of Standard N-Grams.
2. **Skip-Grams (Typo Bridging):** Generates 1-gap patterns (e.g., `S_b_l` from `bul`). If a user types `"bol"`, the skip-gram `S_b_l` perfectly matches the `S_b_l` in `"Bulgaria"`, mathematically bridging the typo.
3. **Floating Momentum (Sequence Multiplier):** Extracts floating 2-grams, 3-grams, and 4-grams with **quadratic weights**. If a user makes an early typo but continues typing correctly (`"Buelgaria"`), the unbroken momentum of `"lgar"`, `"gari"`, and `"aria"` accumulates massive exponential points, easily crushing the early penalty and fixing the "Index Shift" problem of our early Weighted LCS attempt.

## 📊 Real-World Typing Simulation (Test Output)

Notice how the algorithm gracefully recovers from human errors as the user continues typing:

### Scenario 1: Inserted Letter (`Buelgaria` instead of `Bulgaria`)
The user mistakenly hits `e` instead of `u`. The sequence breaks, but recovers flawlessly.
```kotlin
=== Typing: 'Buelg' with typing error of 'Buelgaria' ===
1. Belgium - Score: 238.0
2. Bulgaria - Score: 213.0

=== Typing: 'Buelga' with typing error of 'Buelgaria' ===
1. Bulgaria - Score: 335.0  <-- Momentum kicks in, taking the lead!
2. Belgium - Score: 238.0
```
### Scenario 2: Missing Letter (Cnada instead of Canada)
Even with a missing vowel, the engine identifies the remaining contiguous sequence and corrects the trajectory immediately.

```kotlin
=== Typing: 'Cnad' with typing error of 'Cnada' ===
1. Chad - Score: 254.0
2. Canada - Score: 238.0

=== Typing: 'Cnada' with typing error of 'Cnada' ===
1. Canada - Score: 641.0  <-- Shoots to 1st place!
2. Grenada - Score: 552.0
```

### Scenario 3: Swapped Letters (Greman instead of Germany)
```kotlin
=== Typing: 'Grem' with typing error of 'Gremany' ===
1. Grenada - Score: 383.0
2. Greece - Score: 383.0
3. Germany - Score: 149.0

=== Typing: 'Greman' with typing error of 'Gremany' ===
1. Grenada - Score: 444.0
2. Greece - Score: 383.0
3. Germany - Score: 348.0 <-- Rapidly catching up due to skip-grams!
```

## ✨ Features
- ⚡ Lightning Fast: Precomputes embeddings during initialization. Searching relies on sparse vector dot products, making it practically instantaneous regardless of dataset size (O(1) matching).

- 🛡️ Thread-Safe & Lock-Free: Built on top of Kotlin's StateFlow and atomic Compare-And-Swap (CAS) operations. Multiple threads can read/search concurrently while items are being added/removed without blocking or throwing ConcurrentModificationException.

- 🧬 Generic Architecture: Not limited to plain strings. You can store any complex Object or DTO and simply provide a lambda to extract the searchable text.

- 🌍 Kotlin Multiplatform: 100% pure Kotlin. Works seamlessly on Android, iOS, JVM, Desktop, and Web.

## 📦 Installation
Add the JitPack repository to your root settings.gradle.kts (or build.gradle.kts for older projects):
 
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
    implementation("io.github.karloti:typeahead-kmp:1.0.1")
}
```
## 💻 Usage
The library behaves like an asynchronous, thread-safe, mutable collection.

```kotlin
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.launch

// 1. Define your complex domain model
data class City(
    val id: String,
    val name: String,
    val population: Int
)

val cities = listOf(
    City("1", "Sofia", 1200000),
    City("2", "Plovdiv", 340000),
    City("3", "Varna", 330000)
)

// 2. Initialize the engine by providing a selector lambda
val searchEngine = TypeaheadSearchEngine<City>(textSelector = { it.name })

coroutineScope.launch {
    // 3. Batch load your data (Utilizes all CPU cores for parallel vectorization)
    searchEngine.addAll(cities)

    // 4. Search with a typo (e.g., "Plovdvi" instead of "Plovdiv")
    val results: List<Pair<City, Double>> = searchEngine.find("Plovdvi", maxResults = 5)

    results.forEach { (city, score) ->
        println("Found: ${city.name} (Score: $score)")
    }

    // 5. Mutate the collection dynamically!
    // You can add or remove items on the fly without breaking ongoing searches.
    searchEngine.add(City("4", "Burgas", 200000))
    searchEngine.remove(cities[0])
}
```
## 📜 License
This project is licensed under the Apache License Version 2.0 - see the LICENSE file for details.