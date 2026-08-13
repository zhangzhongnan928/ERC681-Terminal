# OPK ERC-681 mobile terminal and SDK

This mobile build accepts ERC-20 transfers and OPK Protocol 1.6 chain-native payments through
canonical ERC-681 QR codes. A terminal may store up to 32 EVM payment profiles, where each profile
binds one known network, vault, and payment asset; the Android app, iOS app, Kotlin catalog, and
Swift catalog enforce the same cap. A cashier selects exactly one profile per invoice. The Android
and iOS apps create invoices, render the asset-specific payment QR, and observe receiver balances.
Their cameras can import individual contract and payment-asset identifiers in Settings or scan the
separate strict `opk-terminal:provision` setup payload; payment QR payloads are rejected and never
imported or acted on. The reusable Core/RPC payment SDKs remain keyless and read-only. Each native
app also has an isolated, device-local operator module that can submit only a constrained
`ClearingVault.sweepSessions` transaction after payment confirmation.

## Safety boundary

The payment SDK source is intentionally limited to:

- local invoice-ID and CREATE2 receiver derivation;
- canonical ERC-681 encoding and strict parsing;
- payment QR display;
- Settings-only camera scanning for strict address fields and the separate provisioning payload;
- read-only JSON-RPC calls for chain/configuration checks, ERC-20 `balanceOf`, native
  `eth_getBalance` observation, exact blocks, and receiver-scoped ERC-20 logs used for incoming
  transaction evidence;
- local invoice persistence and recovery; and
- a data-only handoff that a native app may pass into its isolated operator module.

There is no NFC, contactless-card path, customer payment-QR import or action, unlocked-node signing, arbitrary transaction API, seed import, or private-key export. Camera access belongs only to the native app Settings UI. Address scan buttons fill only their selected address field, while the separate setup button accepts only the exact provisioning grammar; the reusable SDKs remain camera-free. The payment SDKs cannot call `sweepSessions`, payout, refund, deploy, approve, or transfer. Do not add a private key to an app configuration or RPC URL.

The native operator implementation is a separate trust boundary. It generates one secp256k1 key
on the device and restricts signing to an invoice snapshot's known chain and vault, transaction
value zero, the `sweepSessions(bytes32[],uint256[],address)` selector, that profile's whitelisted
payment asset, and locally persisted paid or overpaid invoices or confirmed late value at a
previously swept receiver. It cannot select a recipient or call payout, refund, rescue, approval,
transfer, or deployment methods. The shipped apps require this wallet to exist before creating a
payment request and use its public address as the terminal identity for every new invoice. The
merchant separately authorizes that same address on each configured vault and pre-funds it with
each used network's native token before the corresponding profile accepts a new invoice; the same
checks run again before settlement. On a native invoice the receiver owns the payment while the
operator EOA pays gas; the settlement transaction never draws gas from receiver funds.

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
entry available. The additive setup scanner has a different exact grammar and derives the factory
and receiver implementation from read-only chain calls. For ERC-20 assets it also derives decimals
and symbol from the token contract. For the native sentinel it accepts the immutable chain
profile's 18-decimal native metadata only after a successful vault `NATIVE_ASSET()` read returns
that sentinel. It accepts only immutable, app-pinned deployment profiles and atomically upserts the
complete payment profile after every pin, CREATE2, capability, whitelist, metadata, and existing
full-validation check succeeds. See
[PROVISIONING.md](./PROVISIONING.md) for the pairing payloads and recovery model.

## Canonical payment request

The SDKs emit and accept exactly one form for each payment-asset class.

ERC-20 transfer:

```text
ethereum:{TOKEN}@{CHAIN_ID}/transfer?address={RECEIVER}&uint256={RAW_TOKEN_UNITS}
```

Native value transfer:

```text
ethereum:{RECEIVER}@{CHAIN_ID}?value={AMOUNT_WEI}
```

For the shared test vectors:

