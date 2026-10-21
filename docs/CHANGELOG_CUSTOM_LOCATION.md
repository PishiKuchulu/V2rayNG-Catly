# Changelog: Custom Location (completion and fixes)

> **Superseded in part.** See `CUSTOM_LOCATION_FINAL_*.md` for the final hardening pass. Notably: service-state handling (UNKNOWN is
> now distinct), restore confirmation, the connector split (`GatewayConnector` + `AndroidServiceGateway` replace `ServiceRouteConnector`), and the
> correct Gradle task names (product flavors `playstore`/`fdroid`: e.g. `testPlaystoreDebugUnitTest`). Where this document differs, the FINAL documents win.


## Added
* **Real exit-location server selection:** consent-gated sequential scan of existing servers, per-server test, ranking,
  "Set as preferred server", and "Use and connect" with post-connect re-verification.
* Observed-exit model with region/city/coordinates; deterministic GeoIP response parser (ip.sb, ipinfo, ipapi.co, ip-api.com, Cloudflare).
* Match policy (country only / prefer finer / strict) and explicit states: not checked, checking, verified match, country match only,
  region/city unverified, no match, connection failed, geolocation unavailable, stale, connected and verified, connected but mismatched.
* Scan journal and "Restore previous state" recovery after an interrupted scan.
* HTTPS warning for the configured check URLs; in-app GeoNames / iso-codes attribution; 54 net new strings in 10 locales.
* 129 JUnit tests (see test report), including a localization consistency test over the real resource files.

## Changed (upstream, additive)
* `ConnectionTestResult`, `SpeedtestManager.RemoteEndpointInfo`: optional `countryCode/regionCode/regionName/city/latitude/longitude`.
* `UrlContentRequest.maxBytes` + bounded read in `HttpUtil.getUrlContent` (0 = previous behavior); IP lookup uses a 64 KB bound.
* `CoreServiceManager.measureV2rayDelay` forwards the new optional fields. `SettingsActivity`: Custom Location entry. Manifest: one activity.
* "Associated server" is now "Preferred server" and has real select/connect actions.

## Fixed
* Country-only verification; exit-status logic now uses the matcher and policy.
* Un-normalized country in the mismatch warning (found by the real JUnit run).
* Observation expiry no longer fires during an operation.

## Removed
* Fake JUnit shim and reflection runner (replaced by a script that uses the real JUnit Platform).
* `ExitLocationProbe` (superseded by `ServiceRouteConnector`).

## Partially implemented / unsupported
* Partial: city/region verification depends on what the provider returns; GPS mock is debug-build only and screen-scoped.
* Unsupported (by platform): isolated (non-connecting) location probing of a server; browser geolocation control; system time-zone
  change; global GPS override; real DNS-leak test.

## Unverified
* Gradle build, lint, APK, existing upstream tests, and all Android-bound code have **not** been built or run (environment blocker).
