# App Store Connect and Play Console declaration checklist

Evidence snapshot: repository state inspected on 26 July 2026 for `0.1.12` / build `14`.

This is a code-grounded submission checklist, not legal advice. Store answers must also reflect the
actual production services, contracts, privacy policy, developer account, and distribution
territories at submission time.

## Verified implementation facts

- Android application ID: `com.openpasskey.terminal`; iOS bundle ID:
  `com.openpasskey.terminal.ios`.
- Base Sepolia (`84532`) is the only enabled network. The compiled endpoint is
  `https://sepolia.base.org`; Base Mainnet is disabled.
- The app creates a device-local merchant operator EOA. Android protects it with Android Keystore
  and device authentication; iOS stores it in Keychain and uses local authentication.
- The operator can sign a zero-native-value, allowlisted `sweepSessions` settlement call to the
  configured merchant vault. It is not a general customer wallet.
- Android's app-owned manifest declares `INTERNET` and `CAMERA`, with camera hardware optional.
  The verified release merged manifest also contains `USE_BIOMETRIC`, legacy `USE_FINGERPRINT`,
  and the app-local `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` from platform/dependency manifests.
  iOS declares Camera and Face ID usage descriptions.
- Camera scanning is limited to terminal provisioning and configuration-address QR codes. A
  scanned payment QR does not initiate a payment.
- Invoice, settlement, configuration, admin-PIN verifier, and operator-key material are stored
  locally. Android disables backup and device transfer for app data.
- Public blockchain addresses, contract calls, balance/log queries, transaction hashes, and signed
  settlement transactions are sent over HTTPS JSON-RPC. The RPC service necessarily receives the
  request and network connection metadata.
- No advertising, social-login, app account, StoreKit, Play Billing, or subscription SDK was found.
- The iOS target has no general-purpose analytics or crash-reporting SDK.
- Android decodes QR camera frames entirely on-device with ZXing. The release dependency graph
  contains no ML Kit, Firebase, or Google Data Transport component.
- The iOS privacy manifest declares no tracking or tracking domains; it declares Coarse Location,
  User ID, Device ID, Purchase History, and Other Financial Info as linked, non-tracking data used
  for App Functionality, plus UserDefaults reason `CA92.1`.
- The device-local operator EOA is persistent for the app installation, is expressly used as the
  terminal identifier, and is sent in JSON-RPC calls. Under the stores' identifier definitions it
  is a Device ID / Device or other ID as well as a blockchain account-level identifier.

## Monetisation and accounts

- [ ] Set both downloads to **Free / zero price**.
- [ ] Do not configure App Store in-app purchases, subscriptions, or Google Play in-app products.
- [ ] Apple: confirm **In-App Purchases: none**.
- [ ] Play: answer **Contains ads: No**.
- [ ] App account/sign-in: **none inside OPK Terminal**.
- [ ] Account deletion: not applicable to the terminal app because it creates no user account.
- [ ] Do not imply that the companion Merchant Portal has no account. Its access and privacy
  practices must be covered separately.
- [ ] Mark review access as restricted because complete terminal operation requires external
  Merchant Portal provisioning, even though the terminal itself has no login.

## Apple App Privacy

The public OpenPasskey privacy page states the first-party fact that OpenPasskey operates no app
backend and does not receive or store OPK Terminal app data. Keep that public statement limited to
OpenPasskey's own practices; do not add RPC-provider collection wording to it. App Store Connect's
answers are a separate, store-scoped compliance declaration and must still cover app-originated
transfers to third parties. Do not publish **Data Not Collected** in App Store Connect: the app
sends JSON-RPC requests directly to Base's public RPC service, and Base's published policy
describes collection and retention beyond real-time request processing.

- Base's published privacy policy says its services may collect IP/derived-location information
  and may analyse public blockchain data including wallet addresses, transaction IDs, digital
  signatures, amounts, and timestamps.
- [x] Camera frames and QR values stay on-device. Android's scanner uses ZXing and the iOS scanner
  uses AVFoundation; neither scanner has an upload or telemetry path.
- [ ] Disclose **Coarse Location**, **User ID**, **Device ID**, **Purchase History**, and
  **Other Financial Info** as linked data used for App Functionality. Base's published policy,
  the persistent per-install operator EOA, and the app's JSON-RPC payloads support these
  categories.
- [ ] Remove **Other Data** unless a separate production flow supports it.
- [ ] Tracking: **No**, subject to confirming that no production service links app data across
  companies for advertising or measurement.

Local-only data is not automatically “collected” under store definitions, but the following still
requires secure handling and accurate store declarations: the operator private key, salted Admin
PIN verifier, vault/token configuration, invoices, amounts, receiver addresses, transaction
hashes, settlement evidence, and local history. This does not require adding provider practices to
OpenPasskey's first-party public privacy statement.

## Google Play Data safety

- [ ] Data encrypted in transit: **Yes for app-controlled traffic**; Android cleartext traffic is
  disabled and the compiled RPC URL is HTTPS.
- [ ] Data deletion request: determine from the final declaration. Local data can be removed by
  uninstalling/resetting, but public blockchain transactions cannot be deleted. Explain this in
  the Play form or review notes where requested; do not expand the first-party public privacy page
  with provider wording.
- [ ] Data collection: do not answer **No data collected**. Base says its service collects and
  retains IP-derived location, device/network information, wallet addresses, and blockchain
  transaction data.
