# Refactor Prompt — Step 1: Embed the merchant portal into the terminal, kill copy-paste provisioning

> Audience: an autonomous coding agent (Codex). Read this whole document before writing code.
> Scope: provisioning/onboarding UX only. **Step 2 (paymaster / EIP-7702 gas sponsorship for the
> operator's sweeps) is explicitly OUT OF SCOPE for this prompt** and will follow separately.

---

## 0. Repositories and required reading

| Repo | Role in this refactor |
|---|---|
| `ER681-Terminal` (Android app + `erc681-sdk`; iOS app + `OPKTerminalCore/RPC/Operator` packages) | Primary work area |
| `OPK-Pay-Merchant-Portal` (Next.js) | Small changes + **reference implementation** for passkey/SCA semantics |
| `OPK-Terminal-Native-Payment-Protocol` (`contracts/src/*.sol`) | Reference only — ABI ground truth. Do not modify. |

Before coding, read in `ER681-Terminal`:
- `SECURITY_AUDIT.md` — the security model you must preserve.
- `MOBILE_SDK.md` and `README.md` — the documented safety boundary.
- `scripts/check-mobile-boundary.sh` — a CI guard that **will fail your build** if you place
  signing, camera, or write-RPC code outside its allowlists. You will need to evolve it
  deliberately (Section 6), never weaken it silently.
- `conformance/opk-erc681-v1.json` — shared cross-platform vectors; the culture of this codebase
  is "pin every derivation to a shared vector and test it on both platforms." Follow it.

Current pain (what we are removing): onboarding requires the merchant to open the web portal,
scan/type **seven** values into the terminal (RPC URL, chain ID, vault, factory, token address,
token symbol, token decimals — see `OPK-Pay-Merchant-Portal/app/dashboard/vault/[address]/add-terminal/page.tsx:119-125`),
then carry the terminal's operator address back to the portal for