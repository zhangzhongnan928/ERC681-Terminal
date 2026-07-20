# Security Audit — ERC-681 Terminal (Android & iOS apps + SDKs)

**Date:** 2026-07-20
**Scope:** `android/` (Jetpack Compose app + `erc681-sdk` Kotlin library) and `ios/`
(SwiftUI app + `OPKTerminalCore`/`OPKTerminalRPC`/`OPKTerminalOperator` Swift packages),
audited against the shared vectors in `conformance/opk-erc681-v1.json` and the reference
`OPK_Protocol_1.5.md` / live 1.4.1 deployment. The `OPK-Pay-Merchant-Portal` and
`OPK-Terminal-Native-Payment-Protocol` repositories were consulted for reference only.
**Commit audited:** `1540829` (v0.1.2).

---

## 1. Verdict

This is an unusually well-constructed, defense-in-depth payment terminal. **No Critical or High
severity issues were found.** The cryptographic core was independently re-implemented from scratch
and reproduces **every** conformance vector byte-for-byte. The device signing boundary is tightly
constrained and cannot be steered by attacker-controlled RPC, invoice, or configuration data into a
different destination, value, selector, or calldata target.

The residual risk is concentrated in one place and is largely **inherent to a serverless,
single-RPC light-client design and already disclosed** in the project's own documentation: a
fully-attacker-controlled (but TLS-valid) RPC endpoint can mis-report payment/settlement state to
the merchant. It cannot steal keys or redirect funds.

| Severity | Count | Summary |
|---|---|---|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 3 | Single-RPC trust (payment/settlement spoofing); Android 30 s auth window (no `CryptoObject`); ERC-681 SDK chain-check is opt-in |
| Low | 8 | Signer-layer calldata validation depth; no gas-fee ceiling; no EIP-55 checksum; dated `web3j`; physical config substitution; cross-platform parser divergence; no `FLAG_SECURE`; non-independent vault re-pin |
| Info | 6 | See §5 |

The apps are correctly described by their own docs as **suitable for Base Sepolia testing, not yet
mainnet-ready.** The findings below are consistent with that posture.

---

## 2. Methodology

1. **Independent cryptographic re-verification.** Keccak-256, secp256k1 ECDSA (RFC 6979), and
   EIP-1559 RLP were re-implemented independently (see `scripts/verify_conformance_vectors.py`) and
   used to recompute every value in `conformance/opk-erc681-v1.json` — invoice ID, all function
   selectors, the `Swept` event topic, the full CREATE2 derivation, `sweepSessions` calldata, and
   the complete signed transaction and hash from the test private key. **All 25 checks pass.** The
   in-repo Kotlin/Swift primitives were then read line-by-line and confirmed structurally
   equivalent.
2. **Line-by-line review** of the key-custody, signing-constraint, ABI, RPC, reconciliation, and
   persistence code on both platforms.
3. **Trust-boundary tracing** of the end-to-end settlement path (paid observation → handoff →
   operator module → calldata → sign → broadcast → verify) hunting for confused-deputy / TOCTOU /
   reconciliation defects.
4. **On-chain assumption check:** the reference `ClearingVault`/`SessionReceiver` contracts were
   read to confirm the property the whole model relies on (sweeps can only land in the merchant
   vault).
5. **Secrets & supply-chain sweep** of the working tree and full git history.

---

## 3. Architecture: why the signing boundary is sound

The terminal holds a device-local secp256k1 "operator" key that can sign exactly one on-chain
action: `ClearingVault.sweepSessions(bytes32[],uint256[],address)`. The safety of this rests on a
layered argument, all of which was verified:

- **On-chain destination is not caller-controlled.** `SessionReceiver.sweep(token)` hard-codes
  `safeTransfer(clearingVault, balance)` and requires `msg.sender == clearingVault`;
  `ClearingVault.sweepSessions` takes **no** destination argument. Therefore *any* `sweepSessions`
  call — even one built from fully-tampered local data — can only move one-time receiver balances
  into the **merchant's own vault**. There is no parameter that redirects funds to an attacker.
- **On-device the signer is constrained to `to`/`value`/`selector`/`chain`.** Because the chain
  guarantees the destination, the device only has to guarantee it is calling `sweepSessions`
  (selector `0x682b11b5`), to the configured vault, with `value == 0`, on the activated chain. Both
  platforms enforce exactly this, from **locally-trusted config**, never from RPC or the handoff.
