# Custom Location: final security and privacy review

Method: source reading, upstream cross-reference and unit tests of the pure logic. **Android-bound code was not run.**

## 1. Changes since the previous review

| Area | Before | Now |
|---|---|---|
| Service state | silence = "stopped" | UNKNOWN is distinct; STOPPED needs positive evidence; unknown blocks any change |
| Restore | success claimed after a start callback | confirmed by a fresh state query, otherwise reported as failed |
| Recovery record | overwritten by a new operation; unreadable record invisible | never overwritten; unreadable counts as pending; user must restore or dismiss |
| Observation trust | any country + IP accepted | conflicting fields or an invalid/missing IP -> inconclusive |
| Re-identification | per-candidate IP dropped | additionally only a **per-scan salted** hash is kept (comparison within one scan; cannot be reversed or linked across scans) |

## 2. Findings

| # | Topic | Assessment |
|---|---|---|
| 1 | Data sent to a third party | The geolocation URL receives a GET through the **running core's proxy**, revealing the tunnel exit IP and test time to that provider (host named in the UI and the consent dialog). No configuration, credential, UUID, key, subscription link or profile name is sent. Nothing is sent when no core runs. |
| 2 | Real-IP exposure | New code contains no network primitives (audited). The measurement is executed by the daemon via its local proxy; with an unknown or stopped service no request is made. **Residual:** the core's *routing rules* choose the outbound; a `direct` rule for the provider host would reveal the real IP to the provider and mislead the result. The UI warns when several tested servers report the same address; it cannot detect a single-candidate case. |
| 3 | Connection tampering / unintended switching | Switching happens only after a consent dialog; the previous state is restored in `finally` and via a persisted record; no automatic switching exists; unmatched candidates cannot be selected. A scan is a genuine, consented interruption of the VPN. |
| 4 | Misleading success | Restoration and start are never claimed without confirmation; refused/cancelled scans show no result summary; exit "verified" requires a fresh, selection-consistent observation. |
| 5 | Logging | Audited: only exception objects and counts. No IPs, coordinates, URLs, GUIDs, profile names or server data. `ObservedLocation.toString()` masks the IP (tested); candidate results hold no IP (tested). Upstream `HttpUtil` logs, unchanged in kind. |
| 6 | Retention | Observations/results in memory only; persisted: desired location, policy, preferred GUID, and a recovery record (selected GUID + boolean). |
| 7 | Hostile provider data | Size-bounded read (64 KB), tolerant parser that never throws, type/length/coordinate/ISO validation, conflicting country fields rejected, missing/invalid IP rejected. Unit-tested. |
| 8 | Transport | HTTPS is the default provider; a non-HTTPS check URL raises a warning (host only). |
| 9 | IPC | The receiver uses the project's existing flags (exported on Android 13+). Measurement replies are tied to a random per-request id; **state events carry no id and can be spoofed by another app**, which could mislead sequencing (a false start success). The state is re-queried after start and the selection is read back, which narrows but does not remove this. |
| 10 | Race with other actors | The daemon starts the server selected at start time and does not report which it runs. Changes to the selection by the main screen between `select` and start are detected only if visible at the read-backs. |
| 11 | Storage/permissions | Existing MMKV only; one atomic key per concern; no new permission in `main`; debug-only `ACCESS_MOCK_LOCATION`; one non-exported activity. |
| 12 | VPN consent | Never bypassed (`VpnService.prepare` pre-flight; user told to start the VPN from the main screen). |
| 13 | Claims | No claim of complete anonymity, leak prevention, or GPS/browser/time-zone control; the privacy panel is explicitly a configuration review, separate from any leak test. |

## 3. Open risks
Uncompiled and un-run Android code; routing-rule bypass; spoofable state broadcasts; selection race; a "not running" reply during a
start transient is indistinguishable from stopped; third-party provider sees the exit IP by design.
