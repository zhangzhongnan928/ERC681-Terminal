# OPK ERC-681 mobile terminal and SDK

This mobile build accepts one payment rail: an ERC-20 `transfer` requested by an ERC-681 QR code. The Android and iOS apps create invoices, render the payment QR, and observe token balances. Their cameras can import individual contract and token addresses in Settings or scan the separate strict `opk-terminal:provision` setup payload; payment QR payloads are rejected and never imported or acted on. The reusable payment SDKs remain keyless and read-only. Each native app also has an isolated, device-local operator module that can submit only a constrained `ClearingVault.sweepSessions` transaction after payment confirmation.

## Safety boundary

The payment SDK source is intentionally limited to:

- local invoice-ID and CREATE2 receiver derivation;
- canonical ERC-681 encoding and strict parsing;
- payment QR display;
- Settings-only camera scanning for strict address fields and the separate provisioning payload;
- read-only JSON-RPC calls for chain/configuration checks and `balanceOf` observation;
- local invoice persistence and recovery; and
- a data-only handoff that a native app may pass into its isolated operator module.

There is no NFC, contactless-card path, customer payment-QR import or action, unlocked-node signing, arbitrary transaction API, seed import, or private-key export. Camera access belongs only to the native app Settings UI. Address scan buttons fill only their selected address field, while the separate setup button accepts only the exact provisioning grammar; the reusable SDKs remain camera-free. The payment SDKs cannot call `sweepSessions`, payout, refund, deploy, approve, or transfer. Do not add a private key to an app configuration or RPC URL.

The native operator implementation is a separate trust boundary. It generates one secp256k1 key on the device and restricts signing to the configured chain and vault, native value zero, the `sweepSessions(bytes32[],uint256[],address)` selector, a whitelisted token, and locally persisted paid or overpaid invoices or confirmed late value at a previously swept receiver. It cannot select a recipient or call payout, refund, rescue, approval, transfer, or deployment methods. The shipped apps require this wallet to exist before creating a payment request and use its public address as the terminal identity for every new invoice. The merchant separately authorizes that address with `grantOperator`, provisions the terminal, and pre-funds it with the network's native token before the terminal accepts a new invoice; the same checks run again before settlement.

On Android, the secp256k1 scalar is encrypted with an AES-GCM wrapping key held by Android
Keystore; only the device-bound ciphertext and IV are stored in private app preferences, and all
wallet material is excluded from backup and device transfer. StrongBox is requested when present.
The app presents a fresh biometric or device-credential prompt for each signing flow; the Keystore
wrapping key uses Android's 30-second recent-authentication window, so this is an app-flow gate and
not a transaction-bound `CryptoObject` authorization. On iOS, the scalar is
stored as a non-synchronizing `WhenUnlockedThisDeviceOnly` Keychain item protected by user
presence; every signature requires device-owner authentication. Neither platform exposes seed
import, key export, or a general-purpose signing interface to app UI.

Address-only configuration import is deliberately separate from payment parsing. It accepts exactly one
non-zero 20-byte EVM address, either as raw `0x` hexadecimal or an address-only `ethereum:` URI.
Chain-qualified, function, query, fragment, WalletConnect, HTTP, JSON, payment, and other payloads
fail closed without mutating settings. A QR cannot select a different field, supply an amount or
recipient, navigate to Payment, or invoke RPC. Camera frames are processed on-device and remain
ephemeral: the app does not log, persist, or transmit frames or rejected payloads. Imported values
still pass the same local and on-chain validation as manually entered settings before any payment
QR can be created. Denying camera access, cancelling, or using a camera-less device leaves manual
entry available. The additive setup scanner has a different exact grammar and derives factory,
receiver implementation, token decimals, and token symbol from read-only chain calls. It accepts
only immutable, app-pinned deployment profiles and atomically persists the complete configuration
after every pin, CREATE2, whitelist, metadata, and existing full-validation check succeeds. See
[PROVISIONING.md](./PROVISIONING.md) for the pairing payloads and recovery model.

## Canonical payment request

Only this exact ERC-20 function form is accepted:

```text
ethereum:{TOKEN}@{CHAIN_ID}/transfer?address={RECEIVER}&uint256={RAW_TOKEN_UNITS}
```

For the shared test vector:

```text
ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0x9107decd2cb06c57c40a663648e19cde1d52f606&uint256=12340000000000000000
```

Addresses are emitted in lower-case hexadecimal. The chain ID and positive `uint256` amount use plain base-10 integers without signs, exponents, leading zeroes, extra parameters, or parameter reordering. Native-asset `?value=` and `approve` fail closed. Pass the configured chain ID to the parser so a request for another chain also fails closed.

