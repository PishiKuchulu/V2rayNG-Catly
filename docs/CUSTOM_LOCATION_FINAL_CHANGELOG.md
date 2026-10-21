# Custom Location: final hardening changelog

## Fixed
* Service state: new `ServiceState` (RUNNING / STOPPED / UNKNOWN); silence, timeout and IPC failure are no longer "stopped".
* Scans are refused when the state is unknown, when nothing is selected (nothing could be restored), or while an earlier interrupted scan is unresolved.
* Restore is confirmed by a fresh state query (selection read-back included) or reported as failed; stop confirmation is polled with a bound.
* A start callback is no longer treated as proof; state and selection are re-checked before and after the measurement.
* Per-candidate time box (150 s) and whole-scan deadline (25 min) enforced by the scanner; unknown state mid-scan stops the scan.
* Refused/cancelled scans no longer show a result summary; a failed restore after leaving the screen is announced.
* Explicit "Use and connect" now journals the previous state so it can be restored if the switch fails.
* An unreadable recovery record is treated as pending (visible, dismissable) instead of silently blocking.
* Provider answers with conflicting country fields or no valid IP are inconclusive.

## Added
* `GatewayConnector` + `ServiceGateway` (pure, tested) and `AndroidServiceGateway` (thin IPC); replaces `ServiceRouteConnector`.
* Shared-exit-address warning (per-scan salted fingerprint) for the routing-rule caveat; "Dismiss" for a stale recovery record.
* 31 tests (129 -> 160): `ServiceStateTest`, `GatewayConnectorTest`, and new scanner/journal tests.
* 7 strings x 10 locales (dismiss, no-selection, state-unknown, stopped-early x2, shared-exit, resolve-first).

## Changed
* Docs corrected: product flavors mean variant-specific Gradle tasks; test report states exactly what was and was not run.

## Removed
* `ServiceRouteConnector.kt` (replaced).

## Unchanged
* Upstream: 17 modified files, 0 deleted; same additive edits as the previous ZIP. No dependency, permission, signing, or lint change.

## Not done / unverified
* Gradle build, tests, lint, APK, device behavior (environment blocker). Repeated-observation conflict detection. Core routing changes.
