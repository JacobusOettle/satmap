import SwiftUI

/// S3 Paywall (docs/groups/SPEC.md §9.3, §10.5): the three plans with the store's prices, buy and restore. On
/// success it closes and the flow continues: S4 for a create, the proof to the server for a renewal (sent by
/// `GroupsModel` on every store change), or the ownership accept.
struct PaywallView: View {
    @ObservedObject var groups: GroupsModel
    @ObservedObject var pro: ProStore
    let next: PaywallNext
    let nav: GroupsNav
    @State private var plan = ProPlan.yearly
    @State private var working = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                Text("Create your own groups")
                    .font(.title2.weight(.bold))
                    .padding(.vertical, 4)
                IconRow(systemImage: "person.3.fill", text: "Create groups and invite as many people as you like")
                IconRow(systemImage: "gift.fill", text: "Members join free")
                IconRow(systemImage: "map.fill", text: "See everyone's position and trail, and share pins")
                IconRow(systemImage: "lock.fill", text: "Encrypted on your phone: we can't read it")
            }

            Section {
                switch pro.prices {
                case .loading:
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Loading prices…").foregroundColor(.secondary)
                    }
                case .failed:
                    GroupsNotice(text: ProStore.storeUnavailable)
                case .loaded(let prices, let save):
                    ForEach(ProPlan.allCases, id: \.self) { p in
                        Button {
                            plan = p
                        } label: {
                            planRow(p, price: prices[p] ?? "", save: p == .yearly ? save : nil)
                        }
                        .accessibilityIdentifier(p.accessibilityId)
                        .accessibilityAddTraits(p == plan ? .isSelected : [])
                    }
                }
            } header: {
                Text("Choose a plan")
            }

            Section {
                Button {
                    buy()
                } label: {
                    HStack {
                        Text(plan.buyTitle).font(.headline)
                        Spacer()
                        if working { ProgressView() }
                    }
                }
                .disabled(!pricesLoaded || working)
                .accessibilityIdentifier("buyPro")
                Button("Restore purchases") { restore() }
                    .disabled(working)
                    .accessibilityIdentifier("restorePurchases")
                if let m = message {
                    GroupsNotice(text: m)
                }
            } footer: {
                // §9.9: "Google Play" on Android.
                Text("Subscriptions renew automatically until you cancel them in your App Store settings, at least 24 hours before the end of the period. If Pro ends, groups you own pause until you renew or hand them to a member who has Pro. Pro bought on an iPhone doesn't carry over to Android, or the other way round.")
            }

            Section {
                LinkRow(title: "Terms of use", url: GroupConfig.termsURL)
                LinkRow(title: "Privacy notice", url: GroupConfig.privacyURL)
            }
        }
        .navigationTitle("SatMap Pro")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.cancel, action: nav.pop)
        .task { await pro.loadPrices() }
    }

    private var pricesLoaded: Bool {
        if case .loaded = pro.prices { return true }
        return false
    }

    private func planRow(_ p: ProPlan, price: String, save: Int?) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(p.title).foregroundColor(.primary).font(.body.weight(.semibold))
                    if let n = save {
                        Text("Save \(n)%")
                            .font(.caption.weight(.bold))
                            .foregroundColor(.black)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Color.yellow)
                            .clipShape(Capsule())
                    }
                }
                Text(p.detail(price: price)).font(.subheadline).foregroundColor(.secondary)
            }
            Spacer()
            Image(systemName: p == plan ? "checkmark.circle.fill" : "circle")
                .font(.title3)
                .foregroundColor(p == plan ? .yellow : .secondary)
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
    }

    private func buy() {
        working = true
        message = nil
        Task { @MainActor in
            let result = await pro.purchase(plan)
            working = false
            switch result {
            case .success: done()
            case .pending: message = ProStore.pendingText
            case .cancelled: break
            case .failed(let text): message = text
            }
        }
    }

    private func restore() {
        working = true
        message = nil
        Task { @MainActor in
            let result = await pro.restore()
            working = false
            switch result {
            case .found: done()
            case .notFound: message = ProStore.nothingToRestore
            case .cancelled: break
            case .failed(let text): message = text
            }
        }
    }

    /// The screen closes and the flow continues.
    private func done() {
        nav.setPath(GroupsFlow.afterPurchase(nav.path(), next: next))
        groups.continueAfterPurchase(next)
    }
}
