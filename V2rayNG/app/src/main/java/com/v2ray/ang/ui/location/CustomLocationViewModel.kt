package com.v2ray.ang.ui.location

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.location.CoordinateParser
import com.v2ray.ang.handler.location.CountryCatalog
import com.v2ray.ang.handler.location.CountryEntry
import com.v2ray.ang.handler.location.CandidateEvaluation
import com.v2ray.ang.handler.location.CandidateRanking
import com.v2ray.ang.handler.location.CandidateRef
import com.v2ray.ang.handler.location.CandidateResult
import com.v2ray.ang.handler.location.CandidateScanner
import com.v2ray.ang.handler.location.CandidateState
import com.v2ray.ang.handler.location.ConnectOutcome
import com.v2ray.ang.handler.location.ConnectionSnapshot
import com.v2ray.ang.handler.location.EvaluationInput
import com.v2ray.ang.handler.location.LocationMatcher
import com.v2ray.ang.handler.location.ExitCheckFailure
import com.v2ray.ang.handler.location.MatchStrictness
import com.v2ray.ang.handler.location.RefusalReason
import com.v2ray.ang.handler.location.ScanJournalRepository
import com.v2ray.ang.handler.location.ScanOutcome
import com.v2ray.ang.handler.location.AndroidServiceGateway
import com.v2ray.ang.handler.location.GatewayConnector
import com.v2ray.ang.handler.location.StopReason
import com.v2ray.ang.handler.location.ExitCheckState
import com.v2ray.ang.handler.location.GeoCatalog
import com.v2ray.ang.handler.location.GeoCatalogParser
import com.v2ray.ang.handler.location.LocationProfile
import com.v2ray.ang.handler.location.LocationProfileCodec
import com.v2ray.ang.handler.location.LocationProfileOps
import com.v2ray.ang.handler.location.LocationProfileRepository
import com.v2ray.ang.handler.location.LocationProfileStoreData
import com.v2ray.ang.handler.location.LocationProfileValidator
import com.v2ray.ang.handler.location.LocationStatusEvaluator
import com.v2ray.ang.handler.location.MmkvLocationStore
import com.v2ray.ang.handler.location.MockDeliveryResult
import com.v2ray.ang.handler.location.MockLocationController
import com.v2ray.ang.handler.location.MockLocationStateResolver
import com.v2ray.ang.handler.location.MockLocationStatus
import com.v2ray.ang.handler.location.NetworkChangeWatcher
import com.v2ray.ang.handler.location.PrivacyAudit
import com.v2ray.ang.handler.location.PrivacyAuditSource
import com.v2ray.ang.handler.location.RegionalEnvironment
import com.v2ray.ang.handler.location.RegionalSettingsAdapter
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Owns all Custom Location state. Selection is by profile id and server GUID, never by list index.
 * Blocking work (MMKV, asset parsing, binder calls) runs on Dispatchers.IO and CPU-bound filtering on
 * Dispatchers.Default; results are published through [state].
 *
 * Mock delivery is tied to this ViewModel (it stops when the screen is finished). A delivery that
 * outlives the screen would need a foreground service, which is deliberately not added; see
 * docs/CUSTOM_LOCATION.md.
 */
class CustomLocationViewModel(application: Application) : BaseViewModel(application) {

    private data class Model(
        val loaded: Boolean = false,
        val enabled: Boolean = false,
        val profiles: List<LocationProfile> = emptyList(),
        val activeId: String? = null,
        val catalog: GeoCatalog? = null,
        val catalogLoading: Boolean = true,
        val countries: List<CountryEntry> = emptyList(),
        val exitCheck: ExitCheckState = ExitCheckState.Idle,
        val servers: List<ServerOption> = emptyList(),
        val selectedGuid: String? = null,
        val mockDeclared: Boolean = false,
        val mockSelected: Boolean = false,
        val mockDelivering: Boolean = false,
        val mockFailed: Boolean = false,
        val deliveredLat: Double? = null,
        val deliveredLon: Double? = null,
        val regional: RegionalEnvironment = RegionalEnvironment("", null, emptySet()),
        val audit: List<com.v2ray.ang.handler.location.AuditFinding> = emptyList(),
        val storageRecovered: Boolean = false,
        val picker: PickerState? = null,
        val nowMillis: Long = System.currentTimeMillis(),
        /** In-memory only. Never persisted; the IP address is not retained in these results. */
        val results: Map<String, CandidateResult> = emptyMap(),
        val busy: BusyKind = BusyKind.NONE,
        val scanDone: Int = 0,
        val scanTotal: Int = 0,
        val scanSummary: ScanSummary? = null,
        val interrupted: ConnectionSnapshot? = null,
        /** True for any stored record, readable or not: it blocks new operations until restored or dismissed. */
        val interruptedPending: Boolean = false,
        val providerHost: String = "",
    )