```text
ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0xbbd352de4428d535ac79849abefa8d69bb51c671&uint256=12340000000000000000
ethereum:0xbbd352de4428d535ac79849abefa8d69bb51c671@84532?value=12340000000000000000
```

Addresses are emitted in lower-case hexadecimal. The chain ID and positive raw amount use plain
base-10 integers without signs, exponents, leading zeroes, extra parameters, or parameter
reordering. Native amounts are raw wei with 18 decimals. The EIP-7528 sentinel is an on-chain asset
identifier and never appears in a customer payment QR; the native target is the receiver itself.
Other function calls, including `approve`, fail closed. Pass the configured chain ID to the parser
so a request for another chain also fails closed.

The amount in an ERC-681 URI is a wallet suggestion. The observer measures the receiver's actual
ERC-20 or native balance, keeps partial payments open, waits for the configured block count, and
reports overpayment separately.

### Public-RPC request scheduling

Production clients reuse persistent connections within each native HTTP transport and collapse
independent reads into strict JSON-RPC batches. Each physical batch contains at most 10 calls; a
larger settlement proof is split into bounded chunks rather than sent as one unbounded payload.
Batch arrays may be returned out of order, so clients map only exact integer IDs and reject string,
fractional, duplicate, missing, foreign, malformed, or failed-required responses. A specifically
tolerated optional call is represented separately and cannot substitute for a required proof
member. Batch support changes transport scheduling, not the validation boundary: chain identity is
checked before dependent state is accepted, receiver balances use explicit block tags where
confirmation depends on them, and canonical block hashes are checked before and after the proof
window.

Both SDK observers use three sequential network waves per payment sample: chain/head anchor,
fixed-block balance plus saved-cursor identities, then a final canonical-head identity check. Once
cashier work is requested, the native apps defer new background RPC units. One already-started
bounded unit may finish or overlap without owning the cashier queue, while the shared concurrency
limit prevents an RPC burst. Normal payment polling is five seconds and automatic recovery runs on
a 60-second cadence. Settlement evidence can be reused only while its exact configuration, intent,
invoice set, confirmed balances, and canonical cursors match and its original, non-rolling lifetime
of at most 60 seconds remains valid. Live mutable signing checks and the evidence-age check run again around device
authentication and immediately before key use. Do not replace these rules with a general-purpose
RPC cache.

### Android production RPC configuration

The Android app keeps immutable chain and OPK deployment pins separate from the replaceable
transport endpoint. Endpoint resolution is per chain and ordered as follows:

1. an administrator override encrypted with Android Keystore;
2. a build-managed endpoint;
3. the compiled public Base fallback in debug builds only.

Set build-managed endpoints through `OPK_BASE_MAINNET_RPC_URL` and
`OPK_BASE_SEPOLIA_RPC_URL`, or the equivalent Gradle properties `opkBaseMainnetRpcUrl` and
`opkBaseSepoliaRpcUrl`. Do not put credential-bearing values in a tracked `gradle.properties`
file. Admin/setup exposes a masked manual field, explicit reveal, and a QR scanner for long URLs.
Android release builds may leave the build-managed values empty for per-terminal provisioning, but
they reject an explicitly configured Base public RPC host and never use the public fallback. Before
scanning a portal QR, Admin/setup must verify and save an endpoint for that QR's Base Mainnet or
Base Sepolia network. Debug builds alone retain the rate-limited public fallback for development.
The scanner accepts one exact HTTPS URL and only fills the field. Save performs chain and pinned
deployment validation before the encrypted override becomes active. The ordinary provisioning QR
grammar remains unchanged and rejects any RPC parameter.

An administrator override is an explicit change to the terminal's read trust source for that
chain. The pre-save checks prove that the server reports the expected chain and compiled OPK pins;
they cannot make an untrusted RPC server honest. Restrict this control to merchant administrators
and use a provider whose operational and security policy the merchant accepts.

