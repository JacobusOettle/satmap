import Foundation
import Security

/// The memberships in the Keychain (docs/groups/SPEC.md §8.3): one generic-password item per group, service
/// `com.koonap.spoor.groups`, account = the group id, readable after the first unlock (also in the background),
/// never in backups or on other devices, not synchronised. iOS keeps them when the app is deleted, so a reinstall
/// restores the memberships. (§9.9: the Keychain here, the Keystore on Android.)
final class KeychainSecrets: GroupSecretStore {
    static let service = "com.koonap.spoor.groups"

    struct KeychainError: Error {
        let status: OSStatus
    }

    private func baseQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Self.service,
         kSecAttrSynchronizable as String: kCFBooleanFalse as Any]
    }

    /// Throws when the Keychain can't be read now (before the first unlock, for example): the hub then deletes
    /// nothing and reads again later.
    func loadAll() throws -> [GroupSecret] {
        var query = baseQuery()
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        query[kSecReturnAttributes as String] = true
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        let items = result as? [[String: Any]] ?? []
        var secrets: [GroupSecret] = []
        for item in items {
            guard let data = item[kSecValueData as String] as? Data,
                  let secret = try? JSONDecoder().decode(GroupSecret.self, from: data) else {
                // A damaged item: left for the account's next save to replace.
                continue
            }
            if let account = item[kSecAttrAccount as String] as? String, account != secret.groupId { continue }
            secrets.append(secret)
        }
        return secrets
    }

    func save(_ secret: GroupSecret) throws {
        let data = try JSONEncoder().encode(secret)
        var query = baseQuery()
        query[kSecAttrAccount as String] = secret.groupId
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw KeychainError(status: status) }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(add as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainError(status: added) }
    }

    func delete(groupId: String) {
        var query = baseQuery()
        query[kSecAttrAccount as String] = groupId
        SecItemDelete(query as CFDictionary)
    }
}
