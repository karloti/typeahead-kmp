![Typeahead KMP Preview](assets/typeahead.png)
# Typeahead KMP 🚀

A high-performance, asynchronous, and lock-free in-memory fuzzy search engine for Kotlin Multiplatform (KMP).

Unlike standard search algorithms that fail during real-time typing (typeahead), `typeahead-kmp` is specifically designed to understand the **"Blind Continuation" phenomenon**—where users make an early typo but intuitively continue typing the rest of the word correctly.

Powered by a custom **L2-Normalized Sparse Vector Space** algorithm, it acts as a highly optimized, local vector database. It provides `O(1)` lookup times while gracefully handling skipped characters, swapped letters, and phonetic typos, yielding a Cosine Similarity score between `0.0` and `1.0`.

---

## 🛠️ The Evolution: Why standard algorithms fail

Building a perfect typeahead engine is notoriously difficult. During the development of this library, we evaluated and discarded several standard approaches because they fundamentally misalign with human typing behavior.

### The Problem with Server-Side Giants (Algolia, Typesense)
While engines like Algolia and Typesense are industry standards for massive databases, they require network requests. In mobile or web front-ends, **network latency kills the instant "typeahead" feel**. `typeahead-kmp` brings vector-search intelligence directly to the **Edge** (the user's device memory), ensuring zero-latency, offline-capable search capabilities.

### The Problem with Traditional Math
| Algorithm | Prefix Sensitivity | Typo Tolerance | Length Penalty | Blind Continuation | Performance |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Levenshtein Distance** | ❌ Poor | ✅ Good | ❌ Aggressive | ❌ Poor | `O(N*M)` |
| **Standard N-Grams** | ❌ Poor | ✅ Good | ✅ Minimal | ✅ Good | `O(1)` |
| **Typeahead KMP (Ours)**| **✅ Excellent** | **✅ Excellent** | **✅ Dynamic** | **✅ Excellent**| **`O(1)`** |

---

## 🧠 Handling "The 4 Horsemen of Typing Errors"

Our mathematical model is explicitly designed to combat the four most common human typing errors in real-time:

1. **Transposition (Swapped letters):** Typing `Cna` instead of `Can...` (Canada). Traditional strict-prefix trees drop the result immediately. We use *Fuzzy Prefixes* to catch anagrammatic mistakes early.
2. **Deletion (Missed letters):** Typing `Cnad` instead of `Canad...`. Handled via overlapping *Skip-Grams* that bridge the gap.
3. **Insertion (Extra letters):** Typing `Caxnada`. Skip-grams naturally step over the accidental keystroke.
4. **Substitution (Wrong letters):** Typing `Csmada`. Handled by the sheer momentum of subsequent floating N-grams.

---

## 🔬 Under the Hood: The Vector Architecture

When you add an item to `typeahead-kmp`, it isn't just stored; it is mathematically tokenized into a **Sparse Vector** and **L2-Normalized**.

Key features extracted during vectorization:
* **P0 Anchor:** Absolute prioritization of the first letter (rarely mistyped).
* **Typoglycemia Gestalt Anchor:** Evaluates words based on matching length, first, and last letters. It creates a massive mathematical bridge for 1-character typos (e.g., distinguishing `Cnad` aiming for `Chad` vs `Canada`).
* **Strict & Fuzzy Prefixes:** Dynamically sorts prefix characters to tolerate transpositions without breaking the search momentum.
* **Skip-Grams & Floating N-Grams:** Generates a structural skeleton of the word with linearly progressive weights.
* **Cosine Similarity:** By L2-normalizing the vectors at insertion time, the `find()` operation is reduced to a lightning-fast dot product calculation, completely bypassing heavy floating-point divisions during the critical search loop.

---

## 📦 Installation

Available on Maven Central (or JitPack). Add the repository to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    // or maven("[https://jitpack.io](https://jitpack.io)")
}
```

Add the dependency to your module:

```kotlin
dependencies {
    implementation("io.github.karloti:typeahead-kmp:1.2.4") // replace with latest version
}
```

## ⌨️ Real-World Typing Simulation: The "Cnada" Problem

To truly understand the power of `typeahead-kmp`, let's look at a real-time keystroke simulation.
Imagine a user is trying to type **"Canada"**, but they accidentally type **"Cnada"** (a classic transposition error).

Here is how the engine's internal mathematical weighting dynamically reacts at each keystroke in `O(1)` time:

### Step 1: Initial Input (L2 Normalization & Short-Word Bias)
At this early stage, the user types `C` and then `Cn`. The `P0` (First Letter) anchor heavily restricts the search space. Because the input is extremely short, **L2 Normalization** naturally favors shorter words (Short-Word Bias). This brings 4-letter countries like `Cuba` and `Chad` to the top. By the second keystroke, `Canada` barely enters the top 5.

```kotlin
=== Typing: 'C' with typing error of 'Cnada' ===
1. Cuba - Score: 0.19181583900475285
2. Chad - Score: 0.19181583900475285
3. China - Score: 0.14776063566992276
4. Chile - Score: 0.14776063566992276
5. Cyprus - Score: 0.11811359847672041