The amount in an ERC-681 URI is a wallet suggestion. The observer measures the actual token balance, keeps partial payments open, waits for the configured block count, and reports overpayment separately.

## Invoice lifecycle

1. Require the device operator wallet and freshly validate the RPC chain, deployed code,
   factory/implementation link, vault/factory link, token whitelist and metadata, vault
   owner/operator authorization, and the minimum native gas reserve.
2. Use the operator public address as `terminalIdentifier`, and generate
   `invoiceId = keccak256(abi.encode(terminalIdentifier, timestamp, nonce))`.
3. Derive the receiver locally with the protocol's 88-byte CREATE2 init code. Do not trust an RPC response for this address.
4. Render `erc681Uri`/`erc681URI` as a customer-facing payment QR. The configuration scanner rejects this payload without importing or acting on it.
5. Poll the token's `balanceOf(receiver)` at an explicit block.
6. Persist waiting, partial, confirming, paid, overpaid, and expired state, including both the first-detected block number and its canonical block hash. A missing or non-canonical saved hash resets confirmation depth from a fresh canonical observation. While the app is active, recover only a small least-recently-attempted batch of open invoices and likewise reconcile a small durable batch of closed, partially settled, settled, and ambiguous-review receivers. Attempt timestamps are stored before RPC work so restart or cancellation cannot repeatedly starve the tail.
7. After a paid or overpaid observation, place the invoice in the native settlement queue without destroying the confirmed payment evidence. Track newly observed post-sweep value separately, wait for confirmations, and then permit another idempotent sweep of that receiver.
8. Verify the operator address, vault authorization, chain, gas balance, invoice snapshots, receiver balances, confirmation-cursor block hashes, simulation, and gas estimate before asking the user to approve signing. Revalidate each cursor and live balance again immediately before signing.
9. Persist the signed raw transaction and locally computed hash before broadcast, then reconcile ambiguous responses or app restarts without allocating another nonce.
10. Wait for the configured confirmations, require the receipt block hash to equal the canonical block hash at that height, re-read the final head before accepting the required depth, and accept settlement only from a matching `Swept` event. A successful receipt with a missing, malformed, duplicate, zero, orphaned, or mismatched event is not settlement. Ambiguous rows remain under review after missing, zero, or partial new proof; only cumulative canonical proof covering the original expected amount clears ambiguity.

Settlement evidence is cumulative per `(chain, vault, invoiceId, token)`. Store each canonical log
once by `(transactionHash, logIndex)`, reject removed or conflicting logs, and compare the sum of
confirmed swept amounts with the invoice's immutable expected amount. This permits a later sweep to
complete a partial settlement without counting an RPC replay twice. Once cumulative proof already
covers the original invoice, a repeat settlement still requires a new positive canonical event;
historical proof must never turn a zero repeat event into proof of newly observed value.

The reusable SDKs never sweep the receiver. A native app may pass their data-only handoff into its separate approved operator module. On the shipped legacy 1.4.1 deployment, transaction receipt success alone is not proof of settlement: the app must decode a fully matching confirmed `Swept` event and record the actual amount. A future 1.5 deployment may additionally expose settlement accounting, but the bundled deployment must not assume it.

`terminalIdentifier` remains the protocol and reusable-SDK name for a generic, non-secret 20-byte
invoice namespace. The reusable SDK does not require that namespace to have a private key. The
shipped Android and iOS apps apply the stricter application policy described above: they always
pass the device operator EOA public address for new invoices and fail invoice creation unless that
wallet exists, is authorized by the configured vault, and has at least `100000000000000` wei of
native gas balance. These checks run again when preparing a settlement.

## Default network

The bundled development configuration is Base Sepolia, chain ID `84532`:

| Item | Value |
|---|---|
| Factory | `0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5` |
| Receiver implementation | `0xdaa292b1bf533737c5ce5d27f220273971db3bdc` |
| Test vault | `0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1` |
| AUD test token | `0x7ffba642bc902880a737cb1c18a4e9540879e211` (18 decimals) |

The live Base Sepolia contracts are the legacy 1.4.1-compatible deployment. The iOS app pins that configuration to protocol `1.4.1`; the Android app uses the same legacy addresses and does not claim a 1.5 deployment. The QR format and local receiver derivation are shared with 1.5, but 1.5-only settlement accounting must not be assumed on this deployment.

No Base-mainnet or protocol 1.5 deployment configuration is shipped with this mobile repository.
Do not enable mainnet until real deployment constants and a matching CREATE2 vector are published,
the upgradeable vault implementation is pinned and independently checked, and all constants are
accepted by the SDK validation path.

