import SwiftUI
import UIKit

/// S5 Group detail (docs/groups/SPEC.md §9.3): the pause or an ownership offer, showing on the map and sharing,
/// members, invites, pins, names, clearing your trail, the blocked members, and leaving, handing over or deleting.
/// Opening it refreshes the status, the members' last live times and the invites (§8.4).
struct GroupDetailView: View {
    @ObservedObject var groups: GroupsModel
    @ObservedObject var model: AppModel
    let groupId: String
    let nav: GroupsNav
    let leading: GroupsLeading
    @State private var alert: DetailAlert?
    @State private var working = false

    enum DetailAlert: Equatable {
        case stopSharing(String)
        case clearTrail(String)
        case leave(String)
        case delete(String)
        case cancelInvite(String)
        case locationOff
        case error(String)
    }

    var body: some View {
        if let d = groups.hub.detail(groupId) {
            content(d)
                .navigationTitle(d.name)
                .navigationBarTitleDisplayMode(.inline)
                .groupsLeading(leading, action: close)
                .task(id: groupId) { await groups.hub.refreshDetail(groupId) }
                .alert(alertTitle, isPresented: alertShown, presenting: alert, actions: alertActions, message: alertMessage)
        } else {
            Text("This group is no longer on this phone.")
                .foregroundColor(.secondary)
                .groupsLeading(leading, action: close)
        }
    }

    private func close() {
        if leading == .done { nav.closeSheet() } else { nav.pop() }
    }

    // MARK: Sections (in the order of §9.3)