- [ ] Data sharing: answer separately from collection. Reconcile Play's service-provider
  definitions and exceptions with Base's role for RPC traffic. Without a processor agreement or
  a documented exception, conservatively answer that the applicable data is shared with Base.
- [ ] Declare **Approximate location**, **User IDs**, **Device or other IDs**,
  **Purchase history**, and **Other financial info** for required App Functionality, based on
  Base's current published privacy policy, the persistent per-install operator EOA, and the
  JSON-RPC fields sent by the app.
- [x] Camera images and decoded QR values: on-device only; do not declare them as collected.
- [ ] Advertising purpose: **No** based on source and vendor disclosure.
- [x] App interactions, Diagnostics, and Other app performance data: do not select these solely for
  QR scanning; the ML Kit telemetry dependency has been removed.
- [ ] Keep Play and Apple answers mutually consistent while preserving their separate store scope.
  They must not contradict the first-party public policy, but provider-specific store disclosures
  do not need to be copied into that policy.

## Financial and blockchain declarations

### Google Play

All apps must complete the Financial features declaration.

- [ ] Do **not** default to “My app doesn't provide any financial features.”
- [x] Select **Mobile payments and digital wallets** because the app is a merchant tool that
  presents payment QRs and settles received Base Sepolia test tokens.
- [x] Do **not** select **Cryptocurrency wallet**. OPK Terminal is classified as a merchant
  payment terminal, not a general-purpose wallet or financial-services provider. Its
  device-protected operator key is limited to authorised terminal settlement.
- [ ] Answer exchange, token sale/purchase, token rewards/earnings, NFT, lending, money transfer,
  and mining questions **No** where the final console wording matches the verified implementation.
- [ ] Describe it as Base Sepolia testnet-only and non-custodial in any explanation field.
- [ ] Have legal/compliance approve the selected countries. Google states that non-custodial
  wallets are outside its location-specific cryptocurrency wallet policy, but local law and the
  broader financial-services/blockchain policies still apply.

### Apple

- [ ] Confirm the Apple Developer account is enrolled as an **organisation** if App Review treats
  the device-local operator as a cryptocurrency wallet under Guideline 3.1.5(i).
- [ ] In Review Notes, disclose the operator key and constrained settlement honestly; do not
  describe the app as entirely keyless.
- [ ] Explain that there is no exchange, mining, customer private-key storage, customer custody,
  token sale, token reward, or mainnet support.
- [ ] Have owner/legal assess Guideline 3.2.1(viii) and each intended territory before release.

## Permissions and platform declarations

### Apple

- [ ] Camera purpose: provisioning and configuration-address QR scanning only.
- [ ] Face ID purpose: protect access to the device-local settlement operator before signing.
- [ ] Export compliance: the project currently sets `ITSAppUsesNonExemptEncryption = false`.
  Confirm this answer with the owner/export adviser because the app uses HTTPS, Keychain,
  cryptographic signing, and local key protection.
- [ ] App uses IDFA: **No**, based on source/dependency inspection.
- [ ] Content rights: confirm OpenPasskey owns or is authorised to use every brand, icon, screenshot,
  contract name, and third-party element.
- [ ] DSA trader status, contact information, tax/banking agreements, and territories: owner must
  complete in App Store Connect.

### Google Play

- [ ] Permission declaration: explain Camera as an optional, user-initiated QR scanner.
- [ ] The merged release also contains Biometric/Fingerprint for protecting operator-key use and
  an app-local signature permission used for non-exported dynamic receivers; describe these
  accurately if the console surfaces them.
- [ ] No location, contacts, microphone, phone, SMS, storage, notification, or broad media
  permission is declared by the app manifest.
- [ ] Complete App access using the live reviewer instructions.
- [ ] Complete Content rating, Target audience, News, Health, Government, and other App content
  forms accurately. The intended audience is merchant staff/technical testers, not children.
- [ ] Verify the final release AAB's merged manifest and SDK declarations; source-manifest review
  alone does not prove what every dependency contributes to the bundle.

## Store operations

- [ ] Verify the app-specific privacy statement published by OpenPasskey website PR #86. It states
  the first-party fact that OpenPasskey does not collect or store OPK Terminal data and that QR
  processing stays on-device.
- [ ] Keep RPC-provider collection and sharing analysis in these store compliance declarations;
  do not add that provider wording to the first-party public privacy page.
- [ ] Verify `https://www.openpasskey.com/support` is live and that its contact method is monitored.
- [ ] Keep the review portal, Base Sepolia vault, RPC, faucet/test funding, and token available
  throughout review.
- [ ] Use only assets verified against build 14. Preserve the documented build-13 capture and
  upload provenance rather than relabelling those assets as build-14 evidence.
- [ ] Review all text and screenshots in the live console after upload.
- [ ] Select manual release if the owner wants a final approval gate after store review.

## Current official policy references

- Apple App Review Guidelines:
  https://developer.apple.com/app-store/review/guidelines/
- Apple screenshot specifications:
  https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/
- Apple App Privacy:
  https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/
- Google Play preview assets:
  https://support.google.com/googleplay/android-developer/answer/9866151
- Google Play Financial features declaration:
  https://support.google.com/googleplay/android-developer/answer/13849271
- Google Play blockchain-based content:
  https://support.google.com/googleplay/android-developer/answer/13607354
- Base Global Privacy Policy:
  https://docs.base.org/privacy-policy
