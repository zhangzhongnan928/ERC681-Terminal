# Store asset inventory and capture manifest

Policy dimensions were checked against the official Apple and Google documentation on 24 July
2026, and the Google screenshot rules were rechecked on 26 July 2026. The release candidate is
`0.1.12` / build `14`. The existing build-13 captures and their provenance are preserved below; do
not relabel them as build-14 captures. Do not fabricate screens, upscale stale captures, or insert
unverified claims.

## Existing asset inventory

| Existing asset | Inventory | Readiness |
| --- | --- | --- |
| Signed Android build-14 phone PNGs | 5 files in `store/assets/google-play/phone-build14-signed-api36`, all 1440 × 2560, captured from the exact signed `0.1.12` / code 14 release APK on an API 36 emulator on 26 July 2026 | **Current signed-build phone set.** RGB/no alpha; visually inspected; dimensions satisfy Play's mandatory rules and 1080 × 1920-or-greater portrait recommendation. Screens truthfully show the cold-launch choice and isolated simulated demo. |
| Uploaded Android build-13 physical-device PNGs | 5 files in `store/assets/google-play/phone`, all 720 × 1440, captured from `0.1.12` / code 13 on an iMin L2s Pro on 24 July 2026 | **Technically valid historical set.** Uploaded to the Play listing draft; RGB/no alpha; dimensions satisfy Play's 320–3840 px and 2:1 rules. They predate the build-14 cold-launch choice and are not build-14 verification evidence. |
| Older Android UI-test PNGs | 25 files in `artifacts/ui-test`, all 1080 × 2400, captured 19 July 2026 | **Do not submit.** They predate the current operator-wallet/provisioning flow. Their 20:9 ratio also exceeds Play's current rule that the long side be no more than twice the short side. |
| iOS UI-test PNGs | 5 files in `artifacts/ui-test`, all 1206 × 2622, captured 19 July 2026 | **Do not submit.** They predate the current flow and are 6.3-inch captures, not the primary 6.9-inch set requested here. |
| App Store build-14 iPhone candidate PNGs | 5 files in `store/assets/apple/build-14/iphone-6.9`, all 1320 × 2868, captured from the `0.1.12` / build 14 simulator app on an iPhone 17 Pro Max, iOS 26.5, on 26 July 2026 | **Current simulator candidate set.** RGB/no alpha; visually inspected; the dedicated UI test passed. Screens truthfully show the cold-launch choice and isolated simulated demo. Recheck against the eventual signed App Store archive before replacing the uploaded build-13 set. |
| App Store build-14 iPad candidate PNGs | 5 files in `store/assets/apple/build-14/ipad-13`, all 2064 × 2752, captured from the `0.1.12` / build 14 simulator app on an iPad Pro 13-inch (M5), iOS 26.5, on 26 July 2026 | **Current simulator candidate set.** RGB/no alpha; visually inspected; all five came from one passing dedicated UI-test run. Recheck against the eventual signed App Store archive before replacing the uploaded build-13 set. |
| Apple app icon | `ios/App/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`, 1024 × 1024 PNG, no alpha | Ready as the source Apple icon, subject to owner brand review. |
| Android launcher icon | Adaptive vector foreground/background resources only | App launcher-ready. |
| Google Play icon | `store/assets/google-play/store-icon-512.png`, 512 × 512 8-bit RGBA PNG, fully opaque alpha | Uploaded and attached to the Play listing draft on 26 July 2026. Deterministically rendered from `ios/App/Resources/AppIcon.svg`; 3,608 bytes. |
| Google Play feature graphic | `store/assets/google-play/feature-graphic-1024x500.png`, 1024 × 500 8-bit RGB PNG, no alpha; editable source alongside it | Technically validated, uploaded, and attached to the Play listing draft on 26 July 2026. |
| Uploaded Google Play 7-inch tablet PNGs | 5 files in `store/assets/google-play/tablet-7`, all 1080 × 1920 | Attached to the Play listing draft on 26 July 2026. Exact capture-build provenance is not documented in this folder; do not relabel them as build-14 evidence without candidate verification. |
| Uploaded Google Play 10-inch tablet PNGs | 5 files in `store/assets/google-play/tablet-10`, all 1440 × 2560 | Attached to the Play listing draft on 26 July 2026. Exact capture-build provenance is not documented in this folder; do not relabel them as build-14 evidence without candidate verification. |
| Uploaded iPhone build-13 screenshots | 5 files in `store/assets/apple/iphone-6.9`, all 1320 × 2868, captured from `0.1.12` / build 13 on an iPhone 16 Pro Max simulator on 24 July 2026 | **Technically valid historical set.** Uploaded in App Store Connect; RGB/no alpha; exact 6.9-inch house target. They predate the build-14 cold-launch choice and must not be described as build-14 first-run captures. |
| Uploaded iPad build-13 screenshots | 4 files in `store/assets/apple/ipad-13`, all 2064 × 2752, captured from the same `0.1.12` / build 13 simulator product on an iPad Pro 13-inch (M4), iOS 18.4, on 24 July 2026 | **Technically valid historical set.** Uploaded in App Store Connect; RGB/no alpha; exact 13-inch house target. They predate the build-14 cold-launch choice and must not be described as build-14 first-run captures. |

