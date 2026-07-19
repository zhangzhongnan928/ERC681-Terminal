import OPKTerminalCore
import OPKTerminalOperator
import SwiftData
import SwiftUI

struct SettlementView: View {
    @EnvironmentObject private var model: AppModel
    @Query(sort: \StoredInvoice.createdAt, order: .reverse) private var invoices: [StoredInvoice]
    @Query(sort: \StoredSettlement.createdAt, order: .reverse) private var settlements: [StoredSettlement]

    var body: some View {
        NavigationStack {
            List {
                operatorSection

                if groups.isEmpty {
                    Section {
                        ContentUnavailableView(
                            "No paid sessions to settle",
                            systemImage: "checkmark.circle",
                            description: Text("Paid and overpaid invoices appear here after confirmation. Failed or confirmed-partial sweeps can be retried when the receiver has a new nonzero balance.")
                        )
                    }
                } else {
                    ForEach(groups) { group in
                        Section {
                            Button {
                                Task {
                                    await model.prepareSettlement(
                                        for: Array(group.invoices.prefix(20))
                                    )
                                }
                            } label: {
                                Label(
                                    "Prepare batch (\(min(group.invoices.count, 20)))",
                                    systemImage: "square.stack.3d.up.fill"
                                )
                            }
                            .disabled(model.operatorAddress == nil || model.settlementBusy)

                            ForEach(group.invoices) { invoice in
                                HStack(spacing: 12) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(invoice.formattedAmount)
                                            .font(.headline)
                                        Text(abbreviateSettlement(invoice.receiver))
                                            .font(.system(.caption, design: .monospaced))
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Button("Settle") {
                                        Task { await model.prepareSettlement(for: [invoice]) }
                                    }
                                    .buttonStyle(.bordered)
                                    .disabled(model.operatorAddress == nil || model.settlementBusy)
                                }
                            }
                        } header: {
                            Text("\(group.symbol) · chain \(group.chainID)")
                        } footer: {
                            if group.invoices.count > 20 {
                                Text("Batches are capped at 20; the first 20 are prepared together.")
                            }
                        }
                    }
                }

                if !settlements.isEmpty {
                    Section("Settlement transactions") {
                        ForEach(settlements.prefix(20)) { settlement in
                            VStack(alignment: .leading, spacing: 5) {
                                HStack {
                                    Text("\(settlement.invoiceCount) \(settlement.tokenSymbol) session\(settlement.invoiceCount == 1 ? "" : "s")")
                                        .font(.headline)
                                    Spacer()
                                    Text(settlement.statusLabel)
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(statusColor(settlement.phase))
                                }
                                Text(abbreviateSettlement(settlement.transactionHash))
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                if let message = settlement.failureReason ?? settlement.broadcastError {
                                    Text(message)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(3)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settlement")
            .refreshable {
                await model.reconcileSettlements()
                await model.refreshOperatorStatus()
            }
            .task {
                await model.reconcileSettlements()
                await model.refreshOperatorStatus()
            }
            .sheet(isPresented: preparedBinding) {
                if let prepared = model.preparedSettlement {
                    SettlementConfirmationView(prepared: prepared)
                        .environmentObject(model)
                }
            }
        }
    }

    @ViewBuilder
    private var operatorSection: some View {
        Section("Operator") {
            if let address = model.operatorAddress {
                LabeledContent("Wallet", value: abbreviateSettlement(address.hex))
                if let status = model.operatorStatus {
                    LabeledContent(
                        "Gas",
                        value: "\(TokenAmount(rawValue: status.balance, decimals: 18).displayString()) ETH"
                    )
                    Label(
                        status.isAuthorizedOperator ? "Vault authorized" : "Vault authorization required",
                        systemImage: status.isAuthorizedOperator ? "checkmark.shield" : "xmark.shield"
                    )
                    .foregroundStyle(status.isAuthorizedOperator ? .green : .red)
                    if status.isLowGas {
                        Label("Fund the operator wallet with ETH", systemImage: "fuelpump")
                            .foregroundStyle(.orange)
                    }
                } else if let message = model.operatorStatusMessage {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                Label("Create the operator wallet in Settings", systemImage: "key")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var groups: [InvoiceSettlementGroup] {
        let activeIDs = Set(
            settlements
                .filter(\.isActiveClaim)
                .flatMap(\.invoiceIDs)
        )
        let eligible = invoices.filter {
            ($0.statusLabel == "Paid" || $0.statusLabel == "Overpaid")
                && !activeIDs.contains($0.invoiceID)
        }
        return Dictionary(grouping: eligible) {
            "\($0.chainID)|\($0.rpcURL)|\($0.vault.lowercased())|\($0.tokenAddress.lowercased())"
        }
        .map { key, values in
            InvoiceSettlementGroup(
                id: key,
                symbol: values[0].tokenSymbol,
                chainID: values[0].chainID,
                invoices: values
            )
        }
        .sorted { $0.id < $1.id }
    }

    private var preparedBinding: Binding<Bool> {
        Binding(
            get: { model.preparedSettlement != nil },
            set: { if !$0 { model.cancelPreparedSettlement() } }
        )
    }

    private func statusColor(_ phase: SettlementTransactionPhase) -> Color {
        switch phase {
        case .final: .green
        case .failed: .red
        case .needsReview, .unknown: .orange
        case .pending, .mined: .secondary
        }
    }
}

private struct SettlementConfirmationView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let prepared: PreparedSettlement

    var body: some View {
        NavigationStack {
            List {
                Section("Zero-value sweep") {
                    LabeledContent("Sessions", value: String(prepared.intent.sessions.count))
                    LabeledContent("Vault", value: abbreviateSettlement(prepared.intent.vault.hex))
                    LabeledContent("Token", value: abbreviateSettlement(prepared.intent.token.hex))
                    LabeledContent("Gas limit", value: String(prepared.gasLimit))
                    LabeledContent(
                        "Maximum gas reserve",
                        value: "\(TokenAmount(rawValue: prepared.maximumGasCost, decimals: 18).displayString()) ETH"
                    )
                    LabeledContent(
                        "OP L1 reserve",
                        value: "\(TokenAmount(rawValue: prepared.l1DataFeeReserve, decimals: 18).displayString()) ETH"
                    )
                }

                Section {
                    ForEach(Array(prepared.intent.sessions.enumerated()), id: \.offset) { index, session in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(abbreviateSettlement(session.receiver.hex))
                                .font(.system(.caption, design: .monospaced))
                            LabeledContent(
                                "Live balance",
                                value: formattedToken(prepared.observedTokenBalances[index])
                            )
                            LabeledContent(
                                "Immutable expected",
                                value: formattedToken(session.expectedAmount)
                            )
                            if !session.priorConfirmedSweptAmount.isZero {
                                LabeledContent(
                                    "Prior confirmed sweeps",
                                    value: formattedToken(session.priorConfirmedSweptAmount)
                                )
                            }
                        }
                    }
                } header: {
                    Text("Live receiver evidence")
                } footer: {
                    Text("Balances were read directly from the token contract and will be checked again before signing. A confirmed positive sweep below the immutable expected amount remains Needs review and can be completed by a later sweep.")
                }

                Section {
                    Button {
                        Task { await model.confirmPreparedSettlement() }
                    } label: {
                        Label("Confirm, authenticate, and broadcast", systemImage: "faceid")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.settlementBusy)
                } footer: {
                    Text("Device authentication unlocks one typed EIP-1559 sweep signature. The signed raw transaction is saved before the first broadcast and reused unchanged if RPC acceptance is ambiguous.")
                }
            }
            .navigationTitle("Confirm settlement")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        model.cancelPreparedSettlement()
                        dismiss()
                    }
                    .disabled(model.settlementBusy)
                }
            }
        }
    }

    private func formattedToken(_ amount: UInt256) -> String {
        guard let decimals = UInt8(model.settings.tokenDecimals) else {
            return amount.decimalString
        }
        return "\(TokenAmount(rawValue: amount, decimals: decimals).displayString()) \(model.settings.tokenSymbol)"
    }
}

private struct InvoiceSettlementGroup: Identifiable {
    let id: String
    let symbol: String
    let chainID: Int64
    let invoices: [StoredInvoice]
}

private func abbreviateSettlement(_ value: String) -> String {
    guard value.count > 18 else { return value }
    return "\(value.prefix(10))…\(value.suffix(6))"
}
