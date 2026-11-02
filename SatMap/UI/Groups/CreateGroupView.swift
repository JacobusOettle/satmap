import SwiftUI

/// S4 Create group (docs/groups/SPEC.md §9.3): the group's name and yours, then E1 with the Pro proof. On success the
/// stack becomes [List, Consent(gid)], then the group's screen (§9.2).
struct CreateGroupView: View {
    @ObservedObject var groups: GroupsModel
    let nav: GroupsNav
    @State private var name = ""
    @State private var displayName: String
    @State private var working = false
    @State private var error: String?
    /// The server needs terms newer than those accepted, and this app has them: S1 again, then one retry (§8.13).
    @State private var termsAgain = false
    @FocusState private var focused: Bool

    init(groups: GroupsModel, nav: GroupsNav) {
        self.groups = groups
        self.nav = nav
        _displayName = State(initialValue: groups.lastDisplayName)
    }

    private var valid: Bool {
        GroupText.clean(name, .groupName) != nil && GroupText.clean(displayName, .displayName) != nil
    }

    var body: some View {
        Form {
            Section {
                TextField("e.g. Kruger trip", text: $name)
                    .textInputAutocapitalization(.sentences)
                    .focused($focused)
                    .accessibilityIdentifier("groupName")
            } header: {
                Text("Group name")
            }
            Section {
                TextField("e.g. Sam", text: $displayName)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("displayName")
            } header: {
                Text("Your name in the group")
            } footer: {
                Text("Members see these names. Names are encrypted on your phone.")
            }
            Section {
                Button {
                    create(retried: false)
                } label: {
                    if working {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Creating…")
                        }
                    } else {
                        Text("Create group").font(.headline)
                    }
                }
                .disabled(!valid || working)
                .accessibilityIdentifier("createGroup")
                if let e = error {
                    GroupsNotice(text: e)
                }
            }
        }
        .navigationTitle("New group")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.cancel, action: nav.pop)
        .onAppear { if name.isEmpty { focused = true } }
        .sheet(isPresented: $termsAgain) {
            NavigationStack {
                TermsView(groups: groups) {
                    groups.acceptTerms()
                    termsAgain = false
                    create(retried: true)
                }
                .groupsLeading(.cancel) { termsAgain = false }
            }
        }
    }

    private func create(retried: Bool) {
        guard let proof = groups.pro.proof else {
            // Pro isn't known on this phone now (the cache said Pro): the paywall, with Restore purchases.
            nav.setPath(Array(nav.path().dropLast()) + [.paywall(.create)])
            return
        }
        working = true
        error = nil
        let groupName = name, myName = displayName
        Task { @MainActor in
            do {
                let gid = try await groups.hub.createGroup(name: groupName, displayName: myName, pro: proof)
                groups.rememberDisplayName(myName)
                working = false
                nav.setPath(GroupsFlow.afterCreate(gid))
            } catch let e as GroupActionError {
                working = false
                if e.kind == .termsOutdated, !retried, groups.canRetryAfterTermsOutdated() {
                    termsAgain = true
                } else {
                    error = e.message
                }
            } catch {
                working = false
                self.error = GroupActionError(.server).message
            }
        }
    }
}
