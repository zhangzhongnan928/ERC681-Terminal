import OPKTerminalCore
import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var didCopyIdentifier = false
    @State private var didCopyOperator = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Network") {
                    TextField("RPC URL", text: $model.settings.rpcURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Chain ID", text: $model.settings.chainID)
                        .keyboardType(.numberPad)
                    LabeledContent("Protocol", value: "1.4.1 (deployed)")
                }

                Section("Contracts") {
                    AddressField("Factory", text: $model.settings.factory)
                    AddressField("Receiver implementation", text: $model.settings.receiverImplementation)
                    AddressField("Vault", text: $model.settings.vault)
                }

                Section("Payment token") {
                    AddressField("Token", text: $model.settings.tokenAddress)
                    TextField("Symbol", text: $model.settings.tokenSymbol)
                    TextField("Decimals", text: $model.settings.tokenDecimals)
                        .keyboardType(.numberPad)
                    TextField("Confirmation blocks", text: $model.settings.confirmationBlocks)
                        .keyboardType(.numberPad)
                }

                Section("Legacy terminal identifier") {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Legacy identifier")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Text(model.terminalIdentifier.address.hex)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)

                        Button {
                            UIPasteboard.general.string = model.terminalIdentifier.address.hex
                            didCopyIdentifier = true
                        } label: {
                            Label(
                                didCopyIdentifier ? "Identifier copied" : "Copy identifier",
                                systemImage: didCopyIdentifier ? "checkmark" : "doc.on.doc"
                            )
                        }
                        .buttonStyle(.bordered)

                        QRCodeImage(
                            payload: model.terminalIdentifier.address.hex,
                            size: 210,
                            accessibilityLabel: "Terminal identifier QR code",
                            failureDescription: "Copy the identifier instead."
                        )
                        .frame(maxWidth: .infinity)

                        Label {
                            Text("Identifier only — do not send funds here. It has no private key. Existing invoices keep this namespace; after the operator wallet is activated, new invoices use the operator address without changing historical invoices.")
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill")
                        }
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                    }
                }

                Section("Settlement operator wallet") {
                    if let address = model.operatorAddress {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Device EOA")
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Text(address.hex)
                                .font(.system(.footnote, design: .monospaced))
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)

                            Button {
                                UIPasteboard.general.string = address.hex
                                didCopyOperator = true
                            } label: {
                                Label(
                                    didCopyOperator ? "Wallet address copied" : "Copy wallet address",
                                    systemImage: didCopyOperator ? "checkmark" : "doc.on.doc"
                                )
                            }
                            .buttonStyle(.bordered)

                            QRCodeImage(
                                payload: UInt64(model.settings.chainID).flatMap { chainID in
                                    chainID > 0 ? "ethereum:\(address.hex)@\(chainID)" : nil
                                } ?? address.hex,
                                size: 210,
                                accessibilityLabel: "Settlement operator wallet QR code",
                                failureDescription: "Copy the wallet address instead."
                            )
                            .frame(maxWidth: .infinity)

                            if let activation = model.operatorActivation,
                               activation.address.lowercased() == address.hex.lowercased(),
                               activation.chainID == UInt64(model.settings.chainID),
                               activation.vault.lowercased() == model.settings.vault.lowercased() {
                                Label(
                                    "Invoice namespace activated for chain \(activation.chainID) and vault \(abbreviatedSettings(activation.vault))",
                                    systemImage: "link.circle.fill"
                                )
                                .font(.footnote)
                                .foregroundStyle(.green)
                            } else {
                                Label(
                                    "Legacy identifier remains active for new invoices until this wallet is verified as owner/operator for the current chain and vault.",
                                    systemImage: "link.badge.plus"
                                )
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            }

                            if let status = model.operatorStatus {
                                LabeledContent(
                                    "ETH balance",
                                    value: "\(TokenAmount(rawValue: status.balance, decimals: 18).displayString()) ETH"
                                )
                                Label(
                                    status.isAuthorizedOperator
                                        ? (status.isVaultOwner ? "Authorized as vault owner" : "Authorized vault operator")
                                        : "Not authorized by the configured vault",
                                    systemImage: status.isAuthorizedOperator
                                        ? "checkmark.shield.fill"
                                        : "xmark.shield.fill"
                                )
                                .foregroundStyle(status.isAuthorizedOperator ? .green : .red)

                                if status.isLowGas {
                                    Label(
                                        "Low gas balance — fund this address with ETH before settling.",
                                        systemImage: "fuelpump.fill"
                                    )
                                    .foregroundStyle(.orange)
                                }
                            } else if let message = model.operatorStatusMessage {
                                Text(message)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }

                            Button {
                                Task { await model.refreshOperatorStatus() }
                            } label: {
                                Label("Refresh balance and authorization", systemImage: "arrow.clockwise")
                            }
                            .disabled(model.settlementBusy)

                            Text("Send ETH for gas to this operator address, not to the legacy identifier. Its secp256k1 private key is non-syncing Keychain data and every settlement signature requires device authentication.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Create a new, separate secp256k1 wallet for zero-value sweep transactions. The existing random identifier is never interpreted as a key.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)

                            Button {
                                Task { await model.createOperatorWallet() }
                            } label: {
                                Label("Create operator wallet", systemImage: "key.fill")
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(model.settlementBusy)
                        }
                    }
                }

                Section {
                    Button {
                        Task { _ = await model.validateConfiguration() }
                    } label: {
                        Label("Validate configuration", systemImage: "checkmark.shield")
                    }
                    .disabled(model.isBusy)
                    Text(model.validationMessage)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }
}

