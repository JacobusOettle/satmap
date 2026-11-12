package com.jacobusoettle.satmap.groups

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.jacobusoettle.satmap.app.Defaults
import com.jacobusoettle.satmap.app.GroupConfig
import com.jacobusoettle.satmap.core.Base64URL
import com.jacobusoettle.satmap.core.GroupRandom
import com.jacobusoettle.satmap.core.ProProof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.floor

/** The three Pro plans (docs/groups/SPEC.md §10.1), in the paywall's order. */
enum class ProPlan { yearly, monthly, lifetime }

/** A plan's price as the store gives it: Play's `formattedPrice` ("R399.99") and its amount in micros. */
data class StorePrice(val plan: ProPlan, val formatted: String, val micros: Long)

/** A purchase as Play reports it, without Play's types (the fake gateway and the tests make them). */
data class StorePurchase(
    val products: List<String>,
    val token: String,
    val purchased: Boolean,
    val pending: Boolean,
    val acknowledged: Boolean,
    val suspended: Boolean,
    val time: Long,
)

/** How a purchase sheet ended. */
sealed interface PurchaseOutcome {
    data class Purchased(val purchases: List<StorePurchase>) : PurchaseOutcome
    data object Pending : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data object Failed : PurchaseOutcome
}

/** Play Billing behind an interface (§10.4), so the Robolectric tests and demo mode use a fake. */
interface BillingGateway {
    /** The three plans' prices; null when the store can't be reached. */
    suspend fun prices(): Map<ProPlan, StorePrice>?

    /** Every purchase Play holds now (subscriptions and one-time products); null when it can't be asked. */
    suspend fun purchases(): List<StorePurchase>?

    /** Shows the store's purchase sheet for `plan` and waits for how it ends. */
    suspend fun buy(activity: Activity?, plan: ProPlan): PurchaseOutcome

    suspend fun acknowledge(token: String): Boolean

    /** Purchases that change outside a purchase sheet (a pending one approved, a refund): Play's listener. */
    var updates: ((List<StorePurchase>) -> Unit)?
}

/** SatMap Pro (§10): whether this phone has it, the plans' prices, buying, restoring, and the proof sent to the server.
 *  iOS's ProStore.swift does the same with StoreKit 2. Main thread only.
 *
 *  Pro is the best purchase Play holds that is purchased and not suspended: Lifetime first, else the subscription.
 *  It is cached (`proCache`, §10.6) after every store result, so Pro shows at once and offline at launch; when the
 *  store says there is none, the cache is cleared. Play gives no expiry time (§9.9), so the cache's `expiresAt` is
 *  always null. After every store result that says Pro, `onPro` (the hub's `updatePro`) gets the proof. */
