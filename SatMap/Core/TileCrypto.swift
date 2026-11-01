import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import COpenSSL
#endif

/// Offline map tiles are stored encrypted (`MBTiles.put` seals them, `MBTiles.tile` opens them), so the files can't
/// simply be copied off the phone and their imagery shown in QGIS or another app.
///
/// A sealed tile is "SMT" and format version 1 (bytes 53 4D 54 01), a 12-byte random nonce, the AES-256-GCM
/// ciphertext and its 16-byte tag. The associated data is the UTF-8 text "satmap/{z}/{x}/{y}" with the tile's XYZ
/// coordinates (`TileID`'s, not the MBTiles TMS row), so a tile can't be moved to another place. No image format
/// starts with those four bytes (JPEG FF D8, PNG 89 50, WebP RIFF, GIF GIF8), so a reader tells sealed tiles from
/// plain ones (stored before tiles were encrypted) by them. The Android app (TileCrypto.kt) writes and reads the same
/// bytes with the same key, so backups move between the two.
///
/// CryptoKit on Apple platforms; OpenSSL's EVP AES-256-GCM on Linux, where only the tests run.
enum TileCrypto {
    enum Failure: Error {
        /// Not a sealed tile: it doesn't start with `magic`.
        case notSealed
        /// Too short, or the tag doesn't match: the tile was changed or damaged, belongs to another place, or was
        /// sealed with another key.
        case unreadable
        /// The cipher, or the random nonce, failed.
        case cipherFailed
    }

    /// "SMT" and the format version, 1.
    static let magic = Data([0x53, 0x4D, 0x54, 0x01])
    static let nonceSize = 12
    static let tagSize = 16

    /// Whether `blob` is in the sealed format (it starts with `magic`); a plain tile is an image.
    static func isSealed(_ blob: Data) -> Bool {
        blob.starts(with: magic)
    }

    /// The tile's data sealed for its place, with a new random nonce.
    static func seal(_ data: Data, tile: TileID) throws -> Data {
        try seal(data, tile: tile, nonce: randomNonce())
    }

    /// The plain tile from a sealed one. Throws if it isn't sealed (`isSealed`) or doesn't open for this place.
    static func open(_ blob: Data, tile: TileID) throws -> Data {
        guard isSealed(blob) else { throw Failure.notSealed }
        guard blob.count >= magic.count + nonceSize + tagSize else { throw Failure.unreadable }
        let body = Data(blob.dropFirst(magic.count)) // indexed from 0
        #if canImport(CryptoKit)
        do {
            return try AES.GCM.open(AES.GCM.SealedBox(combined: body), using: key, authenticating: associatedData(tile))
        } catch {
            throw Failure.unreadable
        }
        #else
        let bytes = [UInt8](body)
        let nonce = Array(bytes[..<nonceSize])
        let ciphertext = Array(bytes[nonceSize..<(bytes.count - tagSize)])
        let tag = Array(bytes[(bytes.count - tagSize)...])
        return Data(try openSSLOpen(ciphertext, tag: tag, nonce: nonce, aad: [UInt8](associatedData(tile))))
        #endif
    }

    /// `seal` with a given nonce: for the tests, which check that both apps write the same bytes. A nonce must never
    /// be used twice with the key.
    static func seal(_ data: Data, tile: TileID, nonce: Data) throws -> Data {
        precondition(nonce.count == nonceSize)
        #if canImport(CryptoKit)
        let box = try AES.GCM.seal(data, using: key, nonce: AES.GCM.Nonce(data: nonce), authenticating: associatedData(tile))
        guard let combined = box.combined else { throw Failure.cipherFailed } // nonce, ciphertext, tag
        return magic + combined
        #else
        let (ciphertext, tag) = try openSSLSeal([UInt8](data), nonce: [UInt8](nonce), aad: [UInt8](associatedData(tile)))
        return magic + nonce + ciphertext + tag
        #endif
    }

    /// "satmap/{z}/{x}/{y}", the tile's XYZ coordinates.
    static func associatedData(_ tile: TileID) -> Data {
        Data("satmap/\(tile.z)/\(tile.x)/\(tile.y)".utf8)
    }

    // MARK: - The key

