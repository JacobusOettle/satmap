import SwiftUI
import UIKit

/// Drone link's settings (from drone mode's Drone link button): the switch, the phone's address and the port, what to
/// put in QGroundControl, and what the link hears now.
struct DroneLinkSheet: View {
    @ObservedObject var link: DroneLinkModel
    @Environment(\.dismiss) private var dismiss
    @State private var portText = ""
    @State private var copied: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle(isOn: Binding(get: { link.isEnabled }, set: { link.setEnabled($0) })) {
                        Text("Drone link")
                    }
                    .accessibilityIdentifier("droneLinkSwitch")
                    HStack(spacing: 10) {
                        Circle()
                            .fill(statusColour)
                            .frame(width: 10, height: 10)
                        Text(link.status.text)
                            .font(.subheadline)
                            .foregroundColor(link.isEnabled ? .primary : .secondary)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("droneLinkStatus")
                } footer: {
                    Text("Shows your drone and the aircraft its ADS-B receiver hears, from the MAVLink your ground station "
                         + "forwards to this phone over Wi-Fi. For ArduPilot and PX4 drones; DJI drones don't use MAVLink.")
                }

                Section {
                    if link.addresses.isEmpty {
                        Text("Not on Wi-Fi or a hotspot. Join the ground station's Wi-Fi network, or turn on Personal Hotspot and join it from the ground station.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    ForEach(link.addresses, id: \.self) { a in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(a.label).font(.caption).foregroundColor(.secondary)
                                Text("\(a.address):\(String(link.port))")
                                    .font(.body.monospacedDigit())
                            }
                            Spacer()
                            copyButton("\(a.address):\(String(link.port))", label: "Copy", id: "droneLinkCopyAddress")
                        }
                    }
                    HStack {
                        Text("Listen port")
                        Spacer()
                        TextField("14445", text: $portText)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .font(.body.monospacedDigit())
                            .frame(maxWidth: 100)
                            .onChange(of: portText) { text in
                                if let p = DroneLink.port(text) { link.setPort(p) }
                            }
                            .accessibilityIdentifier("droneLinkPort")
                    }
                } header: {
                    Text("This phone")
                } footer: {
                    if DroneLink.port(portText) == nil {
                        Text("Use a port from 1024 to 65535.").foregroundColor(.red)
                    } else {
                        Text("QGroundControl forwards to port \(String(DroneLink.defaultPort)) unless you change it there.")
                    }
                }

                Section {
                    Text(link.qgcInstructions)
                        .font(.subheadline)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("droneLinkQGC")
                    copyButton(link.qgcInstructions, label: "Copy these steps", id: "droneLinkCopySteps")
                } header: {
                    Text("In QGroundControl")
                } footer: {
                    Text("QGroundControl forwards MAVLink 2 only. Mission Planner and MAVProxy can forward too: in MAVProxy, "
                         + "--out=udp:\(link.addresses.first?.address ?? "<phone IP>"):\(String(link.port)). Nothing is sent "
                         + "back: SatMap only listens, and only while it is open.")
                }

                Section {
                    Label {
                        Text(DroneLink.disclaimer).font(.footnote)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.yellow)
                    }
                    .accessibilityIdentifier("droneLinkDisclaimer")
                }
            }
            .navigationTitle("Drone link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear {
                portText = String(link.port)
                link.refreshAddresses()
            }
            .alert("Drone link", isPresented: $link.askingDisclaimer) {
                Button("Cancel", role: .cancel) {}
                Button("I understand") { link.acceptDisclaimer() }
            } message: {
                Text(DroneLink.disclaimer)
            }
        }
    }

    private var statusColour: Color {
        switch link.status {
        case .off: return .gray
        case .waiting: return .yellow
        case .receiving: return .green
        case .silent, .failed: return .red
        }
    }

    private func copyButton(_ text: String, label: String, id: String) -> some View {
        Button {
            UIPasteboard.general.string = text
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            copied = id
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { if copied == id { copied = nil } }
        } label: {
            Label(copied == id ? "Copied" : label, systemImage: copied == id ? "checkmark" : "doc.on.doc")
        }
        .buttonStyle(.borderless)
        .accessibilityIdentifier(id)
    }
}

