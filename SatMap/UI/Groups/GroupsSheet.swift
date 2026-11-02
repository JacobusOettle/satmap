import SwiftUI

/// How a groups screen moves around its sheet: push and pop screens, replace the stack, close the sheet.
struct GroupsNav {
    let push: (GroupsRoute) -> Void
    let pop: () -> Void
    let setPath: ([GroupsRoute]) -> Void
    let path: () -> [GroupsRoute]
    let closeSheet: () -> Void
}

/// The leading button of a groups screen (§9.3 gives each screen's).
enum GroupsLeading {
    case back, done, cancel
}

extension View {
    /// The screen's leading button in place of the system back button: "Back" (with a chevron), "Done" or "Cancel".
    func groupsLeading(_ kind: GroupsLeading?, action: @escaping () -> Void) -> some View {
        navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    if kind == .back {
                        Button(action: action) {
                            HStack(spacing: 4) {
                                Image(systemName: "chevron.left").font(.body.weight(.semibold))
                                Text("Back")
                            }
                        }
                        .accessibilityLabel(Text("Back"))
                    } else if kind == .done {
                        Button("Done", action: action)
                    } else if kind == .cancel {
                        Button("Cancel", action: action)
                    }
                }
            }
    }
}

/// The Groups sheet (`ActiveSheet.groups`, §9.2): S2 at the root (S1 until the terms are accepted, or S5 opened
/// alone), with the screens pushed on it.
struct GroupsSheet: View {
    @ObservedObject var groups: GroupsModel
    @ObservedObject var model: AppModel

    private var nav: GroupsNav {
        GroupsNav(push: { groups.path.append($0) },
                  pop: { if !groups.path.isEmpty { groups.path.removeLast() } },
                  setPath: { groups.path = $0 },
                  path: { groups.path },
                  closeSheet: { model.sheet = nil })
    }

    var body: some View {
        NavigationStack(path: $groups.path) {
            rootView
                .navigationDestination(for: GroupsRoute.self) { route in
                    GroupsDestination(route: route, groups: groups, model: model, nav: nav)
                }
        }
    }

    @ViewBuilder private var rootView: some View {
        switch groups.root {
        case .detail(let gid) where !groups.needsTerms:
            GroupDetailView(groups: groups, model: model, groupId: gid, nav: nav, leading: .done)
        default:
            if groups.needsTerms {
                TermsView(groups: groups, onAgree: { groups.acceptTerms() })
                    .groupsLeading(.done) { model.sheet = nil }
            } else {
                GroupListView(groups: groups, nav: nav)
            }
        }
    }
}

/// A screen of the Groups sheet's stack (or of a map item's sheet), with its leading button.
struct GroupsDestination: View {
    let route: GroupsRoute
    @ObservedObject var groups: GroupsModel
    @ObservedObject var model: AppModel
    let nav: GroupsNav

    var body: some View {
        switch route {
        case .paywall(let next):
            PaywallView(groups: groups, pro: groups.pro, next: next, nav: nav)
        case .create:
            CreateGroupView(groups: groups, nav: nav)
        case .detail(let gid):
            GroupDetailView(groups: groups, model: model, groupId: gid, nav: nav, leading: .back)
        case .consent(let gid, let next):
            ConsentView(groups: groups, recorder: model.recorder, groupId: gid, next: next, nav: nav)
        case .invite(let gid, let inviteId):
            InviteView(groups: groups, groupId: gid, inviteId: inviteId, nav: nav)
        case .joinLink:
            JoinLinkView(groups: groups, nav: nav)
        case .scan:
            ScanView(groups: groups, nav: nav)
        case .member(let gid, let mid):
            MemberDetailView(groups: groups, model: model, groupId: gid, memberId: mid, nav: nav, leading: .back)
        case .pin(let gid, let pid):
            PinDetailView(groups: groups, model: model, groupId: gid, pinId: pid, nav: nav, leading: .back)
        case .report(let gid, let subject):
            ReportView(groups: groups, groupId: gid, subject: subject, nav: nav)
        case .editName(let gid):
            NameEditView(groups: groups, groupId: gid, kind: .yourName, nav: nav)
        case .rename(let gid):
            NameEditView(groups: groups, groupId: gid, kind: .groupName, nav: nav)
        case .blocked(let gid):
            BlockedView(groups: groups, groupId: gid, nav: nav)
        case .handover(let gid):
            HandoverPickerView(groups: groups, groupId: gid, nav: nav)
        case .dropPin(let gid, let lat, let lon):
            DropPinView(groups: groups, groupId: gid, point: GeoPoint(lat: lat, lon: lon), nav: nav, leading: .cancel)
        }
    }
}

