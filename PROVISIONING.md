# Terminal pairing and provisioning v1

The merchant controls vault administration from the OPK Pay Merchant Portal on a personal device.
The terminal never stores a merchant passkey or smart-account signing key. It stores only its own
device-local settlement EOA and a local administrator PIN verifier.

The setup channel trusts the merchant to select the intended portal account, vault, and token. The
terminal's chain reads check protocol compatibility and operational readiness; they do not prove
the merchant's business identity.

## Wire payloads

Payloads are exact, case-sensitive raw ASCII. Parsers do not trim, normalize, URL-decode, or accept
reordered, duplicate, missing, or additional fields. Addresses use a lower-case `0x` prefix,
exactly 40 hexadecimal digits, and must not be zero. Parsed addresses are normalized to lower case.

The terminal first shows its public EOA to the portal:

```text
opk-terminal:operator?v=1&address=<operator>
```

After the merchant confirms `grantOperator`, the portal returns one configuration bound to that
same terminal:

```text
opk-terminal:provision?v=1&chainId=<chainId>&vault=<vault>&token=<token>&operator=<operator>
```

`chainId` is canonical base-10 in the range `1...9223372036854775807`, without a leading zero. The
provisioning `operator` must equal the terminal's local EOA. The shared accepted and rejected vectors
are in `conformance/opk-terminal-provisioning-v1.json`.

## Supported deployment

Automatic provisioning v1 supports Base Sepolia (`84532`) only. The app owns an immutable profile
containing the default RPC endpoint, network name, protocol version, factory, receiver
implementation, CREATE2 vector, and gas-reserve policy. Persisted settings are never a source of
deployment pins. Unknown chains are rejected before contacting an RPC endpoint.

The terminal reads the scanned vault's factory, the pinned factory's implementation, token
whitelist membership, decimals, and symbol. It performs the complete existing configuration and
CREATE2 validation before atomically replacing current settings. A failed or cancelled attempt
does not alter settings. A retained operational RPC override is checked only for the expected chain
ID; vault runtime, factory/implementation, whitelist, token metadata, and full provenance validation
come exclusively from the immutable shipped RPC endpoint. The override is persisted only after both
checks pass. Historical invoices and settlements retain their stored configuration
snapshots. Before settling one after reprovisioning, the app re-derives its receiver and re-proves
its network label, factory/implementation pins, vault runtime and factory link, whitelist, and token
metadata through the immutable shipped RPC endpoint. The stored operational RPC is independently
chain-checked for current EOA authorization, exact balances, simulation, and broadcast.

## Readiness

The setup state advances as follows:

```text
Create device EOA -> set local admin PIN -> authorize EOA in portal -> scan portal QR -> fund EOA -> ready
```

Before every new invoice, the terminal fails closed unless all of these are freshly satisfied:

- the device-local EOA is available;
- the saved configuration validates;
- the EOA is the configured vault owner or an authorized operator; and
- its native balance is at least `100000000000000` wei (`0.0001 ETH`).

An unready terminal blocks only new invoice and payment-QR creation. Settings, funding guidance,
history, existing invoice monitoring, and settlement recovery remain available.

## Local administration

First-run setup remains visible until the device is provisioned. After setup, a six-digit local
administrator PIN hides reprovisioning, network and vault controls, and operator-wallet reset from
normal staff mode. The PIN is a local UI and operations boundary, not merchant authentication.
Failed attempts are throttled, and the admin session locks when the app backgrounds.
Creating the initial PIN unlocks that foreground admin session. After a PIN exists, creating or
replacing a device wallet, every provisioning attempt, and destructive wallet reset require a
currently unlocked session. Reset rechecks that same session after its asynchronous balance proofs
and atomically wraps configuration clear plus key deletion; backgrounding, restart, reset, or an
unlock epoch change invalidates pending authorization before the protected mutation is committed.

Destructive in-app key reset is available only before the first payment QR is issued. After a QR is
published, its receiver remains payable forever, including after a successful sweep, so polling can
never make later key deletion race-free. Normal administration keeps the EOA and uses portal
authorization or reprovisioning. If the device EOA is actually lost, the merchant must authorize a
replacement operator on every historical vault before it can recover later payments. Only a small
native gas balance should be kept on the terminal EOA. Before an otherwise allowed reset, withdraw
all native gas: the app uses the immutable shipped RPC endpoint to require both latest and pending
balances to be exactly zero twice, immediately rechecking before local key deletion. RPC failure
cancels reset. A late transfer to the retired, previously shared address can still be unrecoverable.
