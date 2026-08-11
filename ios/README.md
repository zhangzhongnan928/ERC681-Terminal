# OPK ERC-681 Terminal for iOS

This directory contains a keyless, read-only Swift payment SDK and a SwiftUI terminal app for
ERC-20 and OPK Protocol 1.6 chain-native payments presented as canonical ERC-681 QR codes. The app
stores a catalog of EVM payment profiles and chooses one network/vault/payment-asset profile per
invoice. Its separately isolated operator
module manages one device-local settlement key and can sign only constrained `sweepSessions`
transactions; the payment Core and RPC products do not handle keys or submit transactions. There
is no customer payment-QR importing, NFC, or CoreNFC capability. Camera access is limited to
importing contract and payment-asset addresses in the Settings UI; payment/function URIs and other
non-address payloads fail closed. The Swift package libraries remain camera-free.

## Components

- `OPKTerminalCore`: validated EVM values, exact raw-unit amounts, Ethereum Keccak-256, read-only
  ABI helpers, invoice IDs, local CREATE2 receiver derivation, strict ERC-681, cross-network
  payment-profile catalogs, payment models, and metadata-only settlement handoff.
- `OPKTerminalRPC`: read-only JSON-RPC methods, chain/contract/payment-asset configuration
  validation, and block-confirmed ERC-20 or native balance observation.
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

Fresh, cleared, and recovered-unreadable setup defaults to the published Base Mainnet OPK Protocol
1.6 Route A deployment trust anchors:

- Chain ID: `8453`
- Factory: `0x5418ab1790eaf96a20e26146c5b7765cb99328da`
- Receiver implementation: `0xe6393f6176865cc62cd08d8b8f0c38d35af55254`
- Deployed vault-proxy runtime hash: `0x8c3a56b5606e44613d50c898acf67a3689afc478b47e9a38326699b0df111cbd`
- CREATE2 example vault: `0x1111111111111111111111111111111111111111`
- Native asset identifier: `0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE` (`ETH`, 18 decimals)

The proxy hash is over exact on-chain bytecode with the network's beacon immutable embedded. The
zero-immutable artifact hash emitted by the upstream browser deployer is not valid for raw
`eth_getCode` verification. Base Sepolia remains available as an explicit testnet choice in
protected Advanced manual setup and retains its own independent pins and CREATE2 vector.

The example vault is a deterministic off-chain vector, not a deployed merchant vault. The app
remains unprovisioned until a portal QR identifies a compatible live vault and whitelisted payment
asset that pass the complete on-chain validation flow. The shipped profile uses protocol version
1.6 for every route. A native route is enabled only after the vault successfully answers
`NATIVE_ASSET()` with the exact sentinel and whitelists it; the whitelist read alone is not the
capability proof.

Base Mainnet (`8453`) and Base Sepolia (`84532`) are enabled in this release. The Swift
payment-profile catalog remains EVM-generic, but every other chain is rejected before RPC use.
Existing provisioned profiles and immutable invoice history are never translated between chains.
The compiled public Base RPC endpoints establish network identity but are rate-limited and are not
production-capacity guarantees, so a reviewed operational provider should be provisioned for live
merchant volume. Multiple vault/payment-asset profiles
can coexist, while one selected profile supplies the exact configuration snapshot saved with each
invoice and validated before presenting a QR. RPC
URLs must use HTTPS, except loopback HTTP for local development, and embedded URL credentials and
fragments are rejected.

One payment profile binds exactly one EVM chain, vault, and ERC-20 or native payment asset. Up to 32
canonical
profiles can be added or updated by scanning existing provisioning-v1 QRs; scanning never replaces
unrelated profiles. Checkout selects one profile for one invoice, so a payment never mixes assets
or networks. Profile identity is `eip155:<chain>:<vault>:<token>`, not the displayed symbol. Admin can
remove a local profile with confirmation without rewriting immutable invoice or settlement
history. The SDK's `TerminalPaymentProfileCatalog` provides matching select/upsert/remove behavior.

Each address row keeps its label visible and offers a Settings-only scan button. The scanner accepts
a raw non-zero EVM address or an address-only `ethereum:` URI; it rejects customer payment requests
without importing or acting on them. A denied camera permission, cancellation, or camera-less device leaves manual entry
available. Imported addresses remain subject to the same local and on-chain configuration checks.

The app refuses a newly derived receiver if it already has code or a payment-asset balance at the
sampled block. It reads ERC-20 balances with `balanceOf` and native balances with
`eth_getBalance`, which also works before the counterfactual receiver is deployed. Paid, overpaid,
expired, and locally closed invoices never render a payable QR, including in History. Expiry
closes zero-balance and partially funded requests alike.

ERC-20 invoices use the canonical `transfer(address,uint256)` ERC-681 form. Native invoices use
the plain value-transfer form `ethereum:{receiver}@{chainId}?value={amountWei}`. The sentinel is an
on-chain identifier and never appears in the customer QR. A fresh 1.6 stack must publish and pin
its own factory, receiver implementation, runtime hashes, and CREATE2 vector: changing the factory
or receiver implementation changes every derived receiver address. Only an in-place beacon
upgrade of the existing stack can preserve those commitments.

The operator wallet must exist before the app can present a new payment QR. Its public EOA address
is the terminal identity supplied as `terminalIdentifier` for every new invoice. Vault
authorization and native-token gas funding are checked separately before settlement; they are not
inputs to invoice or receiver derivation. Readiness is refreshed for the selected profile whenever
the merchant switches currency/network. The same EOA is authorized per vault and funded per chain
to that network's compiled minimum reserve (`0.0001 ETH` on Base Mainnet and Base Sepolia).
Destructive reset checks native balances on every network supported by the app, including a network
whose last local profile was removed. Funded and unreachable-network reset failures name the
network and chain ID. The full operator address remains available in Settings with copy and
address-only QR controls.

Upgraded installations preserve each existing invoice's original terminal identifier, invoice ID,
configuration snapshot, and derived receiver. Those historical records are not rewritten, and any
currently authorized vault owner or operator can still sweep their receivers. The app does not use
or display a global legacy random identifier when creating new invoices.