    @ViewBuilder private func content(_ d: GroupDetail) -> some View {
        Form {
            if d.isPaused {
                Section {
                    Text(GroupTexts.pausedSection(reason: d.pausedReason, isOwner: d.yourRole == .owner,
                                                  deleteDate: d.deleteAt.map { groupDates.day($0, now: groupNow) }))
                        .fixedSize(horizontal: false, vertical: true)
                    if d.yourRole == .owner {
                        Button("Renew SatMap Pro") { groups.renew(groupId, nav: nav) }
                        Button("Hand over ownership") { nav.push(.handover(groupId)) }
                    }
                }
                .listRowBackground(Color.orange.opacity(0.22))
            }

            if let owner = d.offerToYouFrom {
                Section {
                    Text("\(owner) wants to make you the owner of this group. You need SatMap Pro.")
                    Button("Accept") { groups.acceptOwnership(groupId, nav: nav) }
                        .accessibilityIdentifier("acceptOwner")
                    Button("Decline") { run { try await $0.cancelOwnershipOffer(groupId: groupId) } }
                }
            }

            if let target = d.offerByYouTo {
                Section {
                    Text("Waiting for \(target) to accept ownership.")
                    Button("Cancel request") { run { try await $0.cancelOwnershipOffer(groupId: groupId) } }
                }
            }

            Section {
                Toggle("Show on the map", isOn: Binding(get: { d.isShown }, set: { groups.setShown(groupId, on: $0) }))
                    .accessibilityIdentifier("showOnMap")
                Toggle("Share my location", isOn: Binding(get: { d.isSharing }, set: { setSharing($0, d) }))
                    .disabled(d.isPaused)
                    .accessibilityIdentifier("shareLocation")
                Picker("Trails", selection: Binding(get: { d.trails }, set: { groups.hub.setTrails($0, groupId: groupId) })) {
                    Text("Off").tag(TrailsMode.off)
                    Text("Last 24 hours").tag(TrailsMode.day)
                    Text("Everything").tag(TrailsMode.all)
                }
                .accessibilityIdentifier("trailsMode")
            } header: {
                Text("On the map")
            } footer: {
                Text(GroupTexts.sharingFooter(isSharing: d.isSharing, isPaused: d.isPaused))
            }

            Section {
                ForEach(d.members, id: \.memberId) { m in
                    NavigationLink(value: GroupsRoute.member(groupId: groupId, memberId: m.memberId)) {
                        HStack(spacing: 12) {
                            MemberDot(colourHex: m.colourHex, initials: m.initials)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(GroupTexts.memberName(m)).foregroundColor(.primary)
                                Text(GroupTexts.memberSubtitle(role: m.role, state: m.state, now: groupNow))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                    .accessibilityIdentifier("memberRow")
                }
            } header: {
                Text("Members (\(d.memberCount))")
            }

            if d.canInvite {
                Section {
                    Button {
                        makeInvite()
                    } label: {
                        HStack {
                            Label("Invite someone", systemImage: "person.badge.plus")
                            Spacer()
                            if working { ProgressView() }
                        }
                    }
                    .disabled(working)
                    .accessibilityIdentifier("inviteSomeone")
                    ForEach(d.invites ?? [], id: \.inviteId) { i in
                        inviteRow(i)
                    }
                } header: {
                    Text("Invites")
                } footer: {
                    Text("Each invite link works once, for one person, and expires after 7 days.")
                }
            }

            Section {
                Button {
                    if let me = groups.myPosition {
                        nav.push(.dropPin(groupId: groupId, lat: me.latitude, lon: me.longitude))
                    }
                } label: {
                    Label("Drop a pin where I am", systemImage: "mappin.and.ellipse")
                }
                .disabled(groups.myPosition == nil || d.isPaused)
                NavigationLink("Your name and colour", value: GroupsRoute.editName(groupId))
                if d.canRename {
                    NavigationLink("Rename group", value: GroupsRoute.rename(groupId))
                }
                if d.canReportGroupName {
                    NavigationLink("Report group name", value: GroupsRoute.report(groupId: groupId, subject: .group))
                        .accessibilityIdentifier("reportGroup")
                }
            }

            Section {
                Button("Clear my trail", role: .destructive) { alert = .clearTrail(d.name) }
            }

            if !d.blocked.isEmpty {
                Section {
                    NavigationLink("Blocked (\(d.blocked.count))", value: GroupsRoute.blocked(groupId))
                }
            }

            Section {
                if d.canHandOver {
                    Button("Hand over ownership") { nav.push(.handover(groupId)) }
                }
                if d.canDeleteGroup {
                    Button("Delete group", role: .destructive) { alert = .delete(d.name) }
                }
                if d.canLeave {
                    Button("Leave group", role: .destructive) { alert = .leave(d.name) }
                }
                Button {
                    UIPasteboard.general.string = d.supportCode
                    groups.toast("Support code copied")
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Support code").foregroundColor(.primary)
                        Text(d.supportCode)
                            .font(.caption.monospaced())
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                }
                .accessibilityIdentifier("supportCode")
            } footer: {
                Text("Report a problem: \(GroupConfig.contactEmail). Quote your support code when you write about your data.")
            }
        }
    }

    private func inviteRow(_ i: PendingInvite) -> some View {
        HStack {
            Button {
                if i.link != nil { nav.push(.invite(groupId: groupId, inviteId: i.inviteId)) }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Invite from \(i.createdByName)").foregroundColor(.primary)
                    Text("Expires \(groupDates.day(i.expiresAt, now: groupNow))")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .disabled(i.link == nil)
            Button("Cancel") { alert = .cancelInvite(i.inviteId) }
                .buttonStyle(.borderless)
                .foregroundColor(.red)
        }
    }

    // MARK: Actions

    private func setSharing(_ on: Bool, _ d: GroupDetail) {
        if on && !d.hasConsent {
            nav.push(.consent(groupId, .back))
            return
        }
        Task { @MainActor in
            if await groups.setSharing(on, groupId: groupId) {
                if !on { alert = .stopSharing(d.name) }
            } else {
                alert = .locationOff
            }
        }
    }

    private func makeInvite() {
        working = true
        Task { @MainActor in
            do {
                let made = try await groups.hub.createInvite(groupId: groupId)
                working = false
                nav.push(.invite(groupId: groupId, inviteId: made.inviteId))
            } catch {
                working = false
                alert = .error("Couldn't make an invite. Check your connection and try again.")
            }
        }
    }

    /// Runs a hub action; its failure text shows in an alert.
    private func run(_ work: @escaping @MainActor (GroupHub) async throws -> Void) {
        let hub = groups.hub
        Task { @MainActor in
            do {
                try await work(hub)
            } catch let e as GroupActionError {
                alert = .error(e.message)
            } catch {
                alert = .error(GroupActionError(.server).message)
            }
        }
    }

    // MARK: Alerts (§9.3 "Alerts")

    private var alertShown: Binding<Bool> {
        Binding(get: { alert != nil }, set: { if !$0 { alert = nil } })
    }

    private var alertTitle: String {
        switch alert {
        case .stopSharing: return "Sharing is off"
        case .clearTrail: return "Clear your trail?"
        case .leave(let g): return "Leave \(g)?"
        case .delete(let g): return "Delete \(g)?"
        case .cancelInvite: return "Cancel this invite?"
        case .locationOff: return "Location is off for SatMap. Turn it on in Settings › Privacy › Location Services to share."
        case .error(let text): return text
        case nil: return ""
        }
    }

    @ViewBuilder private func alertActions(_ a: DetailAlert) -> some View {
        switch a {
        case .stopSharing:
            Button("Clear my trail too", role: .destructive) { groups.hub.clearMyTrail(groupId: groupId) }
            Button("Keep trail", role: .cancel) {}
        case .clearTrail:
            Button("Clear my trail", role: .destructive) { groups.hub.clearMyTrail(groupId: groupId) }
            Button("Cancel", role: .cancel) {}
        case .leave:
            Button("Leave group", role: .destructive) { run { try await $0.leave(groupId: groupId) } }
            Button("Cancel", role: .cancel) {}
        case .delete:
            Button("Delete group", role: .destructive) { run { try await $0.deleteGroup(groupId: groupId) } }
            Button("Cancel", role: .cancel) {}
        case .cancelInvite(let inviteId):
            Button("Cancel invite", role: .destructive) { run { try await $0.cancelInvite(inviteId, groupId: groupId) } }
            Button("Keep", role: .cancel) {}
        case .locationOff, .error:
            Button("OK", role: .cancel) {}
        }
    }

    @ViewBuilder private func alertMessage(_ a: DetailAlert) -> some View {
        switch a {
        case .stopSharing(let g):
            Text("Members can't see where you are now. The trail you already shared in \(g) stays visible to them until you clear it.")
        case .clearTrail(let g):
            Text("Everyone in \(g) stops seeing where you've been. Your own track in Where I've been stays. This can't be undone.")
        case .leave:
            Text("Your trail and pins in this group are deleted. You can only come back with a new invite.")
        case .delete:
            Text("The group, its trails and pins are deleted for everyone. This can't be undone.")
        case .cancelInvite:
            Text("The link stops working.")
        case .locationOff, .error:
            EmptyView()
        }
    }
}
