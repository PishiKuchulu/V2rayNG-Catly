# Custom Location: final test and build report

**Verdict: BLOCKED.** Gradle cannot resolve the Android Gradle Plugin in this environment, so Android compilation, the
Gradle unit-test task, lint, regression runs and APK assembly were **not performed**. 160 JUnit tests were run **outside
Gradle** on a real JUnit Platform and all passed; they cover only the pure-JVM logic.

## 1. Task selection (from the build files, not assumed)

`app/build.gradle.kts`: `compileSdk 37`, `minSdk 24`, flavor dimension `distribution` with flavors **`playstore`** and **`fdroid`**,
build types `debug`/`release`, `testOptions.unitTests.all { useJUnitPlatform(); discovery.issue.severity.critical=WARNING }`.
Applicable tasks therefore are variant-specific: `testPlaystoreDebugUnitTest`, `testFdroidDebugUnitTest`,
`assemblePlaystoreDebug`, `lintPlaystoreDebug`. (Earlier documents named `testDebugUnitTest`/`lintDebug`; those were not
checked against the flavors and should not be relied on.)

## 2. Commands attempted and exact results

Run from `V2rayNG/` with Gradle 9.8.1 obtained from GitHub releases (the wrapper's own download host is unreachable):

| Command | Exit | Result |
|---|---|---|
| `./gradlew --version` | 1 | `Downloading https://services.gradle.org/distributions/gradle-9.8.1-bin.zip` then `java.net.UnknownHostException: services.gradle.org` |
| `gradle --no-daemon testPlaystoreDebugUnitTest` | 1 | `Plugin [id: 'com.android.application', version: '9.4.1', apply: false] was not found in any of the following sources` ... `BUILD FAILED` (26 s) |
| `gradle --no-daemon testFdroidDebugUnitTest` | 1 | same plugin-resolution failure (14 s) |
| `gradle --no-daemon assemblePlaystoreDebug` | 1 | same (14 s) |
| `gradle --no-daemon lintPlaystoreDebug` | 1 | same (13 s) |

Cause: Google Maven (`dl.google.com`), Maven Central (`repo.maven.apache.org`), the Gradle plugin portal and jitpack are all
unreachable; only github.com, npm, PyPI and the Ubuntu archive respond. Also absent: Android SDK (`ANDROID_HOME` unset) and
`V2rayNG/app/libs/libv2ray.aar` (and the `AndroidLibXrayLite` submodule content). Nothing was changed to work around this.

| Required verification | Result |
|---|---|
| Existing unit tests | **NOT RUN** (blocked) |
| New Custom Location unit tests through Gradle | **NOT RUN** (blocked) |
| Android compilation | **NOT RUN** (blocked) |
| Debug APK | **NOT PRODUCED** |
| Lint / static analysis | **NOT RUN** (blocked) |
| Regression checks of VPN connection and profile selection | **NOT RUN** |

## 3. What was executed instead (and its limits)

`tools/local_verification/run_tests.sh` compiles the pure-JVM sources
(`LocationProfile, GeoCatalog, LocationProfileCodec, LocationProfileRepository, LocationStatus, PrivacyAudit, CoordinateParser,
ExitLocation, CandidateScanner, ServiceState, ServiceGateway`) with the project's **unmodified test classes** against the
**real JUnit Jupiter API** and runs them with the **real JUnit Platform console launcher**. No assertion library or runner is simulated.

Differences from the project's real setup: JUnit **Jupiter 5.10.1 / Platform 1.9.1** (project pins **6.1.3**); Kotlin 2.1.21
(project's own); kotlinx-coroutines **1.0.1** from the Ubuntu archive (project uses a current release); no Android classes.

Last run, from a freshly extracted copy of the final ZIP:
`18 containers found/successful, 0 failed; 160 tests found, 160 successful, 0 failed, 0 skipped, 0 aborted.`

| Test class | Tests | Focus |
|---|---|---|
| `ServiceStateTest` | 6 | resolver: reply taken at face value; **timeout with live daemon = UNKNOWN**; **missing response / undetermined process = UNKNOWN**; **IPC failure = UNKNOWN** (even with a stale reply); STOPPED only with positive OS evidence |
| `GatewayConnectorTest` | 17 | snapshot running/stopped/unknown; scan refused before any change on UNKNOWN; correct call order; start failure/timeout; start callback not trusted (STOPPED/UNKNOWN after start); selection that did not take; selection changed by another actor (after start and during measurement); measurement failure mapping; conflicting/missing/invalid provider data; `observeActive` never starts/switches and sends nothing when stopped/unknown; restore confirmed by a fresh query, never claimed on failure, bounded stop polling, UNKNOWN never "stopped"; full scan restores the original server |
| `CandidateScannerTest` | 21 | sequential-only; failure mapping; restore on success/cancel/failed; journal before change; concurrent refusal; dedupe + cap; no silent selection; no IP retained; reclassification; **no-selected-server refusal; hung-candidate time box; whole-scan deadline; unknown-state stop; pending-journal refusal; shared-exit detection + salted fingerprints** |
| `LocationMatcherTest` | 16 | country/region/city, strictness matrix, unknown vs mismatch, IP masking |
| `LocationStatusEvaluatorTest` | 17 | states, expiry, warnings, GPS/tz/locale |
| `LocationProfileRepositoryTest` (+ journal) | 14 | persistence, backup, reset, key set, journal incl. unreadable-record-is-pending |
| `LocationProfileCodecTest` | 13 | round trip, corruption, migration, no observation persisted |
| `GeoCatalogTest` | 12 | ISO validation, parser, bundled asset |
| `PrivacyAuditTest` | 12 | configuration findings, HTTPS warning |
| `GeoIpResponseParserTest` | 10 | provider shapes, hostile input |
| `LocationProfileTest` | 10 | validation and edits |
| `CandidateResultTest` | 5 | TTL, selectability, ranking |
| `LocalizationConsistencyTest` | 4 | real `strings.xml` files: keys, placeholders, blanks, original keys intact |
| `CoordinateParserTest` | 3 | parsing |
| **Total** | **160** | |

**Defect-injection check (this task):** the service-state defect and the unconfirmed-restore defect were planted into the
sources; exactly the intended 5 tests failed (`ServiceStateTest` x2, `GatewayConnectorTest` x3, 151/156 passed at that
moment), and the sources were restored (156/156, later 160/160).

## 4. Other checks run (none is a build)

* **Type-check of `AndroidServiceGateway`** against the real Android API 33 jar with hand-written stubs of the upstream APIs it
  calls (signatures verified against upstream source) and a two-function shim for coroutine members that Ubuntu's 1.0.1 lacks:
  no errors. This catches typos/type errors in that file; it does not prove it builds in the project.
* **Mechanical cross-checks:** every `R.string.*` used is defined; every `cl_*` string is used; all 29 `CustomLocationAction`
  variants handled; every `when` over the new enums exhaustive; every upstream signature the new code calls matches upstream source.
* **Syntax pass** of all Android-bound files with `kotlinc`: only unresolved-reference cascades from absent Compose/AndroidX/project
  classes (883 of 1035 messages), which is uninformative; **not counted as verification**.
* **Static audits:** logging (only exceptions and counts), network primitives in new code (none), manifest diff, hard-coded left/right (none).

## 5. Not covered
`AndroidServiceGateway` at runtime (broadcast timing, daemon-process detection, restart sequencing), `CustomLocationViewModel`,
the Compose screen/Activity, `MockLocationController`, `NetworkChangeWatcher`, `RegionalSettingsAdapter`, `PrivacyAuditSource`,
`MmkvLocationStore`, upstream edits (`HttpUtil`, `SpeedtestManager`, `CoreServiceManager`, DTOs), JUnit 6.1.3 and Mockito-based
project tests, lint (`MissingTranslation`, RTL, `MockLocation`), instrumented tests, and all on-device behavior.

## 6. To finish verification (needs network access to Google/Maven)
```
cd V2rayNG
./gradlew testPlaystoreDebugUnitTest testFdroidDebugUnitTest
./gradlew assemblePlaystoreDebug assembleFdroidDebug
./gradlew lintPlaystoreDebug lintFdroidDebug
```
Then on a device: scan with 2+ servers from running and stopped start states; cancel mid-scan; kill the app mid-scan (restore/dismiss
banner); kill `:daemon` while stopped (state must read STOPPED) and while running; drop the network during a test; a `direct` routing
rule for the geolocation host (shared-address warning); rotate; RTL (fa/ar); small screen; TalkBack.
