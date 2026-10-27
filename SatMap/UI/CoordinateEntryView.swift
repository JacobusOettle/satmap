import CoreLocation
import SwiftUI

/// Type or paste a position in any common format: it is shown on the map as measuring point A, with the distance
/// from you, as if the spot had been tapped while measuring.
struct CoordinateEntryView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    private var me: GeoPoint? {
        model.recorder.location.map { GeoPoint(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) }
    }

    private var result: CoordinateParser.Result? {
        CoordinateParser.parse(text, near: me)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Paste or type coordinates", text: $text, axis: .vertical)
                        .lineLimit(1...4)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .focused($focused)
                        .accessibilityIdentifier("coordinateText")
                    PasteButton(payloadType: String.self) { strings in
                        guard let s = strings.first else { return }
                        DispatchQueue.main.async { text = s }
                    }
                } footer: {
                    Text("Any format: -25.0969, 31.4371 · 25.0969 S 31.4371 E · 25° 05.814' S 31° 26.227' E · 25°05'48.8\"S 31°26'13.6\"E · 2505.814,S,03126.227,E (GPS) · map and geo: links · UTM 36J 342401 7223411 · MGRS 36J UT 42401 23411 · plus code 5G6HWC3P+6R")
                }

                Section {
                    if let r = result {
                        row("Position", Format.coordinate(r.point))
                        row("Read as", r.format)
                        if let me = me {
                            row("From you", Format.distance(Geo.distance(me, r.point)))
                        }
                    } else if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text("The position shows here as you type or paste.").foregroundColor(.secondary)
                    } else {
                        Text("Not a position yet.").foregroundColor(.orange)
                    }
                }

                Section {
                    Button {
                        if let r = result { model.measure(from: r.point) }
                        dismiss()
                    } label: {
                        Label("Show and measure from here", systemImage: "ruler")
                            .font(.headline)
                    }
                    .disabled(result == nil)
                }
            }
            .navigationTitle("Go to coordinates")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear { focused = true }
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundColor(.secondary).multilineTextAlignment(.trailing)
        }
    }
}
