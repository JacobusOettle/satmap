import Foundation
import StoreKit

/// SatMap Pro through StoreKit 2 (docs/groups/SPEC.md §10.3): the three products' prices, purchase, restore, the
/// current entitlement and its proof for the server. Pro is cached on the phone for offline use (§10.6). In demo mode
/// (§8.15) it is a fake: not Pro at launch, fixed prices, and any purchase succeeds at once with yearly Pro.
///
/// Used on the main thread; the methods that wait for the store are main-actor isolated.
final class ProStore: ObservableObject {
    enum Prices: Equatable {
        case loading
        case loaded([ProPlan: String], savePercent: Int?)
        case failed
    }

    enum PurchaseResult: Equatable {
        case success
        case pending
        case cancelled
        case failed(String)
    }

    enum RestoreResult: Equatable {
        case found
        case notFound
        case cancelled
        case failed(String)
    }

    /// The entitlement the server is told about: the proof, its product and its expiry (nil for Lifetime).
    struct Entitlement: Equatable {
        let proof: ProProof
        let productId: String
        let expiresAt: Int64?
    }

    static let storeUnavailable = "The store can't be reached right now. Try again later."
    static let pendingText = "Your purchase is waiting for approval. Pro starts when it's approved."
    static let nothingToRestore = "No Spoor Pro purchase was found for this Apple ID." // §9.9: "Google account" on Android
    static let demoPrices: [ProPlan: String] = [.yearly: "R399.99", .monthly: "R79.99", .lifetime: "R1,199.99"]

    @Published private(set) var isPro = false
    @Published private(set) var prices = Prices.loading
    /// Told about the entitlement at launch and after every store change, to send the proof where needed (§10.7).
    var onEntitlement: ((Entitlement) -> Void)?

    private let demo: Bool
    private let defaults: UserDefaults
    private var products: [String: Product] = [:]
    private(set) var entitlement: Entitlement?
    private var updates: Task<Void, Never>?

    init(demo: Bool, defaults: UserDefaults = .standard) {
        self.demo = demo
        self.defaults = defaults
        if !demo, let cache = ProCache.decode(defaults.data(forKey: "proCache")) {
            // Shown at once, and offline, until the store answers.
            isPro = cache.isPro(now: Self.now)
        }
    }

    deinit {
        updates?.cancel()
    }

    private static var now: Int64 { Int64(Date().timeIntervalSince1970) }

    /// The proof of the current entitlement, for E1 and E17.
    var proof: ProProof? { entitlement?.proof }

    /// At launch: listens for store updates (renewals, refunds, purchases finished elsewhere) and reads the current
    /// entitlement.
    func start() {
        guard !demo, updates == nil else { return }
        updates = Task.detached(priority: .utility) { [weak self] in
            for await update in Transaction.updates {
                guard case .verified(let t) = update else { continue }
                await t.finish()
                await self?.storeChanged()
            }
        }
        Task { @MainActor [weak self] in await self?.storeChanged() }
    }

    @MainActor
    private func storeChanged() async {
        await refresh()
        if let e = entitlement { onEntitlement?(e) }
    }

    /// Reads `Transaction.currentEntitlements`: Lifetime if owned, else the subscription with the latest expiry. Only
    /// verified, unrevoked Pro transactions of this Apple ID count (Family Sharing is off, §10.1).
    @MainActor
    func refresh() async {
        guard !demo else { return }
        var lifetime: (VerificationResult<Transaction>, Transaction)?
        var subscription: (VerificationResult<Transaction>, Transaction)?
        for await result in Transaction.currentEntitlements {
            guard case .verified(let t) = result, GroupConfig.proProducts.all.contains(t.productID), t.revocationDate == nil,
                  t.ownershipType == .purchased else { continue }
            if t.productType == .nonConsumable {
                lifetime = (result, t)
            } else if t.productType == .autoRenewable,
                      (t.expirationDate ?? .distantPast) > (subscription?.1.expirationDate ?? .distantPast) {
                subscription = (result, t)
            }
        }
        if let best = lifetime ?? subscription {
            let (result, t) = best
            let expires = t.productType == .nonConsumable ? nil : t.expirationDate.map { Int64($0.timeIntervalSince1970) }
            entitlement = Entitlement(proof: .apple(signedTransaction: result.jwsRepresentation), productId: t.productID, expiresAt: expires)
            isPro = true
            defaults.set(ProCache(productId: t.productID, expiresAt: expires, checkedAt: Self.now).encoded(), forKey: "proCache")
        } else {
            entitlement = nil
            if Connectivity.shared.isOnline {
                // The store says not Pro while online: the cache goes (§10.6).
                defaults.removeObject(forKey: "proCache")
                isPro = false
            } else {
                isPro = ProCache.decode(defaults.data(forKey: "proCache"))?.isPro(now: Self.now) ?? false
            }
        }
    }

    /// The three products' prices (S3).
    @MainActor
    func loadPrices() async {
        if demo {
            prices = .loaded(Self.demoPrices, savePercent: ProPricing.savePercent(yearly: Decimal(string: "399.99")!, monthly: Decimal(string: "79.99")!))
            return
        }
        if case .loaded = prices, products.count == ProPlan.allCases.count { return }
        prices = .loading
        do {
            let list = try await Product.products(for: GroupConfig.proProducts.all)
            for p in list { products[p.id] = p }
            guard let y = products[ProPlan.yearly.productId], let m = products[ProPlan.monthly.productId],
                  let l = products[ProPlan.lifetime.productId] else {
                prices = .failed
                return
            }
            prices = .loaded([.yearly: y.displayPrice, .monthly: m.displayPrice, .lifetime: l.displayPrice],
                             savePercent: ProPricing.savePercent(yearly: y.price, monthly: m.price))
        } catch {
            prices = .failed
        }
    }

    /// Buys a plan (`purchase(options:)` works from iOS 16; `appAccountToken` is not used).
    @MainActor
    func purchase(_ plan: ProPlan) async -> PurchaseResult {
        if demo {
            let now = Self.now
            entitlement = Entitlement(proof: GroupDemo.proProof(now: now), productId: ProPlan.yearly.productId, expiresAt: now + 365 * 86_400)
            isPro = true
            if let e = entitlement { onEntitlement?(e) }
            return .success
        }
        guard let product = products[plan.productId] else { return .failed(Self.storeUnavailable) }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                // Unverified results are ignored.
                guard case .verified(let t) = verification else { return .cancelled }
                await t.finish()
                await storeChanged()
                return isPro ? .success : .failed(Self.storeUnavailable)
            case .pending:
                return .pending
            case .userCancelled:
                return .cancelled
            @unknown default:
                return .cancelled
            }
        } catch {
            return .failed(Self.storeUnavailable)
        }
    }

    /// "Restore purchases": `AppStore.sync()` (it may ask for the Apple ID password, so only on a tap), then the
    /// entitlement again.
    @MainActor
    func restore() async -> RestoreResult {
        if demo { return entitlement != nil ? .found : .notFound }
        do {
            try await AppStore.sync()
        } catch StoreKitError.userCancelled {
            return .cancelled
        } catch {
            return .failed(Self.storeUnavailable)
        }
        await storeChanged()
        return entitlement != nil ? .found : .notFound
    }
}
