# Custom Location: final hardening audit

Input: the previous deliverable `v2rayNG-custom-location-fixed-source.zip` (SHA-256
`a930cd88a3beb6f245965ebc869f5cfcc7d801fc5f0a65c4950f751ca23cf828`), compared against the original upstream archive
(SHA-256 `bd8e7e66bd07ab65361afb9cf888346fa54b0cf271aff36375c0ce84f2b15c5b`) with `diff -rq` and per-file `diff`.

**Overall verdict: BLOCKED.** The Android build, Gradle tests, lint and APK cannot be produced in this environment
(section 7). Everything that could be verified without them was fixed and tested. Not release-ready.

## 1. Defects found in the previous version, and fixes

| # | Defect | Evidence | Fix |
|---|---|---|---|
| 1 | **`snapshot()` treated "no reply" as "service stopped".** A timeout, a lost broadcast or a failed IPC call became `wasRunning=false`; a scan could then start on a running VPN and *stop it* during "restore". | `ServiceRouteConnector.snapshot`: `queryRunning() ?: false` | New `ServiceState {RUNNING, STOPPED, UNKNOWN}` + `ServiceStateResolver`. STOPPED only on a daemon reply "not running", or silence **plus** OS confirmation that the `:daemon` process does not exist. Silent-but-alive, undeterminable and IPC-failure are UNKNOWN. Scan refused on UNKNOWN. |
| 2 | **Restore claimed success without confirmation** (success = a start callback). | old `restore()` | `GatewayConnector.restore` returns true only after a fresh state query confirms the target (running, or confirmed STOPPED) and the selection read-back matches; stop confirmation is polled, bounded, and UNKNOWN never counts as stopped. |
| 3 | A start callback was treated as proof of a working connection. | old `connectAndObserve` | State is re-queried after start (STOPPED -> failure, UNKNOWN -> abort) and the selection is read back before and after the measurement; a changed selection discards the observation. |
| 4 | Sequencing logic lived in an Android class and could not be tested. | one 200-line class | Split: pure `GatewayConnector` over a thin `ServiceGateway` interface; `AndroidServiceGateway` only does IPC. |
| 5 | A hung candidate could stall a scan indefinitely; no overall bound. | no timeout in `CandidateScanner` | Per-candidate time box (150 s), whole-scan deadline (25 min), both enforced in the scanner independent of the connector. |
| 6 | Unknown state mid-scan was not distinguished; the scan would keep switching. | n/a | `ConnectOutcome.StateUnknown` stops the scan immediately and goes straight to cleanup. |
| 7 | A scan with no selected server could never be restored to "none". | upstream has no API to clear selection | Refused (`NO_SELECTED_SERVER`) instead of pretending. |
| 8 | **A new scan/connect overwrote an unresolved interrupted-scan record**, destroying the only information needed to restore the user's original connection. | `journal.save` unconditional | Scanner refuses while `journal.hasPending()`; UI shows restore / dismiss. An *unreadable* record also counts as pending (otherwise it would block forever with no banner). |
| 9 | A refused or cancelled scan still displayed "No verified matching server was found". | ViewModel always built `ScanSummary` | Summary only when the scan ran to an end. |
| 10 | Explicit "Use and connect" left no way back if the switch failed. | no journal on connect | Snapshot + journal before connecting; cleared only if the outcome is selectable; otherwise "Restore previous state" stays available. Never restored silently. |
| 11 | Leaving the screen mid-scan: a failed restore was silent until the next visit. | n/a | A toast is shown when restore is not confirmed, even after the screen is gone. |
| 12 | Provider answers with conflicting country fields, or no usable IP, could be taken as evidence. | old `toOutcome` | `ObservationReconciler`: conflicting fields or an unparseable/missing IP -> inconclusive, never a match. |
| 13 | **Routing-rule caveat not handled.** The geolocation request goes through the core's local proxy, but the core's *routing rules* decide the outbound. A `direct` rule would report the user's real IP for every candidate. | `CoreServiceManager.measureV2rayDelay` + `getRemoteIPInfo` | Cannot be fixed without touching core routing. Mitigated: servers that report the same address (per-scan salted fingerprint, no recoverable IP kept) raise a visible warning. Documented as a residual risk. |

## 2. Lifecycle trace (candidate -> connect -> verify -> restore)

