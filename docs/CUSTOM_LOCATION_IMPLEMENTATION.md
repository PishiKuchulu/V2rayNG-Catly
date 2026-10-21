# Custom Location: implementation

> **Superseded in part.** See `CUSTOM_LOCATION_FINAL_*.md` for the final hardening pass. Notably: service-state handling (UNKNOWN is
> now distinct), restore confirmation, the connector split (`GatewayConnector` + `AndroidServiceGateway` replace `ServiceRouteConnector`), and the
> correct Gradle task names (product flavors `playstore`/`fdroid`: e.g. `testPlaystoreDebugUnitTest`). Where this document differs, the FINAL documents win.


Status: source complete for the specified workflow; **the Android-bound parts have not been compiled or run**
(see `CUSTOM_LOCATION_TEST_REPORT.md`). Treat as PARTIAL until the Gradle build and on-device checks pass.

## 1. Concepts (kept separate in code and UI)

| Concept | Type | Meaning |
|---|---|---|
| Desired location | `DesiredLocation` (+ `MatchStrictness`) | The user's request: country (required), region, city, policy. Never evidence. |
| Observed location | `ObservedLocation` | What an external GeoIP provider returned for the public IP seen **through one actual route**: source host, timestamp, country, region, city, coordinates, the server GUID selected at the time. In memory only. Its `toString()` masks the IP. |
| Candidate result | `CandidateResult`/`CandidateState` | Verdict for one server from one observation, with a timestamp. |
| Other signals | GPS / browser / language / time zone | Reported as independent capabilities; none is ever derived from the exit result or vice versa. |

## 2. Matching (`LocationMatcher`, pure, unit-tested)

Policy (`MatchStrictness`, user setting, default `PREFER_FINER`):

| Observed vs desired | COUNTRY_ONLY | PREFER_FINER | STRICT |
|---|---|---|---|
| no usable country | GEO_UNAVAILABLE | GEO_UNAVAILABLE | GEO_UNAVAILABLE |
| country differs | NO_MATCH | NO_MATCH | NO_MATCH |
| country equal, nothing finer requested | VERIFIED_MATCH | VERIFIED_MATCH | VERIFIED_MATCH |
| finer requested, all verified | VERIFIED_MATCH | VERIFIED_MATCH | VERIFIED_MATCH |
| finer requested, contradicted | VERIFIED_MATCH (finer ignored) | COUNTRY_MATCH_ONLY | NO_MATCH |
| finer requested, not verifiable | VERIFIED_MATCH | REGION_CITY_UNVERIFIED | REGION_CITY_UNVERIFIED |

Region: equal ISO 3166-2 code (provider code is trusted only if it is a real ISO code of the observed country) -> match,
different valid code -> contradiction; else name equality -> match; **a differing name is "unknown", never a contradiction**
(names vary between providers/languages). City: provider coordinates vs the catalog city: <=40 km match, >150 km
contradiction, between = unknown; without coordinates, name equality = match, otherwise unknown. Nothing is ever inferred
from a profile name, flag, hostname or reverse DNS.

`CandidateState.isSelectable` is true only for VERIFIED_MATCH, REGION_CITY_UNVERIFIED and COUNTRY_MATCH_ONLY. No
unmatched, failed, unknown or stale server can be chosen, and nothing is ever selected automatically.
Results older than 30 minutes display as STALE. Changing the request or policy **re-classifies stored observations**
without reconnecting (`CandidateEvaluation.reclassify`).

## 3. How real candidate testing works

Why a connection is needed: the only route-faithful way to learn a server's exit is to send the geolocation request
through that server. Upstream's isolated `measureOutboundDelay` returns only a delay, so it cannot reveal a location.
This implementation therefore never claims isolated probing; it uses the **existing connection lifecycle**:

`GatewayConnector` (pure) implements the seam `CandidateConnector` over `ServiceGateway`; `AndroidServiceGateway` is the Android IPC part (originally `ServiceRouteConnector`):

1. `snapshot()`: asks the daemon (`MSG_REGISTER_CLIENT`) whether the core runs; reads the selected server GUID.
2. `connectAndObserve(guid)`: `MmkvManager.setSelectServer(guid)` then `LauncherManager.restartServiceOrStart { startService }`;
   waits for `MSG_STATE_START_SUCCESS` (30 s), settles 1 s, then sends the core's own `MSG_MEASURE_DELAY` with a random request id.
   The measurement executes **inside the daemon** and fetches the IP-API URL **through the running core's local proxy**, so
   the request takes that server's route; this process never opens a socket to the provider and never assumes a port.
3. `restore(snapshot)`: re-selects the original server and restarts it, or stops the core if it was not running, and
   confirms by querying state.

