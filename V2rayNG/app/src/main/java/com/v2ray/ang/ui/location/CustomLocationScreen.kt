package com.v2ray.ang.ui.location

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.enums.Language
import com.v2ray.ang.handler.location.AuditFinding
import com.v2ray.ang.handler.location.AuditFindingId
import com.v2ray.ang.handler.location.AuditSeverity
import com.v2ray.ang.handler.location.CapabilityStatus
import com.v2ray.ang.handler.location.CandidateState
import com.v2ray.ang.handler.location.CoordinateParser
import com.v2ray.ang.handler.location.CurrentExitState
import com.v2ray.ang.handler.location.MatchStrictness
import com.v2ray.ang.handler.location.ExitCheckFailure
import com.v2ray.ang.handler.location.ExitCheckState
import com.v2ray.ang.handler.location.IpFamily
import com.v2ray.ang.handler.location.LocationCapability
import com.v2ray.ang.handler.location.LocationWarning
import com.v2ray.ang.handler.location.MockLocationState
import com.v2ray.ang.handler.location.ProfileState
import com.v2ray.ang.handler.location.StopReason
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.ConfirmDialog
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomLocationScreen(
    viewModel: CustomLocationViewModel,
    onBackClick: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onOpenDateSettings: () -> Unit,
    onOpenVpnSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val onAction = viewModel::onAction
    val scrollState = rememberScrollState()

    // Visual expansion only; durable values live in the ViewModel.
    var profileExpanded by rememberSaveable { mutableStateOf(true) }
    var exitExpanded by rememberSaveable { mutableStateOf(false) }
    var gpsExpanded by rememberSaveable { mutableStateOf(false) }
    var browserExpanded by rememberSaveable { mutableStateOf(false) }
    var regionalExpanded by rememberSaveable { mutableStateOf(false) }
    var statusExpanded by rememberSaveable { mutableStateOf(true) }
    var privacyExpanded by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    // Consent for operations that interrupt the connection: "" = all servers, otherwise one server GUID.
    var pendingScan by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConnect by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.cl_title),
                onBackClick = onBackClick,
                isLoading = isLoading || !state.loaded
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
        ) {
            SettingsSwitchItem(
                title = stringResource(R.string.cl_enable_title),
                summary = stringResource(R.string.cl_enable_summary),
                checked = state.enabled,
                onCheckedChange = { onAction(CustomLocationAction.SetEnabled(it)) }
            )
            NoteText(stringResource(R.string.cl_disclaimer))
            if (state.storageRecovered) {
                NoteText(stringResource(R.string.cl_data_recovered), warning = true)
            }

            val profile = state.profile
            if (state.enabled && profile != null) {
                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.cl_section_profile),
                    expanded = profileExpanded,
                    onExpandedChange = { profileExpanded = it }
                )
                if (profileExpanded) {
                    SettingsListItem(
                        title = stringResource(R.string.cl_profile_active),
                        entries = state.profiles.map { it.name },
                        values = state.profiles.map { it.id },
                        selectedValue = profile.id,
                        onSelected = { onAction(CustomLocationAction.SelectProfile(it)) }
                    )
                    SettingsMenuItem(
                        title = stringResource(R.string.cl_profile_new),
                        onClick = { onAction(CustomLocationAction.CreateProfile) }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.cl_profile_name),
                        value = profile.name,
                        onValueChanged = { onAction(CustomLocationAction.SetName(it)) }
                    )
                    SettingsMenuItem(
                        title = stringResource(R.string.cl_country),
                        subtitle = state.countryName ?: stringResource(R.string.cl_not_selected),
                        onClick = { onAction(CustomLocationAction.OpenPicker(PickerKind.COUNTRY)) }
                    )
                    if (profile.countryCode != null) {
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_region),
                            subtitle = when {
                                state.regionName != null -> state.regionName
                                state.regionsAvailable -> stringResource(R.string.cl_not_selected)
                                else -> stringResource(R.string.cl_no_data)
                            },
                            onClick = {
                                if (state.regionsAvailable) onAction(CustomLocationAction.OpenPicker(PickerKind.REGION))
                            }
                        )
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_city),
                            subtitle = when {
                                state.cityLabel != null -> state.cityLabel
                                state.citiesAvailable -> stringResource(R.string.cl_not_selected)
                                else -> stringResource(R.string.cl_no_data)
                            },
                            onClick = {
                                if (state.citiesAvailable) onAction(CustomLocationAction.OpenPicker(PickerKind.CITY))
                            }
                        )
                    }
                    NoteText(
                        when (state.catalogStatus) {
                            CatalogStatus.UNAVAILABLE -> stringResource(R.string.cl_catalog_unavailable)
                            else -> stringResource(
                                R.string.cl_catalog_note,
                                state.catalogGenerated ?: stringResource(R.string.value_unknown)
                            )
                        }
                    )
                    NoteText(stringResource(R.string.cl_catalog_attribution))
                    if (state.hasInconsistentData) {
                        NoteText(stringResource(R.string.cl_inconsistent), warning = true)
                    }
                    SettingsListItem(
                        title = stringResource(R.string.cl_server_title),
                        entries = listOf(stringResource(R.string.cl_server_none)) + state.servers.map { it.label },
                        values = listOf("") + state.servers.map { it.guid },
                        selectedValue = profile.serverGuid.orEmpty(),
                        onSelected = { onAction(CustomLocationAction.SetAssociatedServer(it.ifEmpty { null })) }
                    )
                    NoteText(stringResource(R.string.cl_server_note))
                    if (state.profiles.isNotEmpty()) {
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_profile_delete),
                            onClick = { onAction(CustomLocationAction.DeleteProfile) }
                        )
                    }
                }

                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.cl_section_exit),
                    expanded = exitExpanded,
                    onExpandedChange = { exitExpanded = it }
                )
                if (exitExpanded) {
                    SettingsListItem(
                        title = stringResource(R.string.cl_strictness_title),
                        entries = listOf(
                            stringResource(R.string.cl_strictness_country),
                            stringResource(R.string.cl_strictness_prefer),
                            stringResource(R.string.cl_strictness_strict),
                        ),
                        values = MatchStrictness.entries.map { it.wireValue },
                        selectedValue = state.strictness.wireValue,
                        onSelected = { wire ->
                            MatchStrictness.fromWire(wire)?.let { onAction(CustomLocationAction.SetStrictness(it)) }
                        }
                    )
                    NoteText(stringResource(R.string.cl_strictness_note))
                    InfoBlock(
                        title = stringResource(R.string.cl_exit_requested),
                        body = listOfNotNull(state.countryName, state.regionName, state.cityLabel)
                            .joinToString(" › ").ifEmpty { stringResource(R.string.cl_not_selected) }
                    )
                    InfoBlock(
                        title = stringResource(R.string.cl_cur_title),
                        body = stringResource(currentExitText(state.currentExit))
                    )
                    InfoBlock(title = stringResource(R.string.cl_exit_observed), body = exitText(state))
                    val idle = state.busy == BusyKind.NONE
                    SettingsMenuItem(
                        title = stringResource(
                            if (state.busy == BusyKind.VERIFYING) R.string.cl_exit_checking else R.string.cl_exit_check
                        ),
                        onClick = { if (idle) onAction(CustomLocationAction.CheckExit) }
                    )
                    if (!idle) NoteText(stringResource(R.string.cl_scan_busy))
                    NoteText(stringResource(R.string.cl_exit_note))

                    NoteText(stringResource(R.string.cl_scan_note))
                    if (state.interruptedScan && idle) {
                        NoteText(stringResource(R.string.cl_scan_interrupted), warning = true)
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_scan_restore),
                            onClick = { onAction(CustomLocationAction.RestoreInterrupted) }
                        )
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_scan_dismiss),
                            onClick = { onAction(CustomLocationAction.DismissInterrupted) }
                        )
                    }
                    if (state.busy == BusyKind.SCANNING) {
                        NoteText(
                            stringResource(
                                R.string.cl_scan_progress,
                                (state.scanDone + 1).coerceAtMost(maxOf(state.scanTotal, 1)),
                                state.scanTotal
                            )
                        )
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_scan_cancel),
                            onClick = { onAction(CustomLocationAction.CancelScan) }
                        )
                    } else {
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_scan_all, state.maxScanCandidates),
                            subtitle = if (profile.countryCode == null) stringResource(R.string.cl_scan_need_country) else null,
                            onClick = {
                                if (idle) {
                                    if (profile.countryCode == null) onAction(CustomLocationAction.ScanAll)
                                    else pendingScan = ""
                                }
                            }
                        )
                    }
                    state.scanSummary?.let { summary ->
                        NoteText(
                            if (summary.verifiedMatches > 0) stringResource(R.string.cl_scan_result_found, summary.verifiedMatches)
                            else stringResource(R.string.cl_scan_result_none)
                        )
                        when (summary.stoppedEarly) {
                            StopReason.STATE_UNKNOWN -> NoteText(stringResource(R.string.cl_scan_stopped_unknown), warning = true)
                            StopReason.DEADLINE -> NoteText(stringResource(R.string.cl_scan_stopped_deadline))
                            null -> Unit
                        }
                        if (summary.sharedExit) NoteText(stringResource(R.string.cl_scan_shared_exit), warning = true)
                        if (summary.restoreFailed) NoteText(stringResource(R.string.cl_scan_restore_failed), warning = true)
                    }
                    if (state.candidates.isEmpty()) NoteText(stringResource(R.string.cl_servers_empty))
                    state.candidates.forEach { row ->
                        CandidateCard(
                            row = row,
                            idle = idle,
                            onTest = { pendingScan = row.result.guid },
                            onPrefer = { onAction(CustomLocationAction.PreferServer(row.result.guid)) },
                            onConnect = { pendingConnect = row.result.guid }
                        )
                    }
                    NoteText(stringResource(R.string.cl_cand_approx))
                    NoteText(
                        stringResource(
                            R.string.cl_privacy_geo,
                            state.providerHost.ifEmpty { stringResource(R.string.value_unknown) }
                        )
                    )
                }

                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.cl_section_gps),
                    expanded = gpsExpanded,
                    onExpandedChange = { gpsExpanded = it }
                )
                if (gpsExpanded) {
                    SettingsEditItem(
                        title = stringResource(R.string.cl_gps_coordinates),
                        value = CoordinateParser.format(profile.gpsLatitude, profile.gpsLongitude),
                        onValueChanged = { onAction(CustomLocationAction.SetCoordinates(it)) }
                    )
                    if (profile.cityId != null) {
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_gps_use_city),
                            onClick = { onAction(CustomLocationAction.UseCityCoordinates) }
                        )
                    }
                    InfoBlock(title = null, body = stringResource(mockStateText(state.mock.state)))
                    when (state.mock.state) {
                        MockLocationState.WAITING_FOR_SELECTION ->
                            SettingsMenuItem(
                                title = stringResource(R.string.cl_gps_open_dev),
                                onClick = onOpenDeveloperSettings
                            )

                        MockLocationState.READY, MockLocationState.DELIVERY_FAILED ->
                            SettingsMenuItem(
                                title = stringResource(R.string.cl_gps_start),
                                onClick = { onAction(CustomLocationAction.StartMockDelivery) }
                            )

                        MockLocationState.DELIVERING ->
                            SettingsMenuItem(
                                title = stringResource(R.string.cl_gps_stop),
                                onClick = { onAction(CustomLocationAction.StopMockDelivery) }
                            )

                        MockLocationState.NOT_CONFIGURED, MockLocationState.BUILD_UNSUPPORTED -> Unit
                    }
                    NoteText(stringResource(R.string.cl_gps_note))
                }

                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.cl_section_browser),
                    expanded = browserExpanded,
                    onExpandedChange = { browserExpanded = it }
                )
                if (browserExpanded) {
                    NoteText(stringResource(R.string.cl_browser_body))
                }

                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.cl_section_regional),
                    expanded = regionalExpanded,
                    onExpandedChange = { regionalExpanded = it }
                )
                if (regionalExpanded) {
                    SettingsEditItem(
                        title = stringResource(R.string.cl_tz_title),
                        value = profile.timeZoneId.orEmpty(),
                        onValueChanged = { onAction(CustomLocationAction.SetTimeZone(it)) }
                    )
                    InfoBlock(
                        title = null,
                        body = stringResource(R.string.cl_tz_device, state.deviceTimeZoneId)
                    )
                    SettingsMenuItem(
                        title = stringResource(R.string.cl_open_date_settings),
                        onClick = onOpenDateSettings
                    )
                    NoteText(stringResource(R.string.cl_tz_note))

                    val languageNames = stringArrayResource(R.array.language_select)
                    val languageCodes = stringArrayResource(R.array.language_select_value)
                    val languages = languageCodes.indices.mapNotNull { i ->
                        Language.fromCode(languageCodes[i]).languageTag.takeIf { it.isNotEmpty() }
                            ?.let { tag -> tag to languageNames.getOrElse(i) { tag } }
                    }
                    SettingsListItem(
                        title = stringResource(R.string.cl_locale_title),
                        entries = listOf(stringResource(R.string.cl_locale_none)) + languages.map { it.second },
                        values = listOf("") + languages.map { it.first },
                        selectedValue = profile.localeTag.orEmpty(),
                        onSelected = { onAction(CustomLocationAction.SetLocale(it.ifEmpty { null })) }
                    )
                    if (profile.localeTag != null) {
                        SettingsMenuItem(
                            title = stringResource(R.string.cl_locale_apply),
                            onClick = { onAction(CustomLocationAction.ApplyAppLanguage) }
                        )
                    }
                    NoteText(stringResource(R.string.cl_locale_note))
                }

                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.cl_section_status),
                    expanded = statusExpanded,
                    onExpandedChange = { statusExpanded = it }
                )
                if (statusExpanded) {
                    val report = state.report
                    if (report != null) {
                        InfoBlock(
                            title = stringResource(R.string.cl_section_status),
                            body = stringResource(profileStateText(report.state))
                        )
                        report.capabilities.forEach { row ->
                            InfoBlock(
                                title = stringResource(capabilityName(row.capability)),
                                body = stringResource(capabilityStatusText(row.status))
                            )
                        }
                        report.warnings.forEach { warning ->
                            NoteText(warningText(warning, state), warning = true)
                        }
                    }
                }

                CollapsiblePreferenceGroupHeader(
                    title = stringResource(R.string.cl_section_privacy),
                    expanded = privacyExpanded,
                    onExpandedChange = { privacyExpanded = it }
                )
                if (privacyExpanded) {
                    NoteText(stringResource(R.string.cl_audit_note))
                    state.audit.forEach { finding ->
                        NoteText(auditText(finding), warning = finding.severity == AuditSeverity.WARNING)
                    }
                    SettingsMenuItem(
                        title = stringResource(R.string.title_system_vpn_settings),
                        onClick = onOpenVpnSettings
                    )
                }
            }

            SettingsMenuItem(
                title = stringResource(R.string.cl_reset),
                onClick = { showResetConfirm = true }
            )
            Spacer(modifier = Modifier.height(24.dp))
            NavigationBarsSpacer()
        }
    }

    if (showResetConfirm) {
        ConfirmDialog(
            message = stringResource(R.string.cl_reset_confirm),
            onConfirm = {
                showResetConfirm = false
                onAction(CustomLocationAction.ResetAll)
            },
            onDismiss = { showResetConfirm = false }
        )
    }

    pendingScan?.let { target ->
        val count = if (target.isEmpty()) minOf(state.candidates.size, state.maxScanCandidates) else 1
        ConfirmDialog(
            message = stringResource(
                R.string.cl_scan_consent,
                count,
                state.providerHost.ifEmpty { stringResource(R.string.value_unknown) }
            ),
            onConfirm = {
                pendingScan = null
                onAction(if (target.isEmpty()) CustomLocationAction.ScanAll else CustomLocationAction.ScanOne(target))
            },
            onDismiss = { pendingScan = null }
        )
    }

    pendingConnect?.let { guid ->
        ConfirmDialog(
            message = stringResource(
                R.string.cl_connect_consent,
                state.candidates.firstOrNull { it.result.guid == guid }?.result?.label.orEmpty()
            ),
            onConfirm = {
                pendingConnect = null
                onAction(CustomLocationAction.ConnectCandidate(guid))
            },
            onDismiss = { pendingConnect = null }
        )
    }

    state.picker?.let { picker ->
        SearchableSelectDialog(
            title = stringResource(
                when (picker.kind) {
                    PickerKind.COUNTRY -> R.string.cl_country
                    PickerKind.REGION -> R.string.cl_region
                    PickerKind.CITY -> R.string.cl_city
                }
            ),
            picker = picker,
            onQueryChange = { onAction(CustomLocationAction.SetPickerQuery(it)) },
            onSelect = { onAction(CustomLocationAction.PickItem(it)) },
            onClear = { onAction(CustomLocationAction.ClearPickerSelection) },
            onDismiss = { onAction(CustomLocationAction.ClosePicker) }
        )
    }
}

