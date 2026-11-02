import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// S6 Invite (docs/groups/SPEC.md §9.3, §5.3): the invite's QR code and its link to share or copy. The link holds the
/// group key, so the copy stays on this phone (no Universal Clipboard) and goes after 10 minutes.
struct InviteView: View {
    @ObservedObject var groups: GroupsModel
    let groupId: String
    let inviteId: String
    let nav: GroupsNav
    @State private var qr: UIImage?

    var body: some View {
        let detail = groups.hub.detail(groupId)
        let invite = detail?.invites?.first { $0.inviteId == inviteId }
        Form {
            if let d = detail, let i = invite, let link = i.link {
                Section {
                    VStack(spacing: 12) {
                        Group {
                            if let qr = qr {
                                Image(uiImage: qr)
                                    .interpolation(.none)
                                    .resizable()
                            } else {
                                Color.white
                            }
                        }
                        .frame(width: 240, height: 240)
                        .accessibilityElement()
                        .accessibilityLabel(Text("Invite QR code"))
                        .accessibilityIdentifier("inviteQR")
                        Text(d.name).font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    Text("Ask them to scan this code with their phone's camera, or send them the link.")
                        .fixedSize(horizontal: false, vertical: true)
                }
                Section {
                    Button {
                        // §9.9: the system share sheet (Android adds a text share to its ShareHost).
                        SharePresenter.present([GroupTexts.inviteShareText(group: d.name, link: link)]) {}
                    } label: {
                        Label("Share link", systemImage: "square.and.arrow.up")
                    }
                    .accessibilityIdentifier("shareInvite")
                    Button {
                        copy(link)
                    } label: {
                        Label("Copy link", systemImage: "doc.on.doc")
                    }
                    .accessibilityIdentifier("copyInvite")
                } footer: {
                    Text(GroupTexts.inviteFooter(date: groupDates.day(i.expiresAt, now: groupNow), time: groupDates.time(i.expiresAt)))
                }
                .task(id: link) { qr = QRCode.image(for: link) }
            } else {
                Section {
                    GroupsNotice(text: "Couldn't make an invite. Check your connection and try again.")
                }
            }
        }
        .navigationTitle("Invite")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.done, action: nav.pop)
    }

    /// Local only and gone after 10 minutes (§9.9: Android marks the clip sensitive instead).
    private func copy(_ link: String) {
        UIPasteboard.general.setItems([[UTType.plainText.identifier: link]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(600)])
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        groups.toast("Link copied")
    }
}