    private val repository = LocationProfileRepository(MmkvLocationStore)
    private val journal = ScanJournalRepository(MmkvLocationStore)
    private var scanJob: Job? = null
    private val mock = MockLocationController(application)
    private val saveMutex = Mutex()
    private val model = MutableStateFlow(Model())
    private val _state = MutableStateFlow(CustomLocationUiState())
    val state: StateFlow<CustomLocationUiState> = _state.asStateFlow()

    private var operationJob: Job? = null
    private var mockJob: Job? = null
    private var pickerJob: Job? = null
    private val displayLocale: Locale
        get() = localizedContext.resources.configuration.locales.get(0)

    private val networkWatcher = NetworkChangeWatcher(application) {
        // Binder thread: hop to the ViewModel scope before touching state.
        viewModelScope.launch { invalidateExitObservation() }
    }

    init {
        viewModelScope.launch {
            loadInitial()
            networkWatcher.start()
            // Re-evaluate freshness periodically so an old observation cannot stay "confirmed".
            while (isActive) {
                delay(30_000)
                model.update { it.copy(nowMillis = System.currentTimeMillis()) }
                publish()
            }
        }
    }

    override fun onCleared() {
        networkWatcher.stop()
        super.onCleared()
    }

    // ---- actions ---------------------------------------------------------------------------

    fun onAction(action: CustomLocationAction) {
        when (action) {
            is CustomLocationAction.SetEnabled -> setEnabled(action.enabled)
            is CustomLocationAction.SelectProfile -> selectProfile(action.id)
            CustomLocationAction.CreateProfile -> createProfile()
            CustomLocationAction.DeleteProfile -> deleteProfile()
            is CustomLocationAction.SetName -> edit { LocationProfileOps.withName(it, action.name) }

            is CustomLocationAction.OpenPicker -> openPicker(action.kind)
            is CustomLocationAction.SetPickerQuery -> setPickerQuery(action.query)
            is CustomLocationAction.PickItem -> pickItem(action.id)
            CustomLocationAction.ClearPickerSelection -> clearPickerSelection()
            CustomLocationAction.ClosePicker -> closePicker()

            is CustomLocationAction.SetAssociatedServer -> edit { LocationProfileOps.withServer(it, action.guid) }

            is CustomLocationAction.SetCoordinates -> setCoordinates(action.text)
            CustomLocationAction.UseCityCoordinates -> useCityCoordinates()
            CustomLocationAction.StartMockDelivery -> startMockDelivery()
            CustomLocationAction.StopMockDelivery -> stopMockDelivery()

            is CustomLocationAction.SetTimeZone -> setTimeZone(action.text)
            is CustomLocationAction.SetLocale -> edit { LocationProfileOps.withLocale(it, action.tag) }
            CustomLocationAction.ApplyAppLanguage -> applyAppLanguage()

            is CustomLocationAction.SetStrictness -> edit { LocationProfileOps.withStrictness(it, action.strictness) }
            CustomLocationAction.ScanAll -> startScan(null)
            is CustomLocationAction.ScanOne -> startScan(action.guid)
            CustomLocationAction.CancelScan -> scanJob?.cancel()
            is CustomLocationAction.PreferServer -> edit { LocationProfileOps.withServer(it, action.guid) }
            is CustomLocationAction.ConnectCandidate -> connectCandidate(action.guid)
            CustomLocationAction.RestoreInterrupted -> restoreInterrupted()
            CustomLocationAction.DismissInterrupted -> dismissInterrupted()
            CustomLocationAction.CheckExit -> verifyCurrent()
            CustomLocationAction.RefreshEnvironment -> refreshEnvironment()
            CustomLocationAction.ResetAll -> resetAll()
        }
    }

    // ---- loading ---------------------------------------------------------------------------

