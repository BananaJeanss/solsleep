# Dependency inventory

The version catalog and wrapper properties are the canonical pins. This is the
initial direct-dependency inventory; an exhaustive transitive SBOM and release
notice audit remain M0/release tasks. Nothing in this list grants optional vendor
service access or changes the app's GPL license.

| Family | Version policy | Purpose | Upstream license/source |
| --- | --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | Android packaging/compiler integration | Apache-2.0; Android tools |
| Gradle wrapper | 9.7.1, distribution SHA-256 pinned | Reproducible build entry point | Apache-2.0; gradle/gradle |
| Kotlin / Compose compiler | 2.4.21 | JVM and Compose compilation | Apache-2.0; JetBrains/kotlin |
| Compose UI / Foundation | BOM 2026.09.00 | Native UI and semantics | Apache-2.0; AndroidX |
| Material 3 | Explicit 1.5.0-beta01 | Expressive theme/components | Apache-2.0; AndroidX |
| Activity / Lifecycle / Core | Catalog pins | Android entry points, state, compatibility | Apache-2.0; AndroidX |
| kotlinx.coroutines | 1.11.0 | Structured asynchronous UI operations | Apache-2.0; Kotlin/kotlinx.coroutines |
| kotlinx.serialization JSON | 1.11.0 stable | Shared logical alarm/archive format | Apache-2.0; Kotlin/kotlinx.serialization |
| JUnit 4 | 4.13.2, test-only | Domain and instrumentation assertions | EPL-1.0; junit-team/junit4 |
| AndroidX Test / Espresso | Runner 1.7.0, JUnit extension 1.3.0, Espresso 3.7.0 | Real Android/UI tests | Apache-2.0; android/android-test |
| Android robot derivative | Editable SVG/vector paths | Original sleeping pose and themed icon | CC BY 3.0; Google attribution in NOTICE |
| Dawn chime | Reproducible sine-wave script | Bundled local alarm output | Original SolSleep source; GPL-3.0-or-later |

Expressive is the intentional beta dependency; its APIs are kept in the UI layer.
Core alarm policy and archive validation do not depend on Compose or Android.
The debug-only motion probe adds platform APIs, not an external tracking SDK.
No proprietary sleep, sound classifier, cloud, advertising, analytics or image
service SDK is included.

Before distributing a production APK, resolve and record its exact transitive
runtime artifacts, retain required license/notice text and source obligations,
audit the final merged manifest and signing provenance, and build from a clean
checkout. Do the same for test APK redistribution. Automated correctness checks
and version pins do not establish the complete license inventory by themselves.