## Operator identity, authorization, and gas funding

Creating the operator wallet establishes the terminal identity used for new invoices; it does not
make the EOA a vault operator. The merchant must grant the displayed address with the vault's
administrative `grantOperator` flow (the vault owner itself is also accepted), scan the portal's
operator-bound provisioning QR, then transfer at least `0.0001 ETH` of native network currency to
that address for gas. Until all checks pass, the app does not create a new invoice or customer QR.
The app shows the entire address, offers Copy, and displays this address-only funding QR:

```text
ethereum:{OPERATOR_ADDRESS}@{CHAIN_ID}
```

For example, on Base Sepolia:

```text
ethereum:0x2222222222222222222222222222222222222222@84532
```

The QR intentionally omits `?value=` so the funding wallet chooses the amount. Loss or deletion of
the device-local operator key requires the merchant to revoke that operator and authorize a newly
generated address; the key is not backed up. Historical invoices do not change when the operator
changes and may be swept by any currently authorized vault owner or operator. Because every
published receiver remains payable forever, the apps allow destructive local key reset only before
the first payment QR is issued. An allowed reset queries the immutable shipped RPC endpoint twice
and requires both latest and pending native balances to be exactly zero before deletion. Withdraw
all gas first; a later deposit to the previously shared retired address can still be lost. After a
QR is issued, retain the key and use portal authorization or terminal reprovisioning; a replacement
operator must be authorized on each historical vault before it can recover later payments.

## Android app and SDK

Requirements: JDK 17 and Android SDK platform 35. Gradle installs the compatible Build Tools selected by the Android Gradle plugin.

Build the app and publish the SDK to the project-local Maven repository:

```bash
cd android
./gradlew \
  :erc681-sdk:test \
  :erc681-sdk:publishAllPublicationsToProjectLocalRepository \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease
```

Outputs:

- debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- unsigned, minified release APK: `android/app/build/outputs/apk/release/app-release-unsigned.apk`
- SDK JAR and sources: `android/erc681-sdk/build/libs/`
- Maven repository: `android/erc681-sdk/build/repository/`
- Maven coordinate: `com.openpasskey:opk-erc681-sdk:0.1.0`

Point a terminal project at the local repository and add the dependency:

```kotlin
repositories {
    maven { url = uri("/path/to/android/erc681-sdk/build/repository") }
}

dependencies {
    implementation("com.openpasskey:opk-erc681-sdk:0.1.0")
}
```

A minimal Kotlin integration looks like this. Run the RPC calls away from the UI thread.

```kotlin
import com.openpasskey.erc681.*

val network = NetworkConfig(
    chainId = 84532,
    rpcUrl = "https://sepolia.base.org",
    factory = EvmAddress.parse("0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5"),
    receiverImplementation = EvmAddress.parse("0xdaa292b1bf533737c5ce5d27f220273971db3bdc"),
    vault = EvmAddress.parse("0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1"),
)
val token = EvmAddress.parse("0x7ffba642bc902880a737cb1c18a4e9540879e211")
val rpc = ReadOnlyRpcClient(network)
val validated = rpc.validate(token, expectedDecimals = 18)

val operatorAddress = requireNotNull(loadDeviceOperatorAddress()) {
    "Create the device operator wallet before creating a payment request"
}
val invoiceNamespace = EvmAddress.parse(operatorAddress)

val invoice = PaymentInvoiceFactory.create(
    network = network,
    token = token,
    amount = TokenAmount.parse("12.34", validated.tokenDecimals),
    terminalIdentifier = invoiceNamespace,
)
displayQr(invoice.erc681Uri)

val observer = PaymentObserver(rpc)
var observation = observer.observe(invoice.request, requiredConfirmations = 2)
observation = observer.observe(
    invoice.request,
    previous = observation,
    requiredConfirmations = 2,
)

if (observation.status == PaymentStatus.PAID) {
    val metadataOnly = SettlementHandoff.from(invoice, observation)
    passToApprovedNativeOperatorModule(metadataOnly)
}
```

On installations upgraded from the QR-only release, existing invoice records keep their invoice
ID, configuration snapshot, and derived receiver. iOS also keeps the per-invoice
`terminalIdentifier`; Android does not need it to monitor or settle a persisted receiver. Do not
rewrite or reinterpret those immutable records. The application no longer uses the old random
value as a global fallback: every new invoice requires the operator wallet and supplies that EOA's
public address as its `terminalIdentifier`, matching protocol 1.4.1. Historical receivers remain
settleable by any currently authorized vault owner or operator.

## Swift package and iOS app

