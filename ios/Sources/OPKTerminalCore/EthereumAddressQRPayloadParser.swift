// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation

public enum EthereumAddressQRPayloadError: Error, Equatable, Sendable {
    case emptyPayload
    case unsupportedScheme
    case malformedURI
    case invalidAddress
}

/// Extracts a non-zero Ethereum address from a QR payload.
///
/// Only raw addresses and address-only `ethereum:` URIs (with one optional
/// `//`) are accepted. Payment URIs are rejected so a token target cannot be
/// silently imported into an unrelated contract field.
public enum EthereumAddressQRPayloadParser {
    public static func parse(_ payload: String) throws -> EthereumAddress {
        guard !payload.isEmpty else { throw EthereumAddressQRPayloadError.emptyPayload }

        if let separator = payload.firstIndex(of: ":") {
            let scheme = String(payload[..<separator])
            guard scheme.caseInsensitiveCompare("ethereum") == .orderedSame else {
                throw EthereumAddressQRPayloadError.unsupportedScheme
            }
            var body = String(payload[payload.index(after: separator)...])
            if body.hasPrefix("//") {
                body.removeFirst(2)
            }
            guard !body.isEmpty,
                  !body.contains("@"),
                  !body.contains("/"),
                  !body.contains("?"),
                  !body.contains("#")
            else {
                throw EthereumAddressQRPayloadError.malformedURI
            }
            return try parseAddress(body)
        }

        return try parseAddress(payload)
    }

    private static func parseAddress(_ value: String) throws -> EthereumAddress {
        do {
            return try EthereumAddress(hex: value, allowZero: false)
        } catch {
            throw EthereumAddressQRPayloadError.invalidAddress
        }
    }
}
