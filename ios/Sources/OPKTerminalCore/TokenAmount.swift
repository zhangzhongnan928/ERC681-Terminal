// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation

public enum TokenAmountError: Error, Equatable, Sendable {
    case empty
    case invalidFormat
    case tooManyFractionDigits(maximum: Int)
    case overflow
    case zeroNotAllowed
}

public struct TokenAmount: Hashable, Sendable, Codable {
    public let rawValue: UInt256
    public let decimals: UInt8

    public init(rawValue: UInt256, decimals: UInt8) {
        self.rawValue = rawValue
        self.decimals = decimals
    }

    public init(display: String, decimals: UInt8, allowZero: Bool = false) throws {
        guard !display.isEmpty else { throw TokenAmountError.empty }
        guard display == display.trimmingCharacters(in: .whitespacesAndNewlines) else {
            throw TokenAmountError.invalidFormat
        }

        let pieces = display.split(separator: ".", omittingEmptySubsequences: false)
        guard pieces.count == 1 || pieces.count == 2,
              let whole = pieces.first,
              !whole.isEmpty,
              whole.allSatisfy({ $0 >= "0" && $0 <= "9" })
        else {
            throw TokenAmountError.invalidFormat
        }

        let fraction = pieces.count == 2 ? pieces[1] : Substring()
        if pieces.count == 2 && fraction.isEmpty {
            throw TokenAmountError.invalidFormat
        }
        guard fraction.allSatisfy({ $0 >= "0" && $0 <= "9" }) else {
            throw TokenAmountError.invalidFormat
        }
        guard fraction.count <= Int(decimals) else {
            throw TokenAmountError.tooManyFractionDigits(maximum: Int(decimals))
        }

        let paddedFraction = String(fraction) + String(repeating: "0", count: Int(decimals) - fraction.count)
        let combined = (String(whole) + paddedFraction).drop { $0 == "0" }
        do {
            rawValue = try UInt256(decimalString: combined.isEmpty ? "0" : String(combined))
        } catch UInt256Error.overflow {
            throw TokenAmountError.overflow
        } catch {
            throw TokenAmountError.invalidFormat
        }
        guard allowZero || !rawValue.isZero else { throw TokenAmountError.zeroNotAllowed }
        self.decimals = decimals
    }

    public func displayString(trimTrailingZeros: Bool = true) -> String {
        guard decimals > 0 else { return rawValue.decimalString }
        var digits = rawValue.decimalString
        let decimalCount = Int(decimals)
        if digits.count <= decimalCount {
            digits = String(repeating: "0", count: decimalCount + 1 - digits.count) + digits
        }
        let split = digits.index(digits.endIndex, offsetBy: -decimalCount)
        let whole = String(digits[..<split])
        var fraction = String(digits[split...])
        if trimTrailingZeros {
            while fraction.last == "0" { fraction.removeLast() }
        }
        return fraction.isEmpty ? whole : "\(whole).\(fraction)"
    }
}

public struct PaymentToken: Hashable, Sendable, Codable, Identifiable {
    public let address: EthereumAddress
    public let symbol: String
    public let decimals: UInt8

    public init(address: EthereumAddress, symbol: String, decimals: UInt8) throws {
        guard !address.isZero else { throw FixedBytesError.zeroAddress }
        self.address = address
        self.symbol = symbol
        self.decimals = decimals
    }

    public var id: EthereumAddress { address }
}
