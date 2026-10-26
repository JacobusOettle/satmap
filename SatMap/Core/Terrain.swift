import Foundation

/// A decoded elevation tile: `size`×`size` samples in metres, row-major from the north edge.
/// Sample (i, j) is the value at the centre of pixel (i, j).
final class DEMTile {
    let id: TileID
    let size: Int
    let values: [Float]

    init(id: TileID, size: Int, values: [Float]) {
        self.id = id
        self.size = size
        self.values = values
    }
}

enum Terrarium {
    /// Highest zoom published by the AWS Terrain Tiles dataset.
    static let maxZoom = 15

    /// Decodes a Terrarium-encoded PNG: elevation = R·256 + G + B/256 − 32768.
    static func decode(_ data: Data, id: TileID) -> DEMTile? {
        guard let img = PNGDecoder.decode(data), img.width == img.height else { return nil }
        let count = img.width * img.height
        var values = [Float](repeating: 0, count: count)
        img.pixels.withUnsafeBufferPointer { p in
            for i in 0..<count {
                let r = Float(p[i * 4]), g = Float(p[i * 4 + 1]), b = Float(p[i * 4 + 2])
                values[i] = r * 256 + g + b / 256 - 32768
            }
        }
        return DEMTile(id: id, size: img.width, values: values)
    }
}

/// Bilinear sampling of elevation tiles at one zoom level, across tile edges.
struct DEMSampler {
    let zoom: Int
    /// Returns the decoded tile or nil if unavailable. May block (disk / network).
    let tile: (TileID) -> DEMTile?

    /// A rectangular block of pixel samples assembled from one or more tiles.
    private struct Mosaic {
        let px0: Int
        let py0: Int
        let w: Int
        let h: Int
        let values: [Float]

        func at(_ px: Int, _ py: Int) -> Float {
            let x = min(max(px - px0, 0), w - 1)
            let y = min(max(py - py0, 0), h - 1)
            return values[y * w + x]
        }
    }

    /// Builds a mosaic covering global pixels [px0...px1] × [py0...py1].
    /// Missing neighbour tiles fall back to the edge of the tile containing `anchor`; returns nil if that tile is missing.
    private func mosaic(px0: Int, py0: Int, px1: Int, py1: Int, anchor: (Int, Int)) -> Mosaic? {
        let n = 1 << zoom
        let anchorTile = TileID(z: zoom, x: min(max(anchor.0, 0), n - 1), y: min(max(anchor.1, 0), n - 1))
        guard let primary = tile(anchorTile) else { return nil }
        let size = primary.size
        let worldPixels = n * size
        let cx0 = max(0, px0), cy0 = max(0, py0)
        let cx1 = min(worldPixels - 1, px1), cy1 = min(worldPixels - 1, py1)
        guard cx0 <= cx1, cy0 <= cy1 else { return nil }

        var tiles: [TileID: DEMTile] = [anchorTile: primary]
        var missing = Set<TileID>()
        func fetch(_ id: TileID) -> DEMTile? {
            if let t = tiles[id] { return t }
            if missing.contains(id) { return nil }
            if let t = tile(id), t.size == size {
                tiles[id] = t
                return t
            }
            missing.insert(id)
            return nil
        }

        let w = cx1 - cx0 + 1, h = cy1 - cy0 + 1
        var values = [Float](repeating: 0, count: w * h)
        let primaryMinX = anchorTile.x * size, primaryMinY = anchorTile.y * size
        for ty in (cy0 / size)...(cy1 / size) {
            for tx in (cx0 / size)...(cx1 / size) {
                let id = TileID(z: zoom, x: tx, y: ty)
                let t = fetch(id)
                let yStart = max(cy0, ty * size), yEnd = min(cy1, ty * size + size - 1)
                let xStart = max(cx0, tx * size), xEnd = min(cx1, tx * size + size - 1)
                // For a missing tile, borrow the nearest row/column of the neighbour that shares the anchor's
                // row or column, so adjacent map tiles fall back to identical values along their common edge.
                let sameRow = t == nil && ty != anchorTile.y ? fetch(TileID(z: zoom, x: tx, y: anchorTile.y)) : nil
                let sameColumn = t == nil && sameRow == nil && tx != anchorTile.x ? fetch(TileID(z: zoom, x: anchorTile.x, y: ty)) : nil
                for gy in yStart...yEnd {
                    for gx in xStart...xEnd {
                        let v: Float
                        if let t = t {
                            v = t.values[(gy - ty * size) * size + (gx - tx * size)]
                        } else if let alt = sameRow {
                            let ly = min(max(gy - primaryMinY, 0), size - 1)
                            v = alt.values[ly * size + (gx - tx * size)]
                        } else if let alt = sameColumn {
                            let lx = min(max(gx - primaryMinX, 0), size - 1)
                            v = alt.values[(gy - ty * size) * size + lx]
                        } else {
                            let lx = min(max(gx - primaryMinX, 0), size - 1)
                            let ly = min(max(gy - primaryMinY, 0), size - 1)
                            v = primary.values[ly * size + lx]
                        }
                        values[(gy - cy0) * w + (gx - cx0)] = v
                    }
                }
            }
        }
        return Mosaic(px0: cx0, py0: cy0, w: w, h: h, values: values)
    }

