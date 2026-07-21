import Foundation

/// The immutable route and confirmation policy shared by every invoice in one settlement batch.
/// The Settlement view groups by this key, and AppModel checks the same key again before any RPC
/// validation, simulation, or signing preparation.
struct InvoiceSettlementGroupKey: Hashable {
    let chainID: Int64
    let rpcURL: String
    let protocolVersion: String
    let factory: String
    let receiverImplementation: String
    let vault: String
    let tokenAddress: String
    let tokenSymbol: String
    let tokenDecimals: Int
    let confirmationBlocks: Int64

    init(_ invoice: StoredInvoice) {
        chainID = invoice.chainID
        rpcURL = invoice.rpcURL
        protocolVersion = invoice.protocolVersion
        factory = invoice.factory.lowercased()
        receiverImplementation = invoice.receiverImplementation.lowercased()
        vault = invoice.vault.lowercased()
        tokenAddress = invoice.tokenAddress.lowercased()
        tokenSymbol = invoice.tokenSymbol
        tokenDecimals = invoice.tokenDecimals
        confirmationBlocks = invoice.confirmationBlocks
    }

    fileprivate var sortKey: String {
        [
            String(chainID), rpcURL, protocolVersion, factory,
            receiverImplementation, vault, tokenAddress, tokenSymbol,
            String(tokenDecimals), String(confirmationBlocks),
        ].joined(separator: "\u{0}")
    }
}

func settlementBatchSnapshotsMatch(_ invoices: [StoredInvoice]) -> Bool {
    guard let first = invoices.first else { return false }
    let expected = InvoiceSettlementGroupKey(first)
    return invoices.allSatisfy { InvoiceSettlementGroupKey($0) == expected }
}

func groupedSettlementInvoices(
    _ invoices: [StoredInvoice]
) -> [(key: InvoiceSettlementGroupKey, invoices: [StoredInvoice])] {
    Dictionary(grouping: invoices, by: InvoiceSettlementGroupKey.init)
        .map { (key: $0.key, invoices: $0.value) }
        .sorted { $0.key.sortKey < $1.key.sortKey }
}