The Swift package is in `ios/Package.swift` and supports iOS 16+ and macOS 13+; the sample app targets iOS/iPadOS 17+. Add the local `ios` directory as a Swift package, then link both products:

- `OPKTerminalCore` for exact amounts, invoice IDs, CREATE2, ERC-681, models, and settlement metadata;
- `OPKTerminalRPC` for restricted read-only RPC, configuration validation, and payment monitoring;
- `OPKTerminalOperator` for the separately isolated iOS device key, constrained transaction
  construction, write RPC, and `Swept` verification. Do not expose this product to customer-payment
  QR parsing code or treat it as an arbitrary wallet API.

Example:

```swift
import Foundation
import OPKTerminalCore
import OPKTerminalRPC

let deployment = try OPKDeployment(
    factory: EthereumAddress(hex: "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5", allowZero: false),
    receiverImplementation: EthereumAddress(hex: "0xdaa292b1bf533737c5ce5d27f220273971db3bdc", allowZero: false),
    vault: EthereumAddress(hex: "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1", allowZero: false)
)
let token = try PaymentToken(
    address: EthereumAddress(hex: "0x7ffba642bc902880a737cb1c18a4e9540879e211", allowZero: false),
    symbol: "AUD",
    decimals: 18
)
let endpoint = URL(string: "https://sepolia.base.org")!
let configuration = try TerminalConfiguration(
    chainID: 84_532,
    rpcEndpoints: [endpoint],
    protocolVersion: .v1_4_1,
    deployment: deployment,
    tokens: [token],
    confirmationPolicy: .init(requiredBlocks: 2)
)

let rpc = try JSONRPCEthereumClient(endpoint: endpoint)
_ = try await ConfigurationValidator(rpc: rpc).validate(configuration)

let operatorAddress = try requireDeviceOperatorAddress()
let invoiceNamespace = TerminalIdentifier(address: operatorAddress)
let amount = try TokenAmount(display: "12.34", decimals: token.decimals)
let request = try InvoiceFactory.create(
    terminalIdentifier: invoiceNamespace,
    amount: amount.rawValue,
    token: token,
    configuration: configuration
)
displayQR(request.erc681URI)

let monitor = PaymentMonitor(
    rpc: rpc,
    confirmationPolicy: configuration.confirmationPolicy
)
for try await observation in monitor.observations(for: request) {
    persist(observation)
    switch observation.status {
    case .paid, .overpaid:
        let metadataOnly = try SettlementHandoff.make(
            chainID: request.chainID,
            vault: request.vault,
            token: request.token.address,
            invoices: [request]
        )
        passToApprovedNativeOperatorModule(metadataOnly)
    default:
        break
    }
}
```

Build and run the dependency-free conformance executable with the installed Swift toolchain:

```bash
cd ios
swift build
swift test
swift run OPKTerminalConformance
```

The SwiftUI app sources are under `ios/App/`. `ios/OPKTerminal.xcodeproj` is included and generated
from `ios/project.yml` with XcodeGen. Regenerate it after changing app sources or the spec, then
build with a full Xcode installation:

```bash
cd ios
xcodegen generate --spec project.yml
xcodebuild \
  -project OPKTerminal.xcodeproj \
  -scheme OPKTerminalApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -skipPackagePluginValidation \
  build
```

The secp256k1 dependency is exact-version and revision pinned in `Package.resolved`. The Xcode flag
allows its checked-in Swift package build plugin to run in non-interactive CI; it does not relax the
package revision pin.

A full Xcode installation with an iOS Simulator SDK is required for the sample app build and XCTest
run. Select it with `xcode-select` before running `xcodebuild`. Swift package checks only require a
compatible Swift toolchain and do not need a signing identity. Simulator compilation proves the
scanner code links; a runtime Simulator pass can exercise the UI and camera-unavailable fallback.
Camera grant/deny and successful scanning must also be tested on a physical iPhone or iPad before
release.

## Settlement handoff

`SettlementHandoff` is plain metadata: chain, vault, token, invoice IDs, expected amounts/observed amount, and receivers. It contains no private key, signature, calldata, gas settings, transaction value, or broadcast method.

Only create or export a handoff after the terminal has a paid/overpaid observation with the required confirmations. The native app operator module must independently authenticate its user, rebuild and validate the intended calldata, simulate it, estimate fees, obtain explicit approval, sign through its constrained device-local key, persist before broadcast, and verify the on-chain result. Those capabilities remain outside both reusable payment SDKs.

### Operator setup and funding

1. Generate the operator wallet once on the device before accepting a new payment. Its public
   address becomes the terminal identity for every new invoice. The private scalar is not exported,
   backed up, synchronized, placed on the clipboard, or stored in ordinary app preferences.