## Exact submission targets

| Store slot | Exact house target | Count | Format |
| --- | --- | --- | --- |
| App Store iPhone 6.9-inch — build-14 candidate | **1320 × 2868 portrait** | 5 included; Apple accepts 1–10 | PNG or JPEG, no alpha |
| App Store iPad 13-inch — build-14 candidate | **2064 × 2752 portrait** | 5 included; at least 1 required because the app supports iPad | PNG or JPEG, no alpha |
| Google Play phone — signed build-14 set | **1440 × 2560 portrait (9:16)** | 5 included; Play requires at least 2 and allows up to 8 | 24-bit PNG, no alpha |
| Google Play phone — historical build-13 set | **720 × 1440 portrait (2:1)** | 5 preserved; do not relabel as build 14 | 24-bit PNG, no alpha |
| Google Play 7-inch tablet | **1080 × 1920 portrait (9:16)** | 5 included | 24-bit PNG, no alpha |
| Google Play 10-inch tablet | **1440 × 2560 portrait (9:16)** | 5 included | 24-bit PNG, no alpha |
| Google Play feature graphic | **1024 × 500** | 1 required | JPEG or 24-bit PNG, no alpha |
| Google Play store icon | **512 × 512** | 1 required | 32-bit PNG with alpha, maximum 1024 KB |

Apple currently accepts additional 6.9-inch portrait sizes (1260 × 2736 and 1290 × 2796) and an
additional 13-inch iPad portrait size (2048 × 2732). Standardise this release on the house targets
above so each device set is internally consistent.

## App Store build-14 candidate capture sequence

The two five-image sets under `store/assets/apple/build-14` were captured from the build-14 app
target at source revision `1b866fef4cf965df836c17addfc91612b06d9fb2`. The capture harness ran
with live bootstrap forbidden. These are simulator candidates, not evidence of the eventual signed
App Store archive; preserve the uploaded build-13 sets until archive-parity review justifies
replacement. Screenshots 2–5 keep the full
`OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS` banner visible.

1. **Cold-launch choice** — live merchant setup and the isolated offline reviewer demo are clearly
   separated before either path opens.
2. **Simulated checkout waiting** — 1.00 USDC sample, Waiting status, and a locally rendered fixed
   dummy ERC-681 QR.
3. **Simulated checkout paid** — an explicit local simulation action marks the sample Paid without
   network activity.
4. **In-memory history** — the same paid sample is visible only for the demo session.
5. **Disabled settlement preview** — fixed dummy addresses and a visibly disabled settlement
   action; authentication, signing, RPC, and broadcast are unavailable.

## Signed build-14 phone capture sequence

The five images in `store/assets/google-play/phone-build14-signed-api36` were captured from the
exact signed build-14 release APK. They show only the real cold-launch and isolated offline-demo
UI, with no fabricated provisioning, live network state, production data, or available settlement.
Screenshots 2–5 keep the full simulation/testnet/no-network/no-real-funds banner visible.