/// S11, S12 or S10 opened from the map (`GroupsModel.groupItem`), with S13 pushed on it.
struct GroupItemSheet: View {
    @ObservedObject var groups: GroupsModel
    @ObservedObject var model: AppModel
    let item: GroupItem
    @State private var path: [GroupsRoute] = []

    private var nav: GroupsNav {
        GroupsNav(push: { path.append($0) },
                  pop: { if path.isEmpty { groups.groupItem = nil } else { path.removeLast() } },
                  setPath: { path = $0 },
                  path: { path },
                  closeSheet: { groups.groupItem = nil })
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                switch item {
                case .pin(let gid, let pid):
                    PinDetailView(groups: groups, model: model, groupId: gid, pinId: pid, nav: nav, leading: .done)
                case .member(let gid, let mid):
                    MemberDetailView(groups: groups, model: model, groupId: gid, memberId: mid, nav: nav, leading: .done)
                case .dropPin(let gid, let lat, let lon):
                    DropPinView(groups: groups, groupId: gid, point: GeoPoint(lat: lat, lon: lon), nav: nav, leading: .cancel)
                }
            }
            .navigationDestination(for: GroupsRoute.self) { route in
                GroupsDestination(route: route, groups: groups, model: model, nav: nav)
            }
        }
    }
}

// MARK: - Shared pieces

/// A member's colour dot with initials (S5 rows, S12's header, S15's colours).
struct MemberDot: View {
    let colourHex: String
    let initials: String
    var size: CGFloat = 30

    var body: some View {
        ZStack {
            Circle().fill(Color(groupHex: colourHex))
            Circle().stroke(Color.white, lineWidth: 2)
            Text(initials)
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundColor(.white)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
                .padding(3)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A row with an icon on the left (S1, S3, S9).
struct IconRow: View {
    let systemImage: String?
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            if let s = systemImage {
                Image(systemName: s)
                    .font(.title3)
                    .foregroundColor(.yellow)
                    .frame(width: 28)
                    .accessibilityHidden(true)
            } else {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundColor(.yellow)
                    .frame(width: 28)
                    .accessibilityHidden(true)
            }
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }
}

/// An error or notice under a form's button, in orange (as "Not a position yet.").
struct GroupsNotice: View {
    let text: String

    var body: some View {
        Text(text)
            .foregroundColor(.orange)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("groupsError")
    }
}

/// A title and a value on the right, as the other screens' rows.
struct ValueRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundColor(.secondary).multilineTextAlignment(.trailing)
        }
    }
}

/// Opens a URL (terms, privacy notice, mail) in the system.
struct LinkRow: View {
    let title: String
    let url: URL
    var systemImage: String?

    var body: some View {
        Link(destination: url) {
            HStack {
                if let s = systemImage {
                    Label(title, systemImage: s)
                } else {
                    Text(title)
                }
                Spacer()
                Image(systemName: "arrow.up.right").font(.caption).foregroundColor(.secondary)
            }
        }
    }
}

extension Color {
    /// A "#RRGGBB" colour from core.
    init(groupHex: String) {
        let c = GroupColour.rgb(groupHex) ?? GroupColour.rgb(GroupPolicy.grey)!
        self.init(red: c.red, green: c.green, blue: c.blue)
    }
}

/// Dates and times in the phone's locale.
let groupDates = GroupDates()

/// Now, in whole seconds.
var groupNow: Int64 { Int64(Date().timeIntervalSince1970) }