- **The reusable SDKs are keyless and read-only**, enforced by a CI guard
  (`scripts/check-mobile-boundary.sh`) that statically bans NFC, key custody, signing, and write
  RPC outside a small allowlist of operator files, and bans `eth_sendTransaction`, `eth_sign`,
  `personal_sign`, `signTypedData`, `approve(`, `payoutTo(`, `refund(`, `rescue(`, and `deploy(`
  anywhere in the tree.

**Residual worst case with a fully attacker-controlled RPC:** keys and customer funds are safe; the
operator's only monetary exposure is gas (one reverting/doomed transaction) plus a stuck-nonce DoS.
The genuine residual is **mis-reporting** (see M1).

---

## 4. Findings

### MEDIUM

#### M1 — Payment confirmation and settlement finality both reduce to trusting a single RPC endpoint
**Platforms:** Android + iOS. **Type:** design / trust model.
**Where:** payment confirmation `balanceOf(receiver)` (`PaymentObserver.kt:51`,
`SettlementRpc.kt:134`; iOS `PaymentMonitor`), settlement decided from receipt logs
(`SettlementRepository.kt:399-431`; iOS `SettlementCoordinator` reconcile).

The receiver address is derived **locally** via CREATE2 (good — the app never trusts RPC
`computeReceiver`). But the two decisions that drive merchant behaviour — "is this invoice paid?"
and "did settlement land?" — are taken from responses of the single configured RPC. A hostile or
compromised (but TLS-valid) endpoint can:
- return a fake non-zero `balanceOf` so an **unpaid** invoice shows `PAID`, and/or
- fabricate a `status = success` receipt carrying a canonical `Swept` log with the correct
  receiver/vault/invoiceId/token/expected-amount, forcing the invoice to `SETTLED`/`final`.

**Failure scenario:** a merchant whose terminal points at an attacker's RPC ships goods against a
spoofed "Paid"/"Settled" indicator, with no real on-chain payment.

**Bounding & mitigations present (credit):** funds cannot be redirected (destination is CREATE2/
contract-bound); cleartext is disabled with **system trust anchors only** (no user-CA MITM); HTTPS
is enforced on config save; responses are strictly validated; confirmations and reorg re-checks are
required; settlement requires a canonical `Swept` event (never bare receipt success). This raises
the bar to a *genuinely malicious trusted endpoint*, not passive interception. **Not mitigated:**
no multi-RPC quorum or light-client inclusion proof.

**Recommendation:** before mainnet, add multi-endpoint agreement (or a light-client/inclusion
proof) for payment and settlement confirmation, and document that the RPC must be an authenticated,
operator-trusted provider. This is the headline risk to communicate to operators.

#### M2 — Android signing key is gated by a 30-second time window, not a transaction-bound `CryptoObject`
**Platform:** Android. **Type:** key custody / defense-in-depth.
**Where:** `wallet/OperatorWalletStore.kt:263-289` (`setUserAuthenticationParameters(30, …)`),
`ui/components/DeviceAuthentication.kt:39-45` (`BiometricPrompt.authenticate(PromptInfo)` — the
overload **without** a `CryptoObject`).

Because Android Keystore cannot sign secp256k1, the raw 32-byte scalar is decrypted into app
process memory to sign with `web3j` (`OperatorWalletStore.kt:220-223`). The biometric prompt is a
UI callback; the actual gate is a 30-second Keystore auth window that the prompt refreshes. Auth is
genuinely enforced (a decrypt with no recent auth throws `UserNotAuthenticatedException`), and a
**separate** app cannot exploit this (Keystore keys are UID-scoped; `signSettlementTransaction` is
`internal`). The real exposure is **in-process**: code running in the app within the window (a
compromised transitive dependency, native lib, or a code-exec bug) can call the wrapping key +
`Cipher.DECRYPT_MODE` directly and **exfiltrate the raw scalar**, bypassing every
`signSettlementTransaction` constraint. `AUTH_DEVICE_CREDENTIAL` also means the device PIN alone
suffices.

This is explicitly disclosed (`MOBILE_SDK.md:24-29`) and listed as pre-production hardening. iOS is
stronger here: every signature builds a fresh `LAContext`, calls
`evaluatePolicy(.deviceOwnerAuthentication)`, and gates a `.userPresence` Keychain read — a genuine
per-signature gate.