=== Typing: 'Cn' with typing error of 'Cnada' ===
1. Cuba - Score: 0.10297213760008117
2. Chad - Score: 0.10297213760008117
...
5. Canada - Score: 0.07255630308706752
```

### Step 2: Transposition Recovery (Fuzzy Prefix)
The user meant `Can` but typed `Cna`. A strict-prefix algorithm would drop "Canada" entirely at this exact moment. Our **Fuzzy Prefix** dynamically anchors the first letter (`C`) and alphabetically sorts the remaining characters (`a`, `n`). Both the input `Cna` and the target `Can` generate the exact same spatial feature (`FPR_c_an`). `Canada` instantly rockets to the #1 spot!

```kotlin
=== Typing: 'Cna' with typing error of 'Cnada' ===
1. Canada - Score: 0.14257617990546595 <-- Rockets to #1 via Fuzzy Prefix intersection!
2. Chad - Score: 0.08281542504942256
3. Cuba - Score: 0.07409801188632545
4. China - Score: 0.06757216102651037
5. Chile - Score: 0.05707958943854292
```

### Step 3: Spellchecker Takeover (Typoglycemia Gestalt)
The user types `d`. The engine momentarily switches from "Typeahead Mode" to "Spellchecker Mode" via the **Typoglycemia Gestalt Anchor**. It detects a 4-letter word starting with `C` and ending with `d`. The algorithm mathematically assumes the user is actively trying to spell `Chad` and applies a massive 15.0 spatial intersection multiplier to that specific vector, temporarily overtaking `Canada`.

```kotlin
=== Typing: 'Cnad' with typing error of 'Cnada' ===
1. Chad - Score: 0.1853988462303561 <-- Massive spike due to Gestalt anchor (C...d)!
2. Canada - Score: 0.1278792484954006
3. Cuba - Score: 0.07957032027053908
4. China - Score: 0.04934251382749997
5. Chile - Score: 0.04168063279838507
```

### Step 4: Final Resolution (Skip-Grams & N-Grams)
The final `a` is typed (length 5). The Gestalt anchor for `Chad` (length 4) completely breaks. The engine reverts to deep structural analysis. Overlapping Skip-Grams seamlessly bridge the transposed letters (`C-n-a-d-a`). This structural skeleton perfectly aligns with the core features of `Canada`, accumulating a massive dot-product score that completely overcomes the length penalty. `Canada` firmly reclaims the #1 spot!

```kotlin
=== Typing: 'Cnada' with typing error of 'Cnada' ===
1. Canada - Score: 0.2563201621199545 <-- Reclaims the lead via deep structural sequence momentum!
2. China - Score: 0.10623856459894943
3. Chad - Score: 0.05424611768613351
4. Grenada - Score: 0.04955129623022677
5. Chile - Score: 0.047217139821755294
```

**This dynamic, keystroke-by-keystroke shifting between prefix-matching, gestalt spellchecking, and sequence momentum—all happening in `O(1)` time without memory allocations—is what makes `typeahead-kmp` uniquely powerful for human-driven inputs.**

## 💻 Usage
The library behaves like an asynchronous, thread-safe, mutable collection.

### 1. Basic Setup & Searching
```kotlin
import io.github.karloti.typeahead.TypeaheadSearchEngine
import kotlinx.coroutines.launch

// 1. Define your domain model
data class City(val id: String, val name: String)

val cities = listOf(City("1", "Sofia"), City("2", "Plovdiv"), City("3", "Varna"))

// 2. Initialize the engine by providing a selector lambda
val searchEngine = TypeaheadSearchEngine<City>(textSelector = { it.name })

coroutineScope.launch {
    // 3. Batch load your data (Utilizes all CPU cores for parallel vectorization)
    searchEngine.addAll(cities)

    // 4. Search with a typo
    val results = searchEngine.find("Plovdvi", maxResults = 5)

    results.forEach { (city, score) ->
        println("Found: ${city.name} (Score: $score)") 
        // Score is a Cosine Similarity Double between 0.0 and 1.0
    }
}
```

### 2. State Persistence (Import / Export)
Calculating vector embeddings for tens of thousands of items is CPU-intensive. To prevent `OutOfMemory` (OOM) errors and speed up app startup, the engine supports **Stream-based Serialization** using Kotlin `Sequence`.

You are in full control of the actual byte-serialization (JSON, ProtoBuf, etc.).

```kotlin
// --- EXPORTING STATE ---
// Export the pre-computed vectors lazily to a file or database
val fileWriter = File("vectors.json").bufferedWriter()
searchEngine.exportAsSequence().forEach { record ->
    val jsonLine = myJsonSerializer.toJson(record)
    fileWriter.write(jsonLine + "\n")
}
fileWriter.close()

// --- IMPORTING STATE ---
// Restore the engine instantly without recalculating vectors
val sequenceOfRecords = File("vectors.json").useLines { lines ->
    lines.map { myJsonSerializer.fromJson<TypeaheadRecord<City>>(it) }
}
searchEngine.importFromSequence(sequenceOfRecords)
```

## 📊 Performance & Test Stability
The engine is built on top of a custom `BoundedConcurrentPriorityQueue` utilizing lock-free Compare-And-Swap (CAS) atomic operations. It handles thousands of concurrent reads/writes without dropping frames.

**Test Outputs:**

```prototext
Starting aggressive multi-threading test...
All threads finished. Running exact verification...
✅ Ultimate Thread-safety and Accuracy test passed perfectly!
✅ Export/Import sequence and vector integrity test passed perfectly!

...
Found country: 'Bulgaria' with score: 0.24341542124553966
Found country: 'Burundi' with score: 0.11166355040347513
Found country: 'Burkina Faso' with score: 0.09122675301352591
Total indexed countries: 194
```

## 🚀 Tracking & Roadmap
We use YouTrack for task management and issue tracking.
You can view the current tasks and progress here:
[Typeahead KMP Issues & Roadmap](https://smartcoding.youtrack.cloud/projects/typeahead_kmp)

## 📄 License
This project is licensed under the **Apache License Version 2.0** - see the LICENSE file for details.