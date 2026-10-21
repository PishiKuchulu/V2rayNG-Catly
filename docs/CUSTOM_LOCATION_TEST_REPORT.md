# Custom Location: test and build report

> **Superseded in part.** See `CUSTOM_LOCATION_FINAL_*.md` for the final hardening pass. Notably: service-state handling (UNKNOWN is
> now distinct), restore confirmation, the connector split (`GatewayConnector` + `AndroidServiceGateway` replace `ServiceRouteConnector`), and the
> correct Gradle task names (product flavors `playstore`/`fdroid`: e.g. `testPlaystoreDebugUnitTest`). Where this document differs, the FINAL documents win.


Honest summary: **the Gradle build, Gradle unit tests, lint and APK could not be run** because the environment cannot
resolve the Android Gradle Plugin. 129 JUnit tests were run **outside Gradle** with a real JUnit Platform launcher;
they cover only the pure-JVM logic. Android-bound code was not compiled.

## 1. Environment

| Item | State |
|---|---|
| JDK | OpenJDK 21 (installed from the Ubuntu archive) |
| Gradle | Wrapper cannot download; Gradle **9.8.1** obtained from GitHub releases and run directly |
| Android SDK / `ANDROID_HOME` | absent |
| `V2rayNG/app/libs/libv2ray.aar`, `AndroidLibXrayLite` | absent in the supplied archive |
| Reachable | github.com, registry.npmjs.org, pypi.org, Ubuntu archive. **Unreachable:** services.gradle.org, dl.google.com, repo.maven.apache.org, plugins.gradle.org, jitpack.io |
| Kotlin compiler used for the fallback run | 2.1.21 (project uses its own Gradle-pinned Kotlin) |
| JUnit used for the fallback run | **Jupiter 5.10.1 / Platform console 1.9.1** (Ubuntu `junit5`). The project pins **JUnit 6.1.3**; not available offline |
| kotlinx-coroutines for the fallback run | Ubuntu package version **1.0.1** (project uses a current version) |

## 2. Commands attempted and exact results

```
$ cd V2rayNG && ./gradlew --version
Downloading https://services.gradle.org/distributions/gradle-9.8.1-bin.zip
Exception in thread "main" java.net.UnknownHostException: services.gradle.org
exit=1

$ GRADLE_USER_HOME=/tmp/gh gradle-9.8.1/bin/gradle --no-daemon --console=plain testDebugUnitTest
FAILURE: Build failed with an exception.
* Where: Build file '.../V2rayNG/build.gradle.kts' line: 2
* What went wrong:
Plugin [id: 'com.android.application', version: '9.4.1', apply: false] was not found in any of the following sources:
 ... Searched in the following repositories: Google, MavenRepo, Gradle Central Plugin Repository
BUILD FAILED in 15s
exit=1
```

| Required check | Result |
|---|---|
| Real Gradle unit tests (`testDebugUnitTest`) | **NOT RUN** (blocked above) |
| Debug compile / `assembleDebug` | **NOT RUN** (blocked) |
| Release build, lint (`lintDebug`) | **NOT RUN** (blocked) |
| Existing upstream test suites | **NOT RUN** (blocked) |
| Regression tests of connection management / profile selection on a device | **NOT RUN** |
| APK | **None produced** |

## 3. What was run, and how

`tools/local_verification/run_tests.sh` compiles the pure-JVM main sources
(`LocationProfile, GeoCatalog, LocationProfileCodec, LocationProfileRepository, LocationStatus, PrivacyAudit,
CoordinateParser, ExitLocation, CandidateScanner`) together with the **project's test classes unmodified**, using the
**real JUnit Jupiter API** and the **real JUnit Platform console launcher**. No assertion library or runner is simulated.
(The earlier shim/reflection runner was deleted from the package.)

Result of the last run: **16 containers, 129 tests found, 129 successful, 0 failed, 0 skipped.**

| Test class | Tests | Covers |
|---|---|---|
| `LocationMatcherTest` | 16 | country/region/city matching, strict vs country-only vs prefer-finer, unknown vs mismatch, IP masking, strictness wire values |
| `GeoIpResponseParserTest` | 10 | ip.sb / ip-api / ipinfo / Cloudflare shapes, country-name mapping, malformed/oversize/wrong-typed/hostile input, determinism |
| `CandidateScannerTest` | 14 | sequential-only, failure mapping, restore (normal/cancel/failed), journal-before-change, concurrent refusal, dedupe+cap, no silent selection, no IP retained, reclassification |
| `CandidateResultTest` | 5 | staleness/TTL, selectability, ranking and stable ordering |
| `LocationStatusEvaluatorTest` | 17 | states, honest unavailable, expiry on network/reconnect, server-association warning, GPS/time-zone/locale, browser always unsupported |
| `LocationProfileCodecTest` | 13 | round-trip, corruption, newer schema, per-field dropping, strictness migration, no observation persisted |
| `LocationProfileRepositoryTest` (incl. `ScanJournalRepositoryTest`) | 13 | persistence/restart, backup, reset, only-feature-keys-written, journal round-trip/malformed/no location data |
| `GeoCatalogTest` | 12 | ISO country validation, parser tolerance, duplicates/ambiguity, bundled asset parses cleanly, unique labels |
| `LocationProfileTest` | 10 | validation and edit operations |
| `PrivacyAuditTest` | 12 | IPv4/IPv6/proxy-only/Private DNS findings, host-only disclosure, HTTPS warning |
| `CoordinateParserTest` | 3 | coordinate parsing |
| `LocalizationConsistencyTest` | 4 | reads the **real** `res/values*/strings.xml`: all 10 locales define every `cl_` key, placeholder parity, no blank strings, original keys untouched |
| **Total** | **129** | |

Real-framework value: the first real run exposed a genuine defect (un-normalized country in the mismatch warning),
which was fixed. A mutation check on the evaluator (done earlier in this conversation, against the previous test set) confirmed the harness can fail.

## 4. Not covered (do not read the 129 as coverage of these)

* `ServiceRouteConnector`, `ViewModel`, Compose screen/Activity, `MockLocationController`, `NetworkChangeWatcher`,
  `RegionalSettingsAdapter`, `PrivacyAuditSource`, `MmkvLocationStore`: **not compiled** against the real project. Earlier
  in this conversation `MockLocationController`/`PrivacyAuditSource`/`NetworkChangeWatcher` type-checked against the Android
  API 33 jar with stubs; that is **not** a build and is not repeated as evidence here.
* The upstream edits (`HttpUtil`, `SpeedtestManager`, `CoreServiceManager`, DTOs): reviewed by reading; **not compiled or run**.
* JUnit 6.1.3 behavior; Mockito-based project tests; Android lint (`MissingTranslation`, RTL); instrumentation tests.
* Real connect/restart sequencing, broadcast timing, VPN consent flow, rotation/process death, Wi-Fi<->cellular, tunnel drop.
* Integration tests against actual project components: **none**, because the project cannot be built here.

## 5. Next steps to close the gap

1. On a machine with network access: `cd V2rayNG && ./gradlew testPlaystoreDebugUnitTest assemblePlaystoreDebug lintPlaystoreDebug`.
2. Fix any compile errors in the ViewModel/screen/connector (most likely area).
3. On a device: scan with 2+ servers (connected and disconnected start states), cancel mid-scan, kill the app mid-scan
   (journal banner), disconnect the network during a test, rotate, RTL (fa/ar), small screen, TalkBack.
