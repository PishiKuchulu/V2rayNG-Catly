package com.v2ray.ang.ui.location

import com.v2ray.ang.handler.location.AuditFinding
import com.v2ray.ang.handler.location.CandidateResult
import com.v2ray.ang.handler.location.CurrentExitState
import com.v2ray.ang.handler.location.ExitCheckState
import com.v2ray.ang.handler.location.LocationProfile
import com.v2ray.ang.handler.location.MatchStrictness
import com.v2ray.ang.handler.location.StopReason
import com.v2ray.ang.handler.location.MockLocationStatus
import com.v2ray.ang.handler.location.ProfileStatusReport

enum class PickerKind { COUNTRY, REGION, CITY }

enum class CatalogStatus { LOADING, READY, UNAVAILABLE }

data class ProfileSummary(val id: String, val name: String)

data class ServerOption(val guid: String, val label: String)

/** Only one of these runs at a time; every action that would conflict is rejected while it is not NONE. */
enum class BusyKind { NONE, SCANNING, CONNECTING, VERIFYING, RESTORING }

/** One row of the candidate list. [state] is already time-evaluated (it may be STALE). */
data class CandidateRow(
    val result: CandidateResult,
    val isPreferred: Boolean,
    val isSelected: Boolean,
    val countryName: String?,
)

/** Outcome of the last scan, kept only for display. */
data class ScanSummary(
    val tested: Int,
    val verifiedMatches: Int,
    val restoreFailed: Boolean,
    val stoppedEarly: StopReason? = null,
    val sharedExit: Boolean = false,
)

data class PickerItem(val id: String, val label: String, val detail: String? = null)

data class PickerState(
    val kind: PickerKind,
    val query: String = "",
    val items: List<PickerItem> = emptyList(),
    val selectedId: String? = null,
)

/** Immutable snapshot rendered by the screen. Selection and persistence are keyed by profile id / server GUID. */
data class CustomLocationUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val profiles: List<ProfileSummary> = emptyList(),
    val profile: LocationProfile? = null,
    val countryName: String? = null,
    val regionName: String? = null,
    val cityLabel: String? = null,
    val regionsAvailable: Boolean = false,
    val citiesAvailable: Boolean = false,
    val catalogStatus: CatalogStatus = CatalogStatus.LOADING,
    val catalogGenerated: String? = null,
    val servers: List<ServerOption> = emptyList(),
    val report: ProfileStatusReport? = null,
    val exitCheck: ExitCheckState = ExitCheckState.Idle,
    val currentExit: CurrentExitState = CurrentExitState.NOT_CHECKED,
    val strictness: MatchStrictness = MatchStrictness.DEFAULT,
    val candidates: List<CandidateRow> = emptyList(),
    val busy: BusyKind = BusyKind.NONE,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val scanSummary: ScanSummary? = null,
    /** True when an earlier scan was interrupted before it could restore the previous state. */
    val interruptedScan: Boolean = false,
    /** Host of the third-party IP-geolocation provider that receives the IP seen through a connection. */
    val providerHost: String = "",
    val maxScanCandidates: Int = 30,
    /** Localized country names for codes shown in warnings and exit rows. */
    val countryLabels: Map<String, String> = emptyMap(),
    val mock: MockLocationStatus = MockLocationStatus(),
    val deviceTimeZoneId: String = "",
    val audit: List<AuditFinding> = emptyList(),
    val hasInconsistentData: Boolean = false,
    val storageRecovered: Boolean = false,
    val picker: PickerState? = null,
)

sealed interface CustomLocationAction {
    data class SetEnabled(val enabled: Boolean) : CustomLocationAction
    data class SelectProfile(val id: String) : CustomLocationAction
    data object CreateProfile : CustomLocationAction
    data object DeleteProfile : CustomLocationAction
    data class SetName(val name: String) : CustomLocationAction

    data class OpenPicker(val kind: PickerKind) : CustomLocationAction
    data class SetPickerQuery(val query: String) : CustomLocationAction
    data class PickItem(val id: String) : CustomLocationAction
    data object ClearPickerSelection : CustomLocationAction
    data object ClosePicker : CustomLocationAction

    /** Null clears the association. Never changes the server that is selected for connecting. */
    data class SetAssociatedServer(val guid: String?) : CustomLocationAction

    data class SetCoordinates(val text: String) : CustomLocationAction
    data object UseCityCoordinates : CustomLocationAction
    data object StartMockDelivery : CustomLocationAction
    data object StopMockDelivery : CustomLocationAction

    data class SetTimeZone(val text: String) : CustomLocationAction
    data class SetLocale(val tag: String?) : CustomLocationAction
    data object ApplyAppLanguage : CustomLocationAction

    data class SetStrictness(val strictness: MatchStrictness) : CustomLocationAction

    /** Connects through each candidate in turn. The UI must have obtained the user's consent first. */
    data object ScanAll : CustomLocationAction
    data class ScanOne(val guid: String) : CustomLocationAction
    data object CancelScan : CustomLocationAction
    data class PreferServer(val guid: String) : CustomLocationAction

    /** Explicit switch: selects [guid] and (re)connects through it, then verifies the exit. */
    data class ConnectCandidate(val guid: String) : CustomLocationAction
    data object RestoreInterrupted : CustomLocationAction

    /** Forgets an interrupted-scan record without changing the connection (the user fixed it elsewhere). */
    data object DismissInterrupted : CustomLocationAction
    data object CheckExit : CustomLocationAction
    data object RefreshEnvironment : CustomLocationAction
    data object ResetAll : CustomLocationAction
}
