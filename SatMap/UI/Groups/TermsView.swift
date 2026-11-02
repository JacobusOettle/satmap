import SwiftUI

/// S1 Terms (docs/groups/SPEC.md §9.3, §8.13): what groups are, the rules, and "Agree and continue", which also
/// confirms the person is 18 or older or has a parent's or guardian's permission. Shown before the first use of
/// Groups, and before a join if the terms weren't accepted yet. The caller gives the leading button.
struct TermsView: View {
    @ObservedObject var groups: GroupsModel
    let onAgree: () -> Void

    var body: some View {
        Form {
            Section {
                Text("See your group on the map")
                    .font(.title2.weight(.bold))
                    .padding(.vertical, 4)
                IconRow(systemImage: "person.3.fill", text: "Share your position, trail and pins with friends, family or your drone team.")
                IconRow(systemImage: "link", text: "Only people you invite can join. Each invite works once, for 7 days.")
                IconRow(systemImage: "lock.fill", text: "Everything your group shares is encrypted on your phone. Our server can't read names, places or notes.")
                IconRow(systemImage: "flag.fill", text: "Don't post anything illegal, hateful or dangerous. You can report and block anyone.")
                IconRow(systemImage: "star.fill", text: "Creating a group needs SatMap Pro. Joining is free.")
            }

            Section {
                Text("By tapping Agree and continue you accept the terms of use and confirm that you're 18 or older, or have a parent's or guardian's permission.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Button(action: onAgree) {
                    Text("Agree and continue")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .accessibilityIdentifier("agreeTerms")
            }

            Section {
                LinkRow(title: "Terms of use", url: GroupConfig.termsURL)
                LinkRow(title: "Privacy notice", url: GroupConfig.privacyURL)
            } footer: {
                Text("Questions or a problem? \(GroupConfig.contactEmail)")
            }
        }
        .navigationTitle("Groups")
        .navigationBarTitleDisplayMode(.inline)
    }
}