The encrypted store protects credentials at rest and prevents accidental copying into ordinary
preferences, Room rows, receipts, or diagnostics. It cannot turn a client credential into a true
secret on a rooted or otherwise controlled device. BuildConfig strings are also extractable.
Production fleets should normally use a credential-free OPK gateway URL, give each terminal a
revocable gateway credential, and keep Coinbase CDP or Alchemy server credentials at the gateway.
If connecting directly to Coinbase, use only a CDP Client API Key, which is intended for mobile
clients, and scope/rotate it as a public quota credential. Never use a CDP Secret API Key. For
Alchemy, do not embed a permanent URL key in an APK. The direct terminal transport currently
accepts URL endpoints only. An OPK gateway may authenticate upstream with a short-lived Alchemy
JWT, but direct terminal JWT authentication is not implemented today. Never paste a JWT into the
RPC URL field or encode one in its QR code. One provider project per environment and fleet shard
avoids making one global quota or rotation event affect every terminal.

### iOS production RPC configuration

iOS Admin/setup exposes a masked per-network HTTPS editor with explicit trust confirmation. Saving
first checks the reported chain and revalidates every existing profile for that network against the
compiled OPK pins, then stores the URL in a non-synchronizing, device-only Keychain item. Fresh
provisioning performs the complete pinned deployment proof through that selected endpoint. The URL
is runtime transport only: credential-bearing values are not written to `AppSettings`, SwiftData
invoice or settlement rows, receipts, logs, or long-lived status text. Removing the item explicitly
returns that network to the visibly labeled, rate-limited compiled public fallback.

## Incoming customer transaction evidence

The read-only SDKs can attribute the direct customer transaction that first made a receiver meet
its invoice amount. This is separate from payment status: the balance observer remains the payment
authority. Before showing the QR, persist its canonical publication cursor. When the observer first
meets the expected amount, persist that canonical funding cursor. Do not manufacture either cursor
for migrated or legacy rows.

Both resolvers verify the RPC chain and both saved cursor hashes, require the publication balance
to be below the invoice amount, then binary-search the first fixed block whose ending balance meets
the amount. For ERC-20 payments they decode only `Transfer` logs for the requested token and
receiver, order them by log index, and select the first cumulative incoming transfer that crosses
the threshold. For native payments they order full-block top-level transactions by transaction
index and consider only direct positive-value transfers to the receiver. They then re-read the
payment block and require its number, hash, and timestamp to remain unchanged, followed by fresh
publication and funding cursor checks. Wrong-chain, malformed, overflowed, duplicate, removed, or
reorganized evidence fails closed.

Kotlin:

```kotlin
val evidenceRequest = PaymentEvidenceRequest(
    chainId = invoiceChainId,
    receiver = invoiceReceiver,
    asset = invoiceAsset,
    expectedAmount = invoiceRawAmount,
    publicationCursor = PaymentConfirmationCursor(
        blockNumber = publishedBlock,
        blockHash = publishedBlockHash,
    ),
    fundingCursor = PaymentConfirmationCursor(
        blockNumber = firstFundedBlock,
        blockHash = firstFundedBlockHash,
    ),
)
val incomingEvidence = PaymentEvidenceResolver(ReadOnlyRpcClient(network))
    .resolve(evidenceRequest)
```

Swift:

```swift
let evidenceRequest = try PaymentEvidenceRequest(
    chainID: invoiceChainID,
    receiver: invoiceReceiver,
    asset: invoiceAsset,
    expectedAmount: invoiceRawAmount,
    publicationCursor: PaymentConfirmationCursor(
        blockNumber: publishedBlock,
        blockHash: publishedBlockHash
    ),
    fundingCursor: PaymentConfirmationCursor(
        blockNumber: firstFundedBlock,
        blockHash: firstFundedBlockHash
    )
)
let evidenceClient = try JSONRPCEthereumClient(endpoint: trustedRPCEndpoint)
let incomingEvidence = try await PaymentTransactionResolver(client: evidenceClient)
    .resolve(evidenceRequest)
```

