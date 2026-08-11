# OPK Terminal public-store submission pack

This directory contains English (Australia) metadata and submission checklists for the existing
OPK Terminal `0.1.12` build/version-code `13` store candidate.

## Status

The Google Play listing contains the validated icon, feature graphic, five build-13 Android phone
screenshots, and tablet screenshots whose exact capture-build provenance is not documented. The
signed AAB at
`artifacts/android/v0.1.12/OPK-Terminal-v0.1.12-build13-play-signed.aab` is uploaded as version
`13 (0.1.12)`, targets SDK 35, and was published to the internal-testing track on 26 July 2026 at
7:27 PM Australia/Sydney. The track is active and available to the selected one-address tester
list. `zhangzhongnan928@gmail.com` still needs to accept the invitation at
`https://play.google.com/apps/internaltest/4701183593011427876`.

App Store Connect has iOS `0.1.12` build `13` attached, five build-13 iPhone 6.9-inch screenshots,
and four build-13 iPad 13-inch screenshots. The listing and App Review contact metadata are saved.
The app is Free, Public, Business, rated 4+, and available in 175 countries or regions. It remains
**Prepare for Submission** because the App Privacy response is an unpublished draft.

Build 13 predates the isolated offline product tour. It opens the normal Checkout, History,
Settlement, and Settings tabs and must never be described as containing the build-14 demo. A
signed API-36 build-14 AAB exists locally for preservation, but the owner explicitly selected the
already-uploaded build 13 and no new binary is authorised for this release.

## Contents

- `apple/en-AU.md` — App Store metadata.
- `google-play/en-AU.md` — Google Play metadata.
- `google-play/CLOSED_TEST_HANDOFF.md` — personal-account closed-test gate, tester onboarding,
  feedback checklist, and roster placeholders.
- `review/reviewer-instructions.md` — exact build-13 review limitations and access requirements.
- `console-declarations.md` — code-grounded App Store Connect and Play Console checklist.
- `assets/CAPTURE_MANIFEST.md` — asset inventory, capture provenance, and exact export targets.

## Submission blockers

1. **The app-specific privacy and support pages are live.** OpenPasskey website PR #86 was merged
   and production verification confirms that `https://www.openpasskey.com/privacy` states the
   first-party fact that OpenPasskey operates no app backend and does not receive or store OPK
   Terminal app data. `https://www.openpasskey.com/support` contains app-specific setup and support
   guidance. The store console answers remain separate store-scoped declarations.
2. **Supply truthful build-13 review access.** Core payment creation and settlement require the
   reviewing device's newly generated operator to be authorised through the Merchant Portal, then
   provisioned and funded on Base Sepolia. A static QR from another device cannot provide full
   access. Do not submit the saved Play instruction that claims an offline product tour exists.
3. **Keep build-13 assets and binary aligned.** The uploaded build-13 phone and Apple screenshots
   are the release evidence. Build-14 merchant-core and product-tour assets remain staged only and
   must not replace or relabel the build-13 store assets while build 13 is the selected candidate.
4. **Store privacy/data declarations must remain service-accurate and separately scoped.** The app
   sends blockchain reads and signed settlement transactions to Base's public JSON-RPC provider.
   Base's published policy says its services may collect IP/device information and analyse public
   blockchain data, including wallet addresses, signatures, transaction IDs, amounts, and
   timestamps. Build 13 also embeds ML Kit barcode scanning, whose vendor disclosure includes
   device/app information, a per-install identifier, and diagnostic/usage telemetry; camera frames
   and decoded QR contents stay on-device. Keep these facts in the Apple/Google store declarations.
   Per the owner's direction, the
   public OpenPasskey privacy page remains a first-party statement about what OpenPasskey receives
   and stores. Google separately requires the linked privacy policy to comprehensively describe
   relevant off-device handling and recipient parties, so the Android submission must remain
   blocked until that policy mismatch is resolved without filing a contradictory Data safety form.
5. **Merchant-tool classification is applied.** The Play financial-features draft classifies OPK
   Terminal as a merchant mobile-payment tool, not a cryptocurrency wallet or financial-services
   provider. This product classification does not remove store privacy categories for transaction
   or wallet data sent off-device. Final territories and any legal/compliance determinations
   remain owner decisions.
6. **Finish the platform gates.** Apple App Privacy needs an accurate published response before
   build 13 can be added for review. Play still needs the IARC Terms acceptance and questionnaire,
   a policy-consistent Data safety response, and a qualifying closed test with 12 continuously
   opted-in testers for 14 days; only one candidate email is currently supplied.
7. **The dedicated support destination is live.** `https://www.openpasskey.com/support` provides
   setup guidance, troubleshooting, a monitored contact route, and a warning not to send private
   keys, PINs, or provisioning QRs.

## Product boundary used in this pack

- Merchant-facing app, not a customer wallet.
- Base Sepolia testnet only; Base Mainnet and every other production network are disabled.
- Requires OpenPasskey provisioning to an authorised merchant vault and supported test token.
- Creates ERC-681 test-token payment QRs, monitors public-chain state, and performs a constrained
  `sweepSessions` settlement through a device-local operator EOA.
- Does not exchange, buy, sell, swap, mine, issue, or reward tokens.
- Does not store customer private keys or take custody of customer assets.
- Free download with no in-app purchases, subscriptions, ads, app account, or sign-in.
- No OpenPasskey-operated advertising, analytics, crash-reporting, or app backend. Build-13 camera
  frames and decoded QR contents stay on-device, while the bundled ML Kit component separately
  reports the vendor-disclosed device/app and diagnostic telemetry described in
  `console-declarations.md`.
