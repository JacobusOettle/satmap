import SwiftUI
import UIKit

/// S7 Join with an invite link (docs/groups/SPEC.md §9.3): paste the link (or a whole message holding it), or scan
/// the QR code. "Continue" opens S8.
struct JoinLinkView: View {
    @ObservedObject var groups: GroupsModel
    let nav: GroupsNav
    @State private var text = ""
    @State private var askingCamera = false
    @FocusState private var focused: Bool

    private var parsed: InviteLink.Parsed { InviteLink.parse(text, domain: GroupConfig.domain) }

    var body: some View {
        Form {
            Section {
                TextField("Paste the invite link", text: $text, axis: .vertical)
                    .lineLimit(1...5)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .focused($focused)
                    .accessibilityIdentifier("inviteText")
                PasteButton(payloadType: String.self) { strings in
                    guard let s = strings.first else { return }
                    DispatchQueue.main.async { text = s }
                }
                switch parsed {
                case .missingKey:
                    Text("This invite link is incomplete. Copy the whole link from the message, or scan the QR code.")
                        .foregroundColor(.orange)
                case .notInvite where !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty:
                    Text("That isn't a SatMap invite link.").foregroundColor(.orange)
                default:
                    EmptyView()
                }
            } footer: {
                Text("Invite links start with https://\(GroupConfig.domain)/j/")
            }
            Section {
                Button {
                    focused = false
                    groups.openInvite(parsed)
                } label: {
                    Text("Continue").font(.headline)
                }
                .disabled(!isInvite)
                .accessibilityIdentifier("continueJoin")
                Button {
                    scan()
                } label: {
                    Label("Scan QR code", systemImage: "qrcode.viewfinder")
                }
                .disabled(askingCamera)
                .accessibilityIdentifier("scanQR")
            }
        }
        .navigationTitle("Join a group")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.back, action: nav.pop)
    }

    private var isInvite: Bool {
        if case .invite = parsed { return true }
        return false
    }

    /// Camera permission is asked on the first tap (§5.3); the scanner says so when it is off.
    private func scan() {
        askingCamera = true
        Task { @MainActor in
            _ = await QRScannerView.requestAccess()
            askingCamera = false
            nav.push(.scan)
        }
    }
}

/// S17 Scan QR code (docs/groups/SPEC.md §9.3): the camera with a square guide; a found invite closes it and opens S8.
struct ScanView: View {
    @ObservedObject var groups: GroupsModel
    let nav: GroupsNav
    @State private var allowed: Bool?
    @State private var hint: String?
    @State private var found = false

