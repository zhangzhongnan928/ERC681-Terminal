import OPKTerminalCore
import SwiftData
import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            NewSaleView()
                .tabItem { Label("Sale", systemImage: "qrcode") }
            HistoryView()
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .alert("OPK Terminal", isPresented: errorBinding) {
            Button("OK", role: .cancel) { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "Unknown error")
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )
    }
}

private struct NewSaleView: View {
    @EnvironmentObject private var model: AppModel
    @State private var amount = ""

    var body: some View {
        NavigationStack {
            if let request = model.activeRequest {
                PaymentPresentationView(
                    request: request,
                    observation: model.activeObservation,
                    onClose: {
                        model.closeActiveSale()
                        amount = ""
                    }
                )
            } else {
                Form {
                    Section("Amount") {
                        TextField("0.00", text: $amount)
                            .keyboardType(.decimalPad)
                            .font(.system(.title, design: .rounded, weight: .semibold))
                            .accessibilityLabel("Sale amount")
                        LabeledContent("Token", value: model.settings.tokenSymbol)
                    }

                    Section {
                        Button {
                            Task { await model.createSale(displayAmount: amount) }
                        } label: {
                            Label("Create payment QR", systemImage: "qrcode")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(amount.isEmpty || model.isBusy)
                    }

                    Section {
                        Label(model.validationMessage, systemImage: "checkmark.shield")
                            .foregroundStyle(.secondary)
                    } footer: {
                        Text("The app validates chain contracts before presenting a QR. It never holds a key or submits a transaction.")
                    }
                }
                .navigationTitle("New Sale")
            }
        }
    }
}

private struct PaymentPresentationView: View {
    let request: PaymentRequest
    let observation: PaymentObservation?
    let onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text(TokenAmount(rawValue: request.expectedAmount, decimals: request.token.decimals).displayString())
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                Text(request.token.symbol)
                    .font(.title3)
                    .foregroundStyle(.secondary)

                PaymentStatusLabel(status: observation?.status ?? .waiting)

                if shouldPresentQR {
                    QRCodeImage(payload: request.erc681URI)

                    Button {
                        UIPasteboard.general.string = request.erc681URI
                    } label: {
                        Label("Copy payment URI", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)
                } else {
                    ContentUnavailableView(
                        "QR closed",
                        systemImage: "qrcode",
                        description: Text("This payment request is no longer being presented.")
                    )
                }

                GroupBox("Payment details") {
                    VStack(alignment: .leading, spacing: 8) {
                        DetailRow(label: "Receiver", value: abbreviated(request.receiver.hex))
                        DetailRow(label: "Invoice", value: abbreviated(request.invoiceID.hex))
                        if let observation {
                            DetailRow(
                                label: "Observed",
                                value: TokenAmount(
                                    rawValue: observation.balance,
                                    decimals: request.token.decimals
                                ).displayString()
                            )
                            DetailRow(label: "Block", value: String(observation.blockNumber))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button("Close", action: onClose)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
            }
            .padding()
        }
        .navigationTitle("Payment")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private var shouldPresentQR: Bool {
        guard let status = observation?.status else { return true }
        switch status {
        case .waiting, .partial, .confirming: return true
        case .paid, .overpaid, .expired: return false
        }
    }
}

private struct PaymentStatusLabel: View {
    let status: PaymentStatus

    var body: some View {
        Label(label, systemImage: symbol)
            .font(.headline)
            .foregroundStyle(color)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(color.opacity(0.12), in: Capsule())
    }

    private var label: String {
        switch status {
        case .waiting: "Waiting for payment"
        case .partial: "Partially funded"
        case let .confirming(_, confirmations, required): "Confirming \(confirmations)/\(required)"
        case .paid: "Paid"
        case .overpaid: "Paid — overpayment"
        case .expired: "Expired"
        }
    }

    private var symbol: String {
        switch status {
        case .waiting, .confirming: "hourglass"
        case .partial: "circle.lefthalf.filled"
        case .paid, .overpaid: "checkmark.circle.fill"
        case .expired: "clock.badge.exclamationmark"
        }
    }

    private var color: Color {
        switch status {
        case .paid, .overpaid: .green
        case .partial, .confirming: .orange
        case .expired: .red
        case .waiting: .secondary
        }
    }
}

private struct HistoryView: View {
    @Query(sort: \StoredInvoice.createdAt, order: .reverse) private var invoices: [StoredInvoice]

    var body: some View {
        NavigationStack {
            List(invoices) { invoice in
                NavigationLink {
                    InvoiceDetailView(invoice: invoice)
                } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack {
                            Text(invoice.formattedAmount)
                                .font(.headline)
                            Spacer()
                            Text(invoice.statusLabel)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Text(invoice.createdAt, format: .dateTime.day().month().hour().minute())
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .overlay {
                if invoices.isEmpty {
                    ContentUnavailableView(
                        "No invoices",
                        systemImage: "clock",
                        description: Text("New payment requests appear here.")
                    )
                }
            }
            .navigationTitle("History")
        }
    }
}

private struct InvoiceDetailView: View {
    let invoice: StoredInvoice

    var body: some View {
        List {
            Section {
                LabeledContent("Amount", value: invoice.formattedAmount)
                LabeledContent("Status", value: invoice.statusLabel)
                LabeledContent("Balance", value: invoice.observedBalance)
                LabeledContent("Receiver", value: abbreviated(invoice.receiver))
                LabeledContent("Invoice", value: abbreviated(invoice.invoiceID))
            }
            Section("QR") {
                if invoice.shouldPresentQRCode {
                    QRCodeImage(payload: invoice.erc681URI, size: 240)
                        .frame(maxWidth: .infinity)
                } else {
                    ContentUnavailableView(
                        "QR closed",
                        systemImage: "qrcode",
                        description: Text("This invoice is \(invoice.statusLabel.lowercased()) and is no longer payable from the terminal.")
                    )
                }
            }
        }
        .navigationTitle("Invoice")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DetailRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.system(.body, design: .monospaced))
        }
    }
}

private func abbreviated(_ value: String) -> String {
    guard value.count > 18 else { return value }
    return "\(value.prefix(10))…\(value.suffix(6))"
}