Successful evidence contains the direct transaction hash, a non-zero payer, canonical block
number and hash, and canonical block timestamp. `null` or `nil` means the confirmed balance
crossing could not be attributed safely. In particular, internal native transfers, self-destruct
funding, and other indirect balance changes are intentionally unsupported without trace evidence.
Keep the payment confirmed, but disable consumer receipt details until direct evidence is
available. Never use a later settlement, sweep, or `Swept` receipt transaction hash as the
customer's payment transaction. Persist a result only if the invoice's publication and funding
cursors still match the request after resolution.

## Invoice lifecycle

1. Require the device operator wallet and freshly validate the RPC chain, deployed code,
   factory/implementation link, vault/factory link, payment-asset whitelist, metadata and native
   capability when applicable, and vault
   owner/operator authorization. The minimum native gas reserve is checked too; on Android a low
   balance warns without blocking invoice creation (settlement waits for funding), while iOS still
   requires it.
2. Use the operator public address as `terminalIdentifier`, and generate
   `invoiceId = keccak256(abi.encode(terminalIdentifier, timestamp, nonce))`.
3. Derive the receiver locally with the protocol's 88-byte CREATE2 init code. Do not trust an RPC response for this address.
4. Render `erc681Uri`/`erc681URI` as a customer-facing payment QR. The configuration scanner rejects this payload without importing or acting on it.
5. Poll the ERC-20's `balanceOf(receiver)` or the receiver's `eth_getBalance` at an explicit block.
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

The reusable payment SDKs never sweep the receiver. A native app may pass their data-only handoff
into its separate approved operator module. The shipped Base Mainnet and Base Sepolia profiles use
their deployed OPK Protocol 1.6 stacks for both ERC-20 and native routes, but transaction receipt
success alone is never
proof of settlement: the app must
decode a fully matching confirmed `Swept` event and record a positive actual amount. The
asset-scoped settlement counters, including `settled(invoiceId, NATIVE_ASSET)` for native invoices,
are supplementary state and do not replace canonical event proof.

`terminalIdentifier` remains the protocol and reusable-SDK name for a generic, non-secret 20-byte
invoice namespace. The reusable SDK does not require that namespace to have a private key. The
shipped Android and iOS apps apply the stricter application policy described above: they always
pass the device operator EOA public address for new invoices and fail invoice creation unless that
wallet exists and is authorized by the selected profile's vault. On Android, a balance below the
selected network's compiled minimum native-gas reserve warns without blocking invoice creation
(customer funds land at the one-time receiver regardless, and settlement waits for funding); iOS
still requires the reserve before each new invoice. Both shipped Base profiles currently use
`100000000000000` wei, or `0.0001 ETH`; another enabled EVM network may use a different native
currency, decimals, and reserve. These checks run again when preparing a settlement, where the
reserve remains required.

## Known EVM networks

Base Mainnet, chain ID `8453`, is the default for fresh application configuration:

| Item | Value |
|---|---|
| Protocol version | `1.6` |
| Factory | `0x5418ab1790eaf96a20e26146c5b7765cb99328da` |
| Receiver implementation | `0xe6393f6176865cc62cd08d8b8f0c38d35af55254` |
| Vault beacon embedded in the proxy runtime | `0xd051ba174636a1bb663559e9c454053a543488ef` |
| Deployed vault-proxy runtime hash | `0x8c3a56b5606e44613d50c898acf67a3689afc478b47e9a38326699b0df111cbd` |
| CREATE2 example vault | `0x1111111111111111111111111111111111111111` |
| Native asset identifier | `0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE` (`ETH`, 18 decimals) |

Base Sepolia, chain ID `84532`, remains available as the explicit test network:

