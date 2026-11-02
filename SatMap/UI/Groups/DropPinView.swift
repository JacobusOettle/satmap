import SwiftUI

/// S10 Drop pin (docs/groups/SPEC.md §9.3, §9.4): the type (the animal for a sighting), an optional note, at a spot
/// tapped on the map or at your position. The pin shows on the map at once and is sent when online.
struct DropPinView: View {
    @ObservedObject var groups: GroupsModel
    let groupId: String
    let point: GeoPoint
    let nav: GroupsNav
    let leading: GroupsLeading
    @State private var type: PinType?
    @State private var animal = ""
    @State private var note = ""
    @State private var error: String?
    @FocusState private var animalFocused: Bool

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    var body: some View {
        let name = groups.hub.detail(groupId)?.name ?? GroupHub.unnamedGroup
        Form {
            Section {
                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(PinType.allCases, id: \.self) { t in
                        typeButton(t)
                    }
                }
                .padding(.vertical, 6)
            }
            if type == .animal {
                Section {
                    TextField("e.g. Rhino", text: $animal)
                        .textInputAutocapitalization(.words)
                        .focused($animalFocused)
                        .accessibilityIdentifier("pinAnimal")
                } header: {
                    Text("Which animal?")
                }
            }
            Section {
                TextField("e.g. Two adults near the water hole", text: $note, axis: .vertical)
                    .lineLimit(1...4)
                    .accessibilityIdentifier("pinNote")
            } header: {
                Text("Note (optional)")
            }
            Section {
                HStack(spacing: 8) {
                    Image(systemName: "mappin").foregroundColor(.secondary)
                    Text(Format.coordinate(point)).monospacedDigit()
                }
                .accessibilityElement(children: .combine)
            } footer: {
                Text(GroupTexts.dropPinFooter(type: type, group: name))
            }
            Section {
                Button {
                    drop()
                } label: {
                    Text("Drop pin").font(.headline)
                }
                .disabled(!ready)
                .accessibilityIdentifier("dropPinConfirm")
                if let e = error {
                    GroupsNotice(text: e)
                }
            }
        }
        .navigationTitle("Drop pin")
        .navigationBarTitleDisplayMode(.inline)
        .groupsLeading(leading, action: nav.pop)
    }

    /// A type is chosen, and for Animal an animal typed.
    private var ready: Bool {
        guard let t = type else { return false }
        return t != .animal || !animal.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func typeButton(_ t: PinType) -> some View {
        let selected = type == t
        return Button {
            type = t
            error = nil
            if t == .animal { animalFocused = true }
        } label: {
            VStack(spacing: 6) {
                ZStack {
                    Circle().fill(Color(groupHex: t.colourHex))
                    Circle().stroke(Color.white, lineWidth: 2)
                    Image(systemName: GroupTexts.pinSymbol(t))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                }
                .frame(width: 36, height: 36)
                Text(GroupTexts.pinShortLabel(t))
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 10).fill(selected ? Color.yellow.opacity(0.22) : Color.white.opacity(0.06)))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? Color.yellow : Color.clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(t.label))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("pinType-\(t.rawValue)")
    }

    private func drop() {
        guard let t = type else { return }
        do {
            try groups.hub.dropPin(groupId: groupId, type: t, animal: t == .animal ? animal : nil, note: note,
                                   lat: point.lat, lon: point.lon)
            // From the map: the tapped spot's marker goes, so the new pin shows there.
            if nav.path().isEmpty { groups.app?.selectedPoint = nil }
            nav.closeSheetOrPop()
        } catch let e as GroupActionError {
            error = e.message
        } catch {
            self.error = GroupActionError(.server).message
        }
    }
}

extension GroupsNav {
    /// Back to the screen before (S5), or the sheet closes when this is its first screen (from the map).
    func closeSheetOrPop() {
        if path().isEmpty { closeSheet() } else { pop() }
    }
}
