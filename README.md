# ERC-681 Terminal

ERC-20 payment terminal apps and reusable SDKs for Android and iOS. The apps display canonical
ERC-681 payment QR codes, can scan QR codes to import configuration addresses, and can settle
confirmed payments through a tightly constrained device-local operator wallet.

The terminal creates a unique invoice, derives its receiver locally with CREATE2, presents a
canonical ERC-681 QR code, and observes the receiver's ERC-20 balance through read-only JSON-RPC.
After confirmation, the native app may sign exactly one allowed contract method:
`ClearingVault.sweepSessions`. The receiver contract then moves the payment into the merchant
vault; the terminal never chooses a payout destination.

## Safety boundary

- ERC-20 `transfer` QR payments only
- No NFC, contactless-card, customer payment-QR import, or camera-triggered payment action
- Camera scanning is available only beside contract and token fields in Settings. It accepts one
  non-zero EVM address, including an address-only `ethereum:` QR, and rejects payment URIs and all
  other payloads without changing the field.
- The reusable Android and Swift payment SDKs remain keyless and read-only.
- Each native app can generate a separate, device-local secp256k1 settlement-operator wallet.
  Existing random identifiers are retained only for legacy invoice namespaces and are never
  reinterpreted as wallet keys.
- Signing is restricted to the configured chain and vault, zero native value, whitelisted token,
  confirmed locally persisted invoices, and the `sweepSessions` selector. There is no arbitrary
  transaction, transfer, approval, payout, refund, deployment, private-key export, or seed import.
- The merchant must grant the displayed operator address on the vault and fund it with a small
  amount of the chain's native token for gas. Customer ERC-20 payments still go only to one-time
  receiver addresses.
- Settings shows the full operator address with Copy and an address-only, chain-qualified funding
  QR. The operator is a real EOA; the retained legacy identifier is not and must never be funded.
- Receipt success is insufficient: the app waits for confirmations and verifies a matching,
  non-zero `Swept` event before recording settlement.

Native-asset `?value=` requests and non-transfer calls fail closed.

## Payment flow

1. Validate the configured chain, contracts, vault, token whitelist, and token decimals.
2. Generate an invoice ID and derive the counterfactual receiver locally.
3. Refuse receiver reuse if code or an existing token balance is detected.
4. Display the canonical ERC-681 ERC-20 transfer QR.
5. Observe partial payment, confirmations, exact payment, overpayment, or expiry.
6. Persist the paid or overpaid invoice without replacing the confirmed payment observation.
7. Verify operator authorization and native gas balance, then simulate and estimate a constrained
   `sweepSessions` transaction.
8. Persist the signed transaction before broadcast and reconcile it after restart or an ambiguous
   RPC response.
9. Decode confirmed `Swept` events per invoice; zero, partial, malformed, duplicate, or mismatched
   evidence never becomes a successful full settlement.

## Repository layout

```text
android/       Jetpack Compose app, read-only Kotlin SDK, and isolated app operator wallet
ios/           SwiftUI app, Core/RPC/Operator Swift packages, tests, and generated Xcode project
conformance/   Shared invoice, CREATE2, ABI, amount, and URI vectors
scripts/       Boundary and reproducible verification checks
MOBILE_SDK.md  Integration and deployment guide
```

## Default network

The sample apps default to the currently deployed Base Sepolia stack on chain `84532`. No Base
mainnet configuration is shipped. Production or mainnet use must wait for independently verified
deployment constants and a matching CREATE2 vector. The current sample also lacks cross-operator
`Swept`-log discovery and same-nonce fee replacement/cancellation; see the recovery limits in
[MOBILE_SDK.md](./MOBILE_SDK.md) before operating the settlement wallet.

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

See [MOBILE_SDK.md](./MOBILE_SDK.md) for SDK examples, exact configuration, lifecycle details,
and build outputs.

## License

Proprietary — OpenPasskey Pty Ltd | ACN 688 670 420
