# Terminal pairing and provisioning v1

The merchant controls vault administration from the OPK Pay Merchant Portal on a personal device.
The terminal never stores a merchant passkey or smart-account signing key. It stores only its own
device-local settlement EOA and a local administrator PIN verifier.

The setup channel trusts the merchant to select the intended portal account, vault, and payment
asset. The terminal's chain reads check protocol compatibility and operational readiness; they do
not prove the merchant's business identity.

## Wire payloads

Payloads are exact, case-sensitive raw ASCII. Parsers do not trim, normalize, URL-decode, or accept
reordered, duplicate, missing, or additional fields. Addresses use a lower-case `0x` prefix,
exactly 40 hexadecimal digits, and must not be zero. Parsed addresses are normalized to lower case.

The terminal first shows its public EOA to the portal:

```text
opk-terminal:operator?v=1&address=<operator>
```

After the merchant confirms `grantOperator`, the portal returns one payment profile bound to that
same terminal:

```text
opk-terminal:provision?v=1&chainId=<chainId>&vault=<vault>&token=<token>&operator=<operator>
```

`chainId` is canonical base-10 in the range `1...9223372036854775807`, without a leading zero. The
provisioning `operator` must equal the terminal's local EOA. The shared accepted and rejected vectors
are in `conformance/opk-terminal-provisioning-v1.json`.

`token` names the on-chain payment asset. It may be an ERC-20 contract or the exact EIP-7528
sentinel `0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE` for the chain-native asset. The sentinel is
used only for configuration and contract calls; it never appears in the native customer payment
QR.

Confirmation depth is deliberately not a v1 QR field. The first route on an EVM network starts at
that network's compiled default (one block on Base Sepolia). After provisioning, a merchant
administrator can choose a value from that network's compiled minimum through 64 in the terminal's
PIN-protected Admin/setup controls. All profiles on the same chain share that network policy, and a
new profile on an already configured chain inherits its current value. The value is snapshotted into
new invoices; changing it does not rewrite historical invoices or their settlement requirements.

Each successful scan atomically adds or updates the profile identified by `(chainId, vault, token)`
and selects it; it does not delete other configured profiles. The merchant can therefore scan the
portal setup QR for each desired vault/payment-asset combination. A sale selects exactly one stored profile,
and an invoice never requests multiple payment assets. Re-scanning the same identity refreshes its
chain-derived metadata without creating a duplicate. The iOS and Android apps and their reusable
Swift and Kotlin catalogs all enforce a maximum of 32 profiles per terminal.

## Supported deployment

The native apps enable only Base Sepolia (`84532`) in this release. They own an immutable per-chain
profile containing the default RPC endpoint, network name, protocol version, factory, receiver
implementation, vault runtime hash, CREATE2 vector, testnet flag, native-currency metadata,
minimum/default confirmation policy, and gas-reserve policy. Persisted settings are never a source
of those trust anchors or minimums. Base Sepolia's compiled confirmation minimum and fresh-network
default are both one block. The PIN-protected merchant control can select any allowed value but may
never reduce a network below the compiled floor. Base Mainnet (`8453`) and all other chains are
rejected before contacting an RPC endpoint. A published Base Mainnet OPK Protocol 1.6 Route A
deployment exists, but Mainnet remains disabled until an explicit product decision ships its
reviewed operational RPC, finality, native-currency, gas-reserve, deployment-pin, and CREATE2
policy. The checked-in enabled cross-platform constants are in
`conformance/opk-terminal-networks-v1.json`; the reusable SDK payment-profile catalogs remain
EVM-generic.

The terminal reads the scanned vault's factory, the pinned factory's implementation, and
payment-asset whitelist membership. For an ERC-20 it also reads contract code, decimals, and
symbol. For the native sentinel it instead requires a successful `NATIVE_ASSET()` call returning
that exact address and uses the immutable chain profile's `ETH`/18-decimal metadata. The whitelist
mapping alone is not a capability probe: the terminal must successfully read the exact sentinel
before interpreting its whitelist result or offering native checkout. The terminal performs the
complete configuration and CREATE2 validation before atomically upserting and selecting that
payment profile. A failed or cancelled attempt does not alter the catalog or its current selection.
A retained operational RPC override is checked only for the expected chain ID; vault runtime,
factory/implementation, capability, whitelist, payment-asset metadata, and full provenance validation
come exclusively from the immutable shipped RPC endpoint. The override is persisted only after both
checks pass. Historical invoices and settlements retain their stored configuration
snapshots. Before settling one after reprovisioning, the app re-derives its receiver and re-proves
its network label, factory/implementation pins, vault runtime and factory link, capability,
whitelist, and payment-asset metadata through the immutable shipped RPC endpoint. The stored
operational RPC is independently
chain-checked for current EOA authorization, exact balances, simulation, and broadcast.

Receiver derivation uses the existing CREATE2 formula, but a receiver commits to the factory and
receiver implementation. This release consumes the published Base Sepolia Route A constants and
matching 1.6 CREATE2 vector, so its receiver addresses differ from the previous stack. Any future
fresh stack must likewise publish and ship new per-chain constants and a matching vector before it
can be enabled. Address preservation applies only to a beacon upgrade of the same stack.

## Readiness

The setup state advances as follows:

```text
Create device EOA -> set local admin PIN -> authorize EOA in portal -> scan portal QR -> fund EOA -> ready
```

Before every new invoice, the terminal fails closed unless all of these are freshly satisfied for
the selected payment profile:

- the device-local EOA is available;
- the saved profile, known-network deployment pins, payment-asset whitelist, metadata, and any
  required native capability validate;
- the EOA is that profile's vault owner or an authorized operator; and
- its native balance on that profile's chain meets the compiled network reserve
  (`100000000000000` wei, or `0.0001 ETH`, on Base Sepolia); and
- the profile's confirmation depth meets that network's compiled minimum.

The same device EOA can be authorized by multiple vaults and has the same address on every EVM
chain. Authorization is still vault-specific, and gas funding is chain-specific. An unready profile
does not erase or invalidate another profile: the cashier can switch currencies, and the terminal
then performs a fresh readiness check for the newly selected route before enabling checkout.

An unready terminal blocks only new invoice and payment-QR creation. Settings, funding guidance,
history, existing invoice monitoring, and settlement recovery remain available.

## Local administration

First-run setup remains visible until the device is provisioned. After setup, a six-digit local
administrator PIN hides reprovisioning, network and vault controls, and operator-wallet reset from
normal staff mode. The PIN is a local UI and operations boundary, not merchant authentication.
Failed attempts are throttled, and the admin session locks when the app backgrounds.
The same protected controls let the merchant administrator adjust the confirmation requirement for
the selected EVM network without changing the immutable network trust anchors. The allowed range is
the network's compiled minimum through 64; all profiles on that chain share the choice, and the
setting applies only to future invoices.
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
cancels reset, and reset diagnostics identify the network name and chain ID that is funded or could
not be reached. A late transfer to the retired, previously shared address can still be unrecoverable.
