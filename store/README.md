# OPK Terminal public-store submission pack

This directory contains draft English (Australia) metadata and submission checklists for the OPK
Terminal `0.1.12` build/version-code `14` release candidate.

## Status

The copy and mandatory listing assets are staged after the owner resolves the blockers below. The
Google Play listing was saved as a draft on 26 July 2026 with the validated icon, feature graphic,
five historical build-13 Android phone screenshots, and tablet screenshots whose exact
capture-build provenance is not documented. App Store Connect contains five historical build-13
iPhone 6.9-inch screenshots and four historical build-13 iPad 13-inch screenshots. As preserved
submission history, the signed Play AAB at
`artifacts/android/v0.1.12/OPK-Terminal-v0.1.12-build13-play-signed.aab` was uploaded, validated as
version `13 (0.1.12)` / target SDK 35, and saved with its release notes in an internal-testing
release draft on 26 July 2026. That build-13 draft predates the isolated offline product tour and
must not be treated as the build-14 release candidate. Its keystore and password remain outside
source control. No release has been rolled out and no store submission has been sent for review.
The developer account email is selected in the internal-testing list. At the last verified console
check on 26 July 2026, the release preview also showed an optional native debug-symbol
recommendation. Working reviewer access, legal acceptances, and final owner-controlled submissions
are not included.

The current local Google Play candidate is the signed API-36 AAB at
`artifacts/android/v0.1.12/OPK-Terminal-v0.1.12-build14-play-signed.aab`, SHA-256
`d6ed150884e21f1491ed9dba68bf249af77be9cd95989b265eb8ef7c6e896b76`. It has not been uploaded.
The exact AAB passed bundletool validation, device-specific split installation, and an API-36
offline product-tour smoke test. Its exact release code and current screenshot PNGs are recorded by
source/assets commit `477817a59ecce16319fc13023fbe575e88ebd9a6`.

## Contents

- `apple/en-AU.md` — App Store metadata.
- `google-play/en-AU.md` — Google Play metadata.
- `google-play/CLOSED_TEST_HANDOFF.md` — personal-account closed-test gate, tester onboarding,
  feedback checklist, and roster placeholders.
- `review/reviewer-instructions.md` — offline review path and live-testnet fallback instructions.
- `console-declarations.md` — code-grounded App Store Connect and Play Console checklist.
- `assets/CAPTURE_MANIFEST.md` — asset inventory, capture provenance, and exact export targets.

## Submission blockers

1. **The app-specific privacy and support pages are live.** OpenPasskey website PR #86 was merged
   and production verification confirms that `https://www.openpasskey.com/privacy` states the
   first-party fact that OpenPasskey operates no app backend and does not receive or store OPK
   Terminal app data. `https://www.openpasskey.com/support` contains app-specific setup and support
   guidance. The store console answers remain separate store-scoped declarations.
2. **Verify the isolated offline product tour in build 14.** The cold-launch tour gives reviewers
   a representative checkout, payment-status, history, and disabled-settlement flow without an
   account, provisioning QR, network, storage, authentication, signing, or test funds. Complete the
   clean-device isolation checks in `review/reviewer-instructions.md` before uploading build 14.
3. **Build-14 store assets are staged, not uploaded.** The included build-14 merchant-core and
   product-tour sets for iPhone 6.9-inch, iPad 13-inch, and signed Play phone satisfy their
   mandatory listing dimensions. The consoles still contain historical build-13 phone and Apple
   sets plus tablet sets with undocumented capture-build provenance. Replace them only after final
   candidate-parity review. The historical 720 × 1440 Play phone captures meet Play's mandatory
   rules but not its optional 1080-pixel promotion recommendation; both staged build-14 Play phone
   sets are 1440 × 2560.
4. **Store privacy/data declarations must remain service-accurate and separately scoped.** The app
   sends blockchain reads and signed settlement transactions to Base's public JSON-RPC provider.
   Base's published policy says its services may collect IP/device information and analyse public
   blockchain data, including wallet addresses, signatures, transaction IDs, amounts, and
   timestamps. QR frames are decoded entirely on-device with ZXing; the build-14 Android release
   dependency graph contains no ML Kit, Firebase, or Google Data Transport component. Keep these
   provider facts in the Apple/Google compliance declarations. Per the owner's direction, the
   public OpenPasskey privacy page remains a first-party statement about what OpenPasskey receives
   and stores. Google separately requires the linked privacy policy to comprehensively describe
   relevant off-device handling and recipient parties, so the Android submission must remain
   blocked until that policy mismatch is resolved without filing a contradictory Data safety form.
5. **Merchant-tool classification is applied.** The Play financial-features draft classifies OPK
   Terminal as a merchant mobile-payment tool, not a cryptocurrency wallet or financial-services
   provider. This product classification does not remove store privacy categories for transaction
   or wallet data sent off-device. Final territories and any legal/compliance determinations
   remain owner decisions.
6. **The dedicated support destination is live.** `https://www.openpasskey.com/support` provides
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
- No OpenPasskey-operated advertising, analytics, crash-reporting, or app backend. Android QR
  decoding is on-device with ZXing.
