#!/usr/bin/env python3
"""Independent, dependency-free re-verification of every conformance vector.

This is an audit artifact (see SECURITY_AUDIT.md). It re-implements Keccak-256,
secp256k1 ECDSA (RFC 6979, low-s), and EIP-1559 RLP from scratch -- deliberately
sharing no code with the Kotlin/Swift SDKs -- and recomputes every value in
conformance/opk-erc681-v1.json: the invoice ID, all function selectors, the Swept
event topic, the full CREATE2 receiver derivation, sweepSessions calldata, and the
complete signed type-2 transaction and hash from the public test key. Any mismatch
means the shared vectors (and therefore both platform implementations pinned to them)
have drifted.

Usage:
    python3 scripts/verify_conformance_vectors.py
Exit code 0 = all vectors reproduced; 1 = a mismatch was found.
Requires only the Python 3 standard library.
"""

import hashlib
import hmac
import json
import os
import sys

# --------------------------------------------------------------------------- #
# Keccak-256 (Ethereum: legacy 0x01 padding, not NIST SHA3-256)
# --------------------------------------------------------------------------- #
_RC = [
    0x0000000000000001, 0x0000000000008082, 0x800000000000808A, 0x8000000080008000,
    0x000000000000808B, 0x0000000080000001, 0x8000000080008081, 0x8000000000008009,
    0x000000000000008A, 0x0000000000000088, 0x0000000080008009, 0x000000008000000A,
    0x000000008000808B, 0x800000000000008B, 0x8000000000008089, 0x8000000000008003,
    0x8000000000008002, 0x8000000000000080, 0x000000000000800A, 0x800000008000000A,
    0x8000000080008081, 0x8000000000008080, 0x0000000080000001, 0x8000000080008008,
]
_ROT = [
    0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39,
    41, 45, 15, 21, 8, 18, 2, 61, 56, 14,
]
_MASK = (1 << 64) - 1


def _rol(x, n):
    return ((x << n) | (x >> (64 - n))) & _MASK if n else x


def _keccak_f(st):
    for rc in _RC:
        c = [st[x] ^ st[x + 5] ^ st[x + 10] ^ st[x + 15] ^ st[x + 20] for x in range(5)]
        d = [c[(x + 4) % 5] ^ _rol(c[(x + 1) % 5], 1) for x in range(5)]
        for x in range(5):
            for y in range(5):
                st[x + 5 * y] ^= d[x]
        b = [0] * 25
        for x in range(5):
            for y in range(5):
                b[y + 5 * ((2 * x + 3 * y) % 5)] = _rol(st[x + 5 * y], _ROT[x + 5 * y])
        for x in range(5):
            for y in range(5):
                st[x + 5 * y] = b[x + 5 * y] ^ ((~b[(x + 1) % 5 + 5 * y]) & b[(x + 2) % 5 + 5 * y]) & _MASK
        st[0] ^= rc