    /// The app's one 256-bit key, built into the app (here, and the same bytes in TileCrypto.kt on Android) as two
    /// random 32-byte halves XORed together the first time it is needed, so it isn't a plain string in the binary.
    /// It is in the app, not made on each phone, so backups restore on the user's other phones, iPhone or Android.
    /// It stops someone copying the offline files off a phone (or from a backup) and showing the imagery in QGIS or
    /// another app. It doesn't stop someone who takes the app apart and extracts the key.
    private static let keyBytes: [UInt8] = {
        let mask: [UInt8] = [
            0x82, 0x9E, 0x31, 0x88, 0x5F, 0x1A, 0x52, 0xD6, 0x57, 0x5B, 0x1E, 0x90, 0xAF, 0x3E, 0xD9, 0x3F,
            0xD0, 0xB9, 0xEA, 0x3A, 0xC6, 0x88, 0x46, 0x29, 0xA4, 0xA2, 0x29, 0xFE, 0x86, 0x18, 0x71, 0x52,
        ]
        let masked: [UInt8] = [
            0x58, 0xCD, 0x76, 0x80, 0x0E, 0x86, 0xDA, 0x3F, 0xE0, 0x57, 0x4E, 0x4F, 0x40, 0xD6, 0x9E, 0x17,
            0xE8, 0x04, 0x50, 0x73, 0x98, 0x43, 0xF1, 0x27, 0x6C, 0xBA, 0x08, 0x75, 0xA5, 0x11, 0x0A, 0xE1,
        ]
        return zip(mask, masked).map { $0 ^ $1 }
    }()

    #if canImport(CryptoKit)
    private static let key = SymmetricKey(data: keyBytes)

    private static func randomNonce() throws -> Data {
        AES.GCM.Nonce().withUnsafeBytes { Data($0) }
    }
    #else
    private static func randomNonce() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: nonceSize)
        guard RAND_bytes(&bytes, Int32(nonceSize)) == 1 else { throw Failure.cipherFailed }
        return Data(bytes)
    }

    private static func openSSLSeal(_ plain: [UInt8], nonce: [UInt8], aad: [UInt8]) throws -> (ciphertext: [UInt8], tag: [UInt8]) {
        guard let ctx = EVP_CIPHER_CTX_new() else { throw Failure.cipherFailed }
        defer { EVP_CIPHER_CTX_free(ctx) }
        var ciphertext = [UInt8](repeating: 0, count: plain.count + 16) // room for a block, as EVP asks
        var tag = [UInt8](repeating: 0, count: tagSize)
        var length: Int32 = 0
        var final: Int32 = 0
        guard EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nil, nil, nil) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, Int32(nonceSize), nil) == 1,
              EVP_EncryptInit_ex(ctx, nil, nil, keyBytes, nonce) == 1,
              EVP_EncryptUpdate(ctx, nil, &length, aad, Int32(aad.count)) == 1,
              EVP_EncryptUpdate(ctx, &ciphertext, &length, plain, Int32(plain.count)) == 1,
              ciphertext.withUnsafeMutableBufferPointer({ EVP_EncryptFinal_ex(ctx, $0.baseAddress! + Int(length), &final) }) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, Int32(tagSize), &tag) == 1 else { throw Failure.cipherFailed }
        return (Array(ciphertext[..<Int(length + final)]), tag)
    }

    private static func openSSLOpen(_ ciphertext: [UInt8], tag: [UInt8], nonce: [UInt8], aad: [UInt8]) throws -> [UInt8] {
        guard let ctx = EVP_CIPHER_CTX_new() else { throw Failure.cipherFailed }
        defer { EVP_CIPHER_CTX_free(ctx) }
        var plain = [UInt8](repeating: 0, count: ciphertext.count + 16)
        var tag = tag
        var length: Int32 = 0
        var final: Int32 = 0
        guard EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nil, nil, nil) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, Int32(nonceSize), nil) == 1,
              EVP_DecryptInit_ex(ctx, nil, nil, keyBytes, nonce) == 1,
              EVP_DecryptUpdate(ctx, nil, &length, aad, Int32(aad.count)) == 1,
              EVP_DecryptUpdate(ctx, &plain, &length, ciphertext, Int32(ciphertext.count)) == 1,
              EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, Int32(tagSize), &tag) == 1 else { throw Failure.cipherFailed }
        // Checks the tag.
        guard plain.withUnsafeMutableBufferPointer({ EVP_DecryptFinal_ex(ctx, $0.baseAddress! + Int(length), &final) }) == 1 else {
            throw Failure.unreadable
        }
        return Array(plain[..<Int(length + final)])
    }
    #endif
}
