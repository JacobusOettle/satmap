import Foundation

/// Where the map's buttons go (groups §9.1; Android `mapButtonGrid`), given their sizes top to bottom: one column,
/// `gap` apart, while it is no taller than `maxHeight` (the usual; the compass slot keeps its 44 pt). Else (a small
/// phone, landscape, tall panels at the top) a grid of `cell`-sized cells, `gap` apart both ways, with as many rows as
/// fit (at least one): filled from the last button (My location stays at the bottom right) up each column, right to
/// left; the leftmost column holds the first buttons from its top, and a smaller button (the compass) is centred in its
/// cell.
struct MapButtonGrid: Equatable {
    /// The whole block.
    let size: CGSize
    /// Each button's top left, from the block's top left.
    let offsets: [CGPoint]

    init(sizes: [CGSize], cell: CGFloat, gap: CGFloat, maxHeight: CGFloat) {
        let column = sizes.reduce(0) { $0 + $1.height } + gap * CGFloat(max(sizes.count - 1, 0))
        if column <= maxHeight || sizes.isEmpty {
            var y: CGFloat = 0
            offsets = sizes.map { s in
                defer { y += s.height + gap }
                return CGPoint(x: (cell - s.width) / 2, y: y)
            }
            size = CGSize(width: cell, height: column)
            return
        }
        let n = sizes.count
        let rows = min(max(Int(((maxHeight + gap) / (cell + gap)).rounded(.down)), 1), n)
        let columns = (n + rows - 1) / rows
        let width = CGFloat(columns) * cell + CGFloat(columns - 1) * gap
        offsets = sizes.enumerated().map { i, s in
            let fromEnd = n - 1 - i
            let col = fromEnd / rows // 0: the rightmost
            let row = col == columns - 1 ? i : rows - 1 - fromEnd % rows
            return CGPoint(x: width - CGFloat(col + 1) * cell - CGFloat(col) * gap + (cell - s.width) / 2,
                           y: CGFloat(row) * (cell + gap) + (cell - s.height) / 2)
        }
        size = CGSize(width: width, height: CGFloat(rows) * cell + CGFloat(rows - 1) * gap)
    }
}
