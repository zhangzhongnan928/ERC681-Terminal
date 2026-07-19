# OPK ERC-681 Terminal for iOS

This directory contains a keyless, read-only Swift SDK and a SwiftUI terminal app source for
ERC-20 payments presented as canonical ERC-681 QR codes. It deliberately contains no private-key
handling, transaction signing, transaction submission, settlement controls, customer payment-QR
importing, NFC, or CoreNFC capability. Camera access is limited to importing contract and token
addresses in the Settings UI; payment/function URIs and other non-address payloads fail closed.
The Swift package libraries remain camera-free.

## Components

- `OPKTerminalCore`: validated EVM values, exact raw-unit amounts, Ethereum Keccak-256, read-only
  ABI helpers, invoice IDs, local CREATE2 receiver derivation, strict ERC-681, payment models, and
  metadata-only settlement handoff.
- `OPKTerminalRPC`: read-only JSON-RPC methods, chain/contract/token configuration validation, and
  block-confirmed balance observation.
- `OPKTerminalConformance`: dependency-free executable checks for the shared Android/iOS golden
  vectors and mocked read-only JSON-RPC behavior.
- `App`: iOS/iPadOS 17 SwiftUI source using SwiftData, Core Image QR rendering with a four-module
  quiet zone, configuration-address QR import, history, settings, and foreground reconciliation.

The package libraries support iOS 16+ and macOS 13+. The sample app targets iOS/iPadOS 17+.

## Verify the package

From this directory:

```sh
swift build
swift run OPKTerminalConformance
swift test
```

The conformance executable loads `../conformance/opk-erc681-v1.json`, compares every shared golden
value, exercises mocked read-only RPC, and verifies that an underfunded invoice expires and ends its
observation stream. XCTest suites live under `Tests/`; select a full Xcode installation with
`xcode-select` if the active developer tools do not include the `xctest` runner.

## Generate and build the app

An Xcode project is included. `project.yml` is its reproducible XcodeGen specification:

```sh
xcodegen generate --spec project.yml
xcodebuild \
  -project OPKTerminal.xcodeproj \
  -scheme OPKTerminalApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  build
```

XcodeGen regeneration is required after app source or project-spec changes so the included project
cannot silently omit a scanner source. Full Xcode, an installed iOS SDK, and an available simulator
are required for the app build. Device/archive builds additionally require an Apple development
team and signing assets. Successful scanning and camera permission grant/deny behavior require a
physical-device test before release; manual entry remains available when camera access is denied.

## Configuration and safety

The sample defaults to the currently deployed Base Sepolia v1.4.1-compatible stack:

- Chain ID: `84532`
- Factory: `0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5`
- Receiver implementation: `0xdaa292b1bf533737c5ce5d27f220273971db3bdc`
- Test vault: `0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1`
- AUD token: `0x7ffba642bc902880a737cb1c18a4e9540879e211`, 18 decimals

No Base-mainnet or v1.5 deployment configuration is shipped or selectable in the sample app. The
exact configuration snapshot is saved with each invoice and validated before presenting a QR. RPC
URLs must use HTTPS, except loopback HTTP for local development, and embedded URL credentials and
fragments are rejected.

Each address row keeps its label visible and offers a Settings-only scan button. The scanner accepts
a raw non-zero EVM address or an address-only `ethereum:` URI; it rejects customer payment requests
without importing or acting on them. A denied camera permission, cancellation, or camera-less device leaves manual entry
available. Imported addresses remain subject to the same local and on-chain configuration checks.

The app refuses a newly derived receiver if it already has code or a token balance at the sampled
block. Paid, overpaid, expired, and locally closed invoices never render a payable QR, including in
History. Expiry closes zero-balance and partially funded requests alike.

The terminal identifier saved in `UserDefaults` is public, random uniqueness material for invoice
IDs. Settings shows its full value with copy and QR controls, but it is not an Ethereum account,
wallet, payment receiver, or signing key and must never be funded.