def keccak256(data):
    rate = 136
    st = [0] * 25
    data = bytearray(data)
    off = 0
    while len(data) - off >= rate:
        for lane in range(rate // 8):
            st[lane] ^= int.from_bytes(data[off + lane * 8: off + lane * 8 + 8], "little")
        _keccak_f(st)
        off += rate
    fin = bytearray(rate)
    rem = len(data) - off
    fin[0:rem] = data[off:]
    fin[rem] ^= 0x01
    fin[rate - 1] ^= 0x80
    for lane in range(rate // 8):
        st[lane] ^= int.from_bytes(fin[lane * 8: lane * 8 + 8], "little")
    _keccak_f(st)
    return b"".join(st[i].to_bytes(8, "little") for i in range(4))


# --------------------------------------------------------------------------- #
# secp256k1 ECDSA (RFC 6979 deterministic nonce, low-s canonical)
# --------------------------------------------------------------------------- #
_P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
_N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
_GX = 0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798
_GY = 0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8


def _add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    if p[0] == q[0] and (p[1] + q[1]) % _P == 0:
        return None
    if p == q:
        l = (3 * p[0] * p[0]) * pow(2 * p[1], -1, _P) % _P
    else:
        l = (q[1] - p[1]) * pow((q[0] - p[0]) % _P, -1, _P) % _P
    x = (l * l - p[0] - q[0]) % _P
    return (x, (l * (p[0] - x) - p[1]) % _P)


def _mul(k, pt=(_GX, _GY)):
    r = None
    while k:
        if k & 1:
            r = _add(r, pt)
        pt = _add(pt, pt)
        k >>= 1
    return r


def _rfc6979_k(z, priv):
    x = priv.to_bytes(32, "big")
    h1 = z.to_bytes(32, "big")
    v = b"\x01" * 32
    k = b"\x00" * 32
    k = hmac.new(k, v + b"\x00" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b"\x01" + x + h1, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        cand = int.from_bytes(v, "big")
        if 1 <= cand < _N:
            return cand
        k = hmac.new(k, v + b"\x00", hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def sign(digest, priv):
    z = int.from_bytes(digest, "big")
    while True:
        k = _rfc6979_k(z, priv)
        pt = _mul(k)
        r = pt[0] % _N
        if r == 0:
            continue
        s = (pow(k, -1, _N) * (z + r * priv)) % _N
        if s == 0:
            continue
        yparity = pt[1] & 1
        if s > _N // 2:  # enforce low-s
            s = _N - s
            yparity ^= 1
        return r, s, yparity


def address_of(priv):
    q = _mul(priv)
    return keccak256(q[0].to_bytes(32, "big") + q[1].to_bytes(32, "big"))[12:]


# --------------------------------------------------------------------------- #
# Minimal RLP
# --------------------------------------------------------------------------- #
def _rlp_len(n, short, long):
    if n <= 55:
        return bytes([short + n])
    lb = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([long + len(lb)]) + lb


def rlp_bytes(b):
    if len(b) == 1 and b[0] < 0x80:
        return b
    return _rlp_len(len(b), 0x80, 0xB7) + b


def rlp_list(items):
    payload = b"".join(items)
    return _rlp_len(len(payload), 0xC0, 0xF7) + payload


def rlp_int(v):
    return b"" if v == 0 else v.to_bytes((v.bit_length() + 7) // 8, "big")


# --------------------------------------------------------------------------- #
# Vector checks
# --------------------------------------------------------------------------- #
def _h(s):
    return bytes.fromhex(s[2:] if s.startswith("0x") else s)


def _hx(b):
    return "0x" + b.hex()


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    vec_path = os.path.join(here, os.pardir, "conformance", "opk-erc681-v1.json")
    with open(vec_path) as f:
        vec = json.load(f)

    ok = True
    results = []

    def check(name, got, exp):
        nonlocal ok
        g = got.lower() if isinstance(got, str) else got
        e = exp.lower() if isinstance(exp, str) else exp
        passed = g == e
        ok = ok and passed
        results.append((passed, name, got, exp))

    # Keccak known-answer (empty input)
    check(
        'keccak256("") known-answer',
        _hx(keccak256(b"")),
        "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
    )

    # Invoice ID
    iv = vec["invoiceVector"]
    abi_enc = (
        _h("0x" + "00" * 12)
        + _h(iv["terminalIdentifier"])
        + iv["timestampSeconds"].to_bytes(32, "big")
        + _h(iv["nonce"])
    )
    check("abiEncoded (invoice)", _hx(abi_enc), iv["abiEncoded"])
    check("invoiceId = keccak256(abiEncoded)", _hx(keccak256(abi_enc)), iv["invoiceId"])

    # Selectors and event topic
    def sel(sig):
        return _hx(keccak256(sig.encode())[:4])

    ro = vec["readOnlyAbi"]
    sa = vec["settlementAbi"]
    check("sweepSessions selector", sel("sweepSessions(bytes32[],uint256[],address)"), sa["sweepSessionsSelector"])
    check("isOperator selector", sel("isOperator(address)"), sa["isOperatorSelector"])
    check("balanceOf selector", sel("balanceOf(address)"), ro["balanceOf(address)"])
    check("computeReceiver selector", sel("computeReceiver(address,bytes32)"), ro["computeReceiver(address,bytes32)"])
    check(
        "Swept topic0",
        _hx(keccak256(b"Swept(address,address,bytes32,address,uint256,uint256,uint256)")),
        ro["Swept(address,address,bytes32,address,uint256,uint256,uint256)"],
    )

    # CREATE2 receiver derivation
    cfg = vec["configuration"]
    rv = vec["receiverVector"]
    vault, impl, factory = _h(cfg["vault"]), _h(cfg["receiverImplementation"]), _h(cfg["factory"])
    invoice_id = _h(iv["invoiceId"])
    salt = keccak256(_h("0x" + "00" * 12) + vault + invoice_id)
    check("CREATE2 salt", _hx(salt), rv["salt"])
    init_code = (
        _h("0x604d80600b6000396000f3")
        + _h("0x363d3d373d3d3d363d73")
        + impl
        + _h("0x5af43d82803e903d91602b57fd5bf3")
        + _h("0x" + "00" * 12)
        + vault
    )
    check("initCode", _hx(init_code), rv["initCode"])
    check("initCode length", len(init_code), rv["initCodeBytes"])
    init_hash = keccak256(init_code)
    check("initCodeHash", _hx(init_hash), rv["initCodeHash"])
    receiver = keccak256(b"\xff" + factory + salt + init_hash)[12:]
    check("receiver", _hx(receiver), rv["receiver"])

    # sweepSessions calldata
    def enc_sweep(token, ids, amts):
        selector = keccak256(b"sweepSessions(bytes32[],uint256[],address)")[:4]
        ids_enc = len(ids).to_bytes(32, "big") + b"".join(ids)
        amts_enc = len(amts).to_bytes(32, "big") + b"".join(a.to_bytes(32, "big") for a in amts)
        off_ids = 0x60
        off_amts = 0x60 + len(ids_enc)
        body = (
            off_ids.to_bytes(32, "big")
            + off_amts.to_bytes(32, "big")
            + (b"\x00" * 12 + token)
            + ids_enc
            + amts_enc
        )
        return selector + body

    token = _h(cfg["token"]["address"])
    amount = int(vec["amountVector"]["rawUnits"])
    check("sweepSessions calldata (1 item)", _hx(enc_sweep(token, [invoice_id], [amount])), sa["sweepSessionsCalldata"])
    check(
        "sweepSessions calldata (2 item)",
        _hx(enc_sweep(token, [invoice_id, _h("0x" + "aa" * 32)], [amount, 1])),
        sa["sweepSessionsTwoItemCalldata"],
    )

    # Full signed EIP-1559 transaction from the public test key
    s = vec["settlementSigningVector"]
    priv = int(s["privateKey"], 16)
    check("operator address from privkey", _hx(address_of(priv)), s["operator"])
    fields = [
        rlp_bytes(rlp_int(s["chainId"])),
        rlp_bytes(rlp_int(s["nonce"])),
        rlp_bytes(rlp_int(int(s["maxPriorityFeePerGas"]))),
        rlp_bytes(rlp_int(int(s["maxFeePerGas"]))),
        rlp_bytes(rlp_int(int(s["gasLimit"]))),
        rlp_bytes(_h(s["to"])),
        rlp_bytes(rlp_int(int(s["value"]))),
        rlp_bytes(_h(s["calldata"])),
        rlp_list([]),
    ]
    digest = keccak256(b"\x02" + rlp_list(fields))
    r, sig_s, yparity = sign(digest, priv)
    signed = b"\x02" + rlp_list(fields + [rlp_bytes(rlp_int(yparity)), rlp_bytes(rlp_int(r)), rlp_bytes(rlp_int(sig_s))])
    check("raw signed transaction", _hx(signed), s["rawTransaction"])
    check("transaction hash", _hx(keccak256(signed)), s["transactionHash"])
    check("low-s enforced", sig_s <= _N // 2, True)

    # ERC-681 canonical string
    check(
        "ERC-681 canonical URI",
        f"ethereum:{cfg['token']['address']}@{cfg['chainId']}/transfer?address={rv['receiver']}&uint256={vec['amountVector']['rawUnits']}",
        vec["erc681"],
    )

    for passed, name, got, exp in results:
        print(f"[{'PASS' if passed else 'FAIL'}] {name}")
        if not passed:
            print(f"        got {got}")
            print(f"        exp {exp}")
    print()
    print("ALL VECTORS PASS" if ok else "SOME VECTORS FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
