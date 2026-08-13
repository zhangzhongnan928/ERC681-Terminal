# ERC-681 Terminal

ERC-20 and chain-native payment terminal apps and reusable SDKs for Android and iOS. The apps
display canonical ERC-681 payment QR codes, can scan QR codes to import configuration addresses,
and can settle confirmed payments through a tightly constrained device-local operator wallet.

A terminal can keep up to 32 payment profiles. Each profile binds one known EVM network, merchant
vault, and payment asset: either an ERC-20 token or the chain's native asset under OPK Protocol 1.6.
The same cap is enforced by both native apps and both reusable SDK catalogs. The cashier chooses
exactly one profile for a sale—for example AUDM on one vault, USDC on another, or ETH on a
native-enabled vault—and the resulting invoice requests only that asset.

The terminal creates a unique invoice, derives its receiver locally with CREATE2, presents a
canonical ERC-681 QR code, and observes the receiver's ERC-20 or native balance through read-only
JSON-RPC. After confirmation, the native app may sign exactly one allowed contract method:
`ClearingVault.sweepSessions`. The receiver contract then moves the payment into the merchant vault;
the terminal never chooses a payout destination or uses receiver funds for gas.

## For wallet developers

Terminals present one of two canonical ERC-681 forms. ERC-20 invoices use:

```text
ethereum:{TOKEN}@{CHAIN_ID}/transfer?address={RECEIVER}&uint256={RAW_TOKEN_UNITS}
```

Native invoices use the receiver as the target:

```text
ethereum:{RECEIVER}@{CHAIN_ID}?value={AMOUNT_WEI}
```

Examples (Base Sepolia, 12.34 units at 18 decimals):

```text
ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0xbbd352de4428d535ac79849abefa8d69bb51c671&uint256=12340000000000000000
ethereum:0xbbd352de4428d535ac79849abefa8d69bb51c671@84532?value=12340000000000000000
```

A wallet is compatible if it scans the relevant QR, prefills either
`token.transfer(receiver, amount)` or a plain native-value transfer on the specified chain, and lets
the user send. Use raw integer token units or wei only: no exponents or percent-encoding. The
EIP-7528 sentinel is an on-chain identifier and never appears in a payment QR.