**Recommendation:** bind signing to a per-operation `BiometricPrompt.CryptoObject` and set the
authentication window to 0 (auth-every-use). This closes the "ride the shared window" gap; the raw
key's in-memory presence during signing is fundamental to Ethereum keys on Android and cannot be
fully removed there. (Usability sub-note: `submit()` re-runs several RPC round-trips *after* the
auth callback and *before* signing; on a slow network these can exceed 30 s and fail signing —
another reason to move to a per-operation `CryptoObject`.)

#### M3 — ERC-681 SDK parser enforces the chain only when the caller opts in
**Platforms:** Android + iOS (published SDK surface). **Type:** API footgun.
**Where:** `Erc681Codec.parse(uri, expectedChainId: Long? = null, …)` (`Erc681Codec.kt:30`,
enforced only `if (expectedChainId != null)` at `:37-42`); iOS
`ERC681TransferRequest.parse(_, expectedChainID: UInt64? = nil)` (`ERC681.swift:43`, enforced only
`if let expectedChainID` at `:61-63`).

`mustReject[3]` (`…@1/transfer?…`, wrong chain) matches the canonical grammar and is rejected
**only** because callers pass the chain. Every in-app production caller does (iOS
`StoredInvoice.swift` passes `expectedChainID` and cross-checks token/recipient/amount/canonical
string), so this is **not exploitable in the shipped apps**. But the SDK is published for reuse
(`com.openpasskey:opk-erc681-sdk`, and the Swift `OPKTerminalCore` product); a third-party
integrator who parses an untrusted URI without passing the chain silently accepts any chain,
including mainnet `@1`. `MOBILE_SDK.md:55` itself makes the safety property depend on the caller
remembering the argument.

**Recommendation:** make the chain parameter required (remove the default), or reject when absent.

### LOW

- **L1 — Innermost signing primitive does not fully self-validate the calldata.** Android's key
  layer checks only the 4-byte selector (`OperatorWalletStore.kt:189`), not the `token` argument
  (vs. whitelist), amounts, array lengths, or trailing bytes; iOS's `OperatorWallet.sign(digest:)`
  (`OperatorWallet.swift:58-66`) signs an **arbitrary** 32-byte digest after user presence, with no
  `to`/`value`/selector assertion at all (it relies entirely on the coordinator, which does
  re-derive and compare calldata). Both are safe today (calldata is built locally; the primitives
  are package-internal single-caller, CI-enforced), but a future second caller or bug would bypass
  the constraint. **Recommendation:** have each platform's signing layer independently decode and
  assert the full `sweepSessions` structure (selector + whitelisted token + array bounds + exact
  length) and `to`/`value`/`chain`, symmetric to iOS's existing `validatePersistedSweep`.