| Item | Value |
|---|---|
| Protocol version | `1.6` |
| Factory | `0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f` |
| Receiver implementation | `0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18` |
| Vault beacon embedded in the proxy runtime | `0xc9c24c87f55c46d42419bc181d427acd1755e46c` |
| Deployed vault-proxy runtime hash | `0x32ad6b6076f449fbc39e115afc2645c65071280af2d461dc315544ac0a1d7e58` |
| CREATE2 example vault | `0x1111111111111111111111111111111111111111` |
| AUD test token | `0x7ffba642bc902880a737cb1c18a4e9540879e211` (18 decimals) |
| Native asset identifier | `0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE` (`ETH`, 18 decimals) |

Each runtime hash is over the exact on-chain proxy bytecode, including that deployment's beacon
immutable (`0xd051ba174636a1bb663559e9c454053a543488ef` on Base Mainnet and
`0xc9c24c87f55c46d42419bc181d427acd1755e46c` on Base Sepolia). Do not substitute an upstream
browser deployer's zero-immutable artifact hash when validating `eth_getCode`; it does not match a
deployed vault.

The example vault is an off-chain CREATE2 test input, not a deployed merchant vault. A production
terminal remains unprovisioned until it validates a compatible live merchant vault and whitelisted
payment asset from a portal provisioning QR. The shipped profile requires OPK Protocol 1.6. A
native route additionally requires a successful `NATIVE_ASSET()` read returning the exact
sentinel and that sentinel's whitelist entry; `isPaymentToken(NATIVE_ASSET) == false` does not
prove native capability.

Base Mainnet and Base Sepolia are enabled in the production apps. The Swift and Kotlin profile
catalogs remain EVM-generic and can model routes on other EVM chains, but an app rejects any chain
absent from its immutable enabled-network registry before RPC use. Enabling another network still
requires reviewed OPK deployment constants, vault runtime hash, trusted HTTPS RPC, matching CREATE2
vector, finality floor/default, native-currency metadata, and minimum gas reserve in both native
registries. A QR cannot introduce these values. Base's public `mainnet.base.org` and
`sepolia.base.org` endpoints are rate-limited and are not production-capacity guarantees; review an
operational provider before live volume.
The enabled cross-platform pins and vectors are recorded in
`conformance/opk-terminal-networks-v1.json`.

The reusable SDKs also expose metadata-only `BaseNetworks.mainnet` and `BaseNetworks.sepolia`
descriptors, verified against `conformance/opk-base-networks-v1.json`. These descriptors contain
chain identity, native-currency, and BaseScan metadata only. They do not include OPK deployment
anchors or an SDK-wide default. The Android and iOS application layers choose their default
explicitly; a descriptor alone never enables a payment network.

The published OPK Protocol 1.6 Route A deployments changed receiver addresses from earlier stacks
because their factory and receiver implementation changed. This release pins the reviewed Base
Mainnet and Base Sepolia factories, receiver implementations, runtime hashes, and CREATE2 test
vectors. Any future fresh stack has the same release gate. Only an in-place beacon upgrade of the
same stack can preserve its receiver commitments.

Both shipped Base profiles have a compiled confirmation minimum and fresh-network default of `1`;
the block that contains the payment counts as confirmation one. A merchant administrator may select
a value from the enabled network's compiled minimum through `64` in the PIN-protected terminal settings. Every
profile on the same chain shares the choice, and a new profile on that chain inherits it. The policy
is copied into each new invoice and its settlement batch, so a later settings change cannot alter
already published payment requests. The strict `opk-terminal:provision` v1 payload has no
confirmation field and cannot override this policy.

## Operator identity, authorization, and gas funding

Creating the operator wallet establishes the terminal identity used for new invoices; it does not
make the EOA a vault operator. For each payment profile, the merchant must grant the displayed
address with that vault's administrative `grantOperator` flow (the vault owner itself is also
accepted), then scan its operator-bound provisioning QR. Repeated scans add or update profiles; they
do not erase unrelated profiles. Fund that same EOA with at least the compiled native-gas reserve
shown for each selected network (`0.0001 ETH` on both shipped Base profiles). Until the selected
profile passes authorization and configuration validation, the app does not create its invoice or
customer QR; on Android a low gas balance only warns, while settlement still waits for funding.
The app shows the entire address, offers Copy, and displays this address-only funding QR:

