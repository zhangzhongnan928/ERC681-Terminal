# App Store Connect and Play Console declaration record

Evidence snapshot: 26 July 2026. Apple listing state re-checked 20 August 2026; Google Play state
not re-checked since the original snapshot.

Release candidate: OPK Terminal `0.1.12` build `13`, source commit `0414d70`.
This record applies only to the build already present in App Store Connect and Google Play. Do not
substitute build 14 behavior, reviewer instructions, screenshots, or declarations.

`main` now carries `0.5.2` / build `23` (tag `v0.5.2`, 14 August 2026), which has not been
submitted to either console. Do not reuse the code-grounded claims below for a `0.5.x` build
without re-deriving them from that source tree.

This is a code- and console-grounded submission record, not legal advice. The owner must approve
legal attestations, privacy publication, and final store submissions.

## Current console state

### Apple App Store Connect

- Developer: **OpenPasskey** organisation.
- Price: **Free**.
- Primary category: **Business**.
- Distribution: **Public**, available in **175 regions**.
- Age rating: **4+**.
- Version `0.1.12`, build `13`, is attached.
- Version status: **Ready for Sale**. The public listing
  (https://apps.apple.com/app/opk-terminal/id6792404106) shows `0.1.12` released 18 August 2026 and
  last updated 19 August 2026, minimum iOS 17.0, seller OPENPASSKEY PTY LTD. Verified 20 August
  2026 from Apple's public listing metadata, which does not expose the build number; confirm the
  live build in App Store Connect.
- Version metadata and App Review contact metadata are saved.
- App Privacy: the July snapshot recorded an unpublished **Data Not Collected** draft and judged it
  indefensible for build 13. Review passed, so some answer was published. Read the published answer
  in App Store Connect and correct it there if it still claims no collection; the off-device
  handling recorded in this file (Base public JSON-RPC, bundled ML Kit telemetry) is unchanged.

### Google Play Console

- Developer: personal account `zhangzhongnan928@gmail.com`.
- Price: **Free**.
- Version `0.1.12` (`13`) targets Android API 35 and is active on internal testing, as of the
  26 July 2026 snapshot. Not re-verified since.
- The IARC content-rating questionnaire and Data safety form remain pending.
- The app is classified as a merchant payment tool, not a financial-services provider or
  general-purpose cryptocurrency wallet.
- Build 13 has no offline product tour or reviewer demo. Do not claim one in App access
  instructions. Full review access still requires a real, reusable Base Sepolia merchant
  provisioning path.
- Production access for this new personal developer account remains subject to Google Play's
  closed-testing eligibility gate and owner-controlled production submission.

## Build 13 implementation facts

- Android application ID: `com.openpasskey.terminal`; iOS bundle ID:
  `com.openpasskey.terminal.ios`.
- Android build 13 has minimum SDK 26, compile SDK 35, and target SDK 35.
- Base Sepolia (`84532`) is the only enabled network. The compiled RPC endpoint is
  `https://sepolia.base.org`; Base Mainnet is disabled.
- OPK Terminal is a merchant terminal. It presents payment requests and allows the merchant
  operator to settle supported receipts. It does not provide exchange, token sale or purchase,
  mining, lending, token rewards, customer custody, or a general-purpose customer wallet.
- The app creates a device-local merchant operator key. Android protects it with Android Keystore
  and device authentication; iOS stores it in Keychain and uses local authentication.
- Invoices, configuration, settlement history, the admin-PIN verifier, and operator-key material
  are stored locally.
- Public wallet addresses, contract calls, balance and log queries, transaction hashes, and signed
  settlement transactions are transmitted over HTTPS JSON-RPC. The RPC recipient receives the
  request contents and network connection metadata.
- There is no advertising, IDFA/Advertising ID use, social login, app account, StoreKit, Play
  Billing, in-app purchase, or subscription implementation.
- iOS has no general-purpose analytics or crash-reporting SDK.
- Android build 13 includes Google ML Kit barcode scanning `17.3.0`. Its disclosed telemetry
  includes device and app information, a per-install identifier, performance and configuration
  information, input/output sizes, events, and errors transmitted over HTTPS.
- Camera frames and decoded QR values remain on-device. ML Kit auto-zoom telemetry is disabled.

## Apple App Privacy mapping

The unpublished **Data Not Collected** answer must be replaced before publication. The minimum
conservative build-13 mapping from the privacy audit is:

| Apple data type | Collected | Linked to user | Tracking | Purpose |
| --- | --- | --- | --- | --- |
| User ID | Yes | Yes | No | App Functionality |
| Other Financial Info | Yes | Yes | No | App Functionality |
| Purchase History | Yes | Yes | No | App Functionality |
| Coarse Location | Yes | Yes | No | App Functionality; Fraud Prevention, Security, and Compliance where applicable |
| Device ID | Yes | Yes | No | App Functionality; Fraud Prevention, Security, and Compliance where applicable |

**Product Interaction** may also be declared for the fuller conservative mapping, with
Collected **Yes**, Linked **Yes**, Tracking **No**, and purpose **App Functionality**.

Do not declare precise location, contact information, contacts, media, audio, files, messages,
advertising data, or tracking without new production evidence. Device-local private keys, PIN
material, configuration, invoices, and camera frames are not independently declared as collected
merely because they exist on the device.

Publishing the corrected Apple privacy response is an owner attestation and requires explicit
owner approval. The current draft must remain unpublished until that approval is given.

## Google Play Data safety mapping

Build 13 transmits both JSON-RPC data and ML Kit telemetry off-device. Google's definition treats
off-device transmission to the developer or a third party as collection, so **No data collected**
is not a supportable answer.

Use this minimum audited mapping:

| Google data type | Collected | Shared | Ephemeral | Required | Purpose |
| --- | --- | --- | --- | --- | --- |
| Approximate location | Yes | Yes | No | Yes | App functionality; Fraud prevention, security and compliance |
| User IDs | Yes | Yes | No | Yes | App functionality; Fraud prevention, security and compliance |
| Purchase history | Yes | Yes | No | Yes | App functionality; Fraud prevention, security and compliance |
| App interactions | Yes | Yes | No | Yes | App functionality; Analytics |
| Diagnostics | Yes | No | No | Yes | Analytics |
| Device or other IDs | Yes | Yes | No | Yes | App functionality; Fraud prevention, security and compliance; Analytics |

- Treat the declared data as linked or pseudonymous, as applicable; do not mark it anonymous.
- Data encrypted in transit: **Yes**.
- Advertising or marketing: **No**.
- Personalisation: **No**.
- Tracking/advertising identifier use: **No**.
- Do not select precise location; name, email address, phone number, or physical address; contacts;
  photos, videos, or other media; audio; files and documents; messages; Advertising ID; crash
  logs; or browsing history without new production evidence.
- Camera frames and decoded QR values stay on-device and are not collected.
- ML Kit auto-zoom telemetry is disabled and must not be described as an active data flow.

The IARC Terms of Use and the final Data safety declaration are legal attestations. Do not accept
or submit them without explicit owner approval.

## Merchant-tool and financial-feature classification

- Keep the store description and review notes focused on a **merchant payment terminal/tool**.
- Do not describe OpenPasskey or OPK Terminal as a financial-services provider.
- Google Financial features: retain **Mobile payments and digital wallets** only to the extent
  required for the merchant payment-terminal flow.
- Do not select **Cryptocurrency wallet** for this constrained merchant terminal.
- Exchange, token sale/purchase, token rewards/earnings, NFT, lending, money transfer, and mining:
  **No**.
- Disclose that the release candidate is Base Sepolia testnet-only, non-custodial, and has no
  real-value production-network flow.

## Public privacy page and submission gates

The public policy remains `https://www.openpasskey.com/privacy`. Its first-party statement that
OpenPasskey does not collect or store OPK Terminal data is unchanged. Do not add provider or Base
language, or otherwise revise the page, without owner approval.

That unchanged first-party statement does not remove the stores' requirement to disclose
third-party/off-device data handling. Google also requires the linked policy to comprehensively
describe collection, use, sharing, retention/deletion, and relevant recipients. Resolve that
policy/declaration mismatch with owner approval before submitting Google Data safety; do not file
a contradictory form.

Remaining owner-controlled gates:

- Approve and publish the corrected Apple App Privacy response.
- Approve the IARC Terms of Use and submit the Google content rating.
- Approve the truthful Google Data safety mapping and any required privacy-policy change.
- Provide and maintain reusable reviewer provisioning for build 13 without claiming an offline
  demo.
- Satisfy Google Play's closed-testing production-access requirement.
- Authorise each final review/production submission and release.

## Official policy references

- Apple App Review Guidelines:
  https://developer.apple.com/app-store/review/guidelines/
- Apple App Privacy:
  https://developer.apple.com/app-store/app-privacy-details/
- Google Play Data safety:
  https://support.google.com/googleplay/android-developer/answer/10787469
- Google Play User Data:
  https://support.google.com/googleplay/android-developer/answer/10144311
- Google Play Financial features declaration:
  https://support.google.com/googleplay/android-developer/answer/13849271
- Google Play production-access testing requirements:
  https://support.google.com/googleplay/android-developer/answer/14151465
- Google Play blockchain-based content:
  https://support.google.com/googleplay/android-developer/answer/13607354
