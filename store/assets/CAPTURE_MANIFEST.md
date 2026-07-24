# Store asset inventory and capture manifest

Policy dimensions checked against the official Apple and Google documentation on 24 July 2026.
Capture the shipping `0.1.12` / build `13` app. Do not fabricate screens, upscale stale captures,
or insert unverified claims.

## Existing asset inventory

| Existing asset | Inventory | Readiness |
| --- | --- | --- |
| Current Android physical-device PNGs | 5 files in `store/assets/google-play/phone`, all 720 × 1440, captured from `0.1.12` / code 13 on an iMin L2s Pro on 24 July 2026 | **Uploadable mandatory set.** Actual current UI; RGB/no alpha; dimensions satisfy Play's 320–3840 px and 2:1 rules. The 720 px short side does not meet the optional 1080 px recommendation-placement guidance. |
| Older Android UI-test PNGs | 25 files in `artifacts/ui-test`, all 1080 × 2400, captured 19 July 2026 | **Do not submit.** They predate the current operator-wallet/provisioning flow. Their 20:9 ratio also exceeds Play's current rule that the long side be no more than twice the short side. |
| iOS UI-test PNGs | 5 files in `artifacts/ui-test`, all 1206 × 2622, captured 19 July 2026 | **Do not submit.** They predate the current flow and are 6.3-inch captures, not the primary 6.9-inch set requested here. |
| Apple app icon | `ios/App/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`, 1024 × 1024 PNG, no alpha | Ready as the source Apple icon, subject to owner brand review. |
| Android launcher icon | Adaptive vector foreground/background resources only | App launcher-ready. |
| Google Play icon | `store/assets/google-play/store-icon-512.png`, 512 × 512 8-bit RGBA PNG, fully opaque alpha | Ready. Deterministically rendered from the approved `ios/App/Resources/AppIcon.svg`; 3,608 bytes. |
| Google Play feature graphic | `store/assets/google-play/feature-graphic-1024x500.png`, 1024 × 500 8-bit RGB PNG, no alpha; editable source alongside it | Generated and technically validated. Obtain final owner/design approval before upload. |
| Current iPhone screenshots | 5 files in `store/assets/apple/iphone-6.9`, all 1320 × 2868, captured from `0.1.12` / build 13 on an iPhone 16 Pro Max simulator on 24 July 2026 | **Uploadable technical set.** Actual current first-run UI; RGB/no alpha; exact 6.9-inch house target. |
| Current iPad screenshots | 4 files in `store/assets/apple/ipad-13`, all 2064 × 2752, captured from the same `0.1.12` / build 13 simulator product on an iPad Pro 13-inch (M4), iOS 18.4, on 24 July 2026 | **Uploadable technical set.** Actual current first-run UI; RGB/no alpha; exact 13-inch house target. |

## Exact submission targets

| Store slot | Exact house target | Count | Format |
| --- | --- | --- | --- |
| App Store iPhone 6.9-inch | **1320 × 2868 portrait** | 5 included; Apple accepts 1–10 | PNG or JPEG, no alpha |
| App Store iPad 13-inch | **2064 × 2752 portrait** | 4 included; at least 1 required because the app supports iPad | PNG or JPEG, no alpha |
| Google Play phone — current mandatory set | **720 × 1440 portrait (2:1)** | 5 included; Play requires at least 2 and allows up to 8 | 24-bit PNG, no alpha |
| Google Play phone — future promotion-optimised set | **1080 × 1920 portrait (9:16)** | 4–8 | JPEG or 24-bit PNG, no alpha |
| Google Play feature graphic | **1024 × 500** | 1 required | JPEG or 24-bit PNG, no alpha |
| Google Play store icon | **512 × 512** | 1 required | 32-bit PNG with alpha, maximum 1024 KB |

Apple currently accepts additional 6.9-inch portrait sizes (1260 × 2736 and 1290 × 2796) and an
additional 13-inch iPad portrait size (2048 × 2732). Standardise this release on the house targets
above so each device set is internally consistent.

## Capture sequence

The five Android captures use the dedicated Base Sepolia demo story below. The current Apple sets
instead document the genuine first-run and setup experience because a reviewer-specific,
operator-bound provisioning QR does not yet exist. Do not fabricate a ready Apple terminal.
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
