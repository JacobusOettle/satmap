import Foundation

/// An invite link (docs/groups/SPEC.md §5.2): `https://{domain}/j/{inviteToken}#{groupKey}`. The token's first 16
/// bytes are the group id; the key travels only in the fragment, never to the server. The landing page's fallback is
/// `satmap://join/{inviteToken}#{groupKey}` (SD15); the apps never share it.
struct InviteLink: Equatable {
    /// 43 base64url characters: the group id and the invite secret.
    let token: String
    /// 16 bytes.
    let groupId: Data
    /// 32 bytes.
    let groupKey: Data

    /// The link for a token and key, or nil if the token isn't 43 base64url characters or the key isn't 32 bytes.
    init?(token: String, groupKey: Data) {
        guard token.utf8.count == 43, let tokenBytes = Base64URL.decode(token, count: 32), groupKey.count == 32 else { return nil }
        self.token = token
        self.groupId = Data(tokenBytes.prefix(16))
        self.groupKey = groupKey
    }

    var groupIdText: String { Base64URL.encode(groupId) }

    func url(domain: String) -> String {
        "https://\(domain)/j/\(token)#\(Base64URL.encode(groupKey))"
    }

    var schemeURL: String {
        "satmap://join/\(token)#\(Base64URL.encode(groupKey))"
    }

    enum Parsed: Equatable {
        case invite(InviteLink)
        /// An invite link without its key (the "incomplete link" text).
        case missingKey
        case notInvite
    }

    /// Finds an invite link in any text, such as a whole pasted message (§5.2): the earliest full link for `domain`
    /// (the host compared ignoring ASCII case) or in the `satmap://` form; otherwise `missingKey` if there is a link
    /// whose key is missing or damaged; otherwise `notInvite`.
    static func parse(_ text: String, domain: String) -> Parsed {
        let whole = NSRange(text.startIndex..., in: text)
        let host = domain.lowercased()
        func group(_ m: NSTextCheckingResult, _ i: Int) -> String? {
            Range(m.range(at: i), in: text).map { String(text[$0]) }
        }
        var found: [(location: Int, token: String, key: String)] = []
        for m in fullHTTPS.matches(in: text, range: whole) {
            if let h = group(m, 1), h.lowercased() == host, let token = group(m, 2), let key = group(m, 3) {
                found.append((m.range.location, token, key))
            }
        }
        for m in fullScheme.matches(in: text, range: whole) {
            if let token = group(m, 1), let key = group(m, 2) {
                found.append((m.range.location, token, key))
            }
        }
        if let first = found.min(by: { $0.location < $1.location }),
           let keyBytes = Base64URL.decode(first.key, count: 32), let link = InviteLink(token: first.token, groupKey: keyBytes) {
            return .invite(link)
        }
        let keylessHere = keylessHTTPS.matches(in: text, range: whole).contains { m in group(m, 1)?.lowercased() == host }
        if keylessHere || keylessScheme.firstMatch(in: text, range: whole) != nil {
            return .missingKey
        }
        return .notInvite
    }

    // The patterns of §5.2, case-sensitive (only the host comparison ignores case).
    private static let fullHTTPS = pattern("https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
    private static let fullScheme = pattern("satmap://join/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
    private static let keylessHTTPS = pattern("https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
    private static let keylessScheme = pattern("satmap://join/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")

    private static func pattern(_ p: String) -> NSRegularExpression {
        // The patterns are constants, so this can't fail.
        try! NSRegularExpression(pattern: p)
    }
}
