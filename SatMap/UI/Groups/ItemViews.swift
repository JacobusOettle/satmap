import SwiftUI

/// The Block alert (docs/groups/SPEC.md §9.3, §8.11): one-way and on this phone only; while you share in the group it
/// also offers "Block and stop sharing".
struct BlockRequest: Equatable {
    let memberId: String
    let name: String
    let sharing: Bool
}

extension View {
    func blockAlert(_ request: Binding<BlockRequest?>, groups: GroupsModel, groupId: String,
                    onBlocked: @escaping () -> Void = {}) -> some View {
        alert(request.wrappedValue.map { "Block \($0.name)?" } ?? "",
              isPresented: Binding(get: { request.wrappedValue != nil }, set: { if !$0 { request.wrappedValue = nil } }),
              presenting: request.wrappedValue) { r in
            Button("Block", role: .destructive) {
                groups.hub.block(r.memberId, groupId: groupId)
                onBlocked()
            }
            if r.sharing {
                Button("Block and stop sharing", role: .destructive) {
                    groups.hub.block(r.memberId, groupId: groupId)
                    groups.hub.setSharing(false, groupId: groupId)
                    onBlocked()
                }
                .accessibilityIdentifier("blockStopSharing")
            }
            Button("Cancel", role: .cancel) {}
        } message: { r in
            Text("You won't see \(r.name)'s position, trail, pins or name on this phone. They won't be told, and they can still see you while you share in this group.")
        }
    }
}

/// A position's coordinates and height, as for a tapped spot: tap the coordinates to copy them, the height to change
/// its unit.
struct GroupPositionRow: View {
    @ObservedObject var model: AppModel
    let position: GroupCoordinate
    let id: String
    @State private var elevation: Double?

    var body: some View {
        let p = GeoPoint(lat: position.lat, lon: position.lon)
        CoordinateRow {
            CoordinateBar(icon: "mappin", text: Format.coordinate(p), id: id)
            HeightBar(elevation: elevation, unit: model.heightUnit, id: id + "Height", onTap: model.nextHeightUnit)
        }
        .frame(maxWidth: .infinity)
        .listRowBackground(Color.clear)
        .task(id: p) { elevation = await TileRepository.shared.elevation(at: p) }
    }
}

/// S11 Pin detail (docs/groups/SPEC.md §9.3): the pin, who dropped it and when, where it is; delete, report or block.
struct PinDetailView: View {
    @ObservedObject var groups: GroupsModel
    @ObservedObject var model: AppModel
    let groupId: String
    let pinId: String
    let nav: GroupsNav
    let leading: GroupsLeading
    @State private var confirmDelete = false
    @State private var block: BlockRequest?

    var body: some View {
        if let p = groups.hub.pinDetail(groupId: groupId, pinId: pinId) {
            let now = groupNow
            Form {
                Section {
                    HStack(spacing: 12) {
                        ZStack {
                            Circle().fill(Color(groupHex: p.knownType?.colourHex ?? PinType.unknownColourHex))
                            Circle().stroke(Color.white, lineWidth: 2)
                            Image(systemName: GroupTexts.pinSymbol(p.knownType))
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundColor(.white)
                        }
                        .frame(width: 40, height: 40)
                        .accessibilityHidden(true)
                        Text(p.knownType == .animal ? (p.animal ?? GroupTexts.pinTitle(p.knownType)) : GroupTexts.pinTitle(p.knownType))
                            .font(.title3.weight(.bold))
                    }
                    if let note = p.note {
                        Text(note)
                    }
                    Text("Dropped by \(p.dropperName) · \(groupDates.moment(p.createdAt, now: now)) (\(GroupFormat.ago(now - p.createdAt)))")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    if p.knownType == .animal, let expires = p.expiresAt {
                        Text("Disappears at \(groupDates.moment(expires, now: now))")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
                Section {
                    GroupPositionRow(model: model, position: p.position, id: "pinCoordinates")
                }
                if p.canDelete || p.canReport || p.canBlock {
                    Section {
                        if p.canDelete {
                            Button("Delete pin", role: .destructive) { confirmDelete = true }
                        }
                        if p.canReport {
                            Button("Report pin") { nav.push(.report(groupId: groupId, subject: .pin(pinId))) }
                        }
                        if p.canBlock {
                            Button("Block \(p.dropperName)") {
                                block = BlockRequest(memberId: p.dropperId, name: p.dropperName,
                                                     sharing: groups.hub.detail(groupId)?.isSharing ?? false)
                            }
                        }
                    }
                }
            }
            .navigationTitle(GroupTexts.pinTitle(p.knownType))
            .navigationBarTitleDisplayMode(.inline)
            .groupsLeading(leading, action: close)
            .alert("Delete this pin for everyone?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) {
                    groups.hub.deletePin(pinId, groupId: groupId)
                    close()
                }
                Button("Cancel", role: .cancel) {}
            }
            .blockAlert($block, groups: groups, groupId: groupId, onBlocked: close)
        } else {
            Text("This is no longer in the group.")
                .foregroundColor(.secondary)
                .groupsLeading(leading, action: close)
        }
    }

    private func close() {
        if leading == .done { nav.closeSheet() } else { nav.pop() }
    }
}

/// S12 Member detail (docs/groups/SPEC.md §9.3): who, their role and state, where they are; the owner's and admins'
/// actions; report, block or unblock; your own name and colour.
struct MemberDetailView: View {
    @ObservedObject var groups: GroupsModel
    @ObservedObject var model: AppModel
    let groupId: String
    let memberId: String
    let nav: GroupsNav
    let leading: GroupsLeading
    @State private var alert: MemberAlert?
    @State private var block: BlockRequest?