Machine-readable vectors, including CREATE2 receiver derivation, are in
[`conformance/opk-erc681-v1.json`](./conformance/opk-erc681-v1.json). Base Sepolia test QR codes
are available on request from [v@openpasskey.com](mailto:v@openpasskey.com). See
[erc681.org](https://erc681.org/) for the ERC-681 specification, wallet-adoption research, and
developer resources.

## Safety boundary

- Canonical ERC-20 `transfer` and native `?value=` QR payments only
- No NFC, contactless-card, customer payment-QR import, or camera-triggered payment action
- Camera scanning remains available beside contract and payment-asset fields in Settings. Those scanners
  accept only one non-zero EVM address, including an address-only `ethereum:` QR, and reject payment
  URIs and all other payloads without changing the field. A separate setup scanner accepts only the
  strict `opk-terminal:provision` payload documented in [PROVISIONING.md](./PROVISIONING.md).
- The reusable Android and Swift payment SDKs remain keyless and read-only.
- Their optional incoming-payment evidence resolvers use only the invoice's saved publication and
  funding cursors to attribute a direct ERC-20 or native customer transaction. An unattributable
  internal native balance change remains without receipt evidence, and a later settlement or sweep
  hash is never substituted for the customer's transaction.
- Each native app generates a separate, device-local secp256k1 operator wallet. Its public address
  is the terminal identity used as the `terminalIdentifier` namespace for every new invoice, so an
  operator wallet must exist before the app can present a new payment QR.
- Historical invoice records retain their invoice ID, configuration snapshot, and derived receiver
  unchanged. New invoices on both platforms also retain the device EOA used as their namespace;
  Android rows migrated from the earlier schema keep an empty legacy operator snapshot and remain
  subject to the current-wallet and fresh on-chain authorization checks. The app does not reuse a
  legacy random identifier for new invoices or reinterpret one as a wallet key.
- Signing is restricted to the invoice profile's chain and vault, zero transaction value, a
  whitelisted payment asset, confirmed locally persisted invoices, and the `sweepSessions`
  selector. There is no arbitrary
  transaction, transfer, approval, payout, refund, deployment, private-key export, or seed import.
- On Android, unattended auto-sweep is an explicit one-time Admin/setup enrollment. The
  administrator authenticates with the device once to create a separate locked-device Keystore
  grant for one exact chain, vault, and operator. Eligible newly issued payments are then
  canonically revalidated, signed, durably recorded, and broadcast without a per-payment dialog.
  Device lock, configuration or RPC changes, disabling the option, safety-limit failures, and
  operator reset revoke or block the grant. Late payments remain manual. Manual settlement keeps
  its existing review and per-use device authentication.
- Vault authorization and native-token gas funding are also new-invoice readiness checks. The apps
  freshly validate configuration, owner/operator authorization, and the selected network profile's
  minimum native-gas reserve (`0.0001 ETH` on the shipped Base profiles) before creating each
  customer invoice.
  Failure blocks only new invoice/QR creation; history,
  existing payment monitoring, settlement recovery, and setup remain available. Customer ERC-20
  and native payments still go only to one-time receiver addresses.
- Settings shows the full operator address with Copy and an address-only, chain-qualified funding
  QR. This is the same real EOA whose public address identifies new invoices and whose private key
  signs constrained settlement transactions.
- Receipt success is insufficient: the app waits for confirmations and verifies a matching,
  non-zero `Swept` event before recording settlement. Published closed and previously swept
  receivers, including ambiguous receipt-review rows, are revisited in small durable
  least-recently-attempted passes; confirmed positive value can be swept again. Ambiguity clears
  only when cumulative canonical proof covers the original expected amount.
- Destructive operator-key reset is allowed only before the first payment QR is issued. A published
  receiver remains payable forever, so later administration uses reprovisioning/authorization
  changes without deleting the key. Before an allowed reset, the active approved RPC must report
  both latest and pending native balances as exactly zero twice; late deposits to the retired,
  previously shared address are still possible and unrecoverable.

The native asset is offered only after a successful vault `NATIVE_ASSET()` read returns the exact
EIP-7528 sentinel and that sentinel is whitelisted. `isPaymentToken(sentinel)` alone is not a
Protocol 1.6 capability probe. Non-canonical payment forms and all other contract calls fail closed.

## RPC endpoint and performance policy

Android resolves each Base network endpoint in this order: an administrator override encrypted on
the terminal, then an optional build-managed endpoint. Debug builds alone may use the compiled
public Base fallback for local development. Configure build
defaults without committing them by setting `OPK_BASE_MAINNET_RPC_URL` and
`OPK_BASE_SEPOLIA_RPC_URL` (or Gradle properties `opkBaseMainnetRpcUrl` and
`opkBaseSepoliaRpcUrl`) before building. Admin/setup also provides a masked text field and a QR
scanner for replacing or clearing one chain's endpoint. Scanning fills the field only; the app
requires explicit verification and save. The portal provisioning QR cannot carry an RPC URL.
A release build may intentionally leave the build-managed values empty so each terminal can receive
a revocable client endpoint through Admin/setup. In that mode, setup fails closed and requires a
saved endpoint for the matching Base Mainnet or Base Sepolia portal QR. Release builds reject an
explicit Base public RPC host and never silently fall back to one. Do not put credential-bearing
values in a tracked Gradle properties file.
Saving an administrator override deliberately changes that chain's read trust source. The app
checks the endpoint's reported chain and compiled OPK deployment pins, but no client can prevent a
chosen RPC server from fabricating responses. Configure only a provider the merchant trusts.

iOS Admin/setup provides the same per-network separation between replaceable RPC transport and
compiled chain/deployment pins. It verifies a masked HTTPS candidate before saving it as a
non-synchronizing, device-only Keychain item. The active iOS endpoint is applied to provisioning,
payment monitoring, receipt evidence, and settlement without copying a credential-bearing URL into
ordinary settings or immutable history. The compiled public Base endpoint remains a visibly labeled,
rate-limited fallback and can be restored only through an explicit Admin action.
On upgrade, an endpoint saved by an older iOS release is copied into Keychain before its redundant
settings, invoice, and settlement URL fields are replaced with the compiled fallback. If Keychain
migration fails, the old value is retained for retry but is neither reported as built-in nor used as
runtime transport.

Provider URLs can contain client credentials and are therefore never copied into the normal chain
catalog, invoices, settlement history, receipts, logs, or long-lived status text. The
secure editor holds the submitted value only as ephemeral local UI state. Admin overrides are
encrypted with an Android Keystore key or stored as device-only iOS Keychain items. BuildConfig
values and direct provider credentials remain
extractable from a sufficiently controlled client, so they must be revocable client credentials,
never server secrets. Prefer a credential-free OPK gateway URL for fleet builds. A Coinbase CDP Client API Key
is an acceptable direct-mobile fallback because Coinbase documents that key type for client-side
use. Do not ship a CDP Secret API Key. For Alchemy, use an OPK gateway instead of a permanent URL
key. The gateway may authenticate upstream with a short-lived JWT, but the terminal currently
accepts URL endpoints only, so Alchemy JWT authentication is supported only behind that gateway
today. Never paste a JWT into the RPC URL field or encode one in its QR code.

Independent read-only calls are grouped into strict JSON-RPC batches of at most 10 items. Larger logical proofs are split
into bounded chunks that may run concurrently, and each short-lived native transport owns and
closes its connection pool. Every required response must contain the exact integer request ID, JSON-RPC
version, and complete result set. Missing, duplicate, unexpected, malformed, failed-required, or
wrong-block responses fail closed. A narrowly optional compatibility read, such as `owner()` after
`isOperator` already proved authorization, is handled explicitly rather than weakening the batch.

Checkout still performs fresh mutable chain validation before every customer QR. Only background
readiness may reuse a short configuration proof. Settlement evidence is bound to the exact intent,
configuration, balances, and canonical cursors; its original lifetime is never rolled forward and
is capped at 60 seconds and checked again before key use. Payment polling runs every five seconds and automatic recovery is
scheduled every 60 seconds. Once cashier work is requested, no new background unit starts; one
already-started bounded unit may finish or briefly overlap under the global concurrency limit.
These controls reduce latency and public-endpoint throttling without turning mutable authorization,
balances, contract links, payment-asset metadata, simulation, nonce, fees, or canonical block identity into
long-lived cache. Wall-clock time remains dependent on the public endpoint and network conditions.

## Payment flow

1. Select one configured payment profile, then require the device operator wallet and freshly
   validate that profile's chain, contracts, vault, payment-asset whitelist, asset metadata or
   native capability, operator authorization, and native gas reserve.
2. Use the operator public address as the invoice's terminal namespace,
   generate an invoice ID, and derive the counterfactual receiver locally.
3. Refuse receiver reuse if code or an existing ERC-20/native balance is detected.
4. Display the canonical ERC-681 ERC-20 transfer or native-value QR.
5. Observe partial payment, confirmations, exact payment, overpayment, or expiry. Persist the
   first-detected block hash with its height and restart confirmation depth when that saved cursor
   is missing or no longer canonical. Continue bounded reconciliation of closed and swept QR
   receivers because a published address cannot be revoked.
6. Persist the paid or overpaid invoice without replacing the confirmed payment observation. When
   the saved publication and funding cursors are available, resolve and conditionally persist the
   direct incoming customer transaction for receipt details; leave it unavailable when the balance
   crossing cannot be attributed safely.
7. Re-prove even historical invoice snapshots through the active approved RPC against the shipped
   chain and deployment pins, verify current operator authorization and exact balances, then
   simulate and estimate a constrained `sweepSessions` transaction. Repeat provenance,
   confirmation-cursor, authorization, exact-balance, and simulation checks immediately before
   activating that historical chain/vault target and signing.
8. Persist the signed transaction before broadcast and reconcile it after restart or an ambiguous
   RPC response.
9. Decode confirmed `Swept` events per invoice only after the receipt block hash matches the
   canonical block at that height and the final head still provides the required depth; zero,
   partial, malformed, duplicate, orphaned, or mismatched
   evidence never becomes a successful full settlement. A positive repeat event can settle newly
   observed late value after prior cumulative proof already covered the original invoice.

## Repository layout

```text
android/       Jetpack Compose app, read-only Kotlin SDK, and isolated app operator wallet
ios/           SwiftUI app, Core/RPC/Operator Swift packages, tests, and generated Xcode project
conformance/   Shared invoice, CREATE2, ABI, amount, and URI vectors
scripts/       Boundary and reproducible verification checks
MOBILE_SDK.md  Integration and deployment guide
```

## Known EVM networks

Base Mainnet (`8453`) and Base Sepolia (`84532`) are enabled in the production apps. Fresh
configuration defaults to Base Mainnet; Base Sepolia remains an explicit testing choice. Their
immutable pins target the published OPK Protocol 1.6 Route A deployments. A
provisioning QR chooses an enabled chain but cannot supply or override its RPC endpoint, factory,
receiver implementation, vault runtime hash, protocol version, CREATE2 vector, finality floor,
native-currency metadata, or minimum operator gas reserve. The shared pins are recorded in
`conformance/opk-terminal-networks-v1.json`. The Swift and Kotlin payment-profile
catalogs remain EVM-generic. Both shipped Base profiles have a compiled confirmation minimum and
fresh-network default of one block; the block containing the payment is confirmation one. In
Admin/setup, a merchant administrator can choose the confirmation requirement for each enabled EVM
network within its allowed range. Every profile on the same chain shares that network policy, and a
new profile inherits the existing choice. The value is snapshotted into new invoices and settlement batches, while
existing invoices retain their original requirement. The strict v1 provisioning QR does not carry
or override this local policy. Any additional network has the same explicit release gate; arbitrary
QR-provided network infrastructure remains unsupported. The separate administrator RPC control
validates a candidate against the selected chain and compiled OPK deployment pins before encrypted
activation. Base's public RPC endpoints are rate-limited and are not production-capacity
guarantees, so every production network must have either an encrypted administrator override or an
optional build-managed endpoint before it can be used.

The current implementation still lacks cross-operator `Swept`-log discovery and same-nonce fee
replacement/cancellation; see the recovery limits in [MOBILE_SDK.md](./MOBILE_SDK.md) before
operating the settlement wallet.

## Set up a terminal

Use the [OPK Pay Merchant Portal](https://paymentportal.openpasskey.com/) from a separate trusted
phone or computer. Create or select the merchant vault, choose **Add Terminal**, scan the operator
QR shown by the terminal, authorize that operator, then scan the portal's generated provisioning QR
back into the terminal.

The portal source and its detailed
[Terminal Setup Flow](https://github.com/Open-Passkey/OPK-Pay-Merchant-Portal#terminal-setup-flow)
are in the
[Open-Passkey/OPK-Pay-Merchant-Portal](https://github.com/Open-Passkey/OPK-Pay-Merchant-Portal)
repository. See [PROVISIONING.md](./PROVISIONING.md) for the exact provisioning payload and the
terminal's device-side verification boundary.

## Verify

Requirements: JDK 17, Android SDK platform 36, Swift 6.1+, XcodeGen, ripgrep (`rg`), and a full Xcode
installation with an iOS Simulator SDK.

```bash
./scripts/verify-mobile.sh
```

The command enforces the payment-QR, Settings-only camera, read-only SDK, and constrained-signer
boundaries; runs Android SDK and app tests, Maven publication, lint, and debug/release-mode
assembly; runs Swift tests and shared conformance checks; proves the generated Xcode project is
current; and compiles the iOS app.

See [PROVISIONING.md](./PROVISIONING.md) for the portal pairing and chain-derived setup protocol,
and [MOBILE_SDK.md](./MOBILE_SDK.md) for SDK examples, exact configuration, lifecycle details, and
build outputs.

## Security and risk

This code has not been independently audited. Base Mainnet (`8453`) is the fresh-install default and
Base Sepolia (`84532`) remains available for testing; every other network remains disabled as
described above.

The safety boundary documented here describes the behaviour of this source tree, built as
published, against the pinned deployments in `conformance/`. Those properties are not guaranteed
to survive a fork, a configuration change, an added network, or a substituted contract deployment.
Running this software with real value is at the operator's own risk; see sections 7 and 8 of
[LICENSE](./LICENSE).

Report vulnerabilities privately — see [SECURITY.md](./SECURITY.md). Do not open a public issue
for a security problem.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE).

Code copyright 2026 Victor Zhang.

The license covers source code, documentation, build scripts, and conformance vectors. It does
not cover the OPK, OPK Pay, OPK Terminal, and OpenPasskey names, the logo, the application icon,
the store icon, or the feature graphic. Those are trademarks and brand assets of OpenPasskey Pty
Ltd (ACN 688 670 420) and are reserved. Forks may ship, but must rebrand — see
[TRADEMARK.md](./TRADEMARK.md).

Contributions are accepted under Apache-2.0 with a DCO sign-off; see
[CONTRIBUTING.md](./CONTRIBUTING.md).
