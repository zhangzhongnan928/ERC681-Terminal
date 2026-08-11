import Foundation

enum MerchantReceiptProfileError: LocalizedError, Equatable {
    case missingName
    case invalidName
    case nameTooLong
    case invalidABNCharacters
    case invalidABNLength
    case invalidABNChecksum

    var errorDescription: String? {
        switch self {
        case .missingName:
            "Merchant name is required."
        case .invalidName:
            "Merchant name must be a single printable line."
        case .nameTooLong:
            "Merchant name must be 64 characters or fewer."
        case .invalidABNCharacters:
            "ABN may contain digits and spaces only."
        case .invalidABNLength:
            "ABN must contain 11 digits."
        case .invalidABNChecksum:
            "Enter a valid Australian ABN."
        }
    }
}

/// Local receipt presentation metadata. Every new invoice snapshots this value so later settings
/// edits cannot alter a historical receipt.
struct MerchantReceiptProfile: Equatable, Sendable {
    static let defaultName = "OPK Terminal"
    static let maximumNameLength = 64
    static let `default` = try! MerchantReceiptProfile(name: defaultName, abn: "")

    let name: String
    let abn: String

    init(name: String, abn: String) throws {
        guard !name.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            throw MerchantReceiptProfileError.invalidName
        }
        let canonicalName = name
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        guard !canonicalName.isEmpty else { throw MerchantReceiptProfileError.missingName }
        guard canonicalName.count <= Self.maximumNameLength else {
            throw MerchantReceiptProfileError.nameTooLong
        }

        guard abn.allSatisfy({ $0 == " " || $0.isASCII && $0.isNumber }) else {
            throw MerchantReceiptProfileError.invalidABNCharacters
        }
        let digits = abn.filter { $0.isASCII && $0.isNumber }
        if digits.isEmpty {
            self.name = canonicalName
            self.abn = ""
            return
        }
        guard digits.count == 11 else { throw MerchantReceiptProfileError.invalidABNLength }
        guard Self.isValidAustralianABN(digits) else {
            throw MerchantReceiptProfileError.invalidABNChecksum
        }

        self.name = canonicalName
        self.abn = "\(digits.prefix(2)) \(digits.dropFirst(2).prefix(3)) "
            + "\(digits.dropFirst(5).prefix(3)) \(digits.suffix(3))"
    }

    static func isValidAustralianABN(_ digits: String) -> Bool {
        guard digits.count == 11,
              digits.allSatisfy({ $0.isASCII && $0.isNumber })
        else { return false }
        let weights = [10, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19]
        let values = digits.compactMap(\.wholeNumberValue)
        guard values.count == weights.count else { return false }
        let weightedSum = zip(values.indices, values).reduce(0) { partial, element in
            let (index, digit) = element
            return partial + (index == 0 ? digit - 1 : digit) * weights[index]
        }
        return weightedSum.isMultiple(of: 89)
    }
}