    var body: some View {
        Group {
            switch allowed {
            case true?:
                ZStack {
                    QRScannerView(onCode: read)
                        .ignoresSafeArea(edges: .bottom)
                    RoundedRectangle(cornerRadius: 18)
                        .stroke(Color.white, lineWidth: 3)
                        .frame(width: 240, height: 240)
                        .shadow(color: .black.opacity(0.6), radius: 3)
                        .allowsHitTesting(false)
                    VStack(spacing: 8) {
                        Spacer()
                        if let h = hint {
                            Text(h)
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(.orange)
                                .multilineTextAlignment(.center)
                        }
                        Text("Point the camera at the invite QR code.")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.white)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity)
                    .background(Color.black.opacity(0.0001))
                    .padding(.bottom, 24)
                    .allowsHitTesting(false)
                }
            case false?:
                VStack(spacing: 16) {
                    Text("Camera access is off for SatMap. Turn it on in Settings to scan a code.")
                        .multilineTextAlignment(.center)
                        .foregroundColor(.secondary)
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            case nil:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Scan QR code")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(.cancel, action: nav.pop)
        .task { allowed = await QRScannerView.requestAccess() }
    }

    private func read(_ text: String) {
        guard !found else { return }
        let parsed = InviteLink.parse(text, domain: GroupConfig.domain)
        switch parsed {
        case .invite:
            found = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            nav.setPath(GroupsFlow.afterScan(nav.path()))
            groups.openInvite(parsed)
        case .missingKey:
            hint = "This invite link is incomplete. Copy the whole link from the message, or scan the QR code."
        case .notInvite:
            hint = "That isn't a SatMap invite link."
        }
    }
}

/// S8 Join confirmation (docs/groups/SPEC.md §9.3): in its own sheet over anything (`GroupsModel.openInvite`). S1
/// first if the terms aren't accepted; then the invite's preview (E9) and your name, and "Join group" (E10). Opening a
/// link never joins by itself (§5.6).
struct JoinSheet: View {
    @ObservedObject var groups: GroupsModel
    let parsed: InviteLink.Parsed
    @State private var phase = Phase.checking
    @State private var displayName: String
    @State private var joining = false
    @State private var joinError: GroupActionError?
    /// The server needs newer terms that this app has: S1 again, then one retry (§8.13).
    @State private var termsAgain = false

    enum Phase {
        case checking
        case ready(InvitePreview)
        case failed(GroupActionError)
    }

    init(groups: GroupsModel, parsed: InviteLink.Parsed) {
        self.groups = groups
        self.parsed = parsed
        _displayName = State(initialValue: groups.lastDisplayName)
    }

    private var invite: InviteLink? {
        if case .invite(let link) = parsed { return link }
        return nil
    }

    var body: some View {
        NavigationStack {
            Group {
                if invite == nil {
                    Form {
                        Section {
                            Text("This invite link is incomplete. Copy the whole link from the message, or scan the QR code.")
                                .foregroundColor(.orange)
                        }
                    }
                    .navigationTitle("Join group")
                } else if groups.needsTerms || termsAgain {
                    TermsView(groups: groups) {
                        groups.acceptTerms()
                        if termsAgain {
                            termsAgain = false
                            join(retried: true)
                        }
                    }
                } else {
                    joinForm
                        .navigationTitle("Join group")
                        .task { if case .checking = phase { await load() } }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { groups.closeInvite() }
                }
            }
        }
    }

    private var joinForm: some View {
        Form {
            switch phase {
            case .checking:
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Checking the invite…").foregroundColor(.secondary)
                    }
                }
            case .failed(let e):
                Section {
                    GroupsNotice(text: e.message)
                    errorActions(e)
                }
            case .ready(let p):
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(p.name)
                            .font(.title2.weight(.bold))
                        if let inviter = p.inviterName {
                            Text("Invited by \(inviter)")
                        }
                        Text(GroupTexts.members(p.memberCount))
                            .foregroundColor(.secondary)
                        if p.isPaused {
                            Text("This group is paused at the moment.")
                                .foregroundColor(.orange)
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    TextField("e.g. Sam", text: $displayName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("displayName")
                } header: {
                    Text("Your name in the group")
                }
                Section {
                    Text("Members will see your name. If you share your location, they also see where you are and the trail you leave.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                    Button {
                        join(retried: false)
                    } label: {
                        HStack {
                            Text("Join group").font(.headline)
                            Spacer()
                            if joining { ProgressView() }
                        }
                    }
                    .disabled(joining || GroupText.clean(displayName, .displayName) == nil)
                    .accessibilityIdentifier("joinGroup")
                    if let e = joinError {
                        GroupsNotice(text: e.message)
                        errorActions(e)
                    }
                }
            }
        }
    }

    @ViewBuilder private func errorActions(_ e: GroupActionError) -> some View {
        switch e.kind {
        case .alreadyMember(let gid):
            Button("Open group") { groups.openGroupFromInvite(gid) }
        case .offline, .server, .rateLimited:
            if case .failed = phase {
                Button("Try again") { Task { @MainActor in await load() } }
            }
        default:
            EmptyView()
        }
    }

    @MainActor
    private func load() async {
        guard let link = invite else { return }
        phase = .checking
        do {
            phase = .ready(try await groups.hub.preview(link))
        } catch let e as GroupActionError {
            phase = .failed(e)
        } catch {
            phase = .failed(GroupActionError(.server))
        }
    }

    private func join(retried: Bool) {
        guard let link = invite else { return }
        joining = true
        joinError = nil
        let name = displayName
        Task { @MainActor in
            do {
                let gid = try await groups.hub.join(link, displayName: name)
                groups.rememberDisplayName(name)
                joining = false
                groups.joined(gid)
            } catch let e as GroupActionError {
                joining = false
                if e.kind == .termsOutdated, !retried, groups.canRetryAfterTermsOutdated() {
                    termsAgain = true
                } else {
                    joinError = e
                }
            } catch {
                joining = false
                joinError = GroupActionError(.server)
            }
        }
    }
}
