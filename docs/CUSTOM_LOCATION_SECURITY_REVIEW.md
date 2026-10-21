# Custom Location: security and privacy review

> **Superseded in part.** See `CUSTOM_LOCATION_FINAL_*.md` for the final hardening pass. Notably: service-state handling (UNKNOWN is
> now distinct), restore confirmation, the connector split (`GatewayConnector` + `AndroidServiceGateway` replace `ServiceRouteConnector`), and the
> correct Gradle task names (product flavors `playstore`/`fdroid`: e.g. `testPlaystoreDebugUnitTest`). Where this document differs, the FINAL documents win.


Scope: threat model and data-handling review of the Custom Location code and the upstream lines it touches.
Method: source reading and unit tests of the pure logic. **Android-bound code was not run**; findings about it are
by inspection only.

## 1. Assets and trust boundaries

* Assets: server credentials/UUIDs/keys and subscription URLs (existing storage, never touched); the user's real and exit IPs;
  the selected-server state; location preferences.
* Boundaries: UI process <-> `:daemon` process (broadcasts); app <-> third-party IP-geolocation provider (always through
  the active tunnel); app <-> bundled catalog asset; Android settings.

## 2. Findings

| # | Topic | Assessment |
|---|---|---|
| 1 | Data sent to the provider | Only an HTTP GET to the configured IP-API URL **through the running core's proxy**. The provider sees the tunnel exit IP. No configuration, credential, UUID, key, subscription link or profile name is sent. Disclosed in the UI (host named) and in the consent dialog. |
| 2 | Real-IP exposure | There is no direct path: the request is made by the daemon through its local proxy; with no core running, no request is made (`NOT_RUNNING`). **Residual:** when the core is *not running*, no check exists at all; a user who expects one must connect first. |
| 3 | Wrong-route queries | The UI never builds a request or assumes a port (dynamic SOCKS port exists only in the daemon). Each candidate is observed immediately after *its own* `START_SUCCESS`; an observation records the selected GUID and is discarded if the selection changes. |
| 4 | Silent switching | None. Every switch needs a consent dialog; scanning restores state in `finally` (also on cancel) and via a persisted journal after a crash; "no silent selection" is unit-tested. **Residual:** a scan is a real, user-consented interruption of the VPN. |
| 5 | Logging | New code logs only counts, exception types and generic messages. No IPs, coordinates, URLs, profile names, GUIDs or server data. `ObservedLocation.toString()` masks the IP (tested). Upstream `HttpUtil` logs exception objects as before (pre-existing; loopback proxy only). |
| 6 | Retention | Observations and candidate results are in memory only; candidate results drop the IP address (tested); only desired location, policy, preferred GUID persist. The scan journal stores a GUID and a boolean. |
| 7 | Hostile/malformed provider data | Response bounded to 64 KB (additive); parser never throws, validates types, strips control characters, bounds field length, validates coordinates and ISO codes; a name that merely differs is "unknown", not "mismatch". Fuzz-style cases are unit-tested. |
| 8 | Transport | Default provider is HTTPS. The URL is user-configurable, so a non-HTTPS delay-test/IP-API URL raises a warning (host only). Not blocked, because upstream allows it. |
| 9 | Deserialization | No reflective/Java deserialization of untrusted JSON: Gson tree parsing with explicit type checks. The existing `Serializable` `ConnectionTestResult` crosses processes of the same app (additive fields). |
| 10 | Broadcast receiver | Registered with the project's existing `Utils.receiverFlags()` (exported on Android 13+). A spoofed result must carry the per-request random UUID; the worst case is a wrong display. State events (`START_SUCCESS`...) have no such id and could be spoofed by another app, which could mislead the sequencing (a failed/early observation). Inherited project convention; not changed. |
| 11 | Storage | Existing MMKV (`MmkvManager`) only; one atomic key; corrupt or newer-schema data is backed up once and never silently destroyed (tested). |
| 12 | Permissions / manifest | No new permission in `main`. Debug-only `ACCESS_MOCK_LOCATION`. One non-exported activity. |
| 13 | VPN consent | Never bypassed: the flow checks `VpnService.prepare` first (mirrors `MainActivity`) and tells the user to start the VPN once from the main screen. |
| 14 | Claims | The UI never says "applied/matched" for a selection; success wording exists only for verified states. It does not claim leak prevention. Privacy diagnostics are configuration checks, not leak tests, and are shown separately. |
| 15 | Geographic data | CC BY 4.0 (GeoNames) and LGPL-2.1+ (ISO 3166-2 via iso-codes); attribution is shown in-app (all locales) and in docs. Data is approximate and may be stale. |

## 3. Not claimed

DNS-leak, WebRTC-leak, GPS-exposure, account-history or fingerprinting protection. An IP-country match is not anonymity
and does not show that a service cannot infer the user's location. Browser/GPS/time-zone/locale are independent of the
exit and are not "spoofed" except the debug-only mock-location delivery, which apps may ignore.

## 4. Open risks

* Android-bound code and upstream edits are uncompiled and untested on a device.
* State broadcasts without request ids (finding 10) are spoofable by another app on the device.
* A geolocation provider can be wrong, rate-limit (-> `GEO_UNAVAILABLE`), or be attacked if configured over HTTP.
* Third-party provider learns the tunnel exit IP and the time of each test (by design, disclosed).
