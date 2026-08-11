import Foundation
import OPKTerminalCore

enum BaseScanExplorerError: LocalizedError, Equatable {
    case invalidTransactionHash
    case unsupportedChain(UInt64)

    var errorDescription: String? {
        switch self {
        case .invalidTransactionHash:
            "Payment transaction hash is not canonical."
        case let .unsupportedChain(chainID):
            "BaseScan is unavailable for chain \(chainID)."
        }
    }
}

enum BaseScanExplorer {
    static func transactionURL(chainID: UInt64, hash: String) throws -> URL {
        guard (try? Bytes32(hex: hash)) != nil else {
            throw BaseScanExplorerError.invalidTransactionHash
        }
        let origin: String
        switch chainID {
        case 8_453:
            origin = "https://basescan.org"
        case 84_532:
            origin = "https://sepolia.basescan.org"
        default:
            throw BaseScanExplorerError.unsupportedChain(chainID)
        }
        guard let url = URL(string: "\(origin)/tx/\(hash.lowercased())") else {
            throw BaseScanExplorerError.invalidTransactionHash
        }
        return url
    }
}
