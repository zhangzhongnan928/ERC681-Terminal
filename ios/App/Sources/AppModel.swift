import Foundation
import OPKTerminalCore
import OPKTerminalRPC
import SwiftData

@MainActor
final class AppModel: ObservableObject {
    @Published var settings: AppSettings {
        didSet { AppPreferences.saveSettings(settings) }
    }
    @Published private(set) var validationMessage = "Not validated"
    @Published private(set) var activeRequest: PaymentRequest?
    @Published private(set) var activeObservation: PaymentObservation?
    @Published private(set) var isBusy = false
    @Published var errorMessage: String?

    let terminalIdentifier: TerminalIdentifier
    private let container: ModelContainer
    private var monitoringTask: Task<Void, Never>?

    init(container: ModelContainer) {
        self.container = container
        settings = AppPreferences.loadSettings()
        terminalIdentifier = AppPreferences.terminalIdentifier()
    }

    func validateConfiguration() async -> Bool {
        isBusy = true
        defer { isBusy = false }
        do {
            let configuration = try settings.configuration()
            try await validate(configuration)
            errorMessage = nil
            return true
        } catch {
            validationMessage = "Validation failed"
            errorMessage = error.localizedDescription
            return false
        }
    }

    func createSale(displayAmount: String) async {
        isBusy = true
        defer { isBusy = false }
        do {
            // Capture once so the exact configuration used for derivation is the one validated.
            let configuration = try settings.configuration()
            try await validate(configuration)
            let token = configuration.tokens[0]
            let amount = try TokenAmount(display: displayAmount, decimals: token.decimals).rawValue
            let request = try InvoiceFactory.create(
                terminalIdentifier: terminalIdentifier,
                amount: amount,
                token: token,
                configuration: configuration,
                expiresAt: Date().addingTimeInterval(15 * 60)
            )
            let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
            let observationBlock = try await rpc.blockNumber()
            let receiverCode = try await rpc.code(at: request.receiver, block: .number(observationBlock))
            guard receiverCode.isEmpty else { throw AppSafetyError.receiverAlreadyDeployed }
            let existingBalanceData = try await rpc.call(
                to: token.address,
                data: ABI.encodeCall(
                    selector: ABI.balanceOfSelector,
                    words: [ABI.word(request.receiver)]
                ),
                block: .number(observationBlock)
            )
            guard try ABI.decodeUInt256(existingBalanceData).isZero else {
                throw AppSafetyError.receiverAlreadyFunded
            }
            container.mainContext.insert(try StoredInvoice(request: request, configuration: configuration))
            try container.mainContext.save()
            activeRequest = request
            activeObservation = nil
            startMonitoring(request, configuration: configuration)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func closeActiveSale() {
        monitoringTask?.cancel()
        monitoringTask = nil
        if let id = activeRequest?.invoiceID.hex {
            do {
                var descriptor = FetchDescriptor<StoredInvoice>(predicate: #Predicate { $0.invoiceID == id })
                descriptor.fetchLimit = 1
                if let invoice = try container.mainContext.fetch(descriptor).first {
                    invoice.locallyClosed = true
                    if invoice.statusLabel != "Paid" && invoice.statusLabel != "Overpaid" && invoice.statusLabel != "Expired" {
                        invoice.statusLabel = "Closed"
                    }
                    try container.mainContext.save()
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
        activeRequest = nil
        activeObservation = nil
    }

    func reconcileForegroundInvoices() async {
        do {
            let invoices = try container.mainContext.fetch(
                FetchDescriptor<StoredInvoice>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)])
            )
            let open = invoices.filter {
                !$0.locallyClosed
                    && $0.statusLabel != "Paid"
                    && $0.statusLabel != "Overpaid"
                    && $0.statusLabel != "Expired"
                    && $0.statusLabel != "Closed"
            }
            guard !open.isEmpty else { return }
            for invoice in open {
                let request = try invoice.paymentRequest()
                let configuration = try invoice.configurationSnapshot()
                try validateSnapshot(request, against: configuration)
                let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
                let actualChainID = try await rpc.chainID()
                guard actualChainID == configuration.chainID else {
                    throw AppSafetyError.snapshotChainMismatch(
                        expected: configuration.chainID,
                        actual: actualChainID
                    )
                }
                let monitor = PaymentMonitor(rpc: rpc, confirmationPolicy: configuration.confirmationPolicy)
                let threshold = invoice.thresholdBlock.flatMap { UInt64(exactly: $0) }
                let observation = try await monitor.sample(
                    request,
                    previousThresholdBlock: threshold
                )
                try invoice.apply(observation)
            }
            try container.mainContext.save()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func validate(_ configuration: TerminalConfiguration) async throws {
        do {
            let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
            let report = try await ConfigurationValidator(rpc: rpc).validate(configuration)
            validationMessage = "Validated \(report.checks.count) checks on chain \(report.chainID)"
        } catch {
            validationMessage = "Validation failed"
            throw error
        }
    }

    private func validateSnapshot(
        _ request: PaymentRequest,
        against configuration: TerminalConfiguration
    ) throws {
        guard request.chainID == configuration.chainID,
              request.vault == configuration.deployment.vault,
              configuration.tokens.contains(request.token)
        else { throw AppSafetyError.corruptInvoiceSnapshot }
        let expectedReceiver = try ReceiverDerivation.receiver(
            factory: configuration.deployment.factory,
            receiverImplementation: configuration.deployment.receiverImplementation,
            vault: configuration.deployment.vault,
            invoiceID: request.invoiceID
        )
        guard expectedReceiver == request.receiver else {
            throw AppSafetyError.corruptInvoiceSnapshot
        }
    }

    private func startMonitoring(_ request: PaymentRequest, configuration: TerminalConfiguration) {
        monitoringTask?.cancel()
        monitoringTask = Task { [weak self] in
            guard let self else { return }
            do {
                let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
                let monitor = PaymentMonitor(rpc: rpc, confirmationPolicy: configuration.confirmationPolicy)
                for try await observation in monitor.observations(for: request) {
                    guard !Task.isCancelled else { return }
                    self.activeObservation = observation
                    try self.persist(observation)
                }
            } catch is CancellationError {
                return
            } catch {
                self.errorMessage = error.localizedDescription
            }
        }
    }

    private func persist(_ observation: PaymentObservation) throws {
        let id = observation.invoiceID.hex
        var descriptor = FetchDescriptor<StoredInvoice>(predicate: #Predicate { $0.invoiceID == id })
        descriptor.fetchLimit = 1
        if let invoice = try container.mainContext.fetch(descriptor).first {
            try invoice.apply(observation)
            try container.mainContext.save()
        }
    }
}

private enum AppSafetyError: LocalizedError {
    case receiverAlreadyDeployed
    case receiverAlreadyFunded
    case corruptInvoiceSnapshot
    case snapshotChainMismatch(expected: UInt64, actual: UInt64)

    var errorDescription: String? {
        switch self {
        case .receiverAlreadyDeployed:
            "The newly derived receiver already has contract code. No QR was created."
        case .receiverAlreadyFunded:
            "The newly derived receiver already has a token balance. No QR was created."
        case .corruptInvoiceSnapshot:
            "A stored invoice no longer matches its saved network configuration. It was not monitored."
        case let .snapshotChainMismatch(expected, actual):
            "A stored invoice expects chain \(expected), but its saved RPC reported chain \(actual). It was not monitored."
        }
    }
}
