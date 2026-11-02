import SwiftUI

/// S2 Groups list (docs/groups/SPEC.md §9.3): your groups, oldest membership first; create a group or join one with
/// an invite link; the terms, the privacy notice and the contact address.
struct GroupListView: View {
    @ObservedObject var groups: GroupsModel
    let nav: GroupsNav

    var body: some View {
        let list = groups.hub.groups
        List {
            Section {
                ForEach(list, id: \.groupId) { g in
                    NavigationLink(value: GroupsRoute.detail(g.groupId)) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(g.name).foregroundColor(.primary)
                                Text(GroupTexts.listSubtitle(g))
                                    .font(.caption)
                                    .foregroundColor(g.hasOfferToYou ? .yellow : .secondary)
                            }
                            Spacer()
                            if g.isShown {
                                Image(systemName: "map.fill")
                                    .foregroundColor(.yellow)
                                    .accessibilityLabel(Text("On the map"))
                            }
                        }
                    }
                    .accessibilityIdentifier("groupRow")
                }
            } header: {
                if !list.isEmpty { Text("Your groups") }
            } footer: {
                if list.isEmpty { Text("You're not in a group yet.") }
            }

            Section {
                Button {
                    nav.push(GroupsFlow.createEntry(isPro: groups.pro.isPro))
                } label: {
                    Label("Create group", systemImage: "plus.circle")
                }
                .accessibilityIdentifier("createGroupRow")
                Button {
                    nav.push(.joinLink)
                } label: {
                    Label("Join with an invite link", systemImage: "link")
                }
                .accessibilityIdentifier("joinLinkRow")
            } footer: {
                Text("To join a group, open the invite link someone in the group sent you, or scan their QR code with your camera.")
            }

            Section {
                LinkRow(title: "Terms of use", url: GroupConfig.termsURL)
                LinkRow(title: "Privacy notice", url: GroupConfig.privacyURL)
                if let mail = URL(string: "mailto:\(GroupConfig.contactEmail)") {
                    LinkRow(title: "Contact us", url: mail)
                }
            }
        }
        .navigationTitle("Groups")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.done, action: nav.closeSheet)
    }
}
