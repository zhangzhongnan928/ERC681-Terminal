# OPK ERC-681 mobile terminal and SDK

This mobile build accepts one payment rail: an ERC-20 `transfer` requested by an ERC-681 QR code. The Android and iOS apps create invoices, render the QR, and observe token balances. They do not hold a wallet or move funds.

## Safety boundary

The app and SDK source is intentionally limited to:

- local invoice-ID and CREATE2 receiver derivation;
- canonical ERC-681 encoding and strict parsing;
- QR display;
- read-only JSON-RPC calls for chain/configuration checks and `balanceOf` observation;
- local invoice persistence and recovery; and
- a data-only handoff for a separate merchant/operator settlement system.

There is no NFC, contactless-card path, camera/QR scanning, private-key custody, signing, raw transaction construction, or write-RPC method. The SDK cannot call `sweepSessions`, payout, refund, deploy, approve, or transfer. Do not add a private key to an app configuration or RPC URL.

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

1. Validate the RPC chain, deployed code, factory/implementation link, vault/factory link, token whitelist, and token decimals.
2. Generate `invoiceId = keccak256(abi.encode(terminalIdentifier, timestamp, nonce))`.
3. Derive the receiver locally with the protocol's 88-byte CREATE2 init code. Do not trust an RPC response for this address.
4. Render `erc681Uri`/`erc681URI` as a display-only QR.
5. Poll the token's `balanceOf(receiver)` at an explicit block.
6. Persist waiting, partial, confirming, paid, overpaid, and expired state. On foreground/restart, reload open invoices and sample them again.
7. After a paid or overpaid observation, pass settlement metadata to the merchant's external operator system if one exists.

The apps never sweep the receiver. A separate approved operator must submit and verify any settlement transaction. Under protocol 1.5, transaction receipt success alone is not proof of settlement: the operator must verify a non-zero `Swept` amount or a matching `settled(invoiceId, token)` delta.

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
independently checked, and accepted by the SDK validation path.

## Android app and SDK

Requirements: JDK 17 and Android SDK platform 35. Gradle installs the compatible Build Tools selected by the Android Gradle plugin.

Build the app and publish the SDK to the project-local Maven repository:

```bash
cd android
./gradlew \
  :erc681-sdk:test \
  :erc681-sdk:publishAllPublicationsToProjectLocalRepository \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease
```

Outputs:

- debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- unsigned, minified release APK: `android/app/build/outputs/apk/release/app-release-unsigned.apk`
- SDK JAR and sources: `android/erc681-sdk/build/libs/`
- Maven repository: `android/erc681-sdk/build/repository/`
- Maven coordinate: `com.openpasskey:opk-erc681-sdk:1.0.0`

Point a terminal project at the local repository and add the dependency:

```kotlin
repositories {
    maven { url = uri("/path/to/android/erc681-sdk/build/repository") }
}

dependencies {
    implementation("com.openpasskey:opk-erc681-sdk:1.0.0")
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

val invoice = PaymentInvoiceFactory.create(
    network = network,
    token = token,
    amount = TokenAmount.parse("12.34", validated.tokenDecimals),
    terminalIdentifier = EvmAddress.parse(persistedNonSecretTerminalNamespace),
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
    persistForApprovedExternalOperator(metadataOnly)
}
```

`terminalIdentifier` is a stable, non-secret 20-byte namespace. It is address-shaped but is not a
wallet account and has no corresponding private key.

## Swift package and iOS app

The Swift package is in `ios/Package.swift` and supports iOS 16+ and macOS 13+; the sample app targets iOS/iPadOS 17+. Add the local `ios` directory as a Swift package, then link both products:

- `OPKTerminalCore` for exact amounts, invoice IDs, CREATE2, ERC-681, models, and settlement metadata;
- `OPKTerminalRPC` for restricted read-only RPC, configuration validation, and payment monitoring.

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

let amount = try TokenAmount(display: "12.34", decimals: token.decimals)
let request = try InvoiceFactory.create(
    terminalIdentifier: persistedNonSecretTerminalIdentifier,
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
        persistForApprovedExternalOperator(metadataOnly)
    default:
        break
    }
}
```

Build and run the dependency-free conformance executable with the installed Swift toolchain:

```bash
cd ios
swift build
swift run OPKTerminalConformance
```

The SwiftUI app sources are under `ios/App/`. `ios/OPKTerminal.xcodeproj` is included and was generated from `ios/project.yml` with XcodeGen 2.46.0. Regenerate it after changing the spec, then build with a full Xcode installation:

```bash
cd ios
xcodegen generate --spec project.yml
xcodebuild \
  -project OPKTerminal.xcodeproj \
  -scheme OPKTerminalApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  build
```

A full Xcode installation with an iOS Simulator SDK is required for the sample app build and XCTest run. Select it with `xcode-select` before running `xcodebuild`. Swift package checks only require a compatible Swift toolchain and do not need a signing identity.

## Settlement handoff

`SettlementHandoff` is plain metadata: chain, vault, token, invoice IDs, expected amounts/observed amount, and receivers. It contains no private key, signature, calldata, gas settings, transaction value, or broadcast method.

Only create or export a handoff after the terminal has a paid/overpaid observation with the required confirmations. The receiving system must authenticate its operator, build and simulate the intended transaction, obtain the required approval, sign through its constrained signer, submit it, and verify the on-chain settlement result. Those steps are outside both mobile SDKs.

## One-command verification

From the repository root:

```bash
./scripts/verify-mobile.sh
```

The script runs the mobile boundary guard, Android SDK tests/Maven publication/app lint, debug
assembly, and unsigned release-mode assembly, then Swift build and the conformance executable. It
also regenerates the included Xcode project when `xcodegen` or `OPK_XCODEGEN_BIN` is available. It
respects `JAVA_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME`. If they are unset,
it checks the repository-local `.tools/jdk17` and `.tools/android-sdk` directories.
`OPK_LOCAL_TOOLS_ROOT`, `OPK_GRADLE_USER_HOME`, `OPK_SWIFT_BIN`, and `OPK_XCODEGEN_BIN` override
those local paths without changing system installations.

The shared language-neutral vectors live in `conformance/opk-erc681-v1.json`. Keep Android and
Swift output pinned to that file when changing invoice derivation, CREATE2, amount conversion, or
URI handling.

Generated APK, JAR, Maven, SwiftPM, and signing outputs are intentionally excluded from source
control. Rebuild them locally or publish them separately as CI/Release artifacts.
