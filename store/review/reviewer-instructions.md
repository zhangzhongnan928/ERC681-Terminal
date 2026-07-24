# Store reviewer instructions template

These notes are a template, not a working review credential set. Replace every bracketed value in
the store consoles immediately before submission. Keep credentials out of source control.

## Review environment that the owner must keep live

| Item | Submission-time value |
| --- | --- |
| Review Merchant Portal URL | `[LIVE_REVIEW_PORTAL_URL]` |
| Portal access instructions | `[REVIEW_PORTAL_ACCESS_OR_DEMO_ACCOUNT]` |
| Base Sepolia review vault | `[BASE_SEPOLIA_REVIEW_VAULT_ADDRESS]` |
| Supported test token | `[BASE_SEPOLIA_TEST_TOKEN_ADDRESS_AND_SYMBOL]` |
| Operator-authorisation method | `[PORTAL_GRANT_OPERATOR_STEPS]` |
| Live provisioning QR method | `[GENERATE_QR_BOUND_TO_REVIEW_DEVICE_OPERATOR]` |
| Base Sepolia test ETH method | `[TEST_ETH_FAUCET_OR_PREFUNDED_METHOD]` |
| Test-token payment method | `[TEST_PAYER_WALLET_OR_FAUCET_STEPS]` |
| Review support contact | `[MONITORED_EMAIL_AND_PHONE_WITH_TIME_ZONE]` |

The provisioning payload contains the operator EOA created on the review device. Therefore, a
static provisioning QR generated before installation will not work. The reviewer must be able to
authorise the newly displayed operator and generate a matching live QR without waiting for manual
intervention.

## Apple App Review notes

OPK Terminal 0.1.12 is a merchant terminal for Base Sepolia testnet only. Base Mainnet and all
other production networks are disabled. The app does not provide a cryptocurrency exchange,
mining, token purchases, token rewards, customer-wallet custody, or customer private-key storage.
It has no app account, advertising SDK, OpenPasskey-operated general-purpose analytics SDK,
in-app purchases, or subscriptions.

The app creates one device-local merchant operator EOA. Its private key remains protected by the
device and can sign only a constrained settlement sweep to the configured merchant vault.

Full review requires the companion OpenPasskey Merchant Portal because every provisioning QR is
bound to the operator generated on the review device.

Review steps:

1. Open **Settings** and select **Create protected operator wallet**. Complete the system
   authentication prompt.
2. Set and confirm a six-digit local Admin PIN. This PIN protects local setup controls and is not
   an OpenPasskey account credential.
3. Copy the displayed operator address or scan its pairing QR in
   `[LIVE_REVIEW_PORTAL_URL]`. Access the portal using
   `[REVIEW_PORTAL_ACCESS_OR_DEMO_ACCOUNT]`.
4. In the portal, select Base Sepolia vault `[BASE_SEPOLIA_REVIEW_VAULT_ADDRESS]` and test token
   `[BASE_SEPOLIA_TEST_TOKEN_ADDRESS_AND_SYMBOL]`; authorise the displayed operator using
   `[PORTAL_GRANT_OPERATOR_STEPS]`.
5. Generate the live provisioning QR for that same operator using
   `[GENERATE_QR_BOUND_TO_REVIEW_DEVICE_OPERATOR]`. In OPK Terminal, select **Scan first payment
   profile** and scan it. Camera access is used only for provisioning/configuration QR codes.
6. Fund the operator with Base Sepolia test ETH using
   `[TEST_ETH_FAUCET_OR_PREFUNDED_METHOD]`, then refresh readiness until the profile is ready.
7. On **Checkout**, select the provisioned profile, enter `1.00`, and create the ERC-681 payment QR.
8. Send the designated Base Sepolia test token using `[TEST_PAYER_WALLET_OR_FAUCET_STEPS]`. Wait
   for the app to show the required confirmation.
9. Open **History** to inspect the invoice. Open **Settle**, review the constrained sweep, and
   complete the Face ID/device-authentication prompt to submit it.

No real-value payment is required or supported. If any review resource is unavailable, contact
`[MONITORED_EMAIL_AND_PHONE_WITH_TIME_ZONE]`.

## Google Play App access instructions

Some functionality is restricted until a merchant authorises and provisions the device-created
operator. No account is created inside OPK Terminal.

Use `[LIVE_REVIEW_PORTAL_URL]` with `[REVIEW_PORTAL_ACCESS_OR_DEMO_ACCOUNT]`, then follow these
steps:

1. In OPK Terminal **Settings**, select **Create protected wallet** and complete the Android
   biometric or device-credential prompt.
2. Enter and confirm the six-digit local Admin PIN, then select **Set admin PIN**.
3. Copy the operator address or scan its pairing QR in the review Merchant Portal.
4. Authorise the operator for Base Sepolia vault `[BASE_SEPOLIA_REVIEW_VAULT_ADDRESS]` and token
   `[BASE_SEPOLIA_TEST_TOKEN_ADDRESS_AND_SYMBOL]`.
5. Generate a provisioning QR bound to that operator and scan it with **Scan merchant portal
   setup**.
6. Fund settlement gas through `[TEST_ETH_FAUCET_OR_PREFUNDED_METHOD]` and refresh readiness.
7. Create a `1.00` payment QR from **Checkout**, pay it using
   `[TEST_PAYER_WALLET_OR_FAUCET_STEPS]`, inspect **History**, and authenticate the constrained
   settlement.

The app is Base Sepolia testnet-only. It has no exchange, mining, customer custody, app account,
ads, in-app products, or subscriptions. Support:
`[MONITORED_EMAIL_AND_PHONE_WITH_TIME_ZONE]`.

## Pre-submission review-environment check

- Perform the entire flow on a clean iPhone/iPad and a clean Android device using the exact
  credentials and URLs placed in the consoles.
- Confirm the portal account is not protected by an unavailable employee passkey or one-time code.
- Confirm the review vault remains deployed, the token remains whitelisted, and the operator can
  be authorised without production funds.
- Confirm test ETH and test tokens are available for multiple review attempts.
- Keep the public Base Sepolia RPC and Merchant Portal available for the full review window.
- Remove all placeholders from both consoles.
