# Contributing to Typeahead KMP

First off, thank you for considering contributing to `typeahead-kmp`! It's people like you that make the open-source community thrive.

This engine is designed to be a high-performance, lock-free, and asynchronous fuzzy search tool for Kotlin Multiplatform. We welcome contributions that improve its efficiency, add platform support, or fix bugs.

## How to Contribute

### 1. Reporting Bugs & Proposing Features
Before writing any code, please **open an issue**.
Discussing your idea or the bug you've found helps ensure that your effort aligns with the project's architecture, especially considering the complex concurrency and vectorization logic involved.

### 2. Local Development
To set up the project locally for development:

* Fork the repository and clone it to your local machine.
* Open the project in IntelliJ IDEA or Android Studio.
* This project requires JVM 11 as the target.
* Sync the Gradle project.

To run the test suite across platforms and verify your environment:
```bash
./gradlew clean check
```
### 3. Making Changes
   When writing your code, please keep the following in mind:

- **Concurrency**: The core of `TypeaheadSearchEngine` relies heavily on coroutines and `StateFlow` for lock-free reads. Ensure any new logic respects this thread-safe architecture.

- **Testing**: If you add a new feature or fix a bug, include a corresponding test in `commonTest`. Concurrency tests (like testing multiple writers simultaneously) are highly encouraged.

- **Language**: Write all comments, commit messages, and KDoc documentation in English.

- **Style**: Follow the standard Kotlin coding conventions.

### 4. Submitting a Pull Request
- Create a new branch for your feature or bugfix (e.g., `feature/custom-embeddings` or `fix/memory-leak`).

- Push your changes to your fork.

- Open a Pull Request against the `main` branch of this repository.

- Provide a clear description of what your PR does and link to the relevant Issue.

Once submitted, your code will be reviewed, and we might suggest some tweaks before merging. Thank you for your time and effort!