class ProStore(
    private val gateway: BillingGateway,
    private val clock: () -> Long,
    /** The proof for a purchase: Google's product and token, or demo mode's fake proof. */
    private val proofFor: (StorePurchase) -> ProProof = { p -> ProProof.Google(productId = p.products.first(), purchaseToken = p.token) },
    private val onPro: suspend (proof: ProProof, productId: String) -> Unit = { _, _ -> },
    /** Where purchases Play announces outside a purchase sheet (a pending one approved) are taken up at once, as
     *  `onPurchasesUpdated` asks (§10.4); without one (the tests), at the next `takeUpdates`. */
    private val scope: CoroutineScope? = null,
) {
    sealed interface Prices {
        data object Loading : Prices
        data class Loaded(val prices: Map<ProPlan, StorePrice>) : Prices
        data object Failed : Prices
    }

    /** How a purchase ended, for the paywall: `Done` continues the flow; `message` is shown. */
    sealed interface Result {
        data object Done : Result
        data object Cancelled : Result
        data class Message(val text: String) : Result
    }

    private val cached = readCache()
    private val _isPro = MutableStateFlow(cached != null)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _prices = MutableStateFlow<Prices>(Prices.Loading)
    val prices: StateFlow<Prices> = _prices.asStateFlow()

    /** The purchase that makes this phone Pro, from the store's last answer. */
    private var best: StorePurchase? = null

    /** Every purchase the store's last answer held (an announcement outside a sheet only names what changed). */
    private var held: List<StorePurchase> = emptyList()
    private val refreshing = Mutex()

    init {
        gateway.updates = { purchases ->
            pendingUpdates = purchases
            scope?.launch { takeUpdates() }
        }
    }

    /** Purchases Play announced outside a sheet, not taken up yet (the listener runs on any thread). */
    @Volatile
    private var pendingUpdates: List<StorePurchase>? = null

    /** The proof of the best purchase, if the store has confirmed one since launch. */
    fun proof(): ProProof? = best?.let(proofFor)

    /** The product of the best purchase ("pro" or "pro_lifetime"). */
    val productId: String? get() = best?.products?.firstOrNull() ?: cached?.first

    suspend fun loadPrices() {
        if (_prices.value is Prices.Loaded) return
        _prices.value = Prices.Loading
        val p = try {
            gateway.prices()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _prices.value = if (p != null && p.size == ProPlan.entries.size) Prices.Loaded(p) else Prices.Failed
    }

    /** Asks Play what it holds (at launch, on every return to the foreground, and for "Restore purchases"):
     *  acknowledges new purchases, then sets Pro and the cache. Returns what it found, or null when Play couldn't be
     *  asked (nothing changes then). */
    suspend fun refresh(): List<StorePurchase>? = refreshing.withLock {
        val list = try {
            gateway.purchases()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withLock null
        apply(list)
        list
    }

    /** The store announced purchases outside a sheet (a pending purchase approved): Play's whole list read again,
     *  acknowledging what is new, so Pro starts at once. When Play can't be asked, the announced purchases replace
     *  those with the same token in the last answer (an announcement names only what changed, so it never takes away
     *  a purchase it doesn't name). */
    suspend fun takeUpdates() {
        val u = pendingUpdates ?: return
        pendingUpdates = null
        if (refresh() != null) return
        refreshing.withLock {
            val tokens = u.map { it.token }.toSet()
            apply(held.filter { it.token !in tokens } + u)
        }
    }

    /** `purchase(plan)`: the store's sheet, then what it says (§9.3 S3 results). */
    suspend fun buy(activity: Activity?, plan: ProPlan): Result {
        val outcome = try {
            gateway.buy(activity, plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PurchaseOutcome.Failed
        }
        return when (outcome) {
            is PurchaseOutcome.Purchased -> {
                // Play's own list, with what the sheet bought acknowledged; the sheet's answer if Play can't be asked.
                if (refresh() == null) refreshing.withLock { apply(outcome.purchases) }
                if (_isPro.value) Result.Done else Result.Message(PENDING)
            }
            PurchaseOutcome.Pending -> Result.Message(PENDING)
            PurchaseOutcome.Cancelled -> Result.Cancelled
            PurchaseOutcome.Failed -> Result.Message(STORE_UNAVAILABLE)
        }
    }

    /** "Restore purchases": Play's purchases read again. */
    suspend fun restore(): Result {
        val list = refresh() ?: return Result.Message(STORE_UNAVAILABLE)
        if (_isPro.value) return Result.Done
        return if (list.any { it.pending && isProProduct(it) }) Result.Message(PENDING) else Result.Message(NOT_FOUND)
    }

    private suspend fun apply(list: List<StorePurchase>) {
        held = list
        for (p in list) {
            if (p.purchased && !p.acknowledged && isProProduct(p)) {
                try {
                    gateway.acknowledge(p.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "a purchase couldn't be acknowledged yet")
                }
            }
        }
        val chosen = best(list)
        best = chosen
        _isPro.value = chosen != null
        if (chosen == null) {
            Defaults.prefs.edit().remove(CACHE_KEY).apply()
            return
        }
        val product = chosen.products.first()
        writeCache(product)
        try {
            onPro(proofFor(chosen), product)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the Pro proof couldn't be sent")
        }
    }

    private fun writeCache(productId: String) {
        Defaults.putString(
            CACHE_KEY,
            buildJsonObject {
                put("productId", productId)
                put("expiresAt", JsonNull)
                put("checkedAt", clock())
            }.toString(),
        )
    }

    /** The cached product and when it was checked, or null. */
    private fun readCache(): Pair<String, Long>? = try {
        Defaults.string(CACHE_KEY)?.let { text ->
            val o = Json.parseToJsonElement(text).jsonObject
            val product = o["productId"]?.jsonPrimitive?.content ?: return@let null
            product to (o["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "groups"
        const val CACHE_KEY = "proCache"
        const val PENDING = "Your purchase is waiting for approval. Pro starts when it's approved."
        const val NOT_FOUND = "No Spoor Pro purchase was found for this Google account."
        const val STORE_UNAVAILABLE = "The store can't be reached right now. Try again later."

        fun isProProduct(p: StorePurchase): Boolean =
            p.products.any { it == GroupConfig.proProducts.subscription || it == GroupConfig.proProducts.lifetime }

        /** Lifetime first, else the newest subscription; only purchased ones that aren't suspended. */
        fun best(list: List<StorePurchase>): StorePurchase? {
            val usable = list.filter { it.purchased && !it.suspended && isProProduct(it) }
            return usable.firstOrNull { GroupConfig.proProducts.lifetime in it.products } ?: usable.maxByOrNull { it.time }
        }

        /** "Save {n}%": n = floor((1 − yearly ÷ (12 × monthly)) × 100), null when it isn't above 0. */
        fun savingPercent(yearlyMicros: Long, monthlyMicros: Long): Int? {
            if (monthlyMicros <= 0) return null
            val n = floor((1 - yearlyMicros.toDouble() / (12.0 * monthlyMicros)) * 100).toInt()
            return n.takeIf { it > 0 }
        }
    }
}

/** Play Billing 9.1.0 (§10.4). The client is made once with pending purchases for one-time products and automatic
 *  reconnection; it connects the first time it is needed. */
class PlayBillingGateway(context: Context) : BillingGateway {
    override var updates: ((List<StorePurchase>) -> Unit)? = null

    /** The purchase sheet open now, waiting for Play's listener. */
    @Volatile
    private var buying: CompletableDeferred<PurchaseOutcome>? = null
    private var details: Map<ProPlan, Pair<ProductDetails, String>> = emptyMap()
    private val connecting = Mutex()

    private val appContext = context.applicationContext

    /** Made when first needed (nothing at launch until Pro is asked about). */
    private val client: BillingClient by lazy { makeClient() }

    private fun makeClient(): BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases ->
            val list = purchases.orEmpty().map(::storePurchase)
            val outcome = when (result.responseCode) {
                BillingClient.BillingResponseCode.OK ->
                    if (list.any { it.purchased }) PurchaseOutcome.Purchased(list)
                    else if (list.any { it.pending }) PurchaseOutcome.Pending
                    else PurchaseOutcome.Failed
                BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.Purchased(list)
                else -> PurchaseOutcome.Failed
            }
            val waiting = buying
            if (waiting != null && !waiting.isCompleted) waiting.complete(outcome) else if (list.isNotEmpty()) updates?.invoke(list)
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun connected(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        val done = CompletableDeferred<Boolean>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                done.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                if (!done.isCompleted) done.complete(false)
            }
        })
        // A phone without the Play Store may never answer.
        withTimeoutOrNull(15_000) { done.await() } ?: false
    }

    override suspend fun prices(): Map<ProPlan, StorePrice>? {
        if (!connected()) return null
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(GroupConfig.proProducts.subscription)
                .setProductType(BillingClient.ProductType.SUBS).build(),
        )
        val lifetime = listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(GroupConfig.proProducts.lifetime)
                .setProductType(BillingClient.ProductType.INAPP).build(),
        )
        val subs = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(products).build())
        val once = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(lifetime).build())
        if (subs.billingResult.responseCode != BillingClient.BillingResponseCode.OK ||
            once.billingResult.responseCode != BillingClient.BillingResponseCode.OK
        ) {
            return null
        }
        val found = HashMap<ProPlan, Pair<ProductDetails, String>>()
        val prices = HashMap<ProPlan, StorePrice>()
        for (d in subs.productDetailsList.orEmpty()) {
            for (offer in d.subscriptionOfferDetails.orEmpty()) {
                // The base plan itself (no offer id), its first (and only) pricing phase.
                if (offer.offerId != null) continue
                val plan = when (offer.basePlanId) {
                    GroupConfig.proProducts.monthlyBasePlan -> ProPlan.monthly
                    GroupConfig.proProducts.yearlyBasePlan -> ProPlan.yearly
                    else -> continue
                }
                val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: continue
                found[plan] = d to offer.offerToken
                prices[plan] = StorePrice(plan, phase.formattedPrice, phase.priceAmountMicros)
            }
        }
        for (d in once.productDetailsList.orEmpty()) {
            val offer = d.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: d.oneTimePurchaseOfferDetails ?: continue
            found[ProPlan.lifetime] = d to (offer.offerToken ?: "")
            prices[ProPlan.lifetime] = StorePrice(ProPlan.lifetime, offer.formattedPrice, offer.priceAmountMicros)
        }
        details = found
        return prices
    }

    override suspend fun purchases(): List<StorePurchase>? {
        if (!connected()) return null
        val all = ArrayList<StorePurchase>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val r = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
            if (r.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
            all += r.purchasesList.map(::storePurchase)
        }
        return all
    }

    override suspend fun buy(activity: Activity?, plan: ProPlan): PurchaseOutcome {
        if (activity == null || !connected()) return PurchaseOutcome.Failed
        if (details[plan] == null) prices()
        val (product, token) = details[plan] ?: return PurchaseOutcome.Failed
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
        if (token.isNotEmpty()) params.setOfferToken(token)
        val waiting = CompletableDeferred<PurchaseOutcome>()
        buying = waiting
        val launched = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params.build())).build(),
        )
        if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
            buying = null
            return if (launched.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                PurchaseOutcome.Purchased(purchases().orEmpty())
            } else {
                PurchaseOutcome.Failed
            }
        }
        return try {
            waiting.await()
        } finally {
            buying = null
        }
    }

    override suspend fun acknowledge(token: String): Boolean {
        if (!connected()) return false
        val r = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build())
        return r.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun storePurchase(p: Purchase) = StorePurchase(
        products = p.products,
        token = p.purchaseToken,
        purchased = p.purchaseState == Purchase.PurchaseState.PURCHASED,
        pending = p.purchaseState == Purchase.PurchaseState.PENDING,
        acknowledged = p.isAcknowledged,
        suspended = p.isSuspended,
        time = p.purchaseTime,
    )
}

