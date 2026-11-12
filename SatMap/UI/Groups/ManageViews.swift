import SwiftUI

/// S13 Report (docs/groups/SPEC.md §9.3, §11.7): why, optional details, and the reporter's decrypted copy goes to the
/// developer, without any position (SD10). Afterwards, for a pin or a member, an offer to block them too.
struct ReportView: View {
    @ObservedObject var groups: GroupsModel
    let groupId: String
    let subject: ReportSubject
    let nav: GroupsNav
    @State private var reason: ReportReason?
    @State private var comment = ""
    @State private var sending = false
    @State private var error: String?
    @State private var thanks = false

    var body: some View {
        Form {
            Section {
                ForEach(ReportReason.allCases, id: \.self) { r in
                    Button {
                        reason = r
                    } label: {
                        HStack {
                            Text(GroupTexts.reportReason(r)).foregroundColor(.primary)
                            Spacer()
                            Image(systemName: "checkmark")
                                .font(.body.weight(.semibold))
                                .foregroundColor(.yellow)
                                .opacity(reason == r ? 1 : 0)
                                .accessibilityHidden(true)
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityAddTraits(reason == r ? .isSelected : [])
                    .accessibilityIdentifier("reportReason-\(r.rawValue)")
                }
            } header: {
                Text(GroupTexts.reportQuestion(subject))
                    .textCase(nil)
            }
            Section {
                TextField("More details (optional)", text: $comment, axis: .vertical)
                    .lineLimit(2...5)
            }
            Section {
                Text(GroupTexts.reportNotice(subject))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Button {
                    send()
                } label: {
                    HStack {
                        Text("Send report").font(.headline)
                        Spacer()
                        if sending { ProgressView() }
                    }
                }
                .disabled(reason == nil || sending)
                .accessibilityIdentifier("sendReport")
                if let e = error {
                    GroupsNotice(text: e)
                }
            }
        }
        .navigationTitle("Report")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.cancel, action: nav.pop)
        .alert("Thanks for reporting", isPresented: $thanks) {
            if let who = blockable {
                Button("Block", role: .destructive) {
                    groups.hub.block(who.memberId, groupId: groupId)
                    nav.pop()
                }
                Button("Not now", role: .cancel) { nav.pop() }
            } else {
                Button("OK", role: .cancel) { nav.pop() }
            }
        } message: {
            if let who = blockable {
                Text("Do you also want to block \(who.name)? You won't see them on this phone. They can still see you while you share.")
            }
        }
    }

    /// Who the "Block" after the report would block: the pin's dropper or the member; nobody for the group name.
    private var blockable: (memberId: String, name: String)? {
        switch subject {
        case .pin(let pinId):
            guard let p = groups.hub.pinDetail(groupId: groupId, pinId: pinId), p.canBlock else { return nil }
            return (p.dropperId, p.dropperName)
        case .member(let memberId):
            guard let m = groups.hub.memberDetail(groupId: groupId, memberId: memberId), m.canBlock, !m.row.isBlocked else { return nil }
            return (memberId, m.row.name)
        case .group:
            return nil
        }
    }

    private func send() {
        guard let r = reason else { return }
        sending = true
        error = nil
        let text = comment
        Task { @MainActor in
            do {
                try await groups.hub.report(groupId: groupId, target: subject.target, reason: r, comment: text)
                sending = false
                thanks = true
            } catch let e as GroupActionError {
                sending = false
                error = e.message
            } catch {
                sending = false
                self.error = GroupActionError(.server).message
            }
        }
    }
}

/// S15 Your name and colour / Rename group (docs/groups/SPEC.md §9.3).
struct NameEditView: View {
    enum Kind {
        case yourName, groupName

        var title: String { self == .yourName ? "Your name and colour" : "Rename group" }
        var placeholder: String { self == .yourName ? "e.g. Sam" : "e.g. Kruger trip" }
        var field: GroupTextField { self == .yourName ? .displayName : .groupName }
    }

    @ObservedObject var groups: GroupsModel
    let groupId: String
    let kind: Kind
    let nav: GroupsNav
    @State private var text = ""
    @State private var colour = 0
    @State private var saving = false
    @State private var error: String?
    @State private var loaded = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 6)