    enum MemberAlert: Equatable {
        case makeOwner(String)
        case remove(String)
        case error(String)
    }

    var body: some View {
        if let md = groups.hub.memberDetail(groupId: groupId, memberId: memberId), let d = groups.hub.detail(groupId) {
            let row = md.row
            // A blocked member's row reads "Blocked member"; the actions name them by their real name (S16 does too).
            let realName = d.blocked.first { $0.memberId == memberId }?.name ?? row.name
            Form {
                Section {
                    HStack(spacing: 14) {
                        MemberDot(colourHex: row.colourHex, initials: row.initials, size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(GroupTexts.memberName(row)).font(.title3.weight(.bold))
                            if let role = GroupTexts.role(row.role) {
                                Text(role).foregroundColor(.secondary)
                            }
                        }
                    }
                    Text(GroupTexts.detailState(row.state, now: groupNow))
                        .foregroundColor(.secondary)
                }
                if let live = md.live {
                    Section {
                        GroupPositionRow(model: model, position: live, id: "memberCoordinates")
                        Button {
                            groups.showOnMap(live)
                        } label: {
                            Label("Show on map", systemImage: "map")
                        }
                    }
                }
                if md.canMakeAdmin || md.canRemoveAdmin || md.canMakeOwner || md.canRemove {
                    Section {
                        if md.canMakeAdmin {
                            Button("Make admin") { run { try await $0.setRole(.admin, memberId: memberId, groupId: groupId) } }
                        }
                        if md.canRemoveAdmin {
                            Button("Remove admin") { run { try await $0.setRole(.member, memberId: memberId, groupId: groupId) } }
                        }
                        if md.canMakeOwner {
                            Button("Make owner") { alert = .makeOwner(realName) }
                        }
                        if md.canRemove {
                            Button("Remove from group", role: .destructive) { alert = .remove(realName) }
                        }
                    }
                }
                if md.canReport || md.canBlock {
                    Section {
                        if md.canReport {
                            Button("Report \(realName)") { nav.push(.report(groupId: groupId, subject: .member(memberId))) }
                        }
                        if md.canBlock {
                            if row.isBlocked {
                                Button("Unblock \(realName)") { groups.hub.unblock(memberId, groupId: groupId) }
                            } else {
                                Button("Block \(realName)") {
                                    block = BlockRequest(memberId: memberId, name: realName, sharing: d.isSharing)
                                }
                            }
                        }
                    }
                }
                if row.isYou {
                    Section {
                        NavigationLink("Your name and colour", value: GroupsRoute.editName(groupId))
                    }
                }
            }
            .navigationTitle(row.name)
            .navigationBarTitleDisplayMode(.inline)
            .groupsLeading(leading, action: close)
            .alert(alertTitle, isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } }), presenting: alert) { a in
                switch a {
                case .makeOwner:
                    Button("Send request") {
                        run { try await $0.offerOwnership(to: memberId, groupId: groupId) }
                    }
                    Button("Cancel", role: .cancel) {}
                case .remove:
                    Button("Remove", role: .destructive) {
                        run { hub in
                            try await hub.removeMember(memberId, groupId: groupId)
                            close()
                        }
                    }
                    Button("Cancel", role: .cancel) {}
                case .error:
                    Button("OK", role: .cancel) {}
                }
            } message: { a in
                switch a {
                case .makeOwner(let name):
                    Text("\(name) needs SatMap Pro to accept. You'll become an admin. If they don't accept within 7 days, the request ends.")
                case .remove(let name):
                    Text("\(name)'s trail and pins in this group are deleted. They can only come back with a new invite.")
                case .error:
                    EmptyView()
                }
            }
            .blockAlert($block, groups: groups, groupId: groupId)
        } else {
            Text("This is no longer in the group.")
                .foregroundColor(.secondary)
                .groupsLeading(leading, action: close)
        }
    }

    private var alertTitle: String {
        switch alert {
        case .makeOwner(let name): return "Make \(name) the owner?"
        case .remove(let name): return "Remove \(name)?"
        case .error(let text): return text
        case nil: return ""
        }
    }

    private func close() {
        if leading == .done { nav.closeSheet() } else { nav.pop() }
    }

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
}
