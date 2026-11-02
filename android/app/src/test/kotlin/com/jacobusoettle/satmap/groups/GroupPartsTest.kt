package com.jacobusoettle.satmap.groups

import android.app.Application
import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.GroupCoordinate
import com.jacobusoettle.satmap.core.GroupDemo
import com.jacobusoettle.satmap.core.GroupMapContent
import com.jacobusoettle.satmap.core.GroupRole
import com.jacobusoettle.satmap.core.GroupSecret
import com.jacobusoettle.satmap.core.GroupSummary
import com.jacobusoettle.satmap.core.InviteLink
import com.jacobusoettle.satmap.core.MapMember
import com.jacobusoettle.satmap.core.MapPin
import com.jacobusoettle.satmap.core.MapTrail
import com.jacobusoettle.satmap.core.MemberRow
import com.jacobusoettle.satmap.core.MemberState
import com.jacobusoettle.satmap.core.PausedReason
import com.jacobusoettle.satmap.core.PinType
import com.jacobusoettle.satmap.core.ProProof
import com.jacobusoettle.satmap.core.ReportTarget
import com.jacobusoettle.satmap.map.GroupLayers
import com.jacobusoettle.satmap.map.MapIds
import com.jacobusoettle.satmap.map.MapStyle
import com.jacobusoettle.satmap.ui.groups.GroupTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.MultiLineString
import java.io.File
import java.util.TimeZone
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The groups pieces under the screens: their texts, the map's features and fit, QR codes, the sealed memberships,
 *  and the Pro store's decisions (docs/groups/SPEC.md §8.3, §9.3–§9.5, §10). */