    private suspend fun loadInitial() {
        val loaded = withContext(Dispatchers.IO) {
            val data = repository.load()
            if (data.issues.isNotEmpty()) {
                // Counts only; never profile contents.
                LogUtil.w(AppConfig.TAG, "CustomLocation: ${data.issues.size} storage issue(s) while loading")
            }
            Triple(repository.isEnabled(), data, readSystemSnapshot())
        }
        val (enabled, data, snapshot) = loaded
        model.update {
            it.copy(
                loaded = true,
                enabled = enabled,
                profiles = data.data.profiles,
                activeId = data.data.activeProfileId ?: data.data.profiles.firstOrNull()?.id,
                storageRecovered = data.issues.isNotEmpty(),
                servers = snapshot.servers,
                selectedGuid = snapshot.selectedGuid,
                mockDeclared = snapshot.mockDeclared,
                mockSelected = snapshot.mockSelected,
                regional = snapshot.regional,
                audit = snapshot.audit,
                providerHost = snapshot.providerHost,
                interrupted = journal.load(), interruptedPending = journal.hasPending(),
            )
        }
        publish()
        // Catalog and country list are heavier; load after the first frame has data to show.
        val catalog = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().assets.open(CATALOG_ASSET).use { GeoCatalogParser.parse(it) }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "CustomLocation: cannot open catalog asset", e)
                null
            }
        }
        val countries = withContext(Dispatchers.Default) { CountryCatalog.entries(displayLocale) }
        if (catalog != null && catalog.issues.isNotEmpty()) {
            LogUtil.w(AppConfig.TAG, "CustomLocation: catalog loaded with ${catalog.issues.size} issue(s)")
        }
        model.update { it.copy(catalog = catalog?.takeUnless { c -> c.isEmpty }, catalogLoading = false, countries = countries) }
        publish()
    }

    private data class SystemSnapshot(
        val servers: List<ServerOption>,
        val selectedGuid: String?,
        val mockDeclared: Boolean,
        val mockSelected: Boolean,
        val regional: RegionalEnvironment,
        val audit: List<com.v2ray.ang.handler.location.AuditFinding>,
        val providerHost: String,
    )

    private fun readSystemSnapshot(): SystemSnapshot {
        val servers = MmkvManager.decodeAllServerList().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { profile ->
                // Display name only; host, credentials and share links are never surfaced here.
                ServerOption(guid, profile.remarks.ifBlank { profile.configType.name })
            }
        }
        val auditInput = PrivacyAuditSource.read(getApplication())
        return SystemSnapshot(
            servers = servers,
            selectedGuid = MmkvManager.getSelectServer(),
            mockDeclared = mock.isPermissionDeclared(),
            mockSelected = mock.isSelectedAsMockApp(),
            regional = RegionalSettingsAdapter.environment(),
            audit = PrivacyAudit.run(auditInput),
            providerHost = PrivacyAudit.hostOf(auditInput.ipApiUrl).orEmpty(),
        )
    }

    private fun refreshEnvironment() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readSystemSnapshot() }
            model.update {
                it.copy(
                    servers = snapshot.servers,
                    selectedGuid = snapshot.selectedGuid,
                    mockDeclared = snapshot.mockDeclared,
                    mockSelected = snapshot.mockSelected,
                    regional = snapshot.regional,
                    audit = snapshot.audit,
                    providerHost = snapshot.providerHost,
                    nowMillis = System.currentTimeMillis(),
                )
            }
            publish()
        }
    }

    // ---- profiles --------------------------------------------------------------------------

    private fun activeProfile(m: Model = model.value): LocationProfile? = m.profiles.firstOrNull { it.id == m.activeId }

    private fun edit(transform: (LocationProfile) -> LocationProfile) {
        val current = activeProfile() ?: return
        replaceActive(transform(current))
    }

    private fun replaceActive(updated: LocationProfile) {
        model.update { m -> m.copy(profiles = m.profiles.map { if (it.id == updated.id) updated else it }) }
        // The exit observation is kept: it describes the connection, not the request, and the
        // evaluator re-compares it against the edited request.
        publish()
        persist()
    }

    private fun setEnabled(enabled: Boolean) {
        if (!enabled) stopMockDelivery()
        model.update { it.copy(enabled = enabled) }
        if (enabled && model.value.profiles.isEmpty()) createProfile() else publish()
        viewModelScope.launch(Dispatchers.IO) { repository.setEnabled(enabled) }
    }

    private fun createProfile() {
        if (model.value.profiles.size >= LocationProfileCodec.MAX_PROFILES) return
        val number = model.value.profiles.size + 1
        val profile = LocationProfile(
            id = LocationProfile.newId(),
            name = getString(R.string.cl_profile_default_name, number),
        )
        model.update { it.copy(profiles = it.profiles + profile, activeId = profile.id, exitCheck = ExitCheckState.Idle) }
        publish()
        persist()
    }

    private fun deleteProfile() {
        val id = model.value.activeId ?: return
        model.update { m ->
            val remaining = m.profiles.filterNot { it.id == id }
            m.copy(profiles = remaining, activeId = remaining.firstOrNull()?.id, exitCheck = ExitCheckState.Idle)
        }
        stopMockDelivery()
        publish()
        persist()
    }

    private fun selectProfile(id: String) {
        if (model.value.profiles.none { it.id == id }) return
        // Only the active profile id changes. No server is selected, started or stopped here.
        model.update { it.copy(activeId = id, exitCheck = ExitCheckState.Idle) }
        publish()
        persist()
    }

    private fun persist() {
        viewModelScope.launch(Dispatchers.IO) {
            saveMutex.withLock {
                val m = model.value
                repository.save(LocationProfileStoreData(m.profiles, m.activeId))
            }
        }
    }

    // ---- pickers ---------------------------------------------------------------------------

    private fun openPicker(kind: PickerKind) {
        val profile = activeProfile() ?: return
        val selected = when (kind) {
            PickerKind.COUNTRY -> profile.countryCode
            PickerKind.REGION -> profile.regionCode
            PickerKind.CITY -> profile.cityId
        }
        model.update { it.copy(picker = PickerState(kind, "", emptyList(), selected)) }
        recomputePicker("")
    }

    private fun setPickerQuery(query: String) {
        model.update { m -> m.copy(picker = m.picker?.copy(query = query)) }
        publish()
        recomputePicker(query)
    }

    private fun recomputePicker(query: String) {
        val m = model.value
        val kind = m.picker?.kind ?: return
        val profile = activeProfile(m)
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            val items = withContext(Dispatchers.Default) { buildPickerItems(kind, query, m, profile) }
            model.update { cur ->
                // Drop a result that a newer query or a closed picker has superseded.
                val open = cur.picker
                if (open != null && open.kind == kind && open.query == query) {
                    cur.copy(picker = open.copy(items = items))
                } else cur
            }
            publish()
        }
    }

    private fun buildPickerItems(kind: PickerKind, query: String, m: Model, profile: LocationProfile?): List<PickerItem> {
        val catalog = m.catalog
        return when (kind) {
            PickerKind.COUNTRY -> m.countries
                .filter { CountryCatalog.matches(query, it.name, it.code) }
                .map { PickerItem(it.code, it.name, it.code) }

            PickerKind.REGION -> {
                val cc = profile?.countryCode ?: return emptyList()
                catalog?.regionsOf(cc).orEmpty()
                    .filter { CountryCatalog.matches(query, it.name, it.code) }
                    .map { PickerItem(it.code, it.name, it.code) }
            }

            PickerKind.CITY -> {
                val cc = profile?.countryCode ?: return emptyList()
                val linked = catalog?.citiesOf(cc, profile.regionCode).orEmpty()
                // City-to-region links are incomplete in the source data, so when the chosen region has
                // no linked cities fall back to the whole country instead of showing an empty list.
                val cities = linked.ifEmpty { catalog?.citiesOf(cc).orEmpty() }
                cities
                    .filter { CountryCatalog.matches(query, it.name) }
                    .map { PickerItem(it.id, catalog!!.cityLabel(it), catalog.regionName(it.regionCode)) }
            }
        }
    }

    private fun pickItem(id: String) {
        val kind = model.value.picker?.kind ?: return
        val catalog = model.value.catalog
        when (kind) {
            PickerKind.COUNTRY -> edit { LocationProfileOps.withCountry(it, id) }
            PickerKind.REGION -> catalog?.let { c -> edit { LocationProfileOps.withRegion(it, id, c) } }
            PickerKind.CITY -> catalog?.let { c -> edit { LocationProfileOps.withCity(it, id, c) } }
        }
        closePicker()
    }

    private fun clearPickerSelection() {
        val catalog = model.value.catalog
        when (model.value.picker?.kind ?: return) {
            PickerKind.COUNTRY -> edit { LocationProfileOps.withCountry(it, null) }
            PickerKind.REGION -> catalog?.let { c -> edit { LocationProfileOps.withRegion(it, null, c) } }
            PickerKind.CITY -> catalog?.let { c -> edit { LocationProfileOps.withCity(it, null, c) } }
        }
        closePicker()
    }

    private fun closePicker() {
        pickerJob?.cancel()
        model.update { it.copy(picker = null) }
        publish()
    }

    // ---- GPS -------------------------------------------------------------------------------

    private fun setCoordinates(text: String) {
        when (val parsed = CoordinateParser.parse(text)) {
            CoordinateParser.Result.Cleared -> edit { LocationProfileOps.withGps(it, null, null) }
            CoordinateParser.Result.Invalid -> toastError(R.string.cl_gps_invalid)
            is CoordinateParser.Result.Parsed -> edit { LocationProfileOps.withGps(it, parsed.latitude, parsed.longitude) }
        }
    }

    private fun useCityCoordinates() {
        val m = model.value
        val city = m.catalog?.city(activeProfile(m)?.cityId) ?: return
        edit { LocationProfileOps.withGps(it, city.latitude, city.longitude) }
    }

    private fun startMockDelivery() {
        val profile = activeProfile() ?: return
        if (!profile.hasGps) return
        mockJob?.cancel()
        mockJob = viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { mock.isPermissionDeclared() to mock.isSelectedAsMockApp() }
            model.update { it.copy(mockDeclared = ready.first, mockSelected = ready.second, mockFailed = false) }
            publish()
            if (!ready.first || !ready.second) return@launch
            try {
                while (isActive) {
                    val current = activeProfile() ?: break
                    val la = current.gpsLatitude ?: break
                    val lo = current.gpsLongitude ?: break
                    val result = withContext(Dispatchers.IO) { mock.deliver(la, lo) }
                    if (result == MockDeliveryResult.DELIVERED) {
                        model.update { it.copy(mockDelivering = true, mockFailed = false, deliveredLat = la, deliveredLon = lo) }
                        publish()
                    } else {
                        model.update { it.copy(mockDelivering = false, mockFailed = true) }
                        publish()
                        break
                    }
                    delay(MOCK_INTERVAL_MILLIS)
                }
            } finally {
                // Always remove the test providers so real location sources resume.
                withContext(NonCancellable + Dispatchers.IO) { mock.stop() }
                model.update { it.copy(mockDelivering = false) }
                publish()
            }
        }
    }

    private fun stopMockDelivery() {
        mockJob?.cancel()
        mockJob = null
    }

    // ---- regional --------------------------------------------------------------------------

    private fun setTimeZone(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            edit { LocationProfileOps.withTimeZone(it, null) }
        } else if (LocationProfileValidator.isValidTimeZone(trimmed)) {
            edit { LocationProfileOps.withTimeZone(it, trimmed) }
        } else {
            toastError(R.string.cl_tz_invalid)
        }
    }

    private fun applyAppLanguage() {
        val tag = activeProfile()?.localeTag ?: return
        if (RegionalSettingsAdapter.applyAppLanguage(tag)) {
            refreshEnvironment()
        } else {
            toastError(R.string.cl_locale_unsupported)
        }
    }

    // ---- exit verification, scanning and connecting -----------------------------------------

    private fun providerHost(): String = model.value.providerHost.ifEmpty { "ip-geolocation provider" }

    private suspend fun <T> withConnector(block: suspend (GatewayConnector) -> T): T {
        val gateway = AndroidServiceGateway(getApplication())
        gateway.open()
        try {
            return block(GatewayConnector(gateway, ::providerHost))
        } finally {
            gateway.close()
        }
    }

    /** Mirrors the main screen's own rule: starting in VPN mode needs the system VPN consent first. */
    private fun vpnPermissionMissing(): Boolean =
        !SettingsManager.isRootMode() && SettingsManager.isVpnMode() &&
            VpnService.prepare(getApplication<Application>()) != null

    private fun tryBegin(kind: BusyKind): Boolean {
        if (model.value.busy != BusyKind.NONE) {
            toastError(R.string.cl_scan_busy)
            return false
        }
        // An unresolved interrupted scan holds the only record of the user's original connection; a new
        // operation would overwrite it. Verifying the current connection changes nothing, so it is allowed.
        if (model.value.interruptedPending && kind != BusyKind.VERIFYING && kind != BusyKind.RESTORING) {
            toastError(R.string.cl_scan_resolve_first)
            return false
        }
        model.update { it.copy(busy = kind) }
        publish()
        return true
    }

    private fun end() {
        model.update { it.copy(busy = BusyKind.NONE, scanDone = 0, scanTotal = 0) }
        publish()
    }

    private fun toCheckState(outcome: ConnectOutcome): ExitCheckState = when (outcome) {
        is ConnectOutcome.Observed -> ExitCheckState.Success(outcome.location)
        ConnectOutcome.ConnectionFailed -> ExitCheckState.Failed(ExitCheckFailure.CONNECTION_FAILED)
        ConnectOutcome.GeoUnavailable -> ExitCheckState.Failed(ExitCheckFailure.GEO_UNAVAILABLE)
        ConnectOutcome.NotRunning -> ExitCheckState.Failed(ExitCheckFailure.NOT_RUNNING)
        ConnectOutcome.StateUnknown -> ExitCheckState.Failed(ExitCheckFailure.STATE_UNKNOWN)
    }

    /** Checks the connection that is active right now. Starts, stops and switches nothing. */
    private fun verifyCurrent() {
        if (!tryBegin(BusyKind.VERIFYING)) return
        model.update { it.copy(exitCheck = ExitCheckState.Running) }
        publish()
        operationJob = viewModelScope.launch {
            try {
                val result = withConnector { it.observeActive() }
                val guid = withContext(Dispatchers.IO) { MmkvManager.getSelectServer() }
                model.update { it.copy(exitCheck = toCheckState(result), selectedGuid = guid, nowMillis = System.currentTimeMillis()) }
            } finally {
                end()
            }
        }
    }

    /** Explicit user action: switch to [guid], connect, and verify what is actually observed. */
    private fun connectCandidate(guid: String) {
        if (model.value.servers.none { it.guid == guid }) return
        if (vpnPermissionMissing()) {
            toastError(R.string.cl_scan_vpn_permission)
            return
        }
        if (!tryBegin(BusyKind.CONNECTING)) return
        model.update { it.copy(exitCheck = ExitCheckState.Running) }
        publish()
        operationJob = viewModelScope.launch {
            try {
                var acceptable = false
                val result = withConnector { connector ->
                    // Needed to offer "restore" if the switch fails; unknown state means we change nothing.
                    val before = connector.snapshot()
                    if (before == null) {
                        ConnectOutcome.StateUnknown
                    } else {
                        if (before.selectedGuid != null) journal.save(before)
                        connector.connectAndObserve(guid)
                    }
                }
                if (result is ConnectOutcome.Observed) {
                    val desired = activeProfile()?.toDesired()
                    acceptable = desired != null &&
                        CandidateEvaluation.stateOf(LocationMatcher.evaluate(desired, result.location, model.value.catalog).verdict)
                            .isSelectable
                }
                // The user chose this switch and the outcome is acceptable: nothing to recover. Otherwise the
                // journal stays so "Restore previous state" remains available (never restored silently).
                if (acceptable) journal.clear()
                val selected = withContext(Dispatchers.IO) { MmkvManager.getSelectServer() }
                model.update {
                    it.copy(
                        exitCheck = toCheckState(result), selectedGuid = selected,
                        nowMillis = System.currentTimeMillis(), interrupted = journal.load(), interruptedPending = journal.hasPending()
                    )
                }
            } finally {
                end()
            }
        }
    }

    /** [onlyGuid] null means "all candidates"; the caller (UI) has shown the consent dialog already. */
    private fun startScan(onlyGuid: String?) {
        val m = model.value
        val desired = activeProfile(m)?.toDesired()
        if (desired == null) {
            toastError(R.string.cl_scan_need_country)
            return
        }
        val ordered = buildList {
            val preferred = activeProfile(m)?.serverGuid
            m.servers.firstOrNull { it.guid == preferred }?.let { add(it) }
            m.servers.firstOrNull { it.guid == m.selectedGuid }?.let { add(it) }
            addAll(m.servers)
        }.distinctBy { it.guid }
        val chosen = if (onlyGuid != null) ordered.filter { it.guid == onlyGuid } else ordered
        if (chosen.isEmpty()) {
            toastError(R.string.cl_scan_no_servers)
            return
        }
        if (vpnPermissionMissing()) {
            toastError(R.string.cl_scan_vpn_permission)
            return
        }
        if (!tryBegin(BusyKind.SCANNING)) return
        val refs = chosen.take(CandidateScanner.MAX_CANDIDATES).map { CandidateRef(it.guid, it.label) }
        scanJob = viewModelScope.launch {
            var restoreFailed = false
            var stoppedEarly: StopReason? = null
            var ranToEnd = false
            try {
                withConnector { connector ->
                    val scanner = CandidateScanner(connector, journal, desired, m.catalog)
                    val outcome = scanner.scan(
                        refs,
                        onUpdate = { list ->
                            model.update { cur ->
                                cur.copy(
                                    // Unchecked rows (including ones reset after cancellation) carry no result.
                                    results = list.fold(cur.results) { acc, r ->
                                        if (r.state == CandidateState.NOT_CHECKED && r.observed == null) acc - r.guid
                                        else acc + (r.guid to r)
                                    },
                                    scanDone = list.count { it.state != CandidateState.NOT_CHECKED && it.state != CandidateState.CHECKING },
                                    scanTotal = list.size,
                                )
                            }
                            publish()
                        },
                        onRestoreFinished = { restoreFailed = !it }
                    )
                    if (outcome is ScanOutcome.Refused) {
                        toastError(
                            when (outcome.reason) {
                                RefusalReason.ALREADY_RUNNING -> R.string.cl_scan_busy
                                RefusalReason.NO_CANDIDATES -> R.string.cl_scan_no_servers
                                RefusalReason.STATE_UNKNOWN -> R.string.cl_scan_refused_unknown
                                RefusalReason.NO_SELECTED_SERVER -> R.string.cl_scan_no_selection
                                RefusalReason.INTERRUPTED_SCAN_PENDING -> R.string.cl_scan_resolve_first
                            }
                        )
                    }
                    if (outcome is ScanOutcome.Stopped) stoppedEarly = outcome.reason
                    // Refused or cancelled scans produce no summary: nothing was fully tested.
                    ranToEnd = outcome is ScanOutcome.Completed || outcome is ScanOutcome.Stopped
                }
            } finally {
                // The connection state may have changed (restored or not); the old exit observation no longer applies.
                withContext(NonCancellable) {
                    // Also covers leaving the screen mid-scan: the user is told even though the screen is gone.
                    if (restoreFailed) toastError(R.string.cl_scan_restore_failed)
                    val matches = model.value.results.values.count { it.state == CandidateState.VERIFIED_MATCH }
                    val tested = model.value.results.values.count { it.checkedAtMillis != null }
                    model.update {
                        it.copy(
                            exitCheck = ExitCheckState.Idle,
                            selectedGuid = MmkvManager.getSelectServer(),
                            scanSummary = if (ranToEnd) {
                                ScanSummary(
                                    tested, matches, restoreFailed, stoppedEarly,
                                    sharedExit = CandidateEvaluation.sharedExit(it.results.values)
                                )
                            } else null,
                            interrupted = journal.load(), interruptedPending = journal.hasPending(),
                        )
                    }
                    end()
                }
            }
        }
    }

    private fun restoreInterrupted() {
        val snapshot = model.value.interrupted
        if (snapshot == null) {
            // The record exists but cannot be read: restoring is impossible, so say so. It can be dismissed.
            toastError(R.string.cl_scan_restore_failed)
            return
        }
        if (!tryBegin(BusyKind.RESTORING)) return
        operationJob = viewModelScope.launch {
            try {
                val ok = withConnector { it.restore(snapshot) }
                if (ok) journal.clear() else toastError(R.string.cl_scan_restore_failed)
                model.update { it.copy(interrupted = journal.load(), interruptedPending = journal.hasPending(), selectedGuid = MmkvManager.getSelectServer(), exitCheck = ExitCheckState.Idle) }
            } finally {
                end()
            }
        }
    }

    private fun dismissInterrupted() {
        if (model.value.busy != BusyKind.NONE) return
        journal.clear()
        model.update { it.copy(interrupted = null, interruptedPending = false) }
        publish()
    }

    private fun invalidateExitObservation() {
        // A network change can alter the exit; never keep presenting the old result as current.
        if (model.value.busy != BusyKind.NONE) return
        val current = model.value.exitCheck
        if (current is ExitCheckState.Success || current is ExitCheckState.Failed) {
            model.update { it.copy(exitCheck = ExitCheckState.Idle) }
            publish()
        }
    }

    // ---- reset -----------------------------------------------------------------------------

    private fun resetAll() {
        stopMockDelivery()
        scanJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { saveMutex.withLock { repository.reset() } }
            model.update {
                it.copy(
                    enabled = false, profiles = emptyList(), activeId = null, exitCheck = ExitCheckState.Idle,
                    picker = null, storageRecovered = false, mockFailed = false,
                )
            }
            publish()
            toastSuccess(R.string.cl_reset_done)
        }
    }

    // ---- state assembly --------------------------------------------------------------------

    private fun publish() {
        val m = model.value
        val profile = activeProfile(m)
        val catalog = m.catalog
        val mockState = MockLocationStateResolver.resolve(
            hasCoordinates = profile?.hasGps == true,
            permissionDeclared = m.mockDeclared,
            selectedAsMockApp = m.mockSelected,
            delivering = m.mockDelivering,
            lastDeliveryFailed = m.mockFailed,
        )
        val mockStatus = MockLocationStatus(mockState, m.deliveredLat, m.deliveredLon)
        val evaluation = profile?.let {
            EvaluationInput(it, catalog, m.exitCheck, m.selectedGuid, m.nowMillis, mockStatus, m.regional)
        }
        val report = evaluation?.let { LocationStatusEvaluator.evaluate(it) }
        val desired = profile?.toDesired()
        val locale = displayLocale
        val labels = buildMap<String, String> {
            profile?.countryCode?.let { code -> CountryCatalog.nameOf(code, locale)?.let { put(code, it) } }
            (m.exitCheck as? ExitCheckState.Success)?.observation?.countryCode
                ?.let { LocationProfileValidator.normalizeCountry(it) }
                ?.let { code -> CountryCatalog.nameOf(code, locale)?.let { put(code, it) } }
        }
        val cc = profile?.countryCode
        val rows = CandidateRanking.sorted(
            m.servers.map { server ->
                val base = m.results[server.guid]?.copy(label = server.label) ?: CandidateResult(server.guid, server.label)
                CandidateEvaluation.reclassify(base, desired, catalog)
            },
            m.nowMillis
        ).map { r ->
            CandidateRow(
                result = r.copy(state = r.effectiveState(m.nowMillis)),
                isPreferred = profile?.serverGuid == r.guid,
                isSelected = m.selectedGuid == r.guid,
                countryName = r.observed?.countryCode?.let { CountryCatalog.nameOf(it, locale) },
            )
        }
        _state.value = CustomLocationUiState(
            loaded = m.loaded,
            enabled = m.enabled,
            profiles = m.profiles.map { ProfileSummary(it.id, it.name.ifBlank { it.id.take(8) }) },
            profile = profile,
            countryName = cc?.let { labels[it] ?: it },
            regionName = catalog?.regionName(profile?.regionCode) ?: profile?.regionCode,
            cityLabel = profile?.cityId?.let { id -> catalog?.let { c -> c.city(id)?.let { c.cityLabel(it) } } ?: id },
            regionsAvailable = cc != null && catalog?.regionsOf(cc)?.isNotEmpty() == true,
            citiesAvailable = cc != null && catalog?.citiesOf(cc)?.isNotEmpty() == true,
            catalogStatus = when {
                m.catalogLoading -> CatalogStatus.LOADING
                catalog == null -> CatalogStatus.UNAVAILABLE
                else -> CatalogStatus.READY
            },
            catalogGenerated = catalog?.meta?.generated,
            servers = m.servers,
            report = report,
            exitCheck = m.exitCheck,
            currentExit = evaluation?.let { LocationStatusEvaluator.currentExit(it) }
                ?: com.v2ray.ang.handler.location.CurrentExitState.NOT_CHECKED,
            strictness = profile?.matchStrictness ?: MatchStrictness.DEFAULT,
            candidates = rows,
            busy = m.busy,
            scanDone = m.scanDone,
            scanTotal = m.scanTotal,
            scanSummary = m.scanSummary,
            interruptedScan = m.interruptedPending,
            providerHost = m.providerHost,
            maxScanCandidates = CandidateScanner.MAX_CANDIDATES,
            countryLabels = labels,
            mock = mockStatus,
            deviceTimeZoneId = m.regional.deviceTimeZoneId,
            audit = m.audit,
            hasInconsistentData = profile != null && LocationProfileValidator.validate(profile, catalog).isNotEmpty(),
            storageRecovered = m.storageRecovered,
            picker = m.picker,
        )
    }

    private companion object {
        const val CATALOG_ASSET = "location_catalog.json"
        const val MOCK_INTERVAL_MILLIS = 1_000L
    }
}
