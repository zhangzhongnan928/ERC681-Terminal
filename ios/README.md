# OPK ERC-681 Terminal for iOS

This directory contains a keyless, read-only Swift payment SDK and a SwiftUI terminal app for
ERC-20 payments presented as canonical ERC-681 QR codes. The app stores a catalog of EVM payment
profiles and chooses one network/vault/token profile per invoice. Its separately isolated operator
module manages one device-local settlement key and can sign only constrained `sweepSessions`
transactions; the payment Core and RPC products do not handle keys or submit transactions. There
is no customer payment-QR importing, NFC, or CoreNFC capability. Camera access is limited to
importing contract and token addresses in the Settings UI; payment/function URIs and other
non-address payloads fail closed. The Swift package libraries remain camera-free.

## Components

- `OPKTerminalCore`: validated EVM values, exact raw-unit amounts, Ethereum Keccak-256, read-only
  ABI helpers, invoice IDs, local CREATE2 receiver derivation, strict ERC-681, cross-network
  payment-profile catalogs, payment models, and metadata-only settlement handoff.
- `OPKTerminalRPC`: read-only JSON-RPC methods, chain/contract/token configuration validation, and
  block-confirmed balance observation.
- `OPKTerminalOperator`: device-local secp256k1 key storage, constrained settlement transaction
  construction and submission, and confirmed `Swept`-event verification.
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

The sample defaults to the Base Sepolia v1.5 deployment trust anchors:

- Chain ID: `84532`
- Factory: `0xb69f725999266c6757284ca4169275c3ebde491a`
- Receiver implementation: `0x8ba9739741ecc79b5d69fe5580d2966092e6f77f`
- Deployed vault-proxy runtime hash: `0x2ceea713f7225b17e43487b8652d8582dadd5aabefc5b9f78d231777958655b9`
- CREATE2 example vault: `0x1111111111111111111111111111111111111111`
- AUD token: `0x7ffba642bc902880a737cb1c18a4e9540879e211`, 18 decimals

The proxy hash is over exact on-chain bytecode with the Base Sepolia beacon immutable embedded;
the zero-immutable artifact hash emitted by the upstream browser deployer is not valid for raw
`eth_getCode` verification.

The example vault is a deterministic off-chain vector, not a deployed merchant vault. The app
remains unprovisioned until a portal QR identifies a live v1.5 vault and whitelisted token that pass
the complete on-chain validation flow.

Base Sepolia is the only network enabled in the production app in this release. The Swift
payment-profile catalog remains EVM-generic, but Base Mainnet (`8453`) and other chains are rejected
before RPC use. Mainnet v1.5 is deployed but remains disabled pending explicit product enablement,
a reviewed operational RPC policy, and addition of its deployment pins, CREATE2 vector, finality
policy, native-currency metadata, and operator gas reserve to the immutable app registry. Multiple vault/token profiles
can coexist, while one selected profile supplies the exact configuration snapshot saved with each
invoice and validated before presenting a QR. RPC
URLs must use HTTPS, except loopback HTTP for local development, and embedded URL credentials and
fragments are rejected.

One payment profile binds exactly one EVM chain, vault, and ERC-20 token. Up to 32 canonical
profiles can be added or updated by scanning existing provisioning-v1 QRs; scanning never replaces
unrelated profiles. Checkout selects one profile for one invoice, so a payment never mixes tokens
or networks. Profile identity is `eip155:<chain>:<vault>:<token>`, not the token symbol. Admin can
remove a local profile with confirmation without rewriting immutable invoice or settlement
history. The SDK's `TerminalPaymentProfileCatalog` provides matching select/upsert/remove behavior.

Each address row keeps its label visible and offers a Settings-only scan button. The scanner accepts
a raw non-zero EVM address or an address-only `ethereum:` URI; it rejects customer payment requests
without importing or acting on them. A denied camera permission, cancellation, or camera-less device leaves manual entry
available. Imported addresses remain subject to the same local and on-chain configuration checks.

The app refuses a newly derived receiver if it already has code or a token balance at the sampled
block. Paid, overpaid, expired, and locally closed invoices never render a payable QR, including in
History. Expiry closes zero-balance and partially funded requests alike.

The operator wallet must exist before the app can present a new payment QR. Its public EOA address
is the terminal identity supplied as `terminalIdentifier` for every new invoice. Vault
authorization and native-token gas funding are checked separately before settlement; they are not
inputs to invoice or receiver derivation. Readiness is refreshed for the selected profile whenever
the merchant switches currency/network. The same EOA is authorized per vault and funded per chain
to that network's compiled minimum reserve (`0.0001 ETH` on Base Sepolia; future enabled networks
may use a different native currency, decimals, and reserve).
Destructive reset checks native balances on every network supported by the app, including a network
whose last local profile was removed. Funded and unreachable-network reset failures name the
network and chain ID. The full operator address remains available in Settings with copy and
address-only QR controls.

Upgraded installations preserve each existing invoice's original terminal identifier, invoice ID,
configuration snapshot, and derived receiver. Those historical records are not rewritten, and any
currently authorized vault owner or operator can still sweep their receivers. The app does not use
or display a global legacy random identifier when creating new invoices.
