import OPKTerminalCore
import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var didCopyIdentifier = false

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

                Section("Terminal") {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Identifier")
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
                            Text("Identifier only — do not send funds to this address. It is not a wallet, payment receiver, or signing key.")
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill")
                        }
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
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
