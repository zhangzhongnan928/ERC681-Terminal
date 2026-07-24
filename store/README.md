# OPK Terminal public-store submission pack

This directory contains draft English (Australia) metadata and submission checklists for OPK
Terminal `0.1.12` (build/version code `13`).

## Status

The copy is ready to paste after the owner resolves the blockers below. The pack includes a
validated Google Play icon and feature graphic, five current Android physical-device screenshots,
five iPhone 6.9-inch screenshots, and four iPad 13-inch screenshots. Credentials, a live
provisioning QR, Google Play signing material, console configuration, and legal determinations are
not included.

## Contents

- `apple/en-AU.md` — App Store metadata.
- `google-play/en-AU.md` — Google Play metadata.
- `review/reviewer-instructions.md` — review-notes template and live test-environment needs.
- `console-declarations.md` — code-grounded App Store Connect and Play Console checklist.
- `assets/CAPTURE_MANIFEST.md` — current asset inventory and exact capture/export targets.

## Submission blockers

1. **Privacy policy is not submission-ready.** `https://www.openpasskey.com/privacy` is live, but
   on 24 July 2026 it labels itself a working draft, says it is not legal advice and should not be
   relied on until reviewed, and does not specifically disclose OPK Terminal's Base Sepolia
   JSON-RPC requests or the RPC provider's handling of them. The owner/legal team must publish a
   binding, app-specific policy before submission.
2. **A self-service review environment is required.** Provisioning is bound to the operator EOA
   created on the review device. A static QR prepared in advance cannot exercise the complete
   flow. Keep a live Base Sepolia review vault/token, Merchant Portal access, test ETH, and test
   tokens available throughout both reviews.
3. **Store assets need final owner approval and signed-build recheck.** The included iPhone
   6.9-inch, iPad 13-inch, and Play sets satisfy their mandatory listing dimensions. The Apple
   captures show the current first-run and setup UI without fabricated provisioning. Recheck them
   against the archived App Store build and the final signed Play AAB before console submission.
   The current 720 × 1440 Play captures are uploadable but do not meet Google's optional 1080 px
   recommendation-placement guidance.
4. **Privacy/data declarations need service-provider confirmation.** The app sends blockchain
   reads and signed settlement transactions to Base's public JSON-RPC provider. Base's published
   privacy policy says its services may collect IP/device information and analyse public
   blockchain data, including wallet addresses, signatures, transaction IDs, amounts, and
   timestamps. Android also includes Google's bundled ML Kit barcode SDK, whose published
   disclosure lists device/app information, a per-install identifier, performance metrics, API
   configuration, feature input/output size and version, event types, and error codes for
   diagnostics and usage analytics. Confirm retention and privacy roles and reflect both services
   in the published policy and console answers. Do not select “Data Not Collected” or the Play
   equivalent.
5. **Financial/crypto classification needs owner/legal approval.** The app is Base Sepolia
   testnet-only and has no exchange, mining, or customer custody, but it creates a device-local
   merchant operator key and signs constrained settlement transactions. Do not select “no
   financial features” without reviewing the store policies and distribution territories.
6. **Support destination needs owner confirmation.** The requested support URL,
   `https://www.openpasskey.com/about`, is live and links to the team, but a dedicated support page
   with an obvious contact route would be stronger for review and ongoing customer support.

## Product boundary used in this pack

- Merchant-facing app, not a customer wallet.
- Base Sepolia testnet only; Base Mainnet and every other production network are disabled.
- Requires OpenPasskey provisioning to an authorised merchant vault and supported test token.
- Creates ERC-681 test-token payment QRs, monitors public-chain state, and performs a constrained
  `sweepSessions` settlement through a device-local operator EOA.
- Does not exchange, buy, sell, swap, mine, issue, or reward tokens.
- Does not store customer private keys or take custody of customer assets.
- Free download with no in-app purchases, subscriptions, ads, app account, or sign-in.
- No OpenPasskey-operated advertising or general-purpose analytics SDK; Android ML Kit's
  documented diagnostic and usage-metrics collection still applies.
