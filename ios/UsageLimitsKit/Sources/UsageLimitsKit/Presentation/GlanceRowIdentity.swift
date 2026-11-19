import Foundation

extension GlanceRow {
    /// Which window a row shows, as far as a row can tell: its horizon, its scope and its name.
    ///
    /// Never its reading and never its place in a list. A view identified by this keeps its
    /// identity while the window's percentage changes and gets a new one when another window
    /// takes its place, so a figure or a bar never animates from one window's value to
    /// another's.
    public struct Window: Hashable, Sendable {
        public let category: WindowCategory
        public let scope: UsageScope
        public let label: String
    }

    public var window: Window {
        Window(category: category, scope: scope, label: label)
    }
}

/// A row with an identity that is unique among the rows of its account.
public struct IdentifiedGlanceRow: Identifiable, Equatable, Sendable {
    /// The window, and which of the rows showing that window this one is.
    ///
    /// A provider can report two windows that agree in horizon, scope and name. Their order is
    /// then all that tells them apart, so the occurrence counts them in the order they came.
    public struct ID: Hashable, Sendable {
        public let window: GlanceRow.Window
        public let occurrence: Int
    }

    public let id: ID
    public let row: GlanceRow
}

extension GlanceAccount {
    /// The account's rows, each identified by the window it shows rather than by its position.
    public var identifiedRows: [IdentifiedGlanceRow] {
        var occurrences: [GlanceRow.Window: Int] = [:]
        return rows.map { row in
            let window = row.window
            let occurrence = occurrences[window, default: 0]
            occurrences[window] = occurrence + 1
            return IdentifiedGlanceRow(
                id: IdentifiedGlanceRow.ID(window: window, occurrence: occurrence), row: row)
        }
    }
}
