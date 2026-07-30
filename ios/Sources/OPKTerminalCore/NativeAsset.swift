// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation

/// OPK Protocol 1.6's EIP-7528 identifier for the current chain's native asset.
public enum NativeAsset {
    public static let sentinelHex = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"
    public static let address = try! EthereumAddress(hex: sentinelHex)
    public static let decimals: UInt8 = 18

    public static func isNative(_ address: EthereumAddress) -> Bool {
        address == self.address
    }
}

public extension PaymentToken {
    var isNativeAsset: Bool { NativeAsset.isNative(address) }
}
