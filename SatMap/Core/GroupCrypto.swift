import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import COpenSSL
#endif

/// Group records are sealed on the phones with AES-256-GCM (docs/groups/SPEC.md §3); the server only stores and
/// forwards them. An envelope is the format version 0x01, a 12-byte random nonce, the ciphertext and the 16-byte tag.
/// Keys are HKDF-SHA256 subkeys of the 256-bit group key, salted with the group id (§3.3), and the associated data
/// binds each record to its group, member, window, time or pin (§3.4, `GroupAAD`).
///
/// CryptoKit on Apple platforms; OpenSSL's EVP AES-256-GCM and an RFC 5869 HKDF on `HMAC(EVP_sha256())` on Linux,
/// where only the tests run (the same dual structure as `TileCrypto`, with the key and the AAD as parameters). The
/// Android core (GroupCrypto.kt) seals and opens the same bytes: the shared vectors check both.
enum GroupCrypto {
    enum Failure: Error, Equatable {
        /// The envelope has another format version: a newer app's record, skipped quietly.
        case unsupportedVersion
        /// Too short, or the tag doesn't match: damaged, moved to another group, member, window or time, or sealed
        /// with another key.
        case unreadable
        /// The cipher failed (a bad key or nonce size).
        case cipherFailed
    }

    static let version: UInt8 = 0x01
    static let nonceSize = 12
    static let tagSize = 16
    /// Version, nonce and tag around an empty plaintext.
    static let minEnvelopeSize = 1 + 12 + 16
    static let keySize = 32

    /// HKDF-SHA256 with the group key as input, the group id's 16 raw bytes as salt and
    /// "satmap.group.v1|key|{purpose}" as info; 32 bytes (§3.3).
    static func subkey(groupKey: Data, groupId: Data, purpose: String) -> Data {
        let info = Data(("satmap.group.v1|key|" + purpose).utf8)
        #if canImport(CryptoKit)
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: groupKey), salt: groupId, info: info,
                                         outputByteCount: keySize)
        return key.withUnsafeBytes { Data($0) }
        #else
        // RFC 5869: PRK = HMAC(salt, IKM); with L = 32 the output is the first block, HMAC(PRK, info ‖ 0x01).
        let prk = hmacSHA256(key: [UInt8](groupId), data: [UInt8](groupKey))
        return Data(hmacSHA256(key: prk, data: [UInt8](info) + [0x01]))
        #endif
    }

    /// The plaintext sealed with a new random nonce.
    static func seal(_ plain: Data, key: Data, aad: String) throws -> Data {
        try seal(plain, key: key, aad: aad, nonce: GroupRandom.bytes(nonceSize))
    }

    /// `seal` with a given nonce: only for the tests, which check the shared vectors. A nonce must never be used
    /// twice with a key.
    static func seal(_ plain: Data, key: Data, aad: String, nonce: Data) throws -> Data {
        guard key.count == keySize, nonce.count == nonceSize else { throw Failure.cipherFailed }
        #if canImport(CryptoKit)
        do {
            let box = try AES.GCM.seal(plain, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce),
                                       authenticating: Data(aad.utf8))
            guard let combined = box.combined else { throw Failure.cipherFailed } // nonce, ciphertext, tag
            return Data([version]) + combined
        } catch {
            throw Failure.cipherFailed
        }
        #else
        let (ciphertext, tag) = try openSSLSeal([UInt8](plain), key: [UInt8](key), nonce: [UInt8](nonce),
                                                aad: Array(aad.utf8))
        return Data([version]) + nonce + Data(ciphertext) + Data(tag)
        #endif
    }

    /// The plaintext of an envelope. Throws `unsupportedVersion` for another format version, `unreadable` if it is
    /// too short or doesn't open with this key and AAD.
    static func open(_ envelope: Data, key: Data, aad: String) throws -> Data {
        let bytes = [UInt8](envelope)
        guard let first = bytes.first else { throw Failure.unreadable }
        guard first == version else { throw Failure.unsupportedVersion }
        guard bytes.count >= minEnvelopeSize else { throw Failure.unreadable }
        guard key.count == keySize else { throw Failure.cipherFailed }
        #if canImport(CryptoKit)
        do {
            let box = try AES.GCM.SealedBox(combined: Data(bytes[1...]))
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: Data(aad.utf8))
        } catch {
            throw Failure.unreadable
        }
        #else
        let nonce = Array(bytes[1...nonceSize])
        let ciphertext = Array(bytes[(1 + nonceSize)..<(bytes.count - tagSize)])
        let tag = Array(bytes[(bytes.count - tagSize)...])
        return Data(try openSSLOpen(ciphertext, tag: tag, key: [UInt8](key), nonce: nonce, aad: Array(aad.utf8)))
        #endif
    }

    #if !canImport(CryptoKit)
    private static func hmacSHA256(key: [UInt8], data: [UInt8]) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 32)
        var length: UInt32 = 0
        _ = HMAC(EVP_sha256(), key, Int32(key.count), data, data.count, &out, &length)
        return Array(out[..<Int(length)])
    }

    private static func openSSLSeal(_ plain: [UInt8], key: [UInt8], nonce: [UInt8], aad: [UInt8]) throws -> (ciphertext: [UInt8], tag: [UInt8]) {
        guard let ctx = EVP_CIPHER_CTX_new() else { throw Failure.cipherFailed }
        defer { EVP_CIPHER_CTX_free(ctx) }
        var ciphertext = [UInt8](repeating: 0, count: plain.count + 16) // room for a block, as EVP asks
        var tag = [UInt8](repeating: 0, count: tagSize)
        var length: Int32 = 0
        var final: Int32 = 0
        guard EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nil, nil, nil) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, Int32(nonceSize), nil) == 1,
              EVP_EncryptInit_ex(ctx, nil, nil, key, nonce) == 1,
              EVP_EncryptUpdate(ctx, nil, &length, aad, Int32(aad.count)) == 1,
              EVP_EncryptUpdate(ctx, &ciphertext, &length, plain, Int32(plain.count)) == 1,
              ciphertext.withUnsafeMutableBufferPointer({ EVP_EncryptFinal_ex(ctx, $0.baseAddress! + Int(length), &final) }) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, Int32(tagSize), &tag) == 1 else { throw Failure.cipherFailed }
        return (Array(ciphertext[..<Int(length + final)]), tag)
    }

    private static func openSSLOpen(_ ciphertext: [UInt8], tag: [UInt8], key: [UInt8], nonce: [UInt8], aad: [UInt8]) throws -> [UInt8] {
        guard let ctx = EVP_CIPHER_CTX_new() else { throw Failure.cipherFailed }
        defer { EVP_CIPHER_CTX_free(ctx) }
        var plain = [UInt8](repeating: 0, count: ciphertext.count + 16)
        var tag = tag
        var length: Int32 = 0
        var final: Int32 = 0
        guard EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nil, nil, nil) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, Int32(nonceSize), nil) == 1,
              EVP_DecryptInit_ex(ctx, nil, nil, key, nonce) == 1,
              EVP_DecryptUpdate(ctx, nil, &length, aad, Int32(aad.count)) == 1,
              EVP_DecryptUpdate(ctx, &plain, &length, ciphertext, Int32(ciphertext.count)) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, Int32(tagSize), &tag) == 1 else { throw Failure.unreadable }
        // Checks the tag.
        guard plain.withUnsafeMutableBufferPointer({ EVP_DecryptFinal_ex(ctx, $0.baseAddress! + Int(length), &final) }) == 1 else {
            throw Failure.unreadable
        }
        return Array(plain[..<Int(length + final)])
    }
    #endif
}

