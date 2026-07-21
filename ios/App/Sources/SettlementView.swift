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
                            "No funded sessions to settle",
                            systemImage: "checkmark.circle",
                            description: Text("Invoices appear only after the currently sweepable receiver balance reaches the saved confirmation requirement. This includes confirmed late payments to closed or expired QRs.")
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
                            .disabled(model.operatorAddress == nil || model.operationBusy)

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
                                    .disabled(model.operatorAddress == nil || model.operationBusy)
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
                                        .foregroundStyle(statusColor(settlement))
                                }
                                Text(abbreviateSettlement(settlement.transactionHash))
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                if let message = settlement.cumulativeEvidenceLastError
                                    ?? settlement.cumulativeReviewLastError
                                    ?? settlement.failureReason
                                    ?? settlement.broadcastError {
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
                await model.reconcileForegroundInvoices()
                await model.reconcileSettlements()
                await model.refreshOperatorStatus()
            }
            .task {
                await model.reconcileSettlementsOnAppearance()
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
                        value: "\(TokenAmount(rawValue: status.balance, decimals: model.settings.displayedPaymentProfile.nativeCurrencyDecimals).displayString()) \(model.settings.displayedPaymentProfile.nativeCurrencySymbol)"
                    )
                    Label(
                        status.isAuthorizedOperator ? "Vault authorized" : "Vault authorization required",
                        systemImage: status.isAuthorizedOperator ? "checkmark.shield" : "xmark.shield"
                    )
                    .foregroundStyle(status.isAuthorizedOperator ? .green : .red)
                    if status.isLowGas {
                        Label(
                            "Fund the operator wallet with \(model.settings.displayedPaymentProfile.nativeCurrencySymbol)",
                            systemImage: "fuelpump"
                        )
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
            guard !activeIDs.contains($0.invoiceID),
                  let cumulative = try? UInt256(
                    decimalString: $0.confirmedCumulativeSweptAmount
                  )
            else { return false }
            return $0.hasConfirmedSweepableFunds(
                confirmedCumulative: cumulative
            )
        }
        return groupedSettlementInvoices(eligible)
        .map { key, values in
            InvoiceSettlementGroup(
                id: key,
                symbol: values[0].tokenSymbol,
                chainID: values[0].chainID,
                invoices: values
            )
        }
    }

    private var preparedBinding: Binding<Bool> {
        Binding(
            get: { model.preparedSettlement != nil },
            set: { if !$0 { model.cancelPreparedSettlement() } }
        )
    }

    private func statusColor(_ settlement: StoredSettlement) -> Color {
        if (!settlement.cumulativeEvidenceIndexed
            && settlement.cumulativeEvidenceLastError != nil)
            || settlement.cumulativeReviewLastError != nil {
            return .orange
        }
        switch settlement.phase {
        case .final: return .green
        case .failed: return .red
        case .needsReview, .unknown: return .orange
        case .pending, .mined: return .secondary
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
                        value: formatNative(prepared.maximumGasCost)
                    )
                    LabeledContent(
                        "OP L1 reserve",
                        value: formatNative(prepared.l1DataFeeReserve)
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
                    .disabled(model.operationBusy)
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
                    .disabled(model.operationBusy)
                }
            }
        }
    }

    private func formattedToken(_ amount: UInt256) -> String {
        guard let token = model.preparedSettlementToken else {
            return amount.decimalString
        }
        return "\(TokenAmount(rawValue: amount, decimals: token.decimals).displayString()) \(token.symbol)"
    }

    private var nativeNetwork: TerminalKnownChainProfile? {
        TerminalKnownChainProfile.profile(for: prepared.intent.chainID)
    }

    private func formatNative(_ amount: UInt256) -> String {
        let decimals = nativeNetwork?.nativeCurrencyDecimals ?? 18
        let symbol = nativeNetwork?.nativeCurrencySymbol ?? "native"
        return "\(TokenAmount(rawValue: amount, decimals: decimals).displayString()) \(symbol)"
    }
}

private struct InvoiceSettlementGroup: Identifiable {
    let id: InvoiceSettlementGroupKey
    let symbol: String
    let chainID: Int64
    let invoices: [StoredInvoice]
}

private func abbreviateSettlement(_ value: String) -> String {
    guard value.count > 18 else { return value }
    return "\(value.prefix(10))…\(value.suffix(6))"
}