1. UI action -> `tryBegin(kind)`: single-flight; rejected while busy or while an interrupted record is pending (main thread only).
2. Pre-flight: VPN consent (`VpnService.prepare`, same rule as `MainActivity`), country chosen, candidates exist.
3. `CandidateScanner.scan`: refuses on pending journal / no candidates / unknown state / no selected server; `snapshot` (state re-queried, selection read) -> **journal saved before any change**.
4. Per candidate (time-boxed): re-query state (UNKNOWN stops) -> `select(guid)` + read-back -> `restartServiceOrStart` -> wait for the start event (30 s) -> **re-query state** -> selection read-back -> settle -> `MSG_MEASURE_DELAY` (runs inside `:daemon`, request goes through the core's own proxy) -> selection read-back -> reconcile -> verdict.
5. `finally` under `NonCancellable`: reset in-flight rows, `restore(snapshot)` (confirmed or reported failed), journal cleared only on confirmed restore.
6. `ViewModel` `finally`: exit observation invalidated, summary (only if the scan ran to an end), pending flag refreshed.
7. Process death: journal survives; next visit shows restore/dismiss and blocks new operations until resolved.

**What this trace cannot prove (by upstream design):** the daemon starts whichever server is selected *at start time* and never
reports which server it runs. "The selected profile is the profile used" is therefore bounded by selection read-backs
before/after, not proven. If another actor (main screen) changes the selection in the window between `select` and the
daemon's read, the observation is attributed to the wrong server; only a detected selection change is caught.

## 3. Source integrity

* **Final vs original upstream:** 17 modified existing files, **0 deleted, 0 renamed**; all upstream source files present.
  Removed lines in modified files: 6 (a trailing newline in `values-bqi-rIR/strings.xml`; one `return`/trailing-comma line each in
  `HttpUtil`, `SpeedtestManager`, `UrlContentRequest`; two signature lines in `SettingsActivity`). Upstream `HttpUtilTest` (the only
  upstream test touching a modified class) is byte-identical and tests only `idnToASCII`.
* **Final vs previous ZIP:** changed: 6 main Kotlin files, 2 test files, 10 `strings.xml`, 2 tool files. **Removed:**
  `ServiceRouteConnector.kt` (replaced). **Added:** `AndroidServiceGateway.kt`, `ServiceGateway.kt`, `ServiceState.kt`, `GatewayConnectorTest.kt`, `ServiceStateTest.kt`.
* Manifest: only the non-exported `CustomLocationActivity`; `src/debug` declares `ACCESS_MOCK_LOCATION` for debug builds only.
* No dependency, signing, package id, Gradle or lint-configuration change.
* Resource references: every `R.string.*` used in Kotlin is defined; every `cl_*` string is used; 10 locales carry identical `cl_*` keys with matching placeholders (checked by a real JUnit test over the real XML).
* Upstream API usage cross-checked against source: `LauncherManager`, `MessageHelper` (including the `internal` `sendMsg2ServiceForResult(ctx, what, content, onResult)` signature), `MmkvManager`, `SettingsManager`, `LogUtil`, `BaseViewModel`, `BaseComponentActivity`, `Language`, `AppLocaleManager`, and every Compose component used (`SettingsSwitchItem/MenuItem/EditItem/ListItem`, `CollapsiblePreferenceGroupHeader`, `ConfirmDialog`, `AppTopBar`, `NavigationBarsSpacer`, `verticalScrollbar`). All matched; all 29 `CustomLocationAction` variants are handled; all `when` branches over the new enums are exhaustive.
* Task names: the project has product flavors `playstore` and `fdroid`, so the real tasks are variant-specific (e.g. `testPlaystoreDebugUnitTest`, `assemblePlaystoreDebug`, `lintPlaystoreDebug`). The earlier docs' `testDebugUnitTest/lintDebug` are not the right names.

## 4. Requirement classification

Legend: PASS = verified by executed evidence; PARTIAL = implemented/verified in part; FAIL; NOT VERIFIED = implemented or reviewed but not executed.

### 1. Service-state uncertainty
| Requirement | Status | Basis |
|---|---|---|
| Timeout/missing response/IPC failure/unknown never read as stopped (logic) | **PASS** | `ServiceStateTest` (6), `GatewayConnectorTest`; planted-defect mutation fails 5 tests |
| Stopped, running, unknown represented separately | **PASS** | `ServiceState` enum, used end-to-end |
| No scan when state cannot be established | **PASS** (logic) | `anUnknownStateRefusesAScanBeforeAnythingIsChanged` |
| Android implementation of the state query (`AndroidServiceGateway.queryState`, process check) | **NOT VERIFIED** | type-checked against stubs only; never run |
| Lifecycle changes / process recreation without stale state | **PARTIAL** | no state cached (every decision re-queries; tested); process-death recovery via journal is unit-tested but not exercised on a device |
| Regression tests: timeout, missing response, IPC failure, stopped, running | **PASS** | listed in the test report |

### 2. Connection and IPC lifecycle audit
| Requirement | Status |
|---|---|
| Execution path traced end to end | **PASS** (section 2) |
| Races / duplicate requests | **PARTIAL**: single-flight and journal guard tested; races with the main screen are detected only via selection read-backs |
| Cancellation, navigation, process death | **PARTIAL**: scanner cancellation/restore tested; ViewModel/Activity behavior not run |
| Previous profile restored after success/failure/cancel/exception | **PASS** (logic, fake connector/gateway) / **NOT VERIFIED** on device |
| Selected profile is the profile used for verification | **PARTIAL**: read-backs only; identity of the running profile is not exposed by upstream |
| Callback success not taken as proof of exit location | **PASS** (state re-query + measurement + reconciliation) |

### 3. Safe scanning
| Requirement | Status |
|---|---|
| No concurrent scans / switches | **PASS** (tests) |
| Explicit consent before disruption | **PASS** by inspection (dialogs) / **NOT VERIFIED** running |
| Bounded timeouts and cleanup | **PASS** (per-candidate, whole-scan, restore polls; tested) |
| Restore when required; explicit restore failure | **PASS** (logic) / **NOT VERIFIED** on device |
| Cancellation/exceptions leave no misleading state | **PASS** (logic); ViewModel not run |
| Never silently connect to an unmatched candidate | **PASS** (tests; `isSelectable`; no auto-switch path) |
| Cannot loop forever or leave the service unknown | **PARTIAL**: bounded in logic; an unconfirmed restore is reported, not hidden, but the service can remain in whatever state the failure left |

### 4. Real exit location
| Requirement | Status |
|---|---|
| Request travels through the candidate's route | **PARTIAL**: by source reading (daemon, core's own proxy, no direct path, no UI-side port); **not run**; a `direct` routing rule defeats it, mitigated by a shared-address warning |
| No stale SOCKS port / direct network / other candidate's route | **PASS** by design and code review (none of the new code opens a socket) / **NOT VERIFIED** at runtime |
| Country vs region/city verification distinguished | **PASS** (matcher tests) |
| Missing IP, malformed responses, provider failure, conflicts | **PASS** (reconciler/parser tests); repeated-observation comparison not implemented |
| No hostname/name/flag-based location claims | **PASS** |
| No anonymity / tracking-prevention claims | **PASS** (UI strings and docs state the opposite) |