/// Drone mode's Drone link button: what the link is doing, a tap opens its settings.
struct DroneLinkBar: View {
    @ObservedObject var link: DroneLinkModel
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.caption.weight(.bold))
                Text(link.isEnabled ? link.status.text : "Drone link")
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.bold))
                    .opacity(0.7)
            }
            .foregroundColor(colour)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.black.opacity(0.72))
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Color.white.opacity(0.18), lineWidth: 0.5))
        }
        .accessibilityLabel(Text("Drone link"))
        .accessibilityValue(Text(link.status.text))
        .accessibilityIdentifier("droneLinkButton")
    }

    private var colour: Color {
        switch link.status {
        case .off: return .white
        case .waiting: return .yellow
        case .receiving: return .green
        case .silent, .failed: return Color(red: 1, green: 0.45, blue: 0.4)
        }
    }
}

/// A tapped drone or aircraft (drone mode): what the link knows of it, the distances in nautical miles, and the
/// disclaimer, at the top of the map like the measuring panel.
struct DroneLinkPanel: View {
    @ObservedObject var link: DroneLinkModel
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            switch link.selection {
            case .drone(let id):
                if let d = link.traffic.drones[id] { drone(d) }
            case .aircraft(let icao):
                if let a = link.traffic.aircraft[icao] { aircraft(a) }
            case nil:
                EmptyView()
            }
            Text(DroneLink.disclaimer)
                .font(.caption2)
                .foregroundColor(.white.opacity(0.7))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("droneLinkPanelDisclaimer")
        }
        .foregroundColor(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.black.opacity(0.78))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Color.white.opacity(0.18), lineWidth: 0.5))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("droneLinkPanel")
    }

    private var me: GeoPoint? {
        model.recorder.location.map { GeoPoint(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) }
    }

    private func header(_ title: String, colour: Color, detail: String?) -> some View {
        HStack(spacing: 8) {
            Circle().fill(colour).frame(width: 10, height: 10)
            Text(title).font(.headline)
            if let d = detail {
                Text(d).font(.subheadline.weight(.semibold)).opacity(0.85)
            }
            Spacer()
            Button { link.select(nil) } label: {
                Image(systemName: "xmark").font(.caption.weight(.bold)).padding(6)
            }
            .accessibilityLabel(Text("Close"))
            .accessibilityIdentifier("droneLinkPanelClose")
        }
    }

    private func row(_ name: String, _ value: String, colour: Color = .white) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(name)
                .font(.caption.weight(.heavy))
                .opacity(0.7)
                .frame(width: 92, alignment: .leading)
            Text(value)
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundColor(colour)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: Drone

    @ViewBuilder private func drone(_ d: DroneLink.Drone) -> some View {
        let now = Date()
        let stale = d.isStale(now)
        let colour = stale ? Color(DroneLinkLayer.staleColour) : Color(DroneLinkLayer.colour(d.colourIndex))
        let armed = d.heartbeat.map { $0.armed ? "Armed" : "Disarmed" }
        header(d.label, colour: colour, detail: armed)
        if stale {
            let age = d.positionAt.map { "No position for \(Int(now.timeIntervalSince($0))) s" } ?? "No position yet"
            row("Position", age, colour: .orange)
        }
        let hu = model.heightUnit
        if let p = d.position {
            row("Height", "\(Format.height(p.relativeAltM, hu)) above take-off · \(Format.height(p.altMslM, hu)) above sea level")
            if let ground = link.selectedGroundM {
                row("≈ above ground", Format.height(p.altMslM - ground, hu))
            }
        }
        let motion = [d.groundSpeedMs.map { DroneLink.speed($0, model.distanceUnit) },
                      d.climbMs.map { "climb " + DroneLink.climb($0, hu) }].compactMap { $0 }
        if !motion.isEmpty { row("Speed", motion.joined(separator: " · ")) }
        let battery = [d.batteryRemainingPct.map { "\($0) %" }, d.batteryVoltageV.map { String(format: "%.1f V", $0) }]
            .compactMap { $0 }
        if !battery.isEmpty { row("Battery", battery.joined(separator: " · ")) }
        if let fix = d.fixText {
            row("GPS", fix + (d.gps?.satellitesVisible.map { " · \($0) satellites" } ?? ""))
        }
        if let p = d.point {
            if let me = me { row("From you", DroneLink.rangeAndBearing(from: me, to: p), colour: Color(AviationView.youColour)) }
            ForEach(Array(airfields(near: p).enumerated()), id: \.offset) { i, text in
                row(i == 0 ? "Airfields" : "", text)
            }
        }
    }

    /// "2.9 NM SW of FASH Stellenbosch" for the nearest, then "FACT Cape Town 17.8 NM" for the next two.
    private func airfields(near p: GeoPoint) -> [String] {
        guard let index = AviationStore.shared.index else { return [] }
        let near = DroneLink.nearestAerodromes(index, to: p)
        return near.enumerated().map { i, n in
            i == 0 ? Aviation.report(p, from: n.place) : "\(n.place.label) \(Aviation.nauticalMiles(n.meters))"
        }
    }

    // MARK: Aircraft

    @ViewBuilder private func aircraft(_ a: DroneLink.Aircraft) -> some View {
        let now = Date()
        let refs = references()
        let low = DroneLink.isLowAndClose(a, references: refs)
        header(a.label, colour: low ? Color(DroneLinkLayer.highlightColour) : .white,
               detail: a.kind.title + (a.callsign != nil ? " · \(a.icaoHex)" : ""))
        if low {
            row("Warning", "Below \(DroneLink.grouped(Int(DroneLink.lowAltitudeFeet))) ft within \(Int(DroneLink.closeNauticalMiles)) NM",
                colour: Color(DroneLinkLayer.highlightColour))
        }
        if let alt = a.altitudeM {
            var text = DroneLink.feet(alt)
            if model.heightUnit == .metres { text += " (\(Format.height(alt, .metres)))" }
            row("Altitude", text + (a.altitudeType == 1 ? " GPS" : " QNH"))
        }
        let motion = [a.speedText, a.climbMs.map { DroneLink.climb($0, .feet) }].compactMap { $0 }
        if !motion.isEmpty { row("Speed", motion.joined(separator: " · ")) }
        if let h = a.headingDeg { row("Heading", String(format: "%03d°", Int(h.rounded()) % 360)) }
        if let sq = a.squawk { row("Squawk", String(format: "%04d", sq)) }
        if let me = me { row("From you", DroneLink.rangeAndBearing(from: me, to: a.position), colour: Color(AviationView.youColour)) }
        ForEach(link.traffic.sortedDrones.filter { $0.point != nil }, id: \.sysid) { d in
            row("From \(d.label)", DroneLink.rangeAndBearing(from: d.point!, to: a.position),
                colour: Color(DroneLinkLayer.colour(d.colourIndex)))
        }
        row("Last heard", "\(max(0, Int(now.timeIntervalSince(a.lastSeen)))) s ago", colour: a.isStale(now) ? .orange : .white)
    }

    private func references() -> [(point: GeoPoint, groundM: Double?)] {
        var refs: [(point: GeoPoint, groundM: Double?)] = []
        if let me = me { refs.append((me, model.userElevation)) }
        for d in link.traffic.sortedDrones { if let p = d.point { refs.append((p, d.takeOffGroundM)) } }
        return refs
    }
}
