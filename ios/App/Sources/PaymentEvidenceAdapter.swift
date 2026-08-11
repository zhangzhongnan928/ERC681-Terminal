import Foundation
import OPKTerminalCore
import OPKTerminalRPC

/// The SDK remains request-scoped. AppModel associates the result with one invoice ID and persists
/// it only while that invoice still has the exact funding cursor used by the request.
typealias AppPaymentEvidenceResolving = @Sendable (
    _ request: PaymentEvidenceRequest,
    _ configuration: TerminalConfiguration
) async throws -> PaymentTransactionEvidence?

enum AppPaymentEvidenceResolver {
    static func resolve(
        _ request: PaymentEvidenceRequest,
        configuration: TerminalConfiguration
    ) async throws -> PaymentTransactionEvidence? {
        let rpc = try EthereumRPCClientPool.shared.client(
            for: configuration.rpcEndpoints[0]
        )
        return try await PaymentTransactionResolver(client: rpc).resolve(request)
    }
}

extension PaymentEvidenceResolutionError {
    var isDefinitiveStoredEvidenceInvalidation: Bool {
        switch self {
        case .canonicalBlockChanged, .publicationAlreadyFunded:
            true
        case .wrongChain,
             .blockNumberMismatch,
             .removedTransferLog,
             .transferLogMismatch,
             .nativeTransactionMismatch,
             .duplicateTransferLogIndex,
             .duplicateNativeTransactionIndex,
             .amountOverflow:
            false
        }
    }
}