### 5. Real project verification
| Requirement | Status |
|---|---|
| Existing unit tests | **NOT VERIFIED** (Gradle blocked) |
| New unit tests, real framework | **PARTIAL**: 160 tests pass on real JUnit Jupiter 5.10.1 via the JUnit Platform launcher, **outside Gradle**; project pins JUnit 6.1.3 |
| Android compilation | **NOT VERIFIED** (blocked) |
| Debug APK | **FAIL / BLOCKED**: none produced |
| Lint / static analysis | **NOT VERIFIED** (blocked) |
| Regression of existing VPN / profile selection | **NOT VERIFIED** |
| Compile errors fixed | **PARTIAL**: no reproducible compiler errors exist to fix; cross-reference of upstream signatures found none, a stub-based type-check of the new gateway is clean |

### 6. Regression and source integrity
| Requirement | Status |
|---|---|
| Unrelated files/features preserved | **PASS** (0 deleted; additive edits) |
| Manifest / permissions / resources / dependencies | **PASS** |
| Localization consistency | **PASS** (real JUnit over real XML) |
| RTL / Persian layout | **PARTIAL**: no hard-coded left/right; fa/ar strings present; not visually verified |
| Every UI action performs a real operation | **PASS** by inspection (each maps to a ViewModel operation) / **NOT VERIFIED** running |
| No credentials/sensitive data logged or transmitted | **PASS** (log audit; only exceptions and counts; no network code in new files) |
| No weakened tests, lint or gates | **PASS** |

## 5. Remaining risks
1. Android-bound code (ViewModel, Compose screen, Activity, gateway, adapters) is uncompiled; upstream edits uncompiled.
2. Routing rules can send the geolocation request outside the proxy outbound; only heuristically detected.
3. Selection/identity race with another UI actor is only partly detectable.
4. A "stopped" reply while the core is mid-start is indistinguishable from stopped.
5. State broadcasts without request ids are spoofable by another app (inherited).

## 6. Not done
Repeated-observation conflict detection; any change to upstream routing; instrumented tests; device validation.

## 7. Environment
Real Gradle 9.8.1 (from GitHub) and the wrapper fail as shown in the test report. No Android SDK, no `libv2ray.aar`.