```text
ethereum:{OPERATOR_ADDRESS}@{CHAIN_ID}
```

For example, on Base Mainnet:

```text
ethereum:0x2222222222222222222222222222222222222222@8453
```

The QR intentionally omits `?value=` so the funding wallet chooses the amount. Loss or deletion of
the device-local operator key requires the merchant to revoke that operator and authorize a newly
generated address; the key is not backed up. Historical invoices do not change when the operator
changes and may be swept by any currently authorized vault owner or operator. Because every
published receiver remains payable forever, the apps allow destructive local key reset only before
the first payment QR is issued. An allowed reset queries the active approved RPC endpoint twice
and requires both latest and pending native balances to be exactly zero before deletion. Withdraw
all gas first; a later deposit to the previously shared retired address can still be lost. After a
QR is issued, retain the key and use portal authorization or terminal reprovisioning; a replacement
operator must be authorized on each historical vault before it can recover later payments.

## Android app and SDK

Requirements: JDK 17 and Android SDK platform 36. The app compiles against and targets Android API
36. Gradle installs the compatible Build Tools selected by the Android Gradle plugin.

The iMin printer integration is pinned to
`com.github.iminsoftware:IminPrinterLibrary:V2.0.0.18`. Before compiling the app, verification
resolves that non-transitive AAR through Gradle and requires its SHA-256 digest to equal
`8efa28e31c6e03ad9b460ecfa36d30471b4ded7f7a3ee4b7ed22e369afb14071`. Treat a coordinate or
digest change as a supply-chain review, not a routine dependency update.

Build the app and publish the SDK to the project-local Maven repository:

```bash
cd android
./gradlew \
  :verifyIminPrinterArtifact \
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
- Maven coordinate: `com.openpasskey:opk-erc681-sdk:0.5.1`

Point a terminal project at the local repository and add the dependency:

```kotlin
repositories {
    maven { url = uri("/path/to/android/erc681-sdk/build/repository") }
}

dependencies {
    implementation("com.openpasskey:opk-erc681-sdk:0.5.1")
}
```

A minimal Kotlin integration looks like this. Run the RPC calls away from the UI thread.

```kotlin
import com.openpasskey.erc681.*

val provisionedVault = EvmAddress.parse(requireNotNull(loadProvisionedMerchantVault()))
val provisionedAsset = EvmAddress.parse(requireNotNull(loadProvisionedPaymentAsset()))
val base = BaseNetworks.mainnet
val network = NetworkConfig(
    chainId = base.chainId,
    // Use an operational HTTPS endpoint reviewed for your live volume. Base's public endpoint is
    // rate-limited and is not a production-capacity guarantee.
    rpcUrl = requireNotNull(loadReviewedRpcEndpoint(base.chainId)),
    factory = EvmAddress.parse("0x5418ab1790eaf96a20e26146c5b7765cb99328da"),
    receiverImplementation = EvmAddress.parse("0xe6393f6176865cc62cd08d8b8f0c38d35af55254"),
    vault = provisionedVault,
)
val paymentAsset = provisionedAsset
val rpc = ReadOnlyRpcClient(network)
val validated = if (NativeAsset.isNative(paymentAsset)) {
    rpc.validate(
        paymentAsset,
        expectedDecimals = NativeAsset.DECIMALS,
        expectedSymbol = "ETH",
    )
} else {
    rpc.validate(paymentAsset)
}
val profile = PaymentProfile(
    network = network,
    token = PaymentTokenConfig(
        address = paymentAsset,
        symbol = validated.tokenSymbol,
        decimals = validated.tokenDecimals,
    ),
)

