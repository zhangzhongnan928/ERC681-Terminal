# Security policy

This project builds a payment terminal that holds a secp256k1 signing key
and submits transactions to a public blockchain. Please read this before
reporting an issue, and before running the code with real value.

## Scope and status

- **This code has not been independently audited.**
- **Base Sepolia (`84532`) is the only network enabled in shipped builds.**
  Base Mainnet (`8453`) and every other network are disabled in code and
  remain disabled until a frozen or multisig-governed, implementation-pinned
  deployment and its CREATE2 vector are reviewed and shipped.
- The safety boundary described in `README.md` and `MOBILE_SDK.md`
  describes the behaviour of *this* source tree, built as published,
  against the pinned deployments in `conformance/`. Those properties are
  not guaranteed to survive a fork, a configuration change, an added
  network, or a substituted contract deployment.
- The known recovery gaps — no cross-operator `Swept` log discovery, no
  same-nonce fee replacement or cancellation — are documented in
  `MOBILE_SDK.md`. Read them before operating the settlement wallet.

Running this software with real value is at the operator's own risk. See
sections 7 and 8 of the Apache License in `LICENSE`.

## Reporting a vulnerability

**Do not open a public issue for a security vulnerability.**

Report privately through one of:

1. GitHub private vulnerability reporting — the **Security** tab of this
   repository, "Report a vulnerability". Preferred.
2. Email [v@openpasskey.com](mailto:v@openpasskey.com).

Please include:

- what an attacker can do, and what they need to start;
- affected component (Android app, iOS app, Kotlin SDK, Swift SDK,
  conformance vectors, provisioning grammar);
- version, build number, or commit;
- reproduction steps, and a transaction hash or invoice ID if the issue
  was observed on-chain.

## What to expect

| Stage | Target |
| --- | --- |
| Acknowledgement of report | 3 business days |
| Initial assessment | 10 business days |
| Fix or mitigation plan for a confirmed issue | case by case, communicated in the assessment |

This is a small project. These are good-faith targets, not a contractual
SLA.

We will credit reporters who want credit. Please give us a reasonable
opportunity to ship a fix before public disclosure, and coordinate timing
with us if the issue affects deployed merchant vaults.

## Areas of particular interest

Reports touching these are especially welcome:

- escaping the constrained signer — anything that produces a signature
  outside the `sweepSessions(bytes32[],uint256[],address)` selector, a
  pinned chain and vault, zero native value, or a whitelisted token;
- key extraction from Android Keystore or iOS Keychain storage, or
  bypassing the per-signing authentication prompt;
- CREATE2 receiver derivation mismatches, or receiver reuse that the
  code/balance checks fail to catch;
- ERC-681 or `opk-terminal:provision` parser flaws that let a scanned QR
  mutate configuration, select a field, or trigger a payment action;
- settlement evidence forgery — anything that records a settlement without
  a matching, canonical, sufficiently confirmed non-zero `Swept` event;
- JSON-RPC response handling that accepts missing, duplicate, foreign,
  malformed, or wrong-block results as proof.

## Supported versions

Only the latest published release receives security fixes. At the time of
writing that is `0.1.12` (build 14). Older builds and forks are not
supported.