@RunWith(AndroidJUnit4::class)
class GroupPartsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Defaults.init(app)
        Defaults.prefs.edit().clear().apply()
    }

    // Texts (§9.3)

    @Test
    fun listRowsSayOfferThenPausedThenMembersAndSharing() {
        fun row(n: Int, sharing: Boolean = false, paused: Boolean = false, offer: Boolean = false) =
            GroupSummary("g", "G", n, sharing, paused, offer, false)
        assertEquals("5 members · Sharing", GroupTexts.listSubtitle(row(5, sharing = true)))
        assertEquals("3 members · Not sharing", GroupTexts.listSubtitle(row(3)))
        assertEquals("1 member · Not sharing", GroupTexts.listSubtitle(row(1)))
        assertEquals("Paused", GroupTexts.listSubtitle(row(3, sharing = true, paused = true)))
        assertEquals("Wants you as owner", GroupTexts.listSubtitle(row(3, paused = true, offer = true)))
    }

    @Test
    fun memberRowsJoinTheRoleAndTheState() {
        val now = 10_000L
        fun row(role: GroupRole, state: MemberState, you: Boolean = false) = MemberRow("m", "Thandi", "T", "#E5484D", role, you, false, state)
        assertEquals("Admin · Sharing · 2 min ago", GroupTexts.memberSubtitle(row(GroupRole.admin, MemberState.Sharing(now - 130)), now))
        assertEquals("Not sharing", GroupTexts.memberSubtitle(row(GroupRole.member, MemberState.NotSharing), now))
        assertEquals("Owner · Last seen 3 h ago", GroupTexts.memberSubtitle(row(GroupRole.owner, MemberState.LastSeen(now - 3 * 3600)), now))
        assertEquals("Thandi (you)", GroupTexts.memberName(row(GroupRole.member, MemberState.NotSharing, you = true)))
        assertEquals("Sharing · last position just now", GroupTexts.memberDetailState(MemberState.Sharing(now - 5), now))
    }

    @Test
    fun pausedTextsFollowTheReasonAndWhoReadsThem() {
        assertEquals("Drone team is paused: the owner's SatMap Pro has ended.", GroupTexts.pausedBanner("Drone team", PausedReason.proEnded, false))
        assertEquals("Drone team is paused: your SatMap Pro couldn't be confirmed. Tap to fix.", GroupTexts.pausedBanner("Drone team", PausedReason.proUnverified, true))
        assertEquals(
            "This group is paused because the owner's SatMap Pro has ended. Nobody can share their location or drop pins until the owner " +
                "renews Pro or hands the group to a member who has Pro. If it stays paused, everything shared in the group is deleted on 29 Nov.",
            GroupTexts.pausedSection(PausedReason.proEnded, owner = false, date = "29 Nov"),
        )
        assertEquals(
            "This group is paused because your SatMap Pro was refunded or cancelled. Nobody can share their location or drop pins until you " +
                "renew Pro or hand the group to a member who has Pro.",
            GroupTexts.pausedSection(PausedReason.proRefunded, owner = true, date = null),
        )
    }

    @Test
    fun datesAndTimesAreThePhonesWithTheYearOnlyWhenItIsAnother() {
        val utc = TimeZone.getTimeZone("UTC")
        val nov9 = 1_794_225_600L // 2026-11-09 12:00 UTC
        val uk = java.util.Locale.UK
        assertEquals("9 Nov", GroupTexts.date(nov9, now = nov9 - 86_400, zone = utc, locale = uk))
        assertEquals("9 Nov 2026", GroupTexts.date(nov9, now = nov9 + 400 * 86_400L, zone = utc, locale = uk))
        assertEquals("Nov 9", GroupTexts.date(nov9, now = nov9 - 86_400, zone = utc, locale = java.util.Locale.US))
        // A moment: the time alone today, else with the day (S11).
        assertEquals("12:00", GroupTexts.moment(nov9, now = nov9 + 3600, use24Hour = true, zone = utc, locale = uk))
        assertEquals("Nov 9, 12:00 AM", GroupTexts.moment(nov9 - 12 * 3600, now = nov9 - 13 * 3600, use24Hour = false, zone = utc, locale = java.util.Locale.US))
        assertEquals("10 Nov, 01:30", GroupTexts.moment(nov9 + 13 * 3600 + 1800, now = nov9, use24Hour = true, zone = utc, locale = uk))
        assertEquals("12:00", GroupTexts.time(nov9 + 0, use24Hour = true, zone = utc))
        assertEquals("2:05 PM", GroupTexts.time(nov9 + 2 * 3600 + 300, use24Hour = false, zone = utc))
    }

    @Test
    fun reportAndShareTextsNameWhatTheyAreAbout() {
        assertEquals("Why are you reporting this pin?", GroupTexts.reportQuestion(ReportTarget.Pin("p")))
        assertEquals("Why are you reporting this group name?", GroupTexts.reportQuestion(ReportTarget.Group))
        assertTrue(GroupTexts.reportNote(ReportTarget.Pin("p")).startsWith("We'll get a copy of this pin's text to review"))
        assertEquals("Join \"Kruger trip\" on SatMap: https://x/j/t#k", GroupTexts.shareText("Kruger trip", "https://x/j/t#k"))
        assertEquals("Animal sighting", GroupTexts.pinLabel(PinType.animal))
        assertEquals("Animal", GroupTexts.pinShortLabel(PinType.animal))
        assertEquals("Pin", GroupTexts.pinLabel(null))
    }

    @Test
    fun aPastedOrScannedLinkSaysWhatIsWrongWithIt() {
        val incomplete = "This invite link is incomplete. Copy the whole link from the message, or scan the QR code."
        assertEquals(incomplete, GroupTexts.linkProblem(InviteLink.Parsed.MissingKey, "https://satmap.app/j/abc"))
        assertEquals("That isn't a SatMap invite link.", GroupTexts.linkProblem(InviteLink.Parsed.NotInvite, "https://example.com"))
        assertNull(GroupTexts.linkProblem(InviteLink.Parsed.NotInvite, "  "))
        val whole = InviteLink.parse(GroupDemo.inviteLink, GroupDemo.domain)
        assertNull(GroupTexts.linkProblem(whole, GroupDemo.inviteLink))
    }

    // The map (§9.5)

    private val content = GroupMapContent(
        groupId = "g",
        members = listOf(
            MapMember("a", GroupCoordinate(-33.9, 18.9), "#E5484D", "TH", headingDeg = 120, isStale = false, a11yLabel = "Thandi, Sharing · just now"),
            MapMember("b", GroupCoordinate(-33.91, 18.91), "#46A758", "SI", headingDeg = 90, isStale = true, a11yLabel = "Sipho, Last seen 25 min ago"),
        ),
        trails = listOf(
            MapTrail("a", "#E5484D", 3.0, listOf(listOf(GroupCoordinate(-33.9, 18.9), GroupCoordinate(-33.901, 18.902)))),
            MapTrail("c", "#000000", 3.0, emptyList()),
        ),
        pins = listOf(
            MapPin("p1", "animal", PinType.animal, GroupCoordinate(-33.92, 18.92), "#2E7D32", "Kudu · 2 h ago", "Kudu, dropped by Thandi 2 h ago"),
            MapPin("p2", "water", PinType.water, GroupCoordinate(-33.93, 18.93), "#1E88E5", null, "Water, dropped by Pieter 5 h ago"),
            MapPin("p3", "ufo", null, GroupCoordinate(-33.94, 18.94), "#757575", null, "Pin, dropped by Pieter 1 h ago"),
        ),
    )

    @Test
    fun trailsArePerMemberMultiLinesInTheirColour() {
        val f = GroupLayers.trailFeatures(content).features()!!
        assertEquals("a member without a line draws nothing", 1, f.size)
        assertEquals("#E5484D", f[0].getStringProperty("colour"))
        assertEquals(1, (f[0].geometry() as MultiLineString).lineStrings().size)
        assertEquals(0, GroupLayers.trailFeatures(null).features()!!.size)
    }

    @Test
    fun pinsNameTheirImageAndOnlyAnimalPinsALabel() {
        val f = GroupLayers.pinFeatures(content).features()!!
        assertEquals(listOf("group-pin/animal", "group-pin/water", "group-pin/unknown"), f.map { it.getStringProperty("img") })
        assertEquals("group-pin-label/Kudu · 2 h ago", f[0].getStringProperty("label"))
        assertFalse(f[1].hasProperty("label"))
    }

    @Test
    fun staleDotsAreGreyAndHaveNoHeading() {
        val f = GroupLayers.memberFeatures(content).features()!!
        assertEquals(120, f[0].getNumberProperty("heading").toInt())
        assertFalse(f[1].hasProperty("heading"))
        assertEquals("group-member/#46A758/SI/stale", f[1].getStringProperty("img"))
        assertEquals("group-member/#E5484D/TH/live", f[0].getStringProperty("img"))
    }

    @Test
    fun theFitIsAtLeast300MetresAcross() {
        val one = GroupLayers.fitBounds(listOf(GeoPoint(-33.9, 18.9)))!!
        assertEquals(300.0, GroupLayers.widthMeters(one), 3.0)
        assertEquals(300.0 / 111_320, one.maxLat - one.minLat, 1e-7)
        val wide = GroupLayers.fitBounds(listOf(GeoPoint(-33.9, 18.9), GeoPoint(-33.95, 18.99)))!!
        assertEquals(-33.95, wide.minLat, 1e-9)
        assertEquals(18.99, wide.maxLon, 1e-9)
        assertNull(GroupLayers.fitBounds(emptyList()))
    }

    @Test
    fun theImagesAreDrawnAtTheirSizes() {
        val m = app.resources.displayMetrics
        val d = m.density
        assertEquals(Math.ceil(26.0 * d).toInt(), GroupLayers.memberImage(m, "#E5484D", "TH", false).width)
        assertEquals(Math.ceil(30.0 * d).toInt(), GroupLayers.pinImage(m, PinType.police).width)
        assertTrue(GroupLayers.labelImage(m, "Kudu · 2 h ago").width > GroupLayers.labelImage(m, "Kudu").width)
    }

    // QR codes (§5.3)

    @Test
    fun theQrCodeHoldsTheLinkExactlyWithAQuietZone() {
        val link = "https://example.invalid/j/oKGio6SlpqeoqaqrrK2ur-Dh4uPk5ebn6Onq6-zt7u8#AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val bitmap = QRCode.bitmap(link)!!
        assertEquals(bitmap.width, bitmap.height)
        // Four white modules all round.
        for (i in 0 until bitmap.width) {
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(i, 0))
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(3, i))
        }
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(4, 4)) // the finder pattern's corner
        // Scaled up as on the screen, it reads back.
        val big = android.graphics.Bitmap.createScaledBitmap(bitmap, bitmap.width * 8, bitmap.height * 8, false)
        assertEquals(link, QRCode.decode(big))
    }

    // Memberships (§8.3)

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun membershipsAreSealedInOneFileAndReadBack() {
        val k = key()
        val file = File(app.cacheDir, "secrets-test/groups/secrets.bin").apply { parentFile!!.deleteRecursively() }
        val store = KeystoreSecrets(file) { k }
        assertEquals(emptyList<GroupSecret>(), store.loadAll())
        val a = GroupSecret("g1", "m1", "pass1", "key1")
        val b = GroupSecret("g2", "m2", "pass2", "key2")
        store.save(a)
        store.save(b)
        store.save(a.copy(pass = "pass1b"))
        assertEquals(listOf(b, a.copy(pass = "pass1b")), store.loadAll())
        val bytes = file.readBytes()
        assertEquals(KeystoreSecrets.VERSION, bytes[0])
        assertFalse("no pass in the clear", String(bytes, Charsets.ISO_8859_1).contains("pass1b"))
        store.delete("g2")
        assertEquals(listOf(a.copy(pass = "pass1b")), KeystoreSecrets(file) { k }.loadAll())
    }

    @Test
    fun aFileThatCantBeOpenedThrowsAndIsntDeleted() {
        val file = File(app.cacheDir, "secrets-test2/secrets.bin").apply { parentFile!!.deleteRecursively() }
        KeystoreSecrets(file, ::keyOne).save(GroupSecret("g1", "m1", "p", "k"))
        val other = KeystoreSecrets(file) { key() }
        assertThrows(Exception::class.java) { other.loadAll() }
        other.delete("g1")
        assertTrue(file.exists())
        assertEquals(1, KeystoreSecrets(file, ::keyOne).loadAll().size)
    }

    @Test
    fun aFileThatCanNeverBeOpenedAgainIsSetAsideByTheNextSave() {
        val file = File(app.cacheDir, "secrets-test3/secrets.bin").apply { parentFile!!.deleteRecursively() }
        val aside = File(file.path + ".unreadable")
        val a = GroupSecret("g1", "m1", "p1", "k1")
        val b = GroupSecret("g2", "m2", "p2", "k2")
        val c = GroupSecret("g3", "m3", "p3", "k3")
        KeystoreSecrets(file, ::keyOne).save(a)
        val old = file.readBytes()
        // The Keystore's key was lost and made anew: the file never opens again. A create or join still saves.
        val renewed = key()
        val store = KeystoreSecrets(file) { renewed }
        assertThrows(Exception::class.java) { store.loadAll() }
        store.save(b)
        assertEquals(listOf(b), store.loadAll())
        assertArrayEquals("kept beside it", old, aside.readBytes())
        // A damaged file the same way.
        file.writeBytes(byteArrayOf(1, 2, 3))
        store.save(c)
        assertEquals(listOf(c), store.loadAll())
        // A Keystore error may pass (before the first unlock): nothing is replaced or set aside.
        aside.delete()
        val locked = KeystoreSecrets(file) { throw java.security.KeyStoreException("locked") }
        assertThrows(java.security.KeyStoreException::class.java) { locked.save(a) }
        assertEquals(listOf(c), store.loadAll())
        assertFalse(aside.exists())
    }

    private val one by lazy { key() }
    private fun keyOne(): SecretKey = one

    // Pro (§10)

    private class Gateway(var held: List<StorePurchase>?, val outcome: PurchaseOutcome = PurchaseOutcome.Cancelled) : BillingGateway {
        override var updates: ((List<StorePurchase>) -> Unit)? = null
        val acknowledged = mutableListOf<String>()
        override suspend fun prices() = null
        override suspend fun purchases() = held
        override suspend fun buy(activity: Activity?, plan: ProPlan) = outcome
        override suspend fun acknowledge(token: String): Boolean {
            acknowledged += token
            return true
        }
    }

    private fun purchase(product: String, token: String, time: Long = 0, purchased: Boolean = true, acknowledged: Boolean = true, suspended: Boolean = false) =
        StorePurchase(listOf(product), token, purchased, pending = !purchased, acknowledged = acknowledged, suspended = suspended, time = time)

    @Test
    fun lifetimeComesFirstThenTheNewestSubscription() {
        val sub1 = purchase("pro", "s1", time = 1)
        val sub2 = purchase("pro", "s2", time = 2)
        val life = purchase("pro_lifetime", "l")
        assertEquals(life, ProStore.best(listOf(sub1, life, sub2)))
        assertEquals(sub2, ProStore.best(listOf(sub1, sub2)))
        assertNull(ProStore.best(listOf(purchase("pro", "x", suspended = true), purchase("pro", "y", purchased = false))))
        assertNull(ProStore.best(listOf(purchase("other", "z"))))
    }

    @Test
    fun aRefreshAcknowledgesCachesAndSendsTheGoogleProof() = runBlocking {
        val g = Gateway(listOf(purchase("pro", "tok", acknowledged = false)))
        val sent = mutableListOf<Pair<ProProof, String>>()
        val store = ProStore(g, clock = { 1000 }, onPro = { p, product -> sent += p to product })
        store.refresh()
        assertTrue(store.isPro.value)
        assertEquals(listOf("tok"), g.acknowledged)
        assertEquals(listOf(ProProof.Google("pro", "tok") to "pro"), sent)
        assertEquals("""{"productId":"pro","expiresAt":null,"checkedAt":1000}""", Defaults.string(ProStore.CACHE_KEY))
        // Shown at once at the next launch, from the cache; but with no proof until the store answers.
        val cachedOnly = ProStore(Gateway(null), clock = { 0 })
        assertTrue(cachedOnly.isPro.value)
        assertNull(cachedOnly.proof())
        // The store says there is none: the cache goes.
        g.held = emptyList()
        store.refresh()
        assertFalse(store.isPro.value)
        assertNull(Defaults.string(ProStore.CACHE_KEY))
    }

    /** A pending purchase approved while SatMap is open: Play's listener, outside a sheet. Taken up at once:
     *  acknowledged, Pro, and the proof sent (§10.4). */
    @Test
    fun aPurchaseApprovedOutsideTheSheetMakesThePhoneProAtOnce() {
        val pending = purchase("pro", "tok", purchased = false, acknowledged = false)
        val g = Gateway(listOf(pending))
        val sent = mutableListOf<ProProof>()
        val store = ProStore(g, clock = { 0 }, onPro = { p, _ -> sent += p }, scope = CoroutineScope(Dispatchers.Unconfined))
        runBlocking { store.refresh() }
        assertFalse(store.isPro.value)
        val approved = purchase("pro", "tok", acknowledged = false)
        g.held = listOf(approved)
        g.updates!!.invoke(listOf(approved))
        assertTrue(store.isPro.value)
        assertEquals(listOf("tok"), g.acknowledged)
        assertEquals(listOf<ProProof>(ProProof.Google("pro", "tok")), sent)
        assertEquals(ProProof.Google("pro", "tok"), store.proof())
    }

    /** When Play can't be asked, an announcement only replaces the purchases it names: a pending one announced
     *  doesn't take away Lifetime. */
    @Test
    fun anAnnouncementTakesNothingAwayItDoesntName() {
        val life = purchase("pro_lifetime", "l")
        val g = Gateway(listOf(life))
        val store = ProStore(g, clock = { 0 }, scope = CoroutineScope(Dispatchers.Unconfined))
        runBlocking { store.refresh() }
        assertTrue(store.isPro.value)
        g.held = null
        g.updates!!.invoke(listOf(purchase("pro", "s", purchased = false)))
        assertTrue(store.isPro.value)
        assertEquals(ProProof.Google("pro_lifetime", "l"), store.proof())
        // Without a scope it waits for takeUpdates.
        Defaults.prefs.edit().remove(ProStore.CACHE_KEY).commit()
        val g2 = Gateway(null)
        val waiting = ProStore(g2, clock = { 0 })
        g2.updates!!.invoke(listOf(purchase("pro", "x")))
        assertFalse(waiting.isPro.value)
        runBlocking { waiting.takeUpdates() }
        assertTrue(waiting.isPro.value)
    }

    @Test
    fun restoreAndBuySayWhatHappened() = runBlocking {
        assertEquals(ProStore.Result.Message(ProStore.NOT_FOUND), ProStore(Gateway(emptyList()), { 0 }).restore())
        assertEquals(ProStore.Result.Message(ProStore.STORE_UNAVAILABLE), ProStore(Gateway(null), { 0 }).restore())
        assertEquals(ProStore.Result.Message(ProStore.PENDING), ProStore(Gateway(listOf(purchase("pro", "p", purchased = false))), { 0 }).restore())
        assertEquals(ProStore.Result.Message(ProStore.PENDING), ProStore(Gateway(emptyList(), PurchaseOutcome.Pending), { 0 }).buy(null, ProPlan.yearly))
        assertEquals(ProStore.Result.Cancelled, ProStore(Gateway(emptyList()), { 0 }).buy(null, ProPlan.yearly))
        assertEquals(ProStore.Result.Message(ProStore.STORE_UNAVAILABLE), ProStore(Gateway(emptyList(), PurchaseOutcome.Failed), { 0 }).buy(null, ProPlan.monthly))
        val bought = purchase("pro_lifetime", "l")
        assertEquals(ProStore.Result.Done, ProStore(Gateway(listOf(bought), PurchaseOutcome.Purchased(listOf(bought))), { 0 }).buy(null, ProPlan.lifetime))
    }

    @Test
    fun theYearlySavingIsFloored() {
        assertEquals(58, ProStore.savingPercent(399_990_000, 79_990_000))
        assertNull(ProStore.savingPercent(1_200_000_000, 100_000_000))
        assertNull(ProStore.savingPercent(1, 0))
    }

    @Test
    fun theBaseUrlIsTheDomainUnlessALaunchExtraSaysOtherwise() {
        assertEquals("https://${GroupConfig.domain}", GroupConfig.baseURL(null))
        assertEquals("http://10.0.2.2:8787", GroupConfig.baseURL("http://10.0.2.2:8787/"))
        assertEquals("https://${GroupConfig.domain}", GroupConfig.baseURL("ftp://nope"))
        // A release build always talks to its own domain.
        assertEquals("https://${GroupConfig.domain}", GroupConfig.baseURL("https://evil.example", honoured = false))
    }

    // The map's layer order (§9.5)

    @Test
    fun theLayerOrderPutsTheGroupOverTheKmlFilesShapesAndPoints() {
        val file = "kml/2b7c9440-0000-0000-0000-000000000001"
        val symbols = MapStyle.groupSymbolLayerIds
        val good = listOf("imagery", "$file/fill", "$file/line", MapIds.GROUP_TRAILS, MapIds.TRACK, "selected", "$file/points") +
            symbols + MapIds.MEASURE_MARKERS
        assertEquals(emptyList<String>(), GroupLayers.orderProblems(good))
        assertFalse(GroupLayers.trailsMisplaced(good))
        assertFalse(GroupLayers.symbolsMisplaced(good))
        // As BlockLayers adds a file shown later: against track and measure-markers, over the group's layers.
        val late = listOf("imagery", MapIds.GROUP_TRAILS, "$file/fill", "$file/line", MapIds.TRACK, "selected") + symbols +
            "$file/points" + MapIds.MEASURE_MARKERS
        assertEquals(
            listOf("$file/fill is over ${MapIds.GROUP_TRAILS}", "$file/line is over ${MapIds.GROUP_TRAILS}",
                   "$file/points is over ${MapIds.GROUP_PINS}"),
            GroupLayers.orderProblems(late),
        )
        assertTrue(GroupLayers.trailsMisplaced(late))
        assertTrue(GroupLayers.symbolsMisplaced(late))
        assertEquals(listOf("${MapIds.GROUP_TRAILS} isn't under ${MapIds.TRACK}", "the group's symbols aren't under ${MapIds.MEASURE_MARKERS}"),
                     GroupLayers.orderProblems(listOf(MapIds.TRACK, MapIds.MEASURE_MARKERS)))
    }
}
