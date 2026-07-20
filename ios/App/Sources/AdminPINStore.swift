import CommonCrypto
import Foundation
import Security

enum AdminPINError: Error, Equatable {
    case invalidFormat
    case alreadyConfigured
    case notConfigured
    case invalidPIN(retryAfterSeconds: Int?)
    case throttled(secondsRemaining: Int)
    case keychainFailure(OSStatus)
}

extension AdminPINError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalidFormat:
            "Enter exactly six digits."
        case .alreadyConfigured:
            "An admin PIN is already configured."
        case .notConfigured:
            "Create the local admin PIN first."
        case let .invalidPIN(retryAfterSeconds):
            if let retryAfterSeconds {
                "Incorrect PIN. Try again in \(retryAfterSeconds) seconds."
            } else {
                "Incorrect PIN."
            }
        case let .throttled(secondsRemaining):
            "Too many attempts. Try again in \(secondsRemaining) seconds."
        case let .keychainFailure(status):
            "The admin PIN verifier could not be accessed in Keychain (status \(status))."
        }
    }
}

protocol AdminPINManaging {
    var isConfigured: Bool { get throws }
    func setPIN(_ pin: String) throws
    func verify(_ pin: String) throws
    func secondsUntilNextAttempt() throws -> Int
}

/// Stores only a salted, deliberately slow PBKDF2-HMAC-SHA256 verifier and persistent throttle
/// state. The PIN itself is never
/// persisted and this verifier is unrelated to merchant/passkey credentials.
final class KeychainAdminPINStore: AdminPINManaging {
    private struct Record: Codable {
        var salt: Data
        var verifier: Data
        var failedAttempts: Int
        var blockedUntil: Date?
    }

    private let service: String
    private let account = "admin-pin-verifier"
    private let now: () -> Date

    init(
        service: String = "com.openpasskey.terminal.admin-pin.v1",
        now: @escaping () -> Date = Date.init
    ) {
        self.service = service
        self.now = now
    }

    var isConfigured: Bool {
        get throws { try load() != nil }
    }

    func setPIN(_ pin: String) throws {
        try Self.validateFormat(pin)
        guard try load() == nil else { throw AdminPINError.alreadyConfigured }
        var salt = Data(count: 32)
        let status = salt.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, bytes.count, bytes.baseAddress!)
        }
        guard status == errSecSuccess else { throw AdminPINError.keychainFailure(status) }
        try save(Record(
            salt: salt,
            verifier: Self.verifier(pin: pin, salt: salt),
            failedAttempts: 0,
            blockedUntil: nil
        ))
    }

    func verify(_ pin: String) throws {
        guard var record = try load() else { throw AdminPINError.notConfigured }
        let current = now()
        if let blockedUntil = record.blockedUntil, blockedUntil > current {
            throw AdminPINError.throttled(
                secondsRemaining: max(1, Int(ceil(blockedUntil.timeIntervalSince(current))))
            )
        }

        let candidate = Self.isValidFormat(pin)
            ? Self.verifier(pin: pin, salt: record.salt)
            : Data()
        if Self.constantTimeEqual(candidate, record.verifier) {
            if record.failedAttempts != 0 || record.blockedUntil != nil {
                record.failedAttempts = 0
                record.blockedUntil = nil
                try save(record)
            }
            return
        }

        record.failedAttempts += 1
        let delay = Self.throttleDelay(afterFailedAttempts: record.failedAttempts)
        record.blockedUntil = delay.map { current.addingTimeInterval(TimeInterval($0)) }
        try save(record)
        if Self.isValidFormat(pin) {
            throw AdminPINError.invalidPIN(retryAfterSeconds: delay)
        }
        throw AdminPINError.invalidFormat
    }

    func secondsUntilNextAttempt() throws -> Int {
        guard let blockedUntil = try load()?.blockedUntil else { return 0 }
        return max(0, Int(ceil(blockedUntil.timeIntervalSince(now()))))
    }

    static func isValidFormat(_ pin: String) -> Bool {
        pin.utf8.count == 6 && pin.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 })
    }

    static func throttleDelay(afterFailedAttempts attempts: Int) -> Int? {
        guard attempts >= 3 else { return nil }
        let exponent = min(attempts - 3, 6)
        return min(300, 5 * (1 << exponent))
    }

    private static func validateFormat(_ pin: String) throws {
        guard isValidFormat(pin) else { throw AdminPINError.invalidFormat }
    }

    private static func verifier(pin: String, salt: Data) -> Data {
        var output = Data(count: Int(CC_SHA256_DIGEST_LENGTH))
        let outputCount = output.count
        let status = pin.withCString { password in
            salt.withUnsafeBytes { saltBytes in
                output.withUnsafeMutableBytes { outputBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        password,
                        pin.utf8.count,
                        saltBytes.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        210_000,
                        outputBytes.bindMemory(to: UInt8.self).baseAddress,
                        outputCount
                    )
                }
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 parameters must be supported")
        return output
    }

    private static func constantTimeEqual(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        return zip(lhs, rhs).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    private func load() throws -> Record? {
        let query: [String: Any] = baseQuery.merging([
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]) { _, new in new }
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw AdminPINError.keychainFailure(status)
        }
        do {
            return try JSONDecoder().decode(Record.self, from: data)
        } catch {
            throw AdminPINError.keychainFailure(errSecDecode)
        }
    }

    private func save(_ record: Record) throws {
        let data: Data
        do {
            data = try JSONEncoder().encode(record)
        } catch {
            throw AdminPINError.keychainFailure(errSecParam)
        }
        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw AdminPINError.keychainFailure(updateStatus)
        }
        let add = baseQuery.merging([
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecAttrSynchronizable as String: false,
        ]) { _, new in new }
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw AdminPINError.keychainFailure(addStatus)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
