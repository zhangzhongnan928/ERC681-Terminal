import Foundation
import OPKTerminalCore
import Security

enum RPCEndpointConfigurationStatus: Equatable {
    case builtIn
    case configured(provider: String)
    case unavailable(String)

    var summary: String {
        switch self {
        case .builtIn:
            "Built-in public endpoint"
        case let .configured(provider):
            "Dedicated endpoint configured, \(provider)"
        case .unavailable:
            "Endpoint unavailable"
        }
    }
}

enum RPCEndpointStoreError: Error, Equatable {
    case invalidURL
    case tooLong(maximumBytes: Int)
    case keychainFailure(OSStatus)
}

extension RPCEndpointStoreError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalidURL:
            "Enter a valid HTTPS RPC URL without embedded user information or a fragment."
        case let .tooLong(maximumBytes):
            "The RPC URL is too long. Enter at most \(maximumBytes) UTF-8 bytes."
        case let .keychainFailure(status):
            "The RPC endpoint could not be accessed in Keychain (status \(status))."
        }
    }
}

enum RPCEndpointMigrationError: Error, Equatable {
    case settingsPersistenceFailed
    case historyPersistenceFailed
}

extension RPCEndpointMigrationError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .settingsPersistenceFailed:
            "The legacy RPC endpoint was secured in Keychain, but its old settings copy could not be removed. The app will retry on its next launch."
        case .historyPersistenceFailed:
            "Legacy RPC transport metadata could not be removed from local history. The app will retry on its next launch."
        }
    }
}

enum RPCEndpointURLParser {
    static let maximumUTF8Bytes = 8_192

    static func parse(_ rawValue: String) throws -> URL {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty,
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { throw RPCEndpointStoreError.invalidURL }
        guard value.utf8.count <= maximumUTF8Bytes else {
            throw RPCEndpointStoreError.tooLong(maximumBytes: maximumUTF8Bytes)
        }
        guard let components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              let host = components.host,
              !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.fragment == nil,
              let endpoint = components.url
        else { throw RPCEndpointStoreError.invalidURL }
        try RPCURLPolicy.validate(endpoint)
        return endpoint
    }

    static func providerLabel(for endpoint: URL) -> String {
        guard let host = endpoint.host?.lowercased() else {
            return "Custom HTTPS provider"
        }
        let providers: [(suffix: String, label: String)] = [
            ("alchemy.com", "Alchemy"),
            ("infura.io", "Infura"),
            ("quiknode.pro", "QuickNode"),
            ("quicknode.com", "QuickNode"),
            ("ankr.com", "Ankr"),
            ("chainstack.com", "Chainstack"),
            ("drpc.org", "dRPC"),
        ]
        return providers.first(where: {
            host == $0.suffix || host.hasSuffix(".\($0.suffix)")
        })?.label ?? "Custom HTTPS provider"
    }
}

protocol RPCEndpointManaging {
    func endpoint(for chainID: UInt64) throws -> URL?
    func save(_ endpoint: URL, for chainID: UInt64) throws
    func removeEndpoint(for chainID: UInt64) throws
}

final class KeychainRPCEndpointStore: RPCEndpointManaging {
    private let service: String

    init(service: String = "com.openpasskey.terminal.rpc-endpoint.v1") {
        self.service = service
    }

    func endpoint(for chainID: UInt64) throws -> URL? {
        let query = baseQuery(chainID: chainID).merging([
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]) { _, new in new }
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess,
              let data = result as? Data,
              let rawValue = String(data: data, encoding: .utf8)
        else {
            throw RPCEndpointStoreError.keychainFailure(
                status == errSecSuccess ? errSecDecode : status
            )
        }
        return try RPCEndpointURLParser.parse(rawValue)
    }

    func save(_ endpoint: URL, for chainID: UInt64) throws {
        let validated = try RPCEndpointURLParser.parse(endpoint.absoluteString)
        let data = Data(validated.absoluteString.utf8)
        let query = baseQuery(chainID: chainID)
        let updateStatus = SecItemUpdate(
            query as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw RPCEndpointStoreError.keychainFailure(updateStatus)
        }
        let add = query.merging([
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: false,
        ]) { _, new in new }
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw RPCEndpointStoreError.keychainFailure(addStatus)
        }
    }

    func removeEndpoint(for chainID: UInt64) throws {
        let status = SecItemDelete(baseQuery(chainID: chainID) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw RPCEndpointStoreError.keychainFailure(status)
        }
    }

    private func baseQuery(chainID: UInt64) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "chain-\(chainID)",
        ]
    }
}

extension TerminalConfiguration {
    func replacingRPCEndpoint(with endpoint: URL) throws -> TerminalConfiguration {
        try TerminalConfiguration(
            chainID: chainID,
            rpcEndpoints: [endpoint],
            protocolVersion: protocolVersion,
            deployment: deployment,
            tokens: tokens,
            confirmationPolicy: confirmationPolicy,
            create2TestVector: create2TestVector
        )
    }
}
