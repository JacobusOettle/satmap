import MapKit
import UIKit

/// The blocks of the imported KML files on a map (the phone's, and the car's with CarPlay): those of the files shown,
/// in the files' own colours or, with those switched off, all in the app's white.
final class BlockLayers {
    private unowned let mapView: MKMapView
    /// Blocks go under this overlay (the track), above the imagery.
    private let below: MKOverlay
    private var overlays: [UUID: [MKOverlay]] = [:]
    private var labels: [UUID: [MKAnnotation]] = [:]
    private var fileColours = true

    init(mapView: MKMapView, below: MKOverlay) {
        self.mapView = mapView
        self.below = below
    }

    /// Shows the blocks of the files that are switched on and takes away the others.
    func update(_ files: [KMLLibrary.File], useFileColours: Bool) {
        if useFileColours != fileColours {
            // Every block drawn again, in the other colours.
            for id in Array(overlays.keys) { remove(id) }
            fileColours = useFileColours
        }
        let shown = Set(files.filter(\.isShown).map(\.id))
        for id in Array(overlays.keys) where !shown.contains(id) { remove(id) }
        for f in files where f.isShown && overlays[f.id] == nil {
            let (o, l) = Self.shapes(f, useFileColours: useFileColours)
            for overlay in o { mapView.insertOverlay(overlay, below: below) }
            mapView.addAnnotations(l)
            overlays[f.id] = o
            labels[f.id] = l
        }
    }

    private func remove(_ id: UUID) {
        if let o = overlays.removeValue(forKey: id) { mapView.removeOverlays(o) }
        if let l = labels.removeValue(forKey: id) { mapView.removeAnnotations(l) }
    }

    /// A KML file's shapes for a map: one overlay for each style's polygons and one for its lines (files often give
    /// every block a style of its own, with the same colours), and the labels of named blocks and points. Without the
    /// file's colours, every shape has the app's own look.
    static func shapes(_ f: KMLLibrary.File, useFileColours: Bool) -> (overlays: [MKOverlay], labels: [MKAnnotation]) {
        var polygons: [KMLStyle?: [MKPolygon]] = [:], polygonOrder: [KMLStyle?] = []
        var lines: [KMLStyle?: [MKPolyline]] = [:], lineOrder: [KMLStyle?] = []
        var labels: [MKAnnotation] = []
        for feature in f.document.features {
            let style = useFileColours ? feature.style : nil
            switch feature.geometry {
            case .polygon(let outer, let holes):
                let interiors = holes.map { h -> MKPolygon in
                    let c = h.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                    return MKPolygon(coordinates: c, count: c.count)
                }
                let c = outer.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                if polygons[style] == nil { polygonOrder.append(style) }
                polygons[style, default: []].append(MKPolygon(coordinates: c, count: c.count, interiorPolygons: interiors))
                if let name = feature.name, let p = PolygonLabel.interiorPoint(outer: outer, holes: holes) {
                    labels.append(BlockLabelAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon),
                                                       title: name, isPoint: false))
                }
            case .line(let pts):
                let c = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                if lines[style] == nil { lineOrder.append(style) }
                lines[style, default: []].append(MKPolyline(coordinates: c, count: c.count))
            case .point(let p):
                labels.append(BlockLabelAnnotation(coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon),
                                                   title: feature.name ?? "", isPoint: true,
                                                   color: style?.iconColor.map(UIColor.init(kml:))))
            }
        }
        var overlays: [MKOverlay] = []
        for style in polygonOrder {
            let o = BlockPolygons(polygons[style] ?? [])
            o.style = style
            overlays.append(o)
        }
        for style in lineOrder {
            let o = BlockLines(lines[style] ?? [])
            o.style = style
            overlays.append(o)
        }
        return (overlays, labels)
    }

    /// Blocks in their file's colours and line widths (KML styles). What a file leaves unset is white (yellow/orange is
    /// used by contours, cyan by the track and pink by the measuring line), with a faint fill.
    static func renderer(_ o: BlockPolygons) -> MKOverlayRenderer {
        let r = MKMultiPolygonRenderer(multiPolygon: o)
        style(r, o.style, polygons: true)
        return r
    }

    static func renderer(_ o: BlockLines) -> MKOverlayRenderer {
        let r = MKMultiPolylineRenderer(multiPolyline: o)
        style(r, o.style, polygons: false)
        return r
    }

    private static func style(_ r: MKOverlayPathRenderer, _ style: KMLStyle?, polygons: Bool) {
        let s = style ?? KMLStyle()
        let width = s.lineWidth ?? 2.5
        let outline = width > 0 && (!polygons || s.outline != false)
        r.strokeColor = outline ? s.lineColor.map(UIColor.init(kml:)) ?? UIColor(white: 1, alpha: 0.95) : .clear
        r.lineWidth = min(12, max(1.5, width))
        if polygons {
            r.fillColor = s.fill == false ? .clear : s.fillColor.map(UIColor.init(kml:)) ?? UIColor(white: 1, alpha: 0.07)
        }
    }
}
