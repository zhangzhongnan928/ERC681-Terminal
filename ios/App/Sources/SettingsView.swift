import OPKTerminalCore
import SwiftUI
import UIKit

private enum SettingsFocusField: Hashable {
    case rpcURL
    case chainID
    case factory
    case receiverImplementation
    case vault
    case tokenAddress
    case tokenSymbol
    case tokenDecimals
    case confirmationBlocks
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var didCopyOperator = false
    @FocusState private var focusedField: SettingsFocusField?

    var body: some View {
        NavigationStack {
            Form {
                Section("Network") {
                    TextField("RPC URL", text: $model.settings.rpcURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focusedField, equals: .rpcURL)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .chainID }
                        .accessibilityLabel("RPC URL")
                    TextField("Chain ID", text: $model.settings.chainID)
                        .keyboardType(.numberPad)
                        .focused($focusedField, equals: .chainID)
                        .accessibilityLabel("Chain ID")
                    LabeledContent("Protocol", value: "1.4.1 (deployed)")
                }

                Section("Contracts") {
                    AddressField(
                        "Factory",
                        text: $model.settings.factory,
                        focus: $focusedField,
                        field: .factory,
                        onSubmit: { focusedField = .receiverImplementation }
                    )
                    AddressField(
                        "Receiver implementation",
                        text: $model.settings.receiverImplementation,
                        focus: $focusedField,
                        field: .receiverImplementation,
                        onSubmit: { focusedField = .vault }
                    )
                    AddressField(
                        "Vault",
                        text: $model.settings.vault,
                        focus: $focusedField,
                        field: .vault,
                        onSubmit: { focusedField = .tokenAddress }
                    )
                }

                Section("Payment token") {
                    AddressField(
                        "Token",
                        text: $model.settings.tokenAddress,
                        focus: $focusedField,
                        field: .tokenAddress,
                        onSubmit: { focusedField = .tokenSymbol }
                    )
                    TextField("Symbol", text: $model.settings.tokenSymbol)
                        .focused($focusedField, equals: .tokenSymbol)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .tokenDecimals }
                        .accessibilityLabel("Token symbol")
                    TextField("Decimals", text: $model.settings.tokenDecimals)
                        .keyboardType(.numberPad)
                        .focused($focusedField, equals: .tokenDecimals)
                        .accessibilityLabel("Token decimals")
                    TextField("Confirmation blocks", text: $model.settings.confirmationBlocks)
                        .keyboardType(.numberPad)
                        .focused($focusedField, equals: .confirmationBlocks)
                        .accessibilityLabel("Confirmation blocks")
                }

                Section("Terminal operator wallet") {
                    if let address = model.operatorAddress {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Terminal identity and settlement EOA")
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

                            Label(
                                "This public address identifies every new invoice. Vault authorization is checked separately before settlement.",
                                systemImage: "person.text.rectangle.fill"
                            )
                            .font(.footnote)
                            .foregroundStyle(.secondary)

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
                                focusedField = nil
                                Task { await model.refreshOperatorStatus() }
                            } label: {
                                Label("Refresh balance and authorization", systemImage: "arrow.clockwise")
                            }
                            .disabled(model.settlementBusy)

                            Text("Send ETH for gas to this operator address. Its secp256k1 private key is non-syncing Keychain data and every settlement signature requires device authentication.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Create the device-local secp256k1 wallet used as the terminal identity for new invoices and to authorize zero-value sweep transactions. Historical invoices remain available.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)

                            Button {
                                focusedField = nil
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
                        focusedField = nil
                        Task { _ = await model.validateConfiguration() }
                    } label: {
                        Label("Validate configuration", systemImage: "checkmark.shield")
                    }
                    .disabled(model.isBusy)
                    Text(model.validationMessage)
                        .foregroundStyle(.secondary)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Settings")
            .toolbar {
                KeyboardDismissToolbar {
                    focusedField = nil
                }
            }
        }
        .onDisappear {
            focusedField = nil
        }
    }
}

private struct AddressField: View {
    let label: String
    @Binding var text: String
    let focus: FocusState<SettingsFocusField?>.Binding
    let field: SettingsFocusField
    let onSubmit: () -> Void
    @State private var isPresentingScanner = false

    init(
        _ label: String,
        text: Binding<String>,
        focus: FocusState<SettingsFocusField?>.Binding,
        field: SettingsFocusField,
        onSubmit: @escaping () -> Void
    ) {
        self.label = label
        _text = text
        self.focus = focus
        self.field = field
        self.onSubmit = onSubmit
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
                    .focused(focus, equals: field)
                    .submitLabel(.next)
                    .onSubmit(onSubmit)
                    .accessibilityLabel("\(label) address")

                Button {
                    focus.wrappedValue = nil
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
