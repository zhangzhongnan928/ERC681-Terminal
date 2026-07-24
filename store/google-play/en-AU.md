# Google Play — English (Australia)

## Product details

| Field | Draft value |
| --- | --- |
| App name (30 max) | OPK Terminal |
| Default language | English (Australia) — en-AU |
| App or game | App |
| Category | Business |
| Tags | Point of sale / Business — select only if offered by Play Console |
| Price | Free |
| Contains ads | No |
| In-app products/subscriptions | None |
| Privacy Policy URL | https://www.openpasskey.com/privacy |
| Website | https://www.openpasskey.com |
| Support URL | https://www.openpasskey.com/about |
| Support email | [OWNER: enter monitored support email] |

## Short description (80 max)

Merchant payment terminal for OpenPasskey on Base Sepolia testnet only

## Full description (4,000 max)

OPK Terminal works only on the Base Sepolia test network. It does not support Base Mainnet or any
production network.

It is a merchant-facing point-of-sale app for testing OpenPasskey ERC-20 payment flows. A merchant
uses OpenPasskey provisioning to bind the terminal to an authorised Base Sepolia vault and
supported test token.

OPK Terminal lets a merchant:

• create a device-protected terminal operator
• import and select up to 32 vault/token payment profiles
• enter an amount and display a canonical ERC-681 transfer QR
• monitor test-token payments and block confirmations on Base Sepolia
• review payment and settlement history stored on the device
• perform a tightly constrained settlement sweep to the configured merchant vault

The terminal operator private key stays on the device and is used only for constrained settlement.
The app does not store customer private keys or take custody of customer assets.

OPK Terminal does not provide an exchange, swaps, token purchases, mining, token rewards, an
off-chain or fiat merchant payout service, or customer refunds. It has no app account or sign-in,
advertising, in-app purchases, or subscriptions. The app is offered at no charge.

Requirements:

• compatible OpenPasskey Merchant Portal provisioning
• an authorised Base Sepolia merchant vault and supported test token
• an internet connection to Base Sepolia JSON-RPC
• Base Sepolia test ETH on the terminal operator for settlement gas
• a compatible customer wallet for sending the designated Base Sepolia test token

This app is intended for merchant and technical testing. Customers do not need OPK Terminal to scan
and pay a merchant's displayed QR.

## Version 0.1.12 release notes (500 max)

First public-store build of OPK Terminal:

• Base Sepolia testnet-only merchant payment flow
• OpenPasskey provisioning for up to 32 vault/token profiles
• ERC-681 payment QR display and on-chain confirmation monitoring
• device-protected, constrained settlement and local history
• in-app Privacy Policy and Support links

## Review access

Paste the completed Google Play section from `../review/reviewer-instructions.md` into App access.
Mark the app as having restricted functionality because the full flow requires live merchant
provisioning. Do not submit placeholders or commit credentials to this repository.

## Owner decisions before paste

- Complete the Financial features and blockchain-content declarations described in
  `../console-declarations.md`; do not select “no financial features” by default.
- Complete the target-audience and content-rating questionnaires honestly. This is a business tool,
  not a child-directed app.
- Confirm all distribution territories with legal/compliance.
- Confirm the live privacy policy includes the approved OPK Terminal data-practice disclosures
  and enter a monitored support email before submission.