/** A store with no Play behind it: demo mode (§8.15: not Pro at launch, any purchase succeeds at once and gives yearly
 *  Pro, at the fixed prices R79.99 / R399.99 / R1,199.99 so the screenshots are stable) and, in debug builds outside
 *  demo mode, the `fakePro` launch extra of the local check (§12.6: Pro at once, with a "local-test-…" token the
 *  server accepts under `ALLOW_UNVERIFIED_PRO`). */
class FakeBillingGateway(initial: List<StorePurchase> = emptyList(), private val clock: () -> Long = { System.currentTimeMillis() }) :
    BillingGateway {
    override var updates: ((List<StorePurchase>) -> Unit)? = null
    private val held = initial.toMutableList()

    override suspend fun prices(): Map<ProPlan, StorePrice> = mapOf(
        ProPlan.yearly to StorePrice(ProPlan.yearly, "R399.99", 399_990_000),
        ProPlan.monthly to StorePrice(ProPlan.monthly, "R79.99", 79_990_000),
        ProPlan.lifetime to StorePrice(ProPlan.lifetime, "R1,199.99", 1_199_990_000),
    )

    override suspend fun purchases(): List<StorePurchase> = held.toList()

    /** Every plan gives yearly Pro (§8.15). */
    override suspend fun buy(activity: Activity?, plan: ProPlan): PurchaseOutcome {
        val p = StorePurchase(listOf(GroupConfig.proProducts.subscription), "demo-" + Base64URL.encode(GroupRandom.bytes(12)),
                              purchased = true, pending = false, acknowledged = true, suspended = false, time = clock())
        held += p
        return PurchaseOutcome.Purchased(held.toList())
    }

    override suspend fun acknowledge(token: String) = true

    companion object {
        /** `fakePro`: the yearly subscription, already bought, with a "local-test-" token (§12.6). */
        fun localTest(): FakeBillingGateway = FakeBillingGateway(
            listOf(
                StorePurchase(listOf(GroupConfig.proProducts.subscription), "local-test-" + Base64URL.encode(GroupRandom.bytes(15)).take(20),
                              purchased = true, pending = false, acknowledged = true, suspended = false, time = 0),
            ),
        )
    }
}
