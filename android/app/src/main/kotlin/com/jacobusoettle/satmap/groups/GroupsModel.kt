package com.jacobusoettle.satmap.groups

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jacobusoettle.satmap.BuildConfig
import com.jacobusoettle.satmap.app.Connectivity
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.GroupConfig
import androidx.annotation.VisibleForTesting
import com.jacobusoettle.satmap.core.FakeGroupServer
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupActionError
import com.jacobusoettle.satmap.core.GroupAlert
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.GroupHub
import com.jacobusoettle.satmap.core.GroupHubEvent
import com.jacobusoettle.satmap.core.GroupMapContent
import com.jacobusoettle.satmap.core.GroupRole
import com.jacobusoettle.satmap.core.GroupStore
import com.jacobusoettle.satmap.core.GroupSummary
import com.jacobusoettle.satmap.core.GroupText
import com.jacobusoettle.satmap.core.GroupTextField
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.core.MadeInvite
import com.jacobusoettle.satmap.core.MemoryGroupSecrets
import com.jacobusoettle.satmap.core.PausedReason
import com.jacobusoettle.satmap.core.ReportTarget
import com.jacobusoettle.satmap.core.TrackPoint
import com.jacobusoettle.satmap.core.WordFilter
import com.jacobusoettle.satmap.location.GpsFix
import com.jacobusoettle.satmap.location.WakeTimer
import com.jacobusoettle.satmap.offline.AndroidSqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The groups sheet's screens (docs/groups/SPEC.md §9.2), one list for both apps, plus `DropPin` (S10, opened from the
 *  map's capsule and from S5) and `Join` (S8, in its own sheet). Plain data: what a screen remembers is kept by
 *  `rememberSaveable` under its entry while another screen is over it. */
sealed interface GroupsRoute {
    data object List : GroupsRoute
    data object Terms : GroupsRoute

    /** S3, and what happens after a purchase. */
    data class Paywall(val then: AfterPro) : GroupsRoute
    data object Create : GroupsRoute
    data class Detail(val groupId: String) : GroupsRoute

    /** S9: `afterCreate` (after create or join) replaces the stack with `[List, Detail]`; otherwise it pops back. */
    data class Consent(val groupId: String, val afterCreate: Boolean) : GroupsRoute

    /** S6: a new invite (`made` null, made when the screen opens) or one this phone made before. */
    data class Invite(val groupId: String, val made: MadeInvite?) : GroupsRoute
    data object JoinLink : GroupsRoute
    data object Scan : GroupsRoute
    data class Member(val groupId: String, val memberId: String) : GroupsRoute
    data class Pin(val groupId: String, val pinId: String) : GroupsRoute
    data class Report(val groupId: String, val target: ReportTarget) : GroupsRoute
    data class EditName(val groupId: String) : GroupsRoute
    data class Rename(val groupId: String) : GroupsRoute
    data class Blocked(val groupId: String) : GroupsRoute
    data class Handover(val groupId: String) : GroupsRoute
    data class DropPin(val groupId: String, val lat: Double, val lon: Double) : GroupsRoute

    /** S8; `link` null: the incomplete-link text. */
    data class Join(val link: InviteLink?) : GroupsRoute
}

/** What a purchase on the paywall goes on to do (§9.2). */
sealed interface AfterPro {
    data object Create : AfterPro
    data class Renew(val groupId: String) : AfterPro
    data class Accept(val groupId: String) : AfterPro
}

/** A request to show the groups sheet with these screens (a join or create flow, an invite for a group you are in). */
data class GroupsNavigation(val id: Int, val routes: kotlin.collections.List<GroupsRoute>)

/** An invite link to confirm (S8, its own sheet `pendingInvite`); `link` null for an incomplete one. */
data class JoinRequest(val id: Int, val link: InviteLink?)

/** The shown group, as the map screen needs it: the Groups button, the paused banner, the drop-pin capsule. */
data class ShownGroup(val groupId: String, val name: String, val isPaused: Boolean, val pausedReason: PausedReason?, val isOwner: Boolean)

