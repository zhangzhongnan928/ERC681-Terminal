import Foundation
import LocalAuthentication
import OPKTerminalCore
import P256K
import Security

protocol OperatorTransactionSigning: Sendable {
    func sign(digest: Bytes32, reason: String) async throws -> EthereumRecoverableSignature
}

/// A device-local secp256k1 EOA. The private scalar is stored only in a non-synchronizing
/// Keychain item protected by device-owner presence. This wallet never reads or reuses the
/// legacy random terminal identifier.
public actor KeychainOperatorWallet: OperatorTransactionSigning {
    private nonisolated let storage: OperatorKeychainStorage

    public init(service: String = "com.openpasskey.terminal.operator-wallet.v1") {
        storage = OperatorKeychainStorage(service: service)
    }

    /// Reads public metadata only and never prompts for authentication.
    public nonisolated func existingAddress() throws -> EthereumAddress? {
        try storage.readPublicAddress()
    }

    /// Creates one new key after explicit device authentication. Existing or orphaned keys
    /// are never overwritten; an orphan's public metadata is repaired instead.
    public func create(reason: String) async throws -> EthereumAddress {
        if try storage.readPublicAddress() != nil {
            throw OperatorWalletError.walletAlreadyExists
        }

        let context = try await authenticatedContext(reason: reason)
        defer { context.invalidate() }

        if var existingSecret = try storage.readPrivateKey(context: context, allowNotFound: true) {
            defer { existingSecret.resetBytes(in: 0..<existingSecret.count) }
            let address = try EthereumSecp256k1.address(privateKey: existingSecret)
            try storage.storePublicAddress(address)
            return address
        }

        let key: P256K.Recovery.PrivateKey
        do {
            key = try P256K.Recovery.PrivateKey(format: .uncompressed)
        } catch {
            throw OperatorWalletError.invalidPrivateKey
        }
        var secret = key.dataRepresentation
        defer { secret.resetBytes(in: 0..<secret.count) }
        let address = try EthereumSecp256k1.address(privateKey: secret)
        try storage.insertPrivateKey(secret)
        try storage.storePublicAddress(address)
        return address
    }

    /// Every signature requires a fresh foreground device-owner authentication prompt.
    func sign(digest: Bytes32, reason: String) async throws -> EthereumRecoverableSignature {
        let context = try await authenticatedContext(reason: reason)
        defer { context.invalidate() }
        guard var secret = try storage.readPrivateKey(context: context, allowNotFound: false) else {
            throw OperatorWalletError.walletNotCreated
        }
        defer { secret.resetBytes(in: 0..<secret.count) }
        return try EthereumSecp256k1.sign(digest: digest, privateKey: secret)
    }

    private func authenticatedContext(reason: String) async throws -> LAContext {
        let context = LAContext()
        context.localizedCancelTitle = "Cancel settlement"
        var evaluationError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &evaluationError) else {
            throw OperatorWalletError.deviceAuthenticationUnavailable
        }
        do {
            try await context.evaluatePolicy(
                .deviceOwnerAuthentication,
                localizedReason: reason
            )
            return context
        } catch {
            context.invalidate()
            throw OperatorWalletError.authenticationFailed
        }
    }
}

enum EthereumSecp256k1 {
    static func address(privateKey: Data) throws -> EthereumAddress {
        let key: P256K.Recovery.PrivateKey
        do {
            key = try P256K.Recovery.PrivateKey(
                dataRepresentation: privateKey,
                format: .uncompressed
            )
        } catch {
            throw OperatorWalletError.invalidPrivateKey
        }
        let publicKey = key.publicKey.dataRepresentation
        guard publicKey.count == 65, publicKey.first == 0x04 else {
            throw OperatorWalletError.invalidPublicKey
        }
        return try EthereumAddress(
            data: Keccak256.hash(publicKey.dropFirstData()).data.suffix(20),
            allowZero: false
        )
    }

    static func sign(digest: Bytes32, privateKey: Data) throws -> EthereumRecoverableSignature {
        let key: P256K.Recovery.PrivateKey
        do {
            key = try P256K.Recovery.PrivateKey(
                dataRepresentation: privateKey,
                format: .uncompressed
            )
        } catch {
            throw OperatorWalletError.invalidPrivateKey
        }
        let signature = key.signature(for: HashDigest([UInt8](digest.data))).compactRepresentation
        guard signature.recoveryId == 0 || signature.recoveryId == 1 else {
            throw OperatorWalletError.invalidRecoveryID(signature.recoveryId)
        }
        return try EthereumRecoverableSignature(
            r: Data(signature.signature.prefix(32)),
            s: Data(signature.signature.suffix(32)),
            yParity: UInt8(signature.recoveryId)
        )
    }
}

private final class OperatorKeychainStorage: @unchecked Sendable {
    private let service: String
    private let privateAccount = "private-key"
    private let publicAccount = "public-address"

    init(service: String) {
        self.service = service
    }

    func readPublicAddress() throws -> EthereumAddress? {
        let query: [String: Any] = baseQuery(account: publicAccount).merging([
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]) { _, new in new }
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw map(status)
        }
        do {
            return try EthereumAddress(data: data, allowZero: false)
        } catch {
            throw OperatorWalletError.invalidPublicKey
        }
    }

    func storePublicAddress(_ address: EthereumAddress) throws {
        let query = baseQuery(account: publicAccount)
        let update: [String: Any] = [kSecValueData as String: address.data]
        let updateStatus = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else { throw map(updateStatus) }

        let add = query.merging([
            kSecValueData as String: address.data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecAttrSynchronizable as String: false,
        ]) { _, new in new }
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw map(status) }
    }

    func insertPrivateKey(_ privateKey: Data) throws {
        var accessError: Unmanaged<CFError>?
        guard let accessControl = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .userPresence,
            &accessError
        ) else {
            throw OperatorWalletError.keychainFailure(errSecParam)
        }
        let query = baseQuery(account: privateAccount).merging([
            kSecValueData as String: privateKey,
            kSecAttrAccessControl as String: accessControl,
            kSecAttrSynchronizable as String: false,
        ]) { _, new in new }
        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem { throw OperatorWalletError.walletAlreadyExists }
        guard status == errSecSuccess else { throw map(status) }
    }

    func readPrivateKey(context: LAContext, allowNotFound: Bool) throws -> Data? {
        let query: [String: Any] = baseQuery(account: privateAccount).merging([
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseAuthenticationContext as String: context,
            kSecAttrSynchronizable as String: false,
        ]) { _, new in new }
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound, allowNotFound { return nil }
        if status == errSecItemNotFound { throw OperatorWalletError.walletNotCreated }
        guard status == errSecSuccess, let data = result as? Data, data.count == 32 else {
            throw map(status == errSecSuccess ? errSecDecode : status)
        }
        return data
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func map(_ status: OSStatus) -> OperatorWalletError {
        if status == errSecAuthFailed || status == errSecUserCanceled || status == errSecInteractionNotAllowed {
            return .authenticationFailed
        }
        return .keychainFailure(status)
    }
}

private extension Data {
    func dropFirstData() -> Data {
        Data(dropFirst())
    }
}
