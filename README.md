# ERC-681 Terminal

QR-only ERC-20 payment terminal apps and reusable SDKs for Android and iOS.

The terminal creates a unique invoice, derives its receiver locally with CREATE2, presents a
canonical ERC-681 QR code, and observes the receiver's ERC-20 balance through read-only JSON-RPC.
It does not hold or move funds.

## Safety boundary

- ERC-20 `transfer` QR payments only
- No NFC, contactless-card, camera, or QR-scanning capability
- No wallet, private key, transaction signing, or write-RPC API
- No sweep, payout, refund, approval, or deployment controls
- Settlement handoff is metadata only and belongs to a separate approved operator system

Native-asset `?value=` requests and non-transfer calls fail closed.

## Payment flow

1. Validate the configured chain, contracts, vault, token whitelist, and token decimals.
2. Generate an invoice ID and derive the counterfactual receiver locally.
3. Refuse receiver reuse if code or an existing token balance is detected.
4. Display the canonical ERC-681 ERC-20 transfer QR.
5. Observe partial payment, confirmations, exact payment, overpayment, or expiry.
6. Persist the invoice and optionally export data-only settlement metadata.

## Repository layout

```text
android/       Jetpack Compose app and pure Kotlin/JVM SDK
ios/           SwiftUI app, Swift Package SDK, tests, and generated Xcode project
conformance/   Shared invoice, CREATE2, ABI, amount, and URI vectors
scripts/       Boundary and reproducible verification checks
MOBILE_SDK.md  Integration and deployment guide
```

## Default network

The sample apps default to the currently deployed Base Sepolia stack on chain `84532`. No Base
mainnet configuration is shipped. Production or mainnet use must wait for independently verified
deployment constants and a matching CREATE2 vector.

## Verify

Requirements: JDK 17, Android SDK platform 35, and Swift 6. Full iOS app compilation additionally
requires Xcode and an installed iOS SDK.

```bash
./scripts/verify-mobile.sh
```

The command runs the QR-only/keyless boundary check, Android SDK tests and Maven publication,
Android lint plus debug/release-mode assembly, Swift package build, shared conformance checks, and
optional Xcode project regeneration.

See [MOBILE_SDK.md](./MOBILE_SDK.md) for SDK examples, exact configuration, lifecycle details,
and build outputs.

## License

Proprietary — OpenPasskey Pty Ltd | ACN 688 670 420
