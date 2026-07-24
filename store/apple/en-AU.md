# Apple App Store — English (Australia)

## Product details

| Field | Draft value |
| --- | --- |
| App name (30 max) | OPK Terminal |
| Subtitle (30 max) | Base Sepolia merchant terminal |
| Primary language | English (Australia) |
| Primary category | Business |
| Secondary category | Finance — optional; owner/legal to confirm |
| Price | Free (tier 0 / AUD 0.00) |
| In-app purchases | None |
| Privacy Policy URL | https://www.openpasskey.com/privacy |
| Support URL | https://www.openpasskey.com/about |
| Marketing URL | https://www.openpasskey.com |
| Copyright | 2026 OpenPasskey Pty Ltd — owner to confirm exact rights-holder text |

## Promotional text (170 max)

Testnet only. OPK Terminal lets merchants provision an OpenPasskey terminal, create ERC-681
payment QRs, and monitor and settle Base Sepolia test-token payments.

## Description (4,000 max)

TESTNET ONLY: OPK Terminal supports Base Sepolia. It does not support Base Mainnet or any other
production network.

OPK Terminal is a merchant-facing point-of-sale app for testing OpenPasskey ERC-20 payment flows.
A merchant first uses OpenPasskey provisioning to bind the terminal to an authorised Base Sepolia
vault and supported test token.

With OPK Terminal, a merchant can:

• create a device-protected terminal operator
• import and select up to 32 vault/token payment profiles
• enter an amount and display a canonical ERC-681 transfer QR
• monitor test-token payments and block confirmations on Base Sepolia
• review payment and settlement history stored on the device
• perform a tightly constrained settlement sweep to the configured merchant vault

The terminal operator private key stays on the device and is used only for constrained settlement.
The app does not store customer private keys or take custody of customer assets. It does not
provide an exchange, swaps, token purchases, mining, an off-chain or fiat merchant payout service,
or customer refunds.

OPK Terminal has no app account or sign-in, advertising SDK, OpenPasskey-operated general-purpose
analytics SDK, in-app purchases, or subscriptions. It is offered at no charge.

Requirements:

• compatible OpenPasskey Merchant Portal provisioning
• an authorised Base Sepolia merchant vault and supported test token
• an internet connection to Base Sepolia JSON-RPC
• Base Sepolia test ETH on the terminal operator for settlement gas
• a compatible customer wallet for sending the designated Base Sepolia test token

This app is intended for merchant and technical testing. Customers do not need OPK Terminal to scan
and pay a merchant's displayed QR.

## Keywords (100 bytes max)

merchant,payment,terminal,point of sale,ERC-681,Base Sepolia,testnet,stablecoin,QR

## Version 0.1.12 release notes

First public-store build of OPK Terminal:

• Base Sepolia testnet-only merchant payment flow
• OpenPasskey provisioning for up to 32 vault/token profiles
• ERC-681 payment QR display and on-chain confirmation monitoring
• device-protected, constrained settlement and local history
• in-app Privacy Policy and Support links

## App Review notes

Paste the completed Apple section from `../review/reviewer-instructions.md`. Do not submit
placeholders or commit review credentials to this repository.

## Owner decisions before paste

- Confirm that **Business** is the primary category and whether **Finance** should be secondary.
- Complete the current age-rating questionnaire honestly; do not assume a rating from this draft.
- Confirm organisation enrolment and the financial/cryptocurrency classification described in
  `../console-declarations.md`.
- Confirm the copyright owner and distribution territories.
- Publish a binding privacy policy before using the URL above in a submission.