/** The app's side of groups (§8.1): `StateFlow`s over the core's `GroupHub` (DESIGN.md D4: no public
 *  `MutableStateFlow`), its transport, secret store and store purchases, its timers and lifecycle. iOS's
 *  `GroupsModel` (an `ObservableObject`) does the same. Made by `AppModel` (`model.groups`) because recording runs
 *  with no screen; `AppModel` passes on the app's lifecycle, its track points and fixes. Main thread only: every hub
 *  call happens there, network calls suspend.
 *
 *  Demo mode (§8.15: the launch extra `groupsDemo YES`) swaps in the core's `FakeGroupServer` with `GroupDemo`'s seed,
 *  memberships in memory, no sockets, and a fake store; nothing else changes. */
class GroupsModel(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** The model's and the hub's work: a later turn of the main thread, never in the middle of a hub call (as a Swift
     *  `Task` on the main actor; DESIGN.md §2.4 rule 2). */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    /** Makes the real hub's parts; tests give their own. */
    private val parts: (GroupsModel) -> Parts = ::realParts,
) {
    /** What a hub runs on. */
    class Parts(
        val hub: GroupHub,
        val billing: BillingGateway,
        val http: GroupHttp? = null,
        val proofFor: ((StorePurchase) -> com.jacobusoettle.satmap.core.ProProof)? = null,
    )

    /** The word filter (§8.12), from the shipped list. */
    val words: WordFilter by lazy {
        val list = try {
            context.assets.open("GroupWords.txt").use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            ""
        }
        WordFilter(list)
    }

    /** True once demo mode is on. */
    var isDemo = false
        private set

    /** Demo mode's fake server (tests change it as another member would). */
    @VisibleForTesting
    internal var demoServer: FakeGroupServer? = null
        private set

    lateinit var hub: GroupHub
        private set
    lateinit var pro: ProStore
        private set
    private var http: GroupHttp? = null

    /** The hub has loaded its memberships (`start`); hub calls wait for it. */
    private var started = CompletableDeferred<Unit>()

    private val _groups = MutableStateFlow<List<GroupSummary>>(emptyList())

    /** S2's rows. */
    val groups: StateFlow<List<GroupSummary>> = _groups.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever any group changes: screens read the hub again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _mapRevision = MutableStateFlow(0)

    /** Bumped when the map's groups content changes (§9.5 binding). */
    val mapRevision: StateFlow<Int> = _mapRevision.asStateFlow()

    private val _shown = MutableStateFlow<ShownGroup?>(null)
    val shown: StateFlow<ShownGroup?> = _shown.asStateFlow()

    private val _alerts = MutableStateFlow<List<GroupAlert>>(emptyList())

    /** Removed, deleted and gone alerts (§9.3), the first shown. */
    val alerts: StateFlow<List<GroupAlert>> = _alerts.asStateFlow()

    private val _pendingInvite = MutableStateFlow<JoinRequest?>(null)
    val pendingInvite: StateFlow<JoinRequest?> = _pendingInvite.asStateFlow()

    private val _groupItem = MutableStateFlow<GroupsRoute?>(null)

    /** The sheet opened from the map: a pin (S11), a member (S12) or dropping a pin at a tapped spot (S10). */
    val groupItem: StateFlow<GroupsRoute?> = _groupItem.asStateFlow()

    private val _navigation = MutableStateFlow<GroupsNavigation?>(null)
    val navigation: StateFlow<GroupsNavigation?> = _navigation.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)

    /** A short message over the groups sheet ("You're already in Kruger trip."). */
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _termsAccepted = MutableStateFlow(Defaults.int(TERMS_KEY) ?: 0)
    val termsAccepted: StateFlow<Int> = _termsAccepted.asStateFlow()

    private val _hiddenHints = MutableStateFlow<Set<String>>(emptySet())

    /** Groups whose paused banner was closed (until the next launch). */
    val hiddenPausedHints: StateFlow<Set<String>> = _hiddenHints.asStateFlow()

    private val _sharingNames = MutableStateFlow<List<String>>(emptyList())

    /** The groups you share with now (sharing on, not paused): the location notification's line (§11.6). */
    val sharingNames: StateFlow<List<String>> = _sharingNames.asStateFlow()

    /** Shows the groups sheet (`AppModel` sets it). */
    var openSheet: (() -> Unit)? = null

    /** Fits the map to the shown group's members and you (`AppModel` sets it to the map's `fit`). */
    var fitMap: ((List<GeoPoint>) -> Unit)? = null

    /** Your position, for the camera fit and "Drop a pin where I am". */
    var myPosition: (() -> GeoPoint?) = { null }

    private var nextId = 0
    private var foreground = false
    private var tickScheduled = false
    private var toastJob: Job? = null
    private val backgroundTimer = WakeTimer(context, "groups") { backgroundTick() }

    /** A group just shown from S5 or S8: the map fits it once its members' positions are known (§9.5 camera). */
    private var pendingFit: Pair<String, Long>? = null
    private var heading: Double? = null

    /** What the phone allows and where it is, as last told: a hub installed later (demo mode) starts from them. */
    private var locationAccess: Pair<Boolean, Boolean>? = null
    private var lastFix: GpsFix? = null

    init {
        install(parts(this))
    }

    private fun install(p: Parts) {
        hub = p.hub
        http = p.http
        hub.acceptedTermsVersion = _termsAccepted.value.takeIf { it > 0 } ?: GroupConfig.termsVersion
        locationAccess?.let { (authorised, precise) -> hub.setLocationAccess(authorised, precise) }
        hub.onChange = { e -> changed(e) }
        http?.events = object : GroupHttp.SocketEvents {
            override fun opened(groupId: String) = hub.socketDidOpen(groupId)
            override fun received(groupId: String, text: String) = hub.socketDidReceive(groupId, text)
            override fun closed(groupId: String, code: Int) = hub.socketDidClose(groupId, code)
        }
        val proof = p.proofFor
        pro = if (proof != null) {
            ProStore(p.billing, clock, proofFor = proof, onPro = { pr, product -> sendPro(pr, product) }, scope = scope)
        } else {
            ProStore(p.billing, clock, onPro = { pr, product -> sendPro(pr, product) }, scope = scope)
        }
        val ready = CompletableDeferred<Unit>()
        started = ready
        val h = hub
        scope.launch {
            try {
                h.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "groups couldn't start: ${e.javaClass.simpleName}")
            }
            ready.complete(Unit)
            lastFix?.let(::sendFix)
            if (foreground) wake()
            pro.refresh()
        }
    }

    private suspend fun sendPro(proof: com.jacobusoettle.satmap.core.ProProof, productId: String) {
        started.await()
        hub.updatePro(proof, productId, expiresAt = null)
    }

    /** Waits until the memberships are loaded. */
    suspend fun ready() = started.await()

    // Launch arguments and demo mode

    /** Reads `groupsDemo` after `Defaults.setLaunchArguments` (as iOS reads `-groupsDemo YES`), in debug builds only:
     *  any app can start SatMap with extras, and demo mode would quietly stop the real sharing. `groupsBaseURL` is read
     *  at each request (also debug builds only). */
    fun applyLaunchArguments() {
        if (!BuildConfig.DEBUG) return
        if (Defaults.bool("groupsDemo") == true && !isDemo) {
            useDemo()
        } else if (!isDemo && Defaults.bool("fakePro") == true && !fakePro) {
            // The local check (§12.6): Pro at once, with a "local-test-" token, in debug builds outside demo mode.
            fakePro = true
            pro = ProStore(FakeBillingGateway.localTest(), clock, onPro = { pr, product -> sendPro(pr, product) }, scope = scope)
            scope.launch { pro.refresh() }
        }
    }

    private var fakePro = false

    /** Demo mode (§8.15): the fake server with the seed, in a store of its own, and the fake Pro store. */
    fun useDemo() {
        if (isDemo) return
        isDemo = true
        hub.onChange = null
        http?.events = null
        hub.enteredBackground()
        val file = File(context.cacheDir, "groups-demo.sqlite")
        for (f in listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))) f.delete()
        val store = GroupStore(file.path, AndroidSqlDriver())
        val demo = GroupDemo.makeHub(store, words, clock, scope)
        demoServer = demo.server
        install(Parts(demo.hub, FakeBillingGateway(clock = { clock() * 1000 }), proofFor = { GroupDemo.proProof(clock()) }))
    }

    // What the hub says

    private fun changed(e: GroupHubEvent) {
        when (e) {
            GroupHubEvent.Groups -> {
                if (foreground && !tickScheduled) scheduleForegroundTick()
                _groups.value = hub.groups
                updateShown()
            }
            is GroupHubEvent.Group -> {
                _revision.value += 1
                updateShown()
            }
            GroupHubEvent.Map -> {
                _mapRevision.value += 1
                fitIfPending()
            }
            is GroupHubEvent.Alert -> {
                _alerts.value = _alerts.value + e.alert
                if (_groupItem.value.groupId == e.alert.groupId) _groupItem.value = null
            }
        }
    }

    private fun updateShown() {
        val id = hub.shownGroupId
        val d = id?.let { hub.detail(it) }
        _shown.value = d?.let { ShownGroup(it.groupId, it.name, it.isPaused, it.pausedReason, it.yourRole == GroupRole.owner) }
        _sharingNames.value = hub.groups.filter { it.isSharing && !it.isPaused }.map { it.name }
    }

    fun dismissAlert() {
        _alerts.value = _alerts.value.drop(1)
    }

    fun mapContent(): GroupMapContent? = if (started.isCompleted) hub.mapContent(clock()) else null

    // Lifecycle (from AppModel)

    fun didBecomeActive() {
        foreground = true
        backgroundTimer.cancel()
        if (started.isCompleted) wake()
        scheduleForegroundTick()
    }

    /** The foreground tick every 5 s (§8.6), on the main looper, while there is a group (or a membership to restore);
     *  `wake` and every hub change set it going again. */
    private val foregroundTick = Runnable {
        tickScheduled = false
        if (!foreground) return@Runnable
        scope.launch {
            if (started.isCompleted) runCatchingHub { hub.tick(foreground = true) }
            scheduleForegroundTick()
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun scheduleForegroundTick() {
        mainHandler.removeCallbacks(foregroundTick)
        tickScheduled = false
        if (!foreground || !started.isCompleted || hub.nextTickDelay(foreground = true) == null) return
        tickScheduled = true
        mainHandler.postDelayed(foregroundTick, GroupPolicyTicks.foregroundMillis)
    }

    private fun wake() {
        scheduleForegroundTick()
        scope.launch {
            runCatchingHub { hub.becameActive() }
            pro.refresh()
            pro.takeUpdates()
        }
    }

    fun didEnterBackground() {
        foreground = false
        mainHandler.removeCallbacks(foregroundTick)
        tickScheduled = false
        if (started.isCompleted) hub.enteredBackground()
        scheduleBackgroundTick()
    }

    /** Uploads go on in the background at the hub's pace, on an alarm that fires while the CPU sleeps (§8.6). */
    private fun scheduleBackgroundTick() {
        if (foreground || !started.isCompleted) return
        val delay = hub.nextTickDelay(foreground = false) ?: return backgroundTimer.cancel()
        backgroundTimer.schedule(delay.toDouble())
    }

    private fun backgroundTick() {
        if (foreground) return
        scope.launch {
            runCatchingHub { hub.tick(foreground = false) }
            scheduleBackgroundTick()
        }
    }

    fun connectivityChanged(online: Boolean) {
        if (!online || !started.isCompleted) return
        scope.launch { runCatchingHub { hub.connectivityChanged(online = true) } }
    }

    fun setLocationAccess(authorised: Boolean, precise: Boolean) {
        locationAccess = authorised to precise
        hub.setLocationAccess(authorised, precise)
    }

    /** A point of "Where I've been" (`AppModel.appendTrack`). */
    fun trackPoint(p: TrackPoint) {
        if (started.isCompleted) hub.trackPoint(p)
    }

    /** The compass heading (the recorder's `onHeading`), sent with the next live position when not moving. */
    fun headingUpdated(h: Double?) {
        heading = h
    }

    /** A fix used by the recorder (`AppModel.locationUpdated`); in the background it also keeps the upload alarm set. */
    fun fix(f: GpsFix) {
        lastFix = f
        if (!started.isCompleted) return
        sendFix(f)
        if (!foreground && !backgroundTimer.scheduled) scheduleBackgroundTick()
    }

    private fun sendFix(f: GpsFix) {
        hub.fix(lat = f.lat, lon = f.lon, accuracy = f.horizontalAccuracy, course = f.course, speed = f.speed, heading = heading,
                time = f.timestamp)
    }

    private suspend fun runCatchingHub(work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "groups work failed: ${e.javaClass.simpleName}")
        }
    }

    // Navigation

    /** Shows the groups sheet with `routes`. */
    fun open(routes: List<GroupsRoute>) {
        _navigation.value = GroupsNavigation(++nextId, routes)
        openSheet?.invoke()
    }

    /** The sheet took the routes. */
    fun navigationTaken(n: GroupsNavigation) {
        if (_navigation.value == n) _navigation.value = null
    }

    fun showToast(text: String) {
        _toast.value = text
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2000)
            _toast.value = null
        }
    }

    /** An invite link opened from another app, a scan or a paste (§5.6): the join confirmation, or the group's screen
     *  when you are in it already. Opening a link never joins by itself. */
    fun openInvite(parsed: InviteLink.Parsed) {
        when (parsed) {
            is InviteLink.Parsed.Invite -> scope.launch {
                ready()
                val d = hub.detail(parsed.link.groupIdText)
                if (d != null) {
                    _pendingInvite.value = null
                    open(listOf(GroupsRoute.List, GroupsRoute.Detail(d.groupId)))
                    showToast("You're already in ${d.name}.")
                } else {
                    _pendingInvite.value = JoinRequest(++nextId, parsed.link)
                }
            }
            InviteLink.Parsed.MissingKey -> _pendingInvite.value = JoinRequest(++nextId, null)
            InviteLink.Parsed.NotInvite -> {}
        }
    }

    fun closeInvite() {
        _pendingInvite.value = null
    }

    /** A member dot, a group pin or "Drop pin" on the map (§9.1). */
    fun openItem(route: GroupsRoute?) {
        _groupItem.value = route
    }

    fun hidePausedHint(groupId: String) {
        _hiddenHints.value = _hiddenHints.value + groupId
    }

    // Terms and names

    fun acceptTerms() {
        Defaults.putInt(TERMS_KEY, GroupConfig.termsVersion)
        _termsAccepted.value = GroupConfig.termsVersion
        hub.acceptedTermsVersion = GroupConfig.termsVersion
    }

    val needsTerms: Boolean get() = _termsAccepted.value < GroupConfig.termsVersion

    /** The last display name typed (`groupsDisplayName`), to prefill. */
    val lastDisplayName: String get() = Defaults.string(NAME_KEY) ?: ""

    private fun rememberName(name: String) {
        GroupText.clean(name, GroupTextField.displayName)?.let { Defaults.putString(NAME_KEY, it) }
    }

    // Actions with app glue (the rest are the hub's own)

    /** S4: needs Pro (the paywall comes first). */
    suspend fun createGroup(name: String, displayName: String): String {
        ready()
        val proof = pro.proof() ?: throw GroupActionError(GroupActionError.Kind.ProNotConfirmed)
        val gid = hub.createGroup(name, displayName, proof)
        rememberName(displayName)
        return gid
    }

    /** S8: the joined group becomes the shown one, and the map fits it. */
    suspend fun join(link: InviteLink, displayName: String): String {
        ready()
        val gid = hub.join(link, displayName)
        rememberName(displayName)
        _pendingInvite.value = null
        requestFit(gid)
        return gid
    }

    /** "Show on the map" (S5): on, the map fits the group (§9.5). */
    fun setShown(groupId: String, on: Boolean) {
        if (on) {
            hub.setShown(groupId)
            requestFit(groupId)
        } else if (hub.shownGroupId == groupId) {
            hub.setShown(null)
        }
    }

    /** The Stop action of the location notification: sharing off in every group (§11.6). */
    fun stopSharingEverywhere() {
        if (!started.isCompleted) return
        for (g in hub.groups) if (g.isSharing) hub.setSharing(false, g.groupId)
    }

    /** The phone has Pro the store has confirmed since launch, so an action that needs Pro (S4's create, S5's Renew
     *  and Accept) can send its proof now; otherwise the paywall comes first (§10.5). The cached `isPro` alone isn't
     *  enough: Play gives no proof offline, and the paywall's "Restore purchases" asks it again. */
    val hasConfirmedPro: Boolean get() = pro.proof() != null

    /** "Renew SatMap Pro" with Pro on the phone, and after the paywall: E5 (§10.5). Throws `ProNotConfirmed` when the
     *  store hasn't confirmed Pro. */
    suspend fun renewPro() {
        ready()
        pro.refresh()
        val proof = pro.proof() ?: throw GroupActionError(GroupActionError.Kind.ProNotConfirmed)
        hub.updatePro(proof, pro.productId ?: GroupConfig.proProducts.subscription, expiresAt = null)
    }

    /** "Accept" an ownership offer (E17), with Pro. */
    suspend fun acceptOwnership(groupId: String) {
        ready()
        val proof = pro.proof() ?: throw GroupActionError(GroupActionError.Kind.ProNotConfirmed)
        hub.acceptOwnership(groupId, proof)
    }

    private fun requestFit(groupId: String) {
        pendingFit = groupId to clock()
        fitIfPending()
    }

    /** Fits the map to every live member and you, once there is a member to fit (within 20 s of the request). */
    private fun fitIfPending() {
        val (gid, at) = pendingFit ?: return
        if (clock() - at > 20 || hub.shownGroupId != gid) {
            pendingFit = null
            return
        }
        val content = hub.mapContent(clock()) ?: return
        if (content.groupId != gid || content.members.isEmpty()) return
        pendingFit = null
        val points = content.members.map { GeoPoint(it.position.lat, it.position.lon) } + listOfNotNull(myPosition())
        fitMap?.invoke(points)
    }

    companion object {
        private const val TAG = "groups"
        const val TERMS_KEY = "groupsTermsAccepted"
        const val NAME_KEY = "groupsDisplayName"

        /** The real hub: the local store and the secrets in `noBackupFilesDir/groups`, OkHttp, Play Billing (or, in
         *  debug builds with the `fakePro` launch extra, a store that says Pro at once, §12.6). */
        fun realParts(model: GroupsModel): Parts {
            val context = model.context
            val dir = File(context.noBackupFilesDir, "groups").apply { mkdirs() }
            val store = try {
                GroupStore(File(dir, "groups.sqlite").path, AndroidSqlDriver())
            } catch (e: GroupStore.StoreError) {
                // The file can't be opened (a full disk, a damaged file): groups run from a fresh copy in the cache
                // for now; the memberships stay in the secrets and are restored from the server (§8.3).
                Log.w(TAG, "the groups database couldn't be opened")
                val spare = File(context.cacheDir, "groups-spare.sqlite").apply { delete() }
                GroupStore(spare.path, AndroidSqlDriver())
            }
            val http = GroupHttp(baseURL = { GroupConfig.baseURL }, isOnline = { Connectivity.shared.isOnlineNow })
            val hub = GroupHub(store, KeystoreSecrets(KeystoreSecrets.file(context.noBackupFilesDir)), http, http, model.words,
                               GroupConfig.domain, model.clock, model.scope)
            return Parts(hub, PlayBillingGateway(context), http)
        }
    }
}

/** The foreground tick (§8.6: every 5 s). */
object GroupPolicyTicks {
    const val foregroundMillis = 5_000L
}

/** The group a map-sheet route belongs to. */
val GroupsRoute?.groupId: String?
    get() = when (this) {
        is GroupsRoute.Pin -> groupId
        is GroupsRoute.Member -> groupId
        is GroupsRoute.DropPin -> groupId
        is GroupsRoute.Report -> groupId
        else -> null
    }