/// A group's keys (§3.3): the four group-wide values derived once, each member's trail and live keys on first use.
/// Kept in memory only.
struct GroupKeys {
    /// The group id's 22-character text.
    let groupId: String
    let group: Data
    let member: Data
    let pin: Data
    /// `subkey("join")`: not an encryption key but the proof sent at create, preview and redeem (SD4).
    let joinProof: Data
    private let groupKey: Data
    private let groupIdBytes: Data
    private let cache = MemberKeyCache()

    init(groupKey: Data, groupId: Data) {
        self.groupKey = groupKey
        self.groupIdBytes = groupId
        self.groupId = Base64URL.encode(groupId)
        group = GroupCrypto.subkey(groupKey: groupKey, groupId: groupId, purpose: "group")
        member = GroupCrypto.subkey(groupKey: groupKey, groupId: groupId, purpose: "member")
        pin = GroupCrypto.subkey(groupKey: groupKey, groupId: groupId, purpose: "pin")
        joinProof = GroupCrypto.subkey(groupKey: groupKey, groupId: groupId, purpose: "join")
    }

    /// The trail key of a member (SD26), by the member's 22-character id.
    func trail(_ memberId: String) -> Data {
        cache.key("trail|" + memberId) { GroupCrypto.subkey(groupKey: groupKey, groupId: groupIdBytes, purpose: $0) }
    }

    /// The live key of a member (SD26).
    func live(_ memberId: String) -> Data {
        cache.key("live|" + memberId) { GroupCrypto.subkey(groupKey: groupKey, groupId: groupIdBytes, purpose: $0) }
    }
}

private final class MemberKeyCache {
    private let lock = NSLock()
    private var keys: [String: Data] = [:]

    func key(_ purpose: String, derive: (String) -> Data) -> Data {
        lock.lock()
        defer { lock.unlock() }
        if let k = keys[purpose] { return k }
        let k = derive(purpose)
        keys[purpose] = k
        return k
    }
}

/// The associated data of each record kind (§3.4): fields joined by "|", ids as their 22-character text, integers in
/// decimal.
enum GroupAAD {
    static func group(_ groupId: String) -> String {
        "satmap.group.v1|group|\(groupId)"
    }

    static func member(_ groupId: String, _ memberId: String) -> String {
        "satmap.group.v1|member|\(groupId)|\(memberId)"
    }

    static func trail(_ groupId: String, _ memberId: String, start: Int64) -> String {
        "satmap.group.v1|trail|\(groupId)|\(memberId)|\(start)"
    }

    static func live(_ groupId: String, _ memberId: String, at: Int64) -> String {
        "satmap.group.v1|live|\(groupId)|\(memberId)|\(at)"
    }

    /// `expiresAt` is "-" for a pin that doesn't expire.
    static func pin(_ groupId: String, pinId: String, memberId: String, createdAt: Int64, expiresAt: Int64?) -> String {
        "satmap.group.v1|pin|\(groupId)|\(pinId)|\(memberId)|\(createdAt)|\(expiresAt.map(String.init) ?? "-")"
    }
}
