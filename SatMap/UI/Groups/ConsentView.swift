import CoreLocation
import SwiftUI

/// S9 Share your location? (docs/groups/SPEC.md §9.3, §8.13): the consent before sharing with a group for the first
/// time, with Google's prominent-disclosure sentence (used on iPhone too). "Share my location" asks for location
/// permission if it isn't decided yet; refused, sharing stays off. "Not now" leaves sharing off.
struct ConsentView: View {
    @ObservedObject var groups: GroupsModel
    @ObservedObject var recorder: LocationRecorder
    let groupId: String
    let next: ConsentNext
    let nav: GroupsNav
    @State private var refused = false
    @State private var working = false

    var body: some View {
        let name = groups.hub.detail(groupId)?.name ?? GroupHub.unnamedGroup
        Form {
            Section {
                Text("Spoor collects location data to share your position and trail with \(name), even when the app is in the background.")
                    .font(.body.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                IconRow(systemImage: "location.fill", text: "Members see where you are while sharing is on.")
                IconRow(systemImage: "point.topleft.down.curvedto.point.bottomright.up", text: "They see the trail you leave from now on, until you clear it.")
                IconRow(systemImage: "lock.fill", text: "Your location is encrypted on your phone. We can't read it.")
                IconRow(systemImage: "switch.2", text: "Turn sharing off any time in the group.")
            }
            Section {
                Button {
                    share()
                } label: {
                    HStack {
                        Text("Share my location").font(.headline)
                        Spacer()
                        if working { ProgressView() }
                    }
                }
                .disabled(working)
                .accessibilityIdentifier("consentShare")
                Button("Not now") { finish() }
                    .disabled(working)
                    .accessibilityIdentifier("consentNotNow")
            } footer: {
                Text("Without sharing you can still see the group and drop pins.")
            }
        }
        .navigationTitle("Share your location?")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(nil) {}
        .interactiveDismissDisabled()
        .alert("Location is off for Spoor. Turn it on in Settings › Privacy › Location Services to share.", isPresented: $refused) {
            Button("OK", role: .cancel) { finish() }
        }
    }

    /// Consent recorded; sharing on when location is allowed (§8.13).
    private func share() {
        working = true
        Task { @MainActor in
            groups.hub.recordConsent(groupId)
            let allowed = await groups.setSharing(true, groupId: groupId)
            working = false
            if allowed { finish() } else { refused = true }
        }
    }

    private func finish() {
        nav.setPath(GroupsFlow.afterConsent(nav.path(), groupId: groupId, next: next))
    }
}