2. Scan the terminal's operator-pairing QR in the merchant portal, confirm
   `grantOperator(address)`, then scan the portal's operator-bound provisioning QR back on the
   terminal. The terminal derives and validates every deployment and token field before saving.
3. Send at least `0.0001 ETH` of the correct network's native token to the operator address for gas
   only. Do not send customer payment tokens to it. The Settings UI displays the exact address,
   chain, funding QR, balance, authorization state, and readiness result. Authorization and the
   minimum balance are required before each new invoice and checked again before a sweep.

Existing invoice records preserve the invoice ID and receiver derived from their historical
namespace; iOS also preserves that namespace per invoice. The app does not expose or reuse a global
legacy identifier for new invoices. Any authorized vault owner or operator can sweep those
historical receivers because authorization depends on the settlement transaction sender, not the
invoice namespace.

### Settlement transaction lifecycle

- Group only paid or overpaid invoices, or previously swept invoices with separately confirmed
  positive late value, with the same chain, vault, and token; batches are capped.
- Encode `sweepSessions(bytes32[],uint256[],address)` locally using each invoice's original expected
  raw amount. Reject empty, duplicate, mixed, zero, or corrupted inputs.
- Check the configured chain, contract links, token whitelist, operator authorization, confirmed
  receiver balances, simulation, gas estimate, current pending nonce, and conservative maximum fee.
- Before sweeping an invoice from an earlier provisioning, re-derive its receiver and re-prove its
  network label, known-chain factory/implementation pins, vault runtime/factory link, token
  whitelist, and token metadata through the immutable shipped RPC. Separately chain-check the
  stored operational RPC, recheck current EOA authorization/exact balances/simulation immediately
  before signing, then atomically activate that historical chain/vault target for the constrained signer.
- Persist the exact signed raw transaction, hash, nonce, fees, calldata, and invoice set before
  calling `eth_sendRawTransaction`. Retry an ambiguous broadcast only with the same bytes and hash.
- Keep payment state separate from settlement state. A sweep reducing the receiver's current
  balance must not erase the terminal's confirmed payment observation.
- After receipt confirmation and canonical block-hash verification, match `Swept` evidence by
  emitting vault, indexed receiver and vault, invoice ID, token, submitted expected amount, and
  swept amount. Zero never proves newly swept value. Ambiguous recovery stays under review after
  zero, missing, or partial proof; cumulative canonical proof at least expected is settled.
- Key loss or rotation never creates a replacement silently. Grant and fund a candidate, reconcile
  pending transactions, revoke the old operator, and only then retire its local key.

### Current recovery limits

The sample apps are suitable for Base Sepolia testing, but they are not yet mainnet-ready. Their
reconciliation intentionally trusts only canonical receipt logs for transactions the terminal
persisted itself. If the vault owner or a different authorized operator sweeps one of the same
receivers externally, the app does not yet discover that proof with `eth_getLogs`; reconcile that
invoice manually instead of retrying it blindly.

An underpriced or permanently dropped transaction also keeps that operator and chain serialized to
the persisted nonce. The apps may rebroadcast the exact signed bytes, but they do not yet offer a
same-nonce fee replacement, cancellation, or automatic abandonment. Resolve or replace the pending
transaction using an audited operator-recovery procedure before submitting another batch. These
recovery paths, Android transaction-bound authentication, app-store device testing, and migration
fixtures require additional hardening before production deployment.

## One-command verification

From the repository root:

```bash
./scripts/verify-mobile.sh
```

The script runs the mobile boundary guard, Android SDK/app tests, Maven publication, app lint, debug
assembly, and unsigned release-mode assembly, then Swift build/tests and the conformance executable.
It requires XcodeGen, proves the included project and Info.plist are current, and compiles the iOS
app for Simulator. It respects `JAVA_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME`. If they are unset,
it checks the repository-local `.tools/jdk17` and `.tools/android-sdk` directories.
`OPK_LOCAL_TOOLS_ROOT`, `OPK_GRADLE_USER_HOME`, `OPK_SWIFT_BIN`, `OPK_XCODEGEN_BIN`, and
`OPK_XCODEBUILD_BIN` override those local paths without changing system installations.

The shared language-neutral vectors live in `conformance/opk-erc681-v1.json`. Keep Android and
Swift output pinned to that file when changing invoice derivation, CREATE2, amount conversion, or
URI handling.

Generated APK, JAR, Maven, SwiftPM, and signing outputs are intentionally excluded from source
control. Rebuild them locally or publish them separately as CI/Release artifacts.
