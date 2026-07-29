# ERC-681 Terminal

ERC-20 payment terminal apps and reusable SDKs for Android and iOS. The apps display canonical
ERC-681 payment QR codes, can scan QR codes to import configuration addresses, and can settle
confirmed payments through a tightly constrained device-local operator wallet.

A terminal can keep up to 32 payment profiles. Each profile binds one known EVM network, merchant
vault, and ERC-20 token. The same cap is enforced by both native apps and both reusable SDK
catalogs. The cashier chooses exactly one profile for a sale—for example AUDM on one vault, AUDD on
another, or USDC on a third—and the resulting invoice requests only that token.

The terminal creates a unique invoice, derives its receiver locally with CREATE2, presents a
canonical ERC-681 QR code, and observes the receiver's ERC-20 balance through read-only JSON-RPC.
After confirmation, the native app may sign exactly one allowed contract method:
`ClearingVault.sweepSessions`. The receiver contract then moves the payment into the merchant
vault; the terminal never chooses a payout destination.

## For wallet developers

Terminals present payment requests as canonical ERC-681 ERC-20 transfer URIs:

```text
ethereum:{TOKEN}@{CHAIN_ID}/transfer?address={RECEIVER}&uint256={RAW_TOKEN_UNITS}
```

Example (Base Sepolia, 12.34 units, 18-decimal token):

```text
ethereum:0x7ffba642bc902880a737cb1c18a4e9540879e211@84532/transfer?address=0x8ad9a4b36c67eafc6ebd08e329e410c932cbfa1c&uint256=12340000000000000000
```

A wallet is compatible if it scans this QR, prefills
`token.transfer(receiver, amount)` on the specified chain, and lets the user send. Use raw integer
token units only: no exponents, no percent-encoding, and no native value.

Machine-readable vectors, including CREATE2 receiver derivation, are in
[`conformance/opk-erc681-v1.json`](./conformance/opk-erc681-v1.json). Base Sepolia test QR codes
are available on request from [v@openpasskey.com](mailto:v@openpasskey.com). See
[erc681.org](https://erc681.org/) for the ERC-681 specification, wallet-adoption research, and
developer resources.

## Safety boundary

- ERC-20 `transfer` QR payments only
- No NFC, contactless-card, customer payment-QR import, or camera-triggered payment action
- Camera scanning remains available beside contract and token fields in Settings. Those scanners
  accept only one non-zero EVM address, including an address-only `ethereum:` QR, and reject payment
  URIs and all other payloads without changing the field. A separate setup scanner accepts only the
  strict `opk-terminal:provision` payload documented in [PROVISIONING.md](./PROVISIONING.md).
- The reusable Android and Swift payment SDKs remain keyless and read-only.
- Each native app generates a separate, device-local secp256k1 operator wallet. Its public address
  is the terminal identity used as the `terminalIdentifier` namespace for every new invoice, so an
  operator wallet must exist before the app can present a new payment QR.
- Historical invoice records retain their invoice ID, configuration snapshot, and derived receiver
  unchanged. New invoices on both platforms also retain the device EOA used as their namespace;
  Android rows migrated from the earlier schema keep an empty legacy operator snapshot and remain
  subject to the current-wallet and fresh on-chain authorization checks. The app does not reuse a
  legacy random identifier for new invoices or reinterpret one as a wallet key.
- Signing is restricted to the invoice profile's chain and vault, zero native value, whitelisted token,
  confirmed locally persisted invoices, and the `sweepSessions` selector. There is no arbitrary
  transaction, transfer, approval, payout, refund, deployment, private-key export, or seed import.
- Vault authorization and native-token gas funding are also new-invoice readiness checks. The apps
  freshly validate configuration, owner/operator authorization, and the selected network profile's
  minimum native-gas reserve (`0.0001 ETH` on Base Sepolia) before creating each customer invoice.
  Failure blocks only new invoice/QR creation; history,
  existing payment monitoring, settlement recovery, and setup remain available. Customer ERC-20
  payments still go only to one-time receiver addresses.
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
  changes without deleting the key. Before an allowed reset, the shipped trusted RPC must report
  both latest and pending native balances as exactly zero twice; late deposits to the retired,
  previously shared address are still possible and unrecoverable.

Native-asset `?value=` requests and non-transfer calls fail closed.

## Public RPC performance policy

The shipped apps are designed to operate directly against the compiled public RPC endpoint; they
do not require a paid provider, proxy, backend, or Multicall deployment. Independent read-only
calls are grouped into strict JSON-RPC batches of at most 10 items. Larger logical proofs are split
into bounded chunks that may run concurrently, and each native transport reuses its own persistent
connection pool. Every required response must contain the exact integer request ID, JSON-RPC
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
balances, contract links, token metadata, simulation, nonce, fees, or canonical block identity into
long-lived cache. Wall-clock time remains dependent on the public endpoint and network conditions.

## Payment flow

1. Select one configured payment profile, then require the device operator wallet and freshly
   validate that profile's chain, contracts, vault, token whitelist, token metadata, operator
   authorization, and native gas reserve.
2. Use the operator public address as the invoice's terminal namespace,
   generate an invoice ID, and derive the counterfactual receiver locally.
3. Refuse receiver reuse if code or an existing token balance is detected.
4. Display the canonical ERC-681 ERC-20 transfer QR.
5. Observe partial payment, confirmations, exact payment, overpayment, or expiry. Persist the
   first-detected block hash with its height and restart confirmation depth when that saved cursor
   is missing or no longer canonical. Continue bounded reconciliation of closed and swept QR
   receivers because a published address cannot be revoked.
6. Persist the paid or overpaid invoice without replacing the confirmed payment observation.
7. Re-prove even historical invoice snapshots against the shipped chain pins and trusted RPC,
   verify current operator authorization and exact balances through the operational RPC, then
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

Base Sepolia (`84532`) is the only network enabled in the production apps in this release. A
provisioning QR chooses an enabled chain but cannot supply or override its RPC trust root, factory,
receiver implementation, vault runtime hash, protocol version, CREATE2 vector, finality floor,
native-currency metadata, or minimum operator gas reserve. The shared pins are recorded in
`conformance/opk-terminal-networks-v1.json`. The Swift and Kotlin payment-profile
catalogs remain EVM-generic. Base Sepolia's compiled confirmation minimum and fresh-network default
are both one block; the block containing the payment is confirmation one. In Admin/setup, a merchant
administrator can choose the confirmation requirement for each enabled EVM network within its
allowed range. Every profile on the same chain shares that network policy, and a new profile inherits
the existing choice. The value is snapshotted into new invoices and settlement batches, while
existing invoices retain their original requirement. The strict v1 provisioning QR does not carry
or override this local policy. Base Mainnet (`8453`) and any additional network remain app-disabled
until a frozen or multisig-governed, implementation-pinned OPK deployment and its CREATE2 vector
are reviewed and shipped; arbitrary QR-provided network infrastructure remains unsupported.

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

Requirements: JDK 17, Android SDK platform 35, Swift 6.1+, XcodeGen, ripgrep (`rg`), and a full Xcode
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

This code has not been independently audited. Base Sepolia (`84532`) is the only network enabled
in shipped builds; Base Mainnet and every other network remain disabled as described above.

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
