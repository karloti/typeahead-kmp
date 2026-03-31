# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`typeahead-kmp` is a Kotlin Multiplatform fuzzy/typeahead search engine library published to Maven Central. It targets JVM, Android, iOS, tvOS, watchOS, macOS, Linux, Windows (mingwX64), JavaScript, and WebAssembly (wasmJs, wasmWasi).

**Group:** `io.github.karloti` | **Artifact:** `typeahead-kmp` | **Current version:** see `shared/build.gradle.kts`

## Build & Test Commands

```bash
# Full verification (run before submitting changes)
./gradlew clean check

# Run all tests across all targets
./gradlew allTests

# Run tests for specific platforms
./gradlew jvmTest
./gradlew jsTest
./gradlew wasmJsTest
./gradlew wasmWasiNodeTest
./gradlew androidDeviceCheck
```

To run a single test class on JVM: `./gradlew jvmTest --tests "TypeaheadSearchEngineTest"`

## Architecture

### Core Data Flow

```
String input → toPositionalEmbedding() → SparseVector
                                            ↓
TypeaheadSearchEngine._embeddings (AtomicRef<PersistentMap<String, PersistentMap<T, SparseVector>>>)
                                            ↓
find(query) → query SparseVector · stored SparseVectors → BoundedConcurrentPriorityQueue → List<Pair<T, Float>>
                                            ↓
_results: MutableStateFlow  /  _highlightedResults: MutableStateFlow
```

### Key Classes

**`TypeaheadSearchEngine<T, K>`** (`shared/src/commonMain/kotlin/`) — Main entry point. Holds all indexed embeddings in a lock-free `AtomicRef<PersistentMap>`. Exposes `results` and `highlightedResults` as `StateFlow` for reactive UIs. The constructor accepts weight parameters for the 8 feature types and a text/key selector.

**`SparseVector`** — Alphabetically sorted `Array<String>` of feature names paired with a `FloatArray` of L2-normalized weights. The sorted order enables O(K) two-pointer cosine similarity via `dotProduct()`.

**`TypeaheadRecord<T>`** — Serializable snapshot of `(item, SparseVector)` used for fast import/export (bypasses re-vectorization).

**`HighlightedMatch<T>`** — Search result with a character-level `IntArray` heatmap (4 tiers: TIER_PRIMARY, TIER_SECONDARY, TIER_TERTIARY, TIER_NONE) for UI highlighting.

**`computeHeatmap.kt`** — Three-phase greedy alignment (Exact → N-Grams → Skip-Grams) producing the heatmap `IntArray`.

### Embedding Feature Types

`toPositionalEmbedding()` extracts 8 feature categories from input strings: P0 Anchors, Length Buckets, Gestalt Anchors, Fuzzy Prefixes, Skip-Grams, N-Grams, plus positional and floating n-grams. Each has a corresponding weight constant tunable via the constructor.

### Concurrency Model

- All embeddings stored in `kotlinx.collections.immutable.PersistentMap` (HAMT) via `AtomicRef` — lock-free reads and CAS-based writes.
- Batch insertions use `flatMapMerge(concurrency = 16)` with back-pressure.
- Results are emitted via `MutableStateFlow` — safe for concurrent collection from multiple coroutines.
- Uses `atomicfu` for atomic primitives across all platforms.

### Import/Export

Use `exportAsSequence()` / `importFromSequence()` to snapshot and restore the search engine state without re-vectorizing. This is ~50× faster than re-inserting items (9ms vs 419ms for 10k records).

## Module Layout

- `shared/` — The library module; all platform source sets live here.
- `samples/sample-jvm/` — Runnable JVM demo showing basic API usage.
- Version catalog: `gradle/libs.versions.toml`

## Key Constraints

- Minimum Android API: 24; minimum JVM target: 11.
- Publishing to Maven Central requires signed publications — `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, and signing keys must be configured.
- The `TypeaheadSearchEngineNew.kt` file in `commonMain` is currently staged as deleted (`AD` in git status) — do not restore it.