1. **Cold-launch choice** — live merchant setup and the isolated offline reviewer demo are clearly
   separated before any live component opens.
   - Play alt text: `OPK Terminal launch screen offering live merchant setup or an isolated offline reviewer demo.`
2. **Simulated checkout waiting** — 1.00 USDC sample, Waiting status, and a locally rendered fixed
   dummy ERC-681 QR.
   - Play alt text: `Offline Base Sepolia demo showing a simulated 1 USDC checkout, Waiting status, and dummy ERC-681 QR.`
3. **Simulated checkout paid** — the explicit local simulation action changes the same sample to
   Paid.
   - Play alt text: `Offline Base Sepolia demo showing a locally simulated 1 USDC payment marked Paid.`
4. **In-memory history** — Paid sample marked `Demo session only · never saved`.
   - Play alt text: `Offline demo history showing a simulated paid Base Sepolia sample that is never saved.`
5. **Disabled settlement preview** — fixed dummy addresses and a visibly disabled settlement
   action; no authentication, signing, RPC, or broadcast is available.
   - Play alt text: `Offline settlement preview with dummy addresses and the settlement action disabled.`

## Historical build-13 live-terminal capture sequence

The five preserved Android build-13 captures use the live Base Sepolia test story below. The
preserved Apple build-13 sets document the then-current direct first-run and setup experience.
Build 14 adds a cold-launch choice and isolated offline reviewer demo that are absent from those
sets. The signed build-14 phone set above supersedes them as current cold-launch/demo evidence.
Retain these historical live-terminal screens only with their original provenance, and recheck any
one selected for submission against the live path in build 14. Do not fabricate a ready terminal.
Capture actual UI with no device frames or marketing overlays.

1. **Base Sepolia terminal ready** — Checkout with the Base Sepolia Ready header, selected
   test-token profile, authorised operator, and ready state. Avoid exposing portal credentials.
   - Play alt text: `Base Sepolia merchant terminal showing an authorised, ready payment profile.`
2. **Create a test payment** — Checkout screen with a clearly selected test token and a sample
   amount.
   - Play alt text: `Merchant enters an amount for a Base Sepolia test-token payment.`
3. **Display the ERC-681 QR** — waiting-for-payment screen with the amount, test-token symbol, and
   QR.
   - Play alt text: `ERC-681 payment QR displayed for a Base Sepolia test-token invoice.`
4. **Confirmed history** — History/invoice detail showing a confirmed test payment.
   - Play alt text: `Local invoice history showing a confirmed Base Sepolia test payment.`
5. **Verified settlement activity** — completed sweep activity and the reconciled
   no-balance-ready state.
   - Play alt text: `Verified Base Sepolia settlement activity with no remaining confirmed receiver balance.`

## Capture safety and quality checks

- Use a dedicated store-demo vault, operator, token, and payer with no personal or production data.
- A displayed receiver QR remains publicly payable after publication. Use an expendable Base
  Sepolia demo receiver and accept that third parties may scan it and send test tokens.
- Show “Base Sepolia” or an equally clear testnet indicator in the first screenshot.
- Do not show Base Mainnet, real-value balances, seed phrases, private keys, portal credentials,
  personal notifications, real customer details, or production merchant data.
- Keep the status bar clean and use full Wi-Fi/cellular/battery indicators where practical.
- Do not crop or stretch screenshots to fit. Capture at the target simulator/device resolution.
- Verify every screenshot against the final release build after capture.
- Check every exported PNG/JPEG for exact dimensions and absence of alpha where required.
- Keep text and UI legible at store-thumbnail size.

## Feature graphic brief

Create a simple 1024 × 500 composition derived from approved OpenPasskey brand assets. Communicate
“merchant terminal” and “Base Sepolia testnet” without showing a live QR, price claim, store badge,
ranking, token value, return, or unsupported mainnet capability. Keep critical content away from
the edges because Play may crop the graphic.

The deterministic artwork, editable SVG, rebuild details, and hashes are in
`store/assets/google-play/`. The feature graphic contains no live QR. The owner/design approver
must still approve the final artwork and rights before upload.
