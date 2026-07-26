# Store reviewer instructions

Use these notes for OPK Terminal `0.1.12` build `14`. The primary review path is the isolated,
in-app offline reviewer demo. It requires no account, credentials, provisioning QR, external
hardware, test funds, or network connection.

The demo is intentionally and persistently labelled:

> OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS

It uses fixed public dummy values and in-memory state only. It does not read or create the live
terminal wallet, Keychain/Keystore data, saved configuration, invoices, preferences, or database;
it cannot authenticate, sign, broadcast, contact an RPC service, or settle a transaction. Closing
the demo discards its state.

## Apple App Review notes

OPK Terminal is a merchant payment terminal for Base Sepolia testnet only. Base Mainnet and all
other production networks are disabled. The app does not provide a cryptocurrency exchange,
mining, token purchases, token rewards, customer-wallet custody, or customer private-key storage.
It has no app account, advertising SDK, in-app purchases, or subscriptions.

No review credentials are required. To review the offline representative preview:

1. Launch OPK Terminal and tap **Explore offline demo**.
2. Confirm the persistent banner says **OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO
   NETWORK · NO REAL FUNDS**.
3. On **Demo Checkout**, inspect the locally rendered sample ERC-681 payment QR and tap
   **Simulate test payment**.
4. Tap **View demo history**, or select the **History** tab, to inspect the in-memory sample
   invoice and its paid status.
5. Select **Settlement**. The illustrative settlement is visible, but
   **Settlement disabled in demo** cannot authenticate, sign, or broadcast.
6. Tap **Close demo**. Reopening the demo starts again at the initial waiting state.

The separate **Open live terminal** path creates a device-local merchant operator and requires a
merchant to authorise and provision it through the companion OpenPasskey Merchant Portal. This
external merchant setup is not required for the reviewer demo. The live operator key remains
device-protected and is used only for the terminal's constrained settlement flow.

Support: `dev@openpasskey.com` and `https://www.openpasskey.com/support`.

If App Review requires a live Base Sepolia transaction in addition to the offline representative
preview, contact `dev@openpasskey.com`. OpenPasskey can provide time-bound testnet provisioning
assistance during review; no production network or real-value funds are supported.

## Google Play App access instructions

Select the Play Console option that says some functionality is restricted, because the live
merchant terminal requires external merchant authorisation and provisioning. No app account or
sign-in exists, and no credentials are required for the self-contained review path.

Reviewer steps:

1. Launch OPK Terminal and tap **Explore offline reviewer demo**.
2. Confirm the persistent banner says **OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO
   NETWORK · NO REAL FUNDS**.
3. On **Checkout preview**, inspect the locally rendered sample ERC-681 payment QR and tap
   **Simulate payment received**.
4. Select **History** to inspect the in-memory sample payment and paid status.
5. Select **Settlement**. The illustrative information is visible, but
   **Settlement disabled in demo** cannot authenticate, sign, or broadcast.
6. Use the back arrow to close the demo. Reopening it starts again at the initial waiting state.

The separate **Set up / open terminal** path is the live merchant path and requires an authorised
Base Sepolia Merchant Portal provisioning payload. The offline reviewer demo does not open that
path or any of its dependencies.

If Google Play review requires a live Base Sepolia transaction in addition to the offline
representative preview, contact `dev@openpasskey.com`. OpenPasskey can provide time-bound testnet
provisioning assistance during review; no production network or real-value funds are supported.

The app is Base Sepolia testnet-only. It has no exchange, mining, customer custody, app account,
ads, in-app products, or subscriptions.

Support: `dev@openpasskey.com` and `https://www.openpasskey.com/support`.

## Pre-submission verification

- [ ] Run the reviewer steps on a clean iPhone/iPad using the exact App Store archive.
- [ ] Run the reviewer steps on a clean Android device using the exact signed Play AAB.
- [ ] Confirm demo entry does not prompt for camera, biometrics, PIN, notification, or network
  access.
- [ ] Confirm demo close/reopen restores Checkout/Waiting and no demo item appears in live history.
- [ ] Confirm the exact safety banner remains visible on Checkout, History, and Settlement.
- [ ] Confirm neither store console contains a placeholder or promises live review credentials.
- [ ] Keep `dev@openpasskey.com` monitored throughout both reviews.
