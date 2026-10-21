# Custom Location: original-versus-modified source audit

> **Superseded in part.** See `CUSTOM_LOCATION_FINAL_*.md` for the final hardening pass. Notably: service-state handling (UNKNOWN is
> now distinct), restore confirmation, the connector split (`GatewayConnector` + `AndroidServiceGateway` replace `ServiceRouteConnector`), and the
> correct Gradle task names (product flavors `playstore`/`fdroid`: e.g. `testPlaystoreDebugUnitTest`). Where this document differs, the FINAL documents win.


## 1. Inputs and method

* **Original:** `v2rayNG-master.zip` (uploaded; SHA-256 `bd8e7e66bd07ab65361afb9cf888346fa54b0cf271aff36375c0ce84f2b15c5b`; 515 archive entries, 406 files).
* **Modified (under audit):** the previous deliverable `v2rayNG-custom-location.zip` produced earlier in this
  conversation. **Only the original archive was uploaded in this task**, so the "modified" archive is that earlier output.
* Both were extracted into separate directories and compared with `diff -rq` plus per-file `diff` (not by memory).
  The final tree was compared the same way against the original (section 5).

## 2. Original project facts (read from source, not assumed)

* Single Gradle module `V2rayNG/app`, Kotlin, Jetpack Compose UI, package `com.v2ray.ang`.
  Versions are pinned in `gradle/libs.versions.toml` (AGP 9.4.1; wrapper Gradle 9.8.1).
* **Process model:** the core and VPN service run in a separate `:daemon` process. The UI controls it with
  broadcasts (`MessageHelper`, `MSG_*`). State comes back as `MSG_STATE_RUNNING/NOT_RUNNING/START_SUCCESS/...`.
* **Server selection and lifecycle:** `MmkvManager.setSelectServer(guid)`; `LauncherManager.startService / stopService /
  restartService / restartServiceOrStart`. In VPN mode the *caller* must hold system VPN consent
  (`VpnService.prepare`), as `MainActivity.requestServiceStart` does.
* **Per-server testing that exists upstream:** `RealPingWorkerService` -> `CoreNativeManager.measureOutboundDelay(config, url)`
  tests *any* configured server in isolation, but **returns only a delay** (no response content, no IP).
* **Active-connection test:** `CoreServiceManager.measureV2rayDelay` (in the daemon) measures delay and then calls
  `SpeedtestManager.getRemoteIPInfo()`, which fetches the configured IP-API URL **through the running core's local
  proxy**. Result: `ConnectionTestResult(delayMillis, errorMessage, country, ipAddress)`.
* **Routing facts that constrain the design:**
  * With a *dynamic SOCKS port* the port exists only in the daemon's memory (`runtimeSocksPort`), so any HTTP call
    built in the UI process would target a wrong port.
  * `getRemoteIPInfo()` returns null when no port is configured and otherwise always uses the proxy (no direct fallback).
  * It parsed only `ip` and `country`/`country_code`, and read the response body **without a size bound**.
* **Tests:** JUnit Jupiter via `junit-bom` **6.1.3** on the JUnit Platform, plus Mockito 5.24.0 / mockito-kotlin 6.4.0
  (`gradle/libs.versions.toml`); `discovery.issue.severity.critical=WARNING` (a test method that returns a value would
  fail discovery).

**Consequence for the central requirement.** The exit location of a server can only be learned by sending a request
through that server's own route. Upstream offers that only for the server the core is *currently running*. Isolated
probing returns no content. Therefore verifying a candidate requires **actually connecting through it**, sequentially
and with user consent, then restoring the user's state.

## 3. File-level diff: original vs. the previous implementation

* Modified: **12** files (`AndroidManifest.xml`, `SettingsActivity.kt`, 10 `strings.xml`). Deleted: **0**. Renamed: **0**.
  No restructuring. Added: `handler/location/*` (13 files), `ui/location/*` (4 files), `assets/location_catalog.json`,
  `src/debug/AndroidManifest.xml`, 7 test files, docs and tools.
* All modifications were additive (the only removed lines were a trailing `</resources>` newline difference and two
  signature lines extended with a comma/parameter in `SettingsActivity`).

## 4. Defects and gaps found in the previous implementation

| # | Finding | Evidence | Severity |
|---|---|---|---|
| 1 | **The central requirement was not implemented.** There was no candidate scan, ranking, select or connect. The feature stored a preference and could only check the *current* connection. | `ui/location/*`, no candidate model; "associated server" had no action | Critical |
| 2 | **Verification was country-only.** `ConnectionTestResult` had no region/city, so region/city could never be verified; a requested city produced no honest verdict. | `ConnectionTestResult.kt`, `LocationStatus.evaluateExit` compared country strings | Critical |
| 3 | Tests were run with a **self-made JUnit shim and reflection runner**, which this task forbids as evidence of passing project tests. | `tools/local_verification/shim`, `Runner.java` | High |
| 4 | "Satisfied" for exit was plain equality: no match policy, no region/city logic, no partial state. | `LocationStatus.kt` | High |
| 5 | Observation was invalidated on *every* network callback, including during an operation; a scan would have wiped its own state. (Latent: no scan existed.) | `NetworkChangeWatcher` use in the ViewModel | Medium |
| 6 | IP-geolocation response read unbounded (pre-existing upstream behavior, now on a path this feature drives). | `HttpUtil.getUrlContent` | Medium |
| 7 | No HTTPS check on the configurable geolocation/delay URLs; no in-app licence attribution for GeoNames (CC BY 4.0). | `PrivacyAudit`, catalog doc said "not implemented" | Medium |
| 8 | **Found by running the real JUnit:** the exit-mismatch warning carried the un-normalized observed country (`de` vs `DE`). | real run: `differentObservedCountryIsAVerificationFailureWithWarning` failed | Low (fixed) |
| 9 | The Compose screen, ViewModel and Android adapters were **never compiled against the real project**, and the earlier report said so. They remain uncompiled here (section 6). | no Android SDK, Gradle repos unreachable | Risk |

Previously correct and kept: honest capability separation (exit/GPS/browser/regional), mock-location debug-only
manifest, no silent changes to servers, corruption-tolerant storage, platform-derived country list.

## 5. Final tree vs. original

* **Modified (17):** `AndroidManifest.xml`; `core/CoreServiceManager.kt`; `dto/ConnectionTestResult.kt`;
  `dto/UrlContentRequest.kt`; `handler/SpeedtestManager.kt`; `ui/settings/SettingsActivity.kt`; `util/HttpUtil.kt`;
  10 `strings.xml`. Removed lines across all of them: 6 (superseded signature/`return` lines, a trailing newline).
* **Deleted / renamed: 0.** Every upstream file is present.
* **Added:** `handler/location/` (15 files), `ui/location/` (4), `assets/location_catalog.json`,
  `src/debug/AndroidManifest.xml`, 12 test files, `docs/`, `tools/`.
* No dependency, permission (besides debug-only `ACCESS_MOCK_LOCATION`), signing, package-id or Gradle change.

## 6. Environment limits that bound this audit

Real Gradle 9.8.1 runs but cannot resolve the Android Gradle Plugin (Google Maven, Maven Central and the plugin
portal are unreachable); there is no Android SDK; `libv2ray.aar` is absent from the archive. See
`CUSTOM_LOCATION_TEST_REPORT.md` for the exact commands and output.