@Composable
private fun NoteText(text: String, warning: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** One merged semantic node per row: the spoken text is "title, body". */
@Composable
private fun InfoBlock(title: String?, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchableSelectDialog(
    title: String,
    picker: PickerState,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = picker.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.cl_search_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (picker.items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cl_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(picker.items, key = { it.id }) { item ->
                            val selected = item.id == picker.selectedId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected,
                                        onClick = { onSelect(item.id) },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                                    if (!item.detail.isNullOrEmpty()) {
                                        Text(
                                            text = item.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (picker.selectedId != null) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.cl_clear_selection)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

// ---- state -> text mapping (resource lookups only) -------------------------------------------

@Composable
private fun exitText(state: CustomLocationUiState): String = when (val check = state.exitCheck) {
    ExitCheckState.Idle -> stringResource(R.string.cl_exit_not_checked)
    ExitCheckState.Running -> stringResource(R.string.cl_exit_checking)
    is ExitCheckState.Failed -> stringResource(
        when (check.reason) {
            ExitCheckFailure.NOT_RUNNING -> R.string.cl_exit_failed_not_running
            ExitCheckFailure.TIMEOUT -> R.string.cl_exit_failed_timeout
            ExitCheckFailure.CONNECTION_FAILED -> R.string.cl_exit_failed_test
            ExitCheckFailure.GEO_UNAVAILABLE -> R.string.cl_exit_failed_geo
            ExitCheckFailure.STATE_UNKNOWN -> R.string.cl_exit_failed_state
        }
    )

    is ExitCheckState.Success -> {
        val obs = check.observation
        val unknown = stringResource(R.string.value_unknown)
        val family = when (obs.ipFamily) {
            IpFamily.IPV4 -> stringResource(R.string.cl_ip_v4)
            IpFamily.IPV6 -> stringResource(R.string.cl_ip_v6)
            IpFamily.UNKNOWN -> unknown
        }
        val code = obs.countryCode
        val main = stringResource(
            R.string.cl_exit_result,
            code?.let { state.countryLabels[it] ?: it } ?: unknown,
            obs.ipAddress ?: unknown,
            family
        )
        val finer = listOfNotNull(obs.regionName, obs.cityName).joinToString(" · ")
        if (finer.isEmpty()) main else main + "\n" + stringResource(R.string.cl_cand_observed, finer)
    }
}

private fun currentExitText(state: CurrentExitState): Int = when (state) {
    CurrentExitState.NOT_CHECKED -> R.string.cl_cur_not_checked
    CurrentExitState.CHECKING -> R.string.cl_cur_checking
    CurrentExitState.NOT_CONNECTED -> R.string.cl_cur_not_connected
    CurrentExitState.CHECK_FAILED -> R.string.cl_cur_failed
    CurrentExitState.GEO_UNAVAILABLE -> R.string.cl_cur_geo
    CurrentExitState.STALE -> R.string.cl_cur_stale
    CurrentExitState.CONNECTED_VERIFIED -> R.string.cl_cur_verified
    CurrentExitState.CONNECTED_PARTIAL -> R.string.cl_cur_partial
    CurrentExitState.CONNECTED_MISMATCH -> R.string.cl_cur_mismatch
}

private fun candidateStateText(state: CandidateState): Int = when (state) {
    CandidateState.NOT_CHECKED -> R.string.cl_cand_not_checked
    CandidateState.CHECKING -> R.string.cl_cand_checking
    CandidateState.VERIFIED_MATCH -> R.string.cl_cand_verified
    CandidateState.COUNTRY_MATCH_ONLY -> R.string.cl_cand_country_only
    CandidateState.REGION_CITY_UNVERIFIED -> R.string.cl_cand_unverified
    CandidateState.NO_MATCH -> R.string.cl_cand_no_match
    CandidateState.CONNECTION_FAILED -> R.string.cl_cand_conn_failed
    CandidateState.GEO_UNAVAILABLE -> R.string.cl_cand_geo
    CandidateState.STALE -> R.string.cl_cand_stale
}

@Composable
private fun CandidateCard(
    row: CandidateRow,
    idle: Boolean,
    onTest: () -> Unit,
    onPrefer: () -> Unit,
    onConnect: () -> Unit,
) {
    val result = row.result
    val badges = listOfNotNull(
        if (row.isPreferred) stringResource(R.string.cl_cand_preferred_badge) else null,
        if (row.isSelected) stringResource(R.string.cl_cand_selected_badge) else null,
    ).joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (badges.isEmpty()) result.label else "${result.label}  ($badges)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(candidateStateText(result.state)),
            style = MaterialTheme.typography.bodyMedium,
            color = when (result.state) {
                CandidateState.NO_MATCH, CandidateState.CONNECTION_FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        result.observed?.let { obs ->
            val line = listOfNotNull(row.countryName ?: obs.countryCode, obs.regionName, obs.cityName).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.cl_cand_observed, line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        result.checkedAtMillis?.let { at ->
            Text(
                text = stringResource(
                    R.string.cl_cand_checked_at,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (idle) {
            TextButton(onClick = onTest) { Text(stringResource(R.string.cl_cand_test)) }
            if (result.state.isSelectable) {
                TextButton(onClick = onPrefer) { Text(stringResource(R.string.cl_cand_prefer)) }
                TextButton(onClick = onConnect) { Text(stringResource(R.string.cl_cand_connect)) }
            } else if (result.checkedAtMillis != null) {
                // Disabled by design, and said why: no verified match means nothing to choose.
                Text(
                    text = stringResource(R.string.cl_cand_unselectable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun mockStateText(state: MockLocationState): Int = when (state) {
    MockLocationState.NOT_CONFIGURED -> R.string.cl_gps_state_not_configured
    MockLocationState.BUILD_UNSUPPORTED -> R.string.cl_gps_state_build_unsupported
    MockLocationState.WAITING_FOR_SELECTION -> R.string.cl_gps_state_waiting
    MockLocationState.READY -> R.string.cl_gps_state_ready
    MockLocationState.DELIVERING -> R.string.cl_gps_state_delivering
    MockLocationState.DELIVERY_FAILED -> R.string.cl_gps_state_failed
}

private fun profileStateText(state: ProfileState): Int = when (state) {
    ProfileState.NOT_CONFIGURED -> R.string.cl_state_not_configured
    ProfileState.CONFIGURED -> R.string.cl_state_configured
    ProfileState.WAITING_FOR_PERMISSION -> R.string.cl_state_waiting
    ProfileState.APPLIED -> R.string.cl_state_applied
    ProfileState.PARTIALLY_APPLIED -> R.string.cl_state_partial
    ProfileState.UNSUPPORTED -> R.string.cl_state_unsupported
    ProfileState.VERIFICATION_FAILED -> R.string.cl_state_failed
}

private fun capabilityName(capability: LocationCapability): Int = when (capability) {
    LocationCapability.EXIT_LOCATION -> R.string.cl_section_exit
    LocationCapability.VIRTUAL_GPS -> R.string.cl_cap_gps
    LocationCapability.BROWSER_LOCATION -> R.string.cl_section_browser
    LocationCapability.TIME_ZONE -> R.string.cl_cap_tz
    LocationCapability.LOCALE -> R.string.cl_cap_locale
}

private fun capabilityStatusText(status: CapabilityStatus): Int = when (status) {
    CapabilityStatus.NOT_REQUESTED -> R.string.cl_cap_not_requested
    CapabilityStatus.SATISFIED -> R.string.cl_cap_satisfied
    CapabilityStatus.PENDING -> R.string.cl_cap_pending
    CapabilityStatus.WAITING_FOR_PERMISSION -> R.string.cl_cap_waiting
    CapabilityStatus.UNSUPPORTED -> R.string.cl_cap_unsupported
    CapabilityStatus.UNAVAILABLE -> R.string.cl_cap_unavailable
    CapabilityStatus.MISMATCH -> R.string.cl_cap_mismatch
    CapabilityStatus.PARTIAL -> R.string.cl_cap_partial
    CapabilityStatus.FAILED -> R.string.cl_cap_failed
}

@Composable
private fun warningText(warning: LocationWarning, state: CustomLocationUiState): String = when (warning) {
    is LocationWarning.ExitCountryMismatch -> stringResource(
        R.string.cl_warn_exit_mismatch,
        state.countryLabels[warning.requested] ?: warning.requested,
        state.countryLabels[warning.observed] ?: warning.observed
    )

    is LocationWarning.GpsFarFromCity -> stringResource(R.string.cl_warn_gps_far, warning.distanceKm)
    is LocationWarning.TimeZoneDiffers -> stringResource(R.string.cl_warn_tz, warning.requested, warning.device)
    LocationWarning.ServerNotSelected -> stringResource(R.string.cl_warn_server)
}

@Composable
private fun auditText(finding: AuditFinding): String = when (finding.id) {
    AuditFindingId.PROXY_ONLY_MODE -> stringResource(R.string.cl_audit_proxy_only)
    AuditFindingId.IPV6_NOT_ROUTED -> stringResource(R.string.cl_audit_ipv6)
    AuditFindingId.PREFER_IPV6_WITHOUT_IPV6 -> stringResource(R.string.cl_audit_prefer_ipv6)
    AuditFindingId.PRIVATE_DNS_ACTIVE -> stringResource(R.string.cl_audit_private_dns)
    AuditFindingId.ALWAYS_ON_NOT_READABLE -> stringResource(R.string.cl_audit_always_on)
    AuditFindingId.EXTERNAL_CHECK_NOT_HTTPS ->
        stringResource(R.string.cl_audit_not_https, finding.args.joinToString(", "))
    AuditFindingId.EXTERNAL_CHECK_DISCLOSURE ->
        stringResource(R.string.cl_audit_disclosure, finding.args.joinToString(", "))
}