// Upsert other independently validated EVM network/vault/payment-asset profiles the same way.
val catalog = PaymentProfileCatalog(listOf(profile), profile.id)
val selected = requireNotNull(catalog.selected)

val operatorAddress = requireNotNull(loadDeviceOperatorAddress()) {
    "Create the device operator wallet before creating a payment request"
}
val invoiceNamespace = EvmAddress.parse(operatorAddress)

val invoice = PaymentInvoiceFactory.create(
    profile = selected,
    amount = TokenAmount.parse("12.34", selected.token.decimals),
    terminalIdentifier = invoiceNamespace,
)
displayQr(invoice.erc681Uri)

val observer = PaymentObserver(ReadOnlyRpcClient(selected.network))
var observation = observer.observe(invoice.request, requiredConfirmations = 1)
observation = observer.observe(
    invoice.request,
    previous = observation,
    requiredConfirmations = 1,
)

if (observation.status == PaymentStatus.PAID) {
    val metadataOnly = SettlementHandoff.from(invoice, observation)
    passToApprovedNativeOperatorModule(metadataOnly)
}
```

The shipped Base Mainnet and Base Sepolia profiles target OPK Protocol 1.6 for every payment asset.
A native-sentinel route additionally requires a successful exact `NATIVE_ASSET()` capability read
and whitelist membership on that vault. Profiles and invoices from pre-release v1.4 builds are
unsupported and must not be
reinterpreted under current deployment pins. Reset any development install carrying those
obsolete local records before provisioning a live merchant vault.

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

let provisionedVault = try EthereumAddress(
    hex: loadProvisionedMerchantVault(),
    allowZero: false
)
let provisionedAsset = try EthereumAddress(
    hex: loadProvisionedPaymentAsset(),
    allowZero: false
)
let knownNetwork = TerminalKnownChainProfile.baseMainnet
let deployment = try OPKDeployment(
    factory: knownNetwork.factory,
    receiverImplementation: knownNetwork.receiverImplementation,
    vault: provisionedVault
)
let isNative = NativeAsset.isNative(provisionedAsset)
let token = try PaymentToken(
    address: provisionedAsset,
    symbol: isNative ? "ETH" : "AUD",
    decimals: isNative ? NativeAsset.decimals : 18
)
// Select an operational endpoint reviewed for live volume. The profile's public Base endpoint is
// rate-limited and is not a production-capacity guarantee.
let endpoint = try loadReviewedRPCEndpoint(for: knownNetwork.chainID)
let configuration = try TerminalConfiguration(
    chainID: knownNetwork.chainID,
    rpcEndpoints: [endpoint],
    protocolVersion: knownNetwork.protocolVersion,
    deployment: deployment,
    tokens: [token],
    confirmationPolicy: .init(requiredBlocks: 1)
)

let rpc = try JSONRPCEthereumClient(endpoint: endpoint)
_ = try await ConfigurationValidator(rpc: rpc).validate(configuration)
let profile = try TerminalPaymentProfile(configuration: configuration, token: token)

// Upsert other independently validated EVM network/vault/payment-asset profiles the same way.
let catalog = try TerminalPaymentProfileCatalog(
    profiles: [profile],
    selectedProfileID: profile.id
)
guard let selected = catalog.selected else {
    throw TerminalPaymentProfileError.profileNotFound(profile.id)
}

let operatorAddress = try requireDeviceOperatorAddress()
let invoiceNamespace = TerminalIdentifier(address: operatorAddress)
let amount = try TokenAmount(display: "12.34", decimals: selected.token.decimals)
let request = try InvoiceFactory.create(
    terminalIdentifier: invoiceNamespace,
    amount: amount,
    profile: selected
)
displayQR(request.erc681URI)

let selectedRPC = try JSONRPCEthereumClient(
    endpoint: selected.configuration.rpcEndpoints[0]
)
let monitor = PaymentMonitor(
    rpc: selectedRPC,
    confirmationPolicy: selected.configuration.confirmationPolicy
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

Only create or export a handoff after the terminal has a paid/overpaid observation with the
required confirmations. The native app operator module must independently authenticate its user,
rebuild and validate the intended calldata, simulate it, estimate fees, obtain explicit approval,
sign through its constrained device-local key, persist before broadcast, and verify the on-chain
result. These write capabilities remain outside `OPKTerminalCore`, `OPKTerminalRPC`, and the
Android payment SDK; the separately isolated Swift `OPKTerminalOperator` product owns the iOS
implementation.

### Operator setup and funding

1. Generate the operator wallet once on the device before accepting a new payment. Its public
   address becomes the terminal identity for every new invoice. The private scalar is not exported,
   backed up, synchronized, placed on the clipboard, or stored in ordinary app preferences.
2. Scan the terminal's operator-pairing QR in the merchant portal. For each desired currency,
   confirm `grantOperator(address)` on its vault and scan that vault/payment-asset's operator-bound
   provisioning QR back on the terminal. The terminal derives and validates every deployment and
   payment-asset field before atomically adding or updating that profile.
3. Send at least the selected network's compiled native-gas reserve to the operator address for gas
   only (`0.0001 ETH` on both shipped Base profiles). Do not send customer payment tokens to it. The Settings UI
   displays the exact address, chain, funding QR, balance, authorization state, and readiness
   result. Authorization is required before each new invoice; the per-network minimum balance
   warns when low on Android (iOS still requires it) and remains required before a sweep.

Existing invoice records preserve the invoice ID and receiver derived from their historical
namespace; iOS also preserves that namespace per invoice. The app does not expose or reuse a global
legacy identifier for new invoices. Any authorized vault owner or operator can sweep those
historical receivers because authorization depends on the settlement transaction sender, not the
invoice namespace.

### Settlement transaction lifecycle

- Android offers an opt-in unattended mode only after a one-time Admin/setup and OS-authenticated
  enrollment. The grant is bound to one exact chain, vault, and operator and can cover the configured
  payment assets on that target. Newly issued payments still pass the full canonical preflight,
  exact-calldata, nonce, fee, balance, endpoint-generation, durable-signature, broadcast-recovery,
  and `Swept`-event checks below. There is no per-payment prompt while the device remains secure and
  unlocked. Device lock defers signing; disabling auto-sweep, changing its configuration or RPC,
  hitting a safety limit, or resetting the operator revokes the grant. Late payments remain manual.
  Manual settlement continues to require explicit review and fresh device authentication.
- Group only paid or overpaid invoices, or previously swept invoices with separately confirmed
  positive late value, with the same immutable chain, RPC, deployment, vault, payment-asset
  metadata, and
  confirmation policy; batches are capped.
- Encode `sweepSessions(bytes32[],uint256[],address)` locally using each invoice's original expected
  raw amount. Reject empty, duplicate, mixed, zero, or corrupted inputs.
- Check the historical invoice profile's chain, contract links, payment-asset whitelist and native
  capability when applicable, operator authorization, confirmed
  receiver balances, simulation, gas estimate, current pending nonce, and conservative maximum fee.
- Before sweeping an invoice from an earlier provisioning, re-derive its receiver and use the
  active approved RPC to re-prove its network label, known-chain factory/implementation pins,
  vault runtime/factory link, payment-asset whitelist, capability, and metadata. Recheck current
  EOA authorization, exact balances, and simulation immediately before signing, then atomically
  activate that historical chain/vault target for the constrained signer.
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

Base Mainnet is enabled and is the fresh-configuration default, but production readiness still
requires an operational RPC sized for live volume plus the recovery and device testing below.
Reconciliation intentionally trusts only canonical receipt logs for transactions the terminal
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

The script runs the mobile boundary guard, verifies the pinned iMin printer AAR bytes, runs Android
SDK/app tests, Maven publication, app lint, debug assembly, and unsigned release-mode assembly,
then Swift build/tests and the conformance executable.
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
