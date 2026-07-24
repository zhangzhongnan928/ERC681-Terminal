# App Store Connect and Play Console declaration checklist

Evidence snapshot: repository state inspected on 24 July 2026 for `0.1.12` / build `13`.

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
  and `ACCESS_NETWORK_STATE` from platform/dependency manifests. iOS declares Camera and Face ID
  usage descriptions.
- Camera scanning is limited to terminal provisioning and configuration-address QR codes. A
  scanned payment QR does not initiate a payment.
- Invoice, settlement, configuration, admin-PIN verifier, and operator-key material are stored
  locally. Android disables backup and device transfer for app data.
- Public blockchain addresses, contract calls, balance/log queries, transaction hashes, and signed
  settlement transactions are sent over HTTPS JSON-RPC. The RPC service necessarily receives the
  request and network connection metadata.
- No advertising, social-login, app account, StoreKit, Play Billing, or subscription SDK was found.
- The iOS target has no general-purpose analytics or crash-reporting SDK.
- Android includes Google's bundled ML Kit barcode SDK. Google's published all-feature disclosure
  says ML Kit collects device/app information, a per-install identifier, performance metrics, API
  configuration, feature input/output size and version, event types, and error codes for
  diagnostics and usage analytics. The app deliberately disables ML Kit auto-zoom, so the
  additional auto-zoom session, zoom-level, and predicted-coordinate fields do not apply.
- The iOS privacy manifest currently declares no tracking, no tracking domains, no collected data
  types, and UserDefaults reason `CA92.1`.

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

Do not publish **Data Not Collected** solely because the checked-in privacy manifest is empty.
The live privacy URL no longer identifies itself as a draft, but it does not specifically disclose
OPK Terminal's Base Sepolia JSON-RPC requests, the RPC provider's handling of them, or Android ML
Kit diagnostics and usage metrics. Treat those missing app-specific disclosures as owner/legal
input, not as a submission-ready privacy position.

- Base's published privacy policy says its services may collect IP/derived-location and
  device/browser information and may analyse public blockchain data including wallet addresses,
  transaction IDs, digital signatures, amounts, and timestamps. Confirm in writing whether and how
  those practices apply to the public `https://sepolia.base.org` endpoint used by this app.
- [ ] Obtain written confirmation of the Base Sepolia RPC operator's handling of IP addresses,
  JSON-RPC payloads, wallet addresses, transaction data, logging, retention, and secondary use.
- [ ] Confirm whether the RPC operator is a “third-party partner” and whether any transmission
  qualifies for Apple's collection exceptions.
- [ ] Confirm the data practices of every included production SDK, including the barcode-scanning
  dependency on Android for the cross-platform Play disclosure.
- [ ] If the service-provider review supports **Data Not Collected**, keep the privacy manifest,
  App Store privacy answers, and published privacy policy aligned.
- [ ] Otherwise disclose the applicable categories and purposes. Likely review candidates include
  identifiers/network data and financial or transaction information sent for app functionality;
  do not select a category without mapping it to Apple's current definitions.
- [ ] Tracking: **No**, subject to confirming that no production service links app data across
  companies for advertising or measurement.

Local-only data is not automatically “collected” under store definitions, but the following still
requires secure handling and accurate policy text: the operator private key, salted Admin PIN
verifier, vault/token configuration, invoices, amounts, receiver addresses, transaction hashes,
settlement evidence, and local history.

## Google Play Data safety

- [ ] Data encrypted in transit: **Yes for app-controlled traffic**; Android cleartext traffic is
  disabled and the compiled RPC URL is HTTPS.
- [ ] Declare ML Kit's documented all-feature collection: device/app information, a per-install
  identifier, performance metrics, API configuration, feature input/output size and version, event
  types, and error codes, used for diagnostics and usage analytics. Map these to the current Play
  categories (likely Device or other IDs plus App info and performance / Diagnostics) using the
  exact live form wording.
- [ ] ML Kit says this diagnostic/usage data is encrypted in transit and is not transferred to
  third parties. Confirm the release's exact `com.google.mlkit:barcode-scanning:17.3.0` behaviour
  against the current vendor disclosure when submitting.
- [ ] Data deletion request: determine from the final declaration. Local data can be removed by
  uninstalling/resetting, but public blockchain transactions cannot be deleted; explain this in
  the privacy policy rather than promising deletion of on-chain records.
- [ ] Data collection: do not answer **No data collected**. ML Kit's vendor disclosure already
  establishes diagnostic/usage collection; the Base RPC assessment may add further categories.
- [ ] Data sharing: answer separately from collection. Reconcile Play's service-provider
  definitions with Google's statement that ML Kit does not transfer its collected data to third
  parties and with Base's role for RPC traffic.
- [ ] Map every transmitted field to current Play categories and purposes. The app sends public
  EVM addresses, blockchain queries, and signed transaction payloads for app functionality; the
  RPC provider may also log IP/device-network metadata.
- [ ] Camera images and decoded QR values: source behaviour is on-device parsing and no upload path
  was found. ML Kit's barcode disclosure does not list image or decoded-value collection, but
  confirm the exact release artefact's current SDK guidance when submitting.
- [ ] Advertising purpose: **No** based on source and vendor disclosure.
- [ ] Analytics purpose: **Yes for ML Kit's documented SDK diagnostics and usage analytics**;
  describe it narrowly and do not imply OpenPasskey profiles terminal behaviour itself.
- [ ] Reconcile Play answers with the final privacy policy and Apple answers.

## Financial and blockchain declarations

### Google Play

All apps must complete the Financial features declaration.

- [ ] Do **not** default to “My app doesn't provide any financial features.”
- [ ] Conservatively assess **Mobile payments and digital wallets** because the app presents
  merchant payment QRs and settles received test tokens.
- [ ] Conservatively assess **Cryptocurrency wallet** because the app creates and stores a
  non-custodial merchant operator private key, even though it has no customer custody and is
  testnet-only.
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
  Access Network State for included Android/ML Kit components; describe these accurately if the
  console surfaces them.
- [ ] No location, contacts, microphone, phone, SMS, storage, notification, or broad media
  permission is declared by the app manifest.
- [ ] Complete App access using the live reviewer instructions.
- [ ] Complete Content rating, Target audience, News, Health, Government, and other App content
  forms accurately. The intended audience is merchant staff/technical testers, not children.
- [ ] Verify the final release AAB's merged manifest and SDK declarations; source-manifest review
  alone does not prove what every dependency contributes to the bundle.

## Store operations

- [ ] Approve app-specific privacy disclosures. The current URL no longer labels itself a draft,
  but it does not specifically disclose the terminal's Base Sepolia RPC requests, provider
  handling, or Android ML Kit diagnostics and usage metrics.
- [ ] Confirm the About page is an adequate Support URL or publish a dedicated support page with a
  monitored contact method.
- [ ] Keep the review portal, Base Sepolia vault, RPC, faucet/test funding, and token available
  throughout review.
- [ ] Upload only the fresh assets listed in `assets/CAPTURE_MANIFEST.md`.
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
- ML Kit Android data disclosure:
  https://developers.google.com/ml-kit/android-data-disclosure
- Base Global Privacy Policy:
  https://docs.base.org/privacy-policy