- **L2 — No absolute ceiling on RPC-supplied gas fees.** `feeQuote` derives `maxFeePerGas`/priority
  from RPC values with no upper clamp (`SettlementRpc.kt`/`SettlementFeePolicy`; iOS
  `OperatorRPC.feeQuote`). A hostile RPC can inflate the tip and drain the operator's **gas float**
  (bounded to the small merchant-funded balance; never customer/vault funds; a wildly high value
  fails the balance check and won't sign). A 120 % review→sign drift cap exists but no absolute
  cap. **Recommendation:** clamp to a configurable absolute ceiling before signing.

- **L3 — No EIP-55 checksum validation.** Mixed-case, checksum-invalid addresses are silently
  lower-cased and accepted by both the URI parser and the config scanner
  (`EvmAddress.kt:18-24`; iOS `FixedBytes.swift` / `Hex.swift`). Mitigated because configuration
  addresses are re-validated on-chain (factory/impl/vault links, `isPaymentToken`/`decimals`)
  before any payment QR, which catches most substitutions. **Recommendation:** enforce EIP-55 as a
  first-line guard against QR corruption/typos.

- **L4 — Dated `web3j 4.8.9-android`** (`gradle/libs.versions.toml:17`, 2021-era) pulls old
  `jackson-databind`/`okhttp`/`bouncycastle` transitively. No directly reachable exploit was found
  (web3j does not enable Jackson default typing for RPC parsing, and key generation via
  `Keys.createEcKeyPair()` is sound), but this is stale supply-chain surface that handles
  attacker-influenced JSON. **Recommendation:** upgrade and run a dependency-vulnerability scan.

- **L5 — Physical-access configuration substitution.** `vault`/`factory`/`receiverImplementation`/
  `token` are editable in Settings and settable via the camera scanner. An attacker with physical
  Settings access who deploys a malicious vault whose `factory()`/`isPaymentToken()` mimic the real
  ones could redirect **new** invoices' settlement to their vault. Bounded by on-chain link
  validation, per-invoice immutable snapshots (no retroactive redirection), and the scanner
  rejecting payment URIs. Disclosed as needing "independently verified deployment constants" before
  mainnet. **Recommendation:** a signed/pinned deployment-constant allowlist for production.

- **L6 — Cross-platform parser divergence.** iOS accepts leading-zero amount integer parts
  (`012.34`) and a `0X` address prefix; Android rejects both. No fund impact (canonical output is
  still correct; amount is only a wallet suggestion), but the platforms disagree on input
  acceptance and iOS violates the stated "reject leading zeros" rule. **Recommendation:** align on
  the stricter (Android) behaviour.

- **L7 — No `FLAG_SECURE` on Android** (`MainActivity.kt` sets only `FLAG_KEEP_SCREEN_ON`). Invoice
  amounts/addresses appear in screenshots and the recents thumbnail. No private-key material is
  ever rendered (only the public operator address), so low value — but a payments terminal
  typically sets `FLAG_SECURE`.

- **L8 — Android key-layer vault check is self-consistent, not an independent pin.**
  `prepareInternal` calls `activateInvoiceNamespace(chainId, vaultAddress)`
  (`SettlementRepository.kt:271`) from the same invoice snapshot that produces `to`, moments before
  signing, so the gate's `to == activatedVault` check confirms "to == the vault just
  activated/simulated" rather than comparing against a separate source of truth. Not exploitable
  (per-invoice snapshots are the real integrity source; only one chain/vault is active at a time),
  but the control reads stronger than it is.

### INFO

- **I1 — `exportSchema = false`** (`InvoiceDatabase.kt:16`) means no checked-in Room schema, so
  migration tests can't verify that the v1 schema contained `settledTxHash`, which `MIGRATION_2_3`
  references (`:54-58`). If v1 lacked it, the 1→2→3 open path throws `no such column`. Verify the v1
  fixture (or add the column defensively). Positively: neither migration uses
  `fallbackToDestructiveMigration`, so no silent data wipe, and both correctly downgrade legacy
  `SETTLED` → `SETTLEMENT_REVIEW_REQUIRED`.
- **I2 — Biometric-enrollment changes do not invalidate the operator key** (Android
  `setInvalidatedByBiometricEnrollment(false)`; iOS `.userPresence` rather than
  `.biometryCurrentSet`). A deliberate, defensible choice for a shared/staff terminal; impact is
  capped by the constrained signer. Consider documenting the threat-model rationale.
- **I3 — No TLS certificate pinning** for the user-configurable RPC host. Hard to pin an arbitrary
  host; consider pinning the shipped default endpoint.
- **I4 — Documented recovery limits** (no same-nonce fee replacement/cancellation; no cross-operator
  `eth_getLogs` discovery) are **safe-by-default (stuck), not unsafe**: an underpriced tx keeps the
  operator serialized to its nonce and only identical bytes are rebroadcast; an externally-swept
  receiver fails the local balance check and must be reconciled manually. Resolve before mainnet.
- **I5 — Android conformance-test gaps:** it does not assert the shared `abiEncoded` or `salt`
  strings (iOS does), and there is no negative test for `parse()` **without** the chain, nor for
  parameter reordering / percent-encoding at the `Erc681Codec` level (the anchored regex covers
  them but they are untested).
- **I6 — iOS `prepareSettlement` omits the CREATE2 receiver re-derivation** that
  `reconcileForegroundInvoices` performs. It fails closed (a wrong receiver yields no matching
  `Swept` event → not settled), so not exploitable; re-deriving would catch a tampered local
  snapshot earlier.
- **Injection note:** repository documentation and source were checked for embedded/adversarial
  instructions; none were found. `README.md`/`MOBILE_SDK.md`/`SETTLEMENT_SECURITY.md` are
  legitimate documentation.

---

## 5. What is implemented well (verified, not assumed)

- **Cryptographic core is correct and conformance-locked.** Independent re-implementation of
  Keccak-256, secp256k1 (RFC 6979), and EIP-1559 RLP reproduces all 25 vectors; both platforms
  assert the full signed `rawTransaction` and `transactionHash` byte-for-byte against the shared
  fixture (`SettlementConformanceTest.kt:131-132`, `OperatorWalletAndSettlementTests.swift:38-66`).
  Keccak uses the correct legacy `0x01` padding (not NIST SHA3); low-s is guaranteed
  (libsecp256k1 on iOS; BouncyCastle on Android).
- **Vetted secp256k1, not hand-rolled ECDSA.** iOS: `swift-secp256k1` pinned `exact: 0.23.2` +
  revision. Android: `web3j`/BouncyCastle. Keys are generated from a CSPRNG
  (`P256K.Recovery.PrivateKey`; `Keys.createEcKeyPair()`), never from the legacy identifier.
- **Constrained signer, defense-in-depth.** `to` = configured vault, `value` = 0, selector pinned,
  chain pinned — enforced from local config, never RPC/handoff. iOS additionally re-validates all 12
  RLP fields of the persisted signed tx on the retry path (`validatePersistedSweep`).
- **Settlement = canonical `Swept` event, never receipt success.** Verification binds
  vault/receiver/invoiceId/token/expected-amount, rejects removed/malformed/duplicate/zero/
  mismatched logs, dedups by a **unique** `(chainId, txHash, logIndex)` index with
  `OnConflictStrategy.ABORT`, and accumulates gross per `(chain, vault, invoiceId, token)` (uint256
  stored as decimal strings to avoid SQLite truncation).
- **Robust nonce/broadcast lifecycle:** persist-before-broadcast, one-active-tx-per-operator gate,
  atomic invoice attach, idempotent same-bytes rebroadcast with "already known" handling,
  reorg-aware confirmation, monotonic local nonce reservation.
- **Strong key custody & hygiene.** Android: AES-256-GCM Keystore wrapping, StrongBox preferred
  with graceful fallback, randomized per-encryption IV, bound AAD, scalar zeroized, address
  re-derived and cross-checked after decrypt. iOS: Keychain `WhenUnlockedThisDeviceOnly` +
  non-synchronizable + `.userPresence`, per-signature auth, scalar zeroized. No seed import, no key
  export on either platform.
- **Platform hardening.** Android: cleartext disabled (system CAs only), `allowBackup=false` plus
  full backup/cloud/device-transfer exclusions, minimal permissions, single non-abusable exported
  launcher activity. iOS: no ATS cleartext exception, camera + Face ID usage strings, correct
  privacy manifest, `http` allowed only for loopback.
- **Narrow, ephemeral config scanner:** accepts only a single non-zero 20-byte address (raw or
  address-only `ethereum:` URI), rejects chain-qualified/function/query/fragment/WalletConnect/
  http/JSON/payment payloads, is non-mutating on reject, bound to one field, and cannot navigate or
  set amounts. Frames are never logged or persisted.
- **CI-enforced trust boundary** (`scripts/check-mobile-boundary.sh`) and a **serverless design**
  (no portal/server dependency; the terminal talks directly to chain RPC).
- **No committed secrets** in the working tree or full git history; the only private key present is
  the documented public test key `0x…0001`, used solely in fixtures/tests.

---

## 6. Recommended actions before mainnet

1. **(M1)** Add multi-RPC agreement or a light-client/inclusion proof for payment and settlement
   confirmation; document the trusted-endpoint requirement.
2. **(M2)** Move Android signing to a per-operation `CryptoObject` with an auth-every-use window.
3. **(M3)** Make the ERC-681 SDK chain argument required.
4. **(L1)** Have both platforms' innermost signing layer fully self-validate the `sweepSessions`
   calldata and `to`/`value`/`chain`.
5. **(L2)** Clamp RPC-supplied gas fees to an absolute ceiling.
6. **(L4)** Upgrade `web3j` and run a dependency-vulnerability scan.
7. **(L5)** Pin/sign the deployment constants for production; **(L3)** enforce EIP-55.
8. **(I1)** Verify the Room v1→3 migration against a real v1 fixture; **(I4)** implement the
   documented operator-recovery paths.

Also finish the hardening the docs already call out: Android transaction-bound authentication,
same-nonce fee replacement/cancellation, cross-operator `Swept` discovery, and physical-device
app-store testing.

---

*This report reflects a point-in-time review of commit `1540829`. It is not a guarantee of the
absence of vulnerabilities. The reproducible vector verification lives in
`scripts/verify_conformance_vectors.py`.*