private func abbreviatedSettings(_ value: String) -> String {
    guard value.count > 18 else { return value }
    return "\(value.prefix(10))…\(value.suffix(6))"
}

private struct AddressField: View {
    let label: String
    @Binding var text: String
    @State private var isPresentingScanner = false

    init(_ label: String, text: Binding<String>) {
        self.label = label
        _text = text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 10) {
                TextField("0x…", text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
                    .accessibilityLabel("\(label) address")

                Button {
                    isPresentingScanner = true
                } label: {
                    Label("Scan \(label)", systemImage: "qrcode.viewfinder")
                        .labelStyle(.iconOnly)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .accessibilityLabel("Scan \(label) address")
                .accessibilityHint("Opens the camera QR code scanner")
            }
        }
        .sheet(isPresented: $isPresentingScanner) {
            AddressScannerSheet(fieldLabel: label) { address in
                text = address.hex
            }
        }
    }
}

private struct AddressScannerSheet: View {
    let fieldLabel: String
    let onAddress: (EthereumAddress) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var scanError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                ConfigurationAddressScanner(onPayload: handlePayload)
                    .ignoresSafeArea(edges: .bottom)

                VStack(spacing: 20) {
                    Text("Scan a raw 0x address or an address-only ethereum:0x… or ethereum://0x… QR code. Payment URIs are rejected.")
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .padding(12)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))

                    Spacer()

                    RoundedRectangle(cornerRadius: 20)
                        .stroke(Color.white, style: StrokeStyle(lineWidth: 3, dash: [12, 8]))
                        .frame(width: 250, height: 250)
                        .shadow(color: .black.opacity(0.6), radius: 4)
                        .accessibilityHidden(true)

                    Spacer()

                    if let scanError {
                        Label(scanError, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                            .padding(12)
                            .background(Color.red.opacity(0.9), in: RoundedRectangle(cornerRadius: 12))
                    }
                }
                .padding()
            }
            .background(Color.black)
            .onChange(of: scanError) { _, message in
                if let message {
                    UIAccessibility.post(notification: .announcement, argument: message)
                }
            }
            .navigationTitle("Scan \(fieldLabel)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func handlePayload(_ payload: String) -> Bool {
        do {
            let address = try EthereumAddressQRPayloadParser.parse(payload)
            onAddress(address)
            dismiss()
            return true
        } catch {
            scanError = "This QR code does not contain a valid non-zero Ethereum address."
            return false
        }
    }
}