`CandidateScanner` (pure, coroutine-based, tested with a fake connector) orchestrates: de-duplicates by GUID, caps at 30,
runs **strictly one at a time**, refuses a second concurrent scan, writes a **journal** (previous GUID + running flag, no
location data) *before* the first change, maps connector failures to CONNECTION_FAILED without aborting, and **always
restores in `finally` under `NonCancellable`** (also on cancel). The journal is cleared only after a confirmed restore.
If the process dies mid-scan, the journal drives an explicit "Restore previous state" banner on the next visit.

User consent: scanning and switching each show a confirmation dialog that states the interruption, the restore, and which
third-party host receives the IP. No automatic switching exists anywhere.

## 4. UI workflow (Settings -> Custom Location)

1. Enable; create/select a profile; pick country (searchable, localized), optional region and city.
2. Choose the match policy.
3. **Exit location and matching servers** section: desired vs observed vs current-connection state; **Verify current exit
   location** (observes only; starts/stops nothing); **Test servers** (consent dialog) or **Test this server** per row.
4. Each row shows its state, observed country/region/city (labelled approximate), time checked, and badges. Actions:
   **Set as preferred server** (stores the association only), **Use and connect** (consent dialog, then switches and
   re-verifies). Non-selectable rows show why nothing can be chosen.
5. After connecting, the exit is re-verified and shown as *Connected and verified*, *Connected: country matches, region/city not
   fully verified*, or *Connected, but the observed exit does not match*.

Buttons that would conflict are inert while an operation runs and the screen says why ("Another operation is in progress").
Operations are single-flight (`BusyKind`). Work runs on `Dispatchers.IO/Default`; state is in a `ViewModel`
(survives rotation). Desired location, policy and the preferred server persist in MMKV; observations and candidate
results are **not** persisted.

## 5. State transitions

* Candidate: `NOT_CHECKED -> CHECKING -> {VERIFIED_MATCH | COUNTRY_MATCH_ONLY | REGION_CITY_UNVERIFIED | NO_MATCH | CONNECTION_FAILED | GEO_UNAVAILABLE}`;
  any settled state `-> STALE` after 30 min; cancel resets `CHECKING -> NOT_CHECKED`; a policy change re-derives the verdict from the stored observation.
* Current exit: `NOT_CHECKED -> CHECKING -> {NOT_CONNECTED | CHECK_FAILED | GEO_UNAVAILABLE | CONNECTED_VERIFIED | CONNECTED_PARTIAL | CONNECTED_MISMATCH}`;
  `-> STALE` when older than 10 min, when the selected server changed, or after a network change (ignored while an operation runs).
* Operation: `NONE -> {SCANNING | CONNECTING | VERIFYING | RESTORING} -> NONE`.

## 6. Provider design

One provider, the project's existing configurable `pref_ip_api_url` (default `https://api.ip.sb/geoip`), through one response:
no second provider receives the IP, so no cross-provider conflict policy is needed. `GeoIpResponseParser` is deterministic
(fixed key order), tolerant of ip.sb / ipinfo / ipapi.co / ip-api.com / Cloudflare-meta shapes, never throws, and sanitizes and
bounds text. The HTTP read is bounded to 64 KB (additive `UrlContentRequest.maxBytes`), timeouts are the project's existing finite
timeouts, no API key is embedded. A non-HTTPS URL is flagged by the privacy panel. The provider host is disclosed in the UI.
No caching is used as proof: results expire (30/10 min) and are labelled stale.

## 7. Existing GPS / browser / regional features (re-audited)

* Mock GPS: debug builds only (`src/debug` declares `ACCESS_MOCK_LOCATION`); release reports "not available in this build".
  Requires the user to select the app in Developer options; delivery stops when the screen closes; "delivered" is not "accepted by an app".
* Browser location: informational/unsupported (no embedded browser; WebView cannot be given coordinates).
* Regional: only the app language can be applied (on tap); time zone is compared read-only; nothing global is changed.
None of these participates in exit verification.

## 8. Known limitations

* Observing a server's exit **requires connecting through it**; scanning interrupts traffic (worst case about 30 candidates x ~60 s).
* A candidate that cannot start, or whose provider answer is unusable, is reported as failed/unavailable, never as a match.
* IP geolocation is an estimate; region/city are approximate; many providers return no region. City-level verification
  needs coordinates or an equal name; otherwise it stays "unverified".
* Servers behind relays/CDNs or CGNAT may geolocate to the relay; the result describes the observed IP only.
* `restore` cannot return to "no server selected" (upstream exposes no way to clear the selection): if a scan starts with no
  server selected and the core stopped, the core is stopped again but the last-tested server remains the selected one.
* "No daemon reply" is interpreted as "not running"; if the daemon is alive but unresponsive the state would be mis-read.
* Starting a candidate shows the app's normal "starting service" toast each time; on the newest Android the main screen
  also asks for local-network permission before starting, which this flow does not.
* `LauncherManager`/messaging semantics were read from source and **not exercised on a device or emulator**.