    /// Elevation in metres at a world coordinate, or nil if the covering tile is unavailable.
    func elevation(worldX: Double, worldY: Double, tileSize: Int = 256) -> Double? {
        let scale = Double(tileSize << zoom)
        let gx = worldX * scale - 0.5, gy = worldY * scale - 0.5
        let i0 = Int(floor(gx)), j0 = Int(floor(gy))
        let fx = Float(gx - Double(i0)), fy = Float(gy - Double(j0))
        let anchor = (Int(floor(worldX * Double(1 << zoom))), Int(floor(worldY * Double(1 << zoom))))
        guard let m = mosaic(px0: i0, py0: j0, px1: i0 + 1, py1: j0 + 1, anchor: anchor) else { return nil }
        let v00 = m.at(i0, j0), v10 = m.at(i0 + 1, j0), v01 = m.at(i0, j0 + 1), v11 = m.at(i0 + 1, j0 + 1)
        let top = v00 + (v10 - v00) * fx
        let bottom = v01 + (v11 - v01) * fx
        return Double(top + (bottom - top) * fy)
    }

    func elevation(at p: GeoPoint, tileSize: Int = 256) -> Double? {
        let w = WebMercator.world(p)
        return elevation(worldX: w.x, worldY: w.y, tileSize: tileSize)
    }

    /// A grid of `nodesX`×`nodesY` bilinear samples spanning the world rectangle edge to edge.
    /// Node (0,0) is at (minX, minY); node (nodesX-1, nodesY-1) is at (maxX, maxY).
    func grid(minX: Double, minY: Double, maxX: Double, maxY: Double, nodesX: Int, nodesY: Int, tileSize: Int = 256) -> [Float]? {
        guard nodesX >= 2, nodesY >= 2 else { return nil }
        let scale = Double(tileSize << zoom)
        let px0 = Int(floor(minX * scale - 0.5)), py0 = Int(floor(minY * scale - 0.5))
        let px1 = Int(floor(maxX * scale - 0.5)) + 1, py1 = Int(floor(maxY * scale - 0.5)) + 1
        let cx = (minX + maxX) / 2, cy = (minY + maxY) / 2
        let anchor = (Int(floor(cx * Double(1 << zoom))), Int(floor(cy * Double(1 << zoom))))
        guard let m = mosaic(px0: px0, py0: py0, px1: px1, py1: py1, anchor: anchor) else { return nil }

        var out = [Float](repeating: 0, count: nodesX * nodesY)
        let dx = (maxX - minX) / Double(nodesX - 1)
        let dy = (maxY - minY) / Double(nodesY - 1)
        for j in 0..<nodesY {
            let gy = (minY + Double(j) * dy) * scale - 0.5
            let j0 = Int(floor(gy))
            let fy = Float(gy - Double(j0))
            for i in 0..<nodesX {
                let gx = (minX + Double(i) * dx) * scale - 0.5
                let i0 = Int(floor(gx))
                let fx = Float(gx - Double(i0))
                let v00 = m.at(i0, j0), v10 = m.at(i0 + 1, j0), v01 = m.at(i0, j0 + 1), v11 = m.at(i0 + 1, j0 + 1)
                let top = v00 + (v10 - v00) * fx
                let bottom = v01 + (v11 - v01) * fx
                out[j * nodesX + i] = top + (bottom - top) * fy
            }
        }
        return out
    }
}