    var body: some View {
        Form {
            Section {
                TextField(kind.placeholder, text: $text)
                    .textInputAutocapitalization(kind == .yourName ? .words : .sentences)
                    .accessibilityIdentifier(kind == .yourName ? "displayName" : "groupName")
            }
            if kind == .yourName {
                Section {
                    LazyVGrid(columns: columns, spacing: 10) {
                        ForEach(GroupPolicy.palette.indices, id: \.self) { i in
                            Button {
                                colour = i
                            } label: {
                                // Your dot as the map draws it, with your initials on the chosen colour, ringed.
                                MemberDot(colourHex: GroupPolicy.palette[i], initials: colour == i ? initials : "", size: 34)
                                    .padding(4)
                                    .overlay(Circle().stroke(Color.white, lineWidth: colour == i ? 2.5 : 0))
                                    .contentShape(Circle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Colour \(i + 1)"))
                            .accessibilityAddTraits(colour == i ? .isSelected : [])
                        }
                    }
                    .padding(.vertical, 6)
                }
            }
            if let e = error {
                Section { GroupsNotice(text: e) }
            }
        }
        .navigationTitle(kind.title)
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.cancel, action: nav.pop)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if saving {
                    ProgressView()
                } else {
                    Button("Save") { save() }
                        .disabled(GroupText.clean(text, kind.field) == nil)
                }
            }
        }
        .onAppear(perform: load)
    }

    /// The initials on the chosen colour: of the name being typed, or of your current name while the field is empty.
    private var initials: String {
        let typed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let current = groups.hub.detail(groupId)?.members.first(where: { $0.isYou })?.name ?? ""
        return GroupText.initials(typed.isEmpty ? current : typed)
    }

    /// Starts from the current name (and your colour).
    private func load() {
        guard !loaded, let d = groups.hub.detail(groupId) else { return }
        loaded = true
        switch kind {
        case .yourName:
            if let me = d.members.first(where: { $0.isYou }) {
                text = me.name
                colour = GroupPolicy.palette.firstIndex(of: me.colourHex) ?? 0
            }
        case .groupName:
            text = d.name
        }
    }

    private func save() {
        saving = true
        error = nil
        let value = text, chosen = colour
        Task { @MainActor in
            do {
                switch kind {
                case .yourName:
                    try await groups.hub.setDisplayName(value, colour: chosen, groupId: groupId)
                    groups.rememberDisplayName(value)
                case .groupName:
                    try await groups.hub.renameGroup(value, groupId: groupId)
                }
                saving = false
                nav.pop()
            } catch let e as GroupActionError {
                saving = false
                error = e.message
            } catch {
                saving = false
                self.error = GroupActionError(.server).message
            }
        }
    }
}

/// S16 Blocked (docs/groups/SPEC.md §9.3): the members blocked on this phone, by their real names.
struct BlockedView: View {
    @ObservedObject var groups: GroupsModel
    let groupId: String
    let nav: GroupsNav

    var body: some View {
        let blocked = groups.hub.detail(groupId)?.blocked ?? []
        List {
            Section {
                ForEach(blocked, id: \.memberId) { m in
                    HStack(spacing: 12) {
                        MemberDot(colourHex: m.colourHex, initials: m.initials)
                        Text(m.name)
                        Spacer()
                        Button("Unblock") { groups.hub.unblock(m.memberId, groupId: groupId) }
                            .buttonStyle(.borderless)
                    }
                }
            } footer: {
                Text("Blocked members are hidden on this phone only. They aren't told, and they can still see you while you share in this group.")
            }
        }
        .navigationTitle("Blocked")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.back, action: nav.pop)
    }
}

/// S14 Hand over ownership, from S5 (docs/groups/SPEC.md §9.3): choose the member, then confirm.
struct HandoverPickerView: View {
    @ObservedObject var groups: GroupsModel
    let groupId: String
    let nav: GroupsNav
    @State private var chosen: MemberRow?
    @State private var error: String?

    var body: some View {
        let members = (groups.hub.detail(groupId)?.members ?? []).filter { !$0.isYou }
        List {
            Section {
                ForEach(members, id: \.memberId) { m in
                    Button {
                        chosen = m
                    } label: {
                        HStack(spacing: 12) {
                            MemberDot(colourHex: m.colourHex, initials: m.initials)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(m.name).foregroundColor(.primary)
                                Text(GroupTexts.memberSubtitle(role: m.role, state: m.state, now: groupNow))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Hand over to")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.back, action: nav.pop)
        .alert(chosen.map { "Make \($0.name) the owner?" } ?? "",
               isPresented: Binding(get: { chosen != nil }, set: { if !$0 { chosen = nil } }), presenting: chosen) { m in
            Button("Send request") { send(to: m.memberId) }
            Button("Cancel", role: .cancel) {}
        } message: { m in
            Text("\(m.name) needs Spoor Pro to accept. You'll become an admin. If they don't accept within 7 days, the request ends.")
        }
        .alert(error ?? "", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func send(to memberId: String) {
        Task { @MainActor in
            do {
                try await groups.hub.offerOwnership(to: memberId, groupId: groupId)
                nav.pop()
            } catch let e as GroupActionError {
                error = e.message
            } catch {
                self.error = GroupActionError(.server).message
            }
        }
    }
}
