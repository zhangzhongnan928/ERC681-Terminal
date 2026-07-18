#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

SOURCE_ROOTS=()
for candidate in \
  "$REPO_ROOT/android/app/src/main" \
  "$REPO_ROOT/android/app/build.gradle.kts" \
  "$REPO_ROOT/android/erc681-sdk/src/main" \
  "$REPO_ROOT/android/erc681-sdk/build.gradle.kts" \
  "$REPO_ROOT/ios/Sources" \
  "$REPO_ROOT/ios/App" \
  "$REPO_ROOT/ios/Package.swift" \
  "$REPO_ROOT/ios/project.yml"; do
  if [[ -e "$candidate" ]]; then
    SOURCE_ROOTS+=("$candidate")
  fi
done

if [[ ${#SOURCE_ROOTS[@]} -eq 0 ]]; then
  echo "No mobile source roots found." >&2
  exit 1
fi

# Match APIs, permissions, and signing/write primitives rather than generic words such as
# "NFC" in a comment. This keeps the check focused on executable capability.
FORBIDDEN_PATTERN='android\.nfc|NfcAdapter|HostApduService|android\.permission\.NFC|hardware\.nfc|CoreNFC|NFCReaderUsageDescription|NFCTagReaderSession|ISO7816Tag|IsoDep|androidx\.camera|android\.hardware\.camera|KeyManager|org\.web3j\.crypto\.(Credentials|ECKeyPair|Keys|RawTransaction|TransactionEncoder)|\bRawTransaction\b|\bTransactionEncoder\b|KEY_ENCRYPTED_PRIVATE_KEY|Keys\.createEcKeyPair|Credentials\.create|privateKey[[:space:]]*[:=]|eth_send|ethSend|eth_sign|personal_sign|wallet_sendCalls|sendRawTransaction|sendTransaction|signTypedData|signMessage|TransactionSigner|secp256k1|android\.permission\.CAMERA|CaptureActivity|NSCameraUsageDescription'

if rg -n -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$FORBIDDEN_PATTERN" "${SOURCE_ROOTS[@]}"; then
  echo "Mobile boundary check failed: forbidden NFC, camera, key-custody, signing, or write-RPC code found." >&2
  exit 1
fi

# The conformance executable contains a native-value URI as a must-reject fixture. Scan only
# runtime SDK/app sources for generation, excluding that negative-test target.
NATIVE_SCAN_ROOTS=()
for candidate in \
  "$REPO_ROOT/android/app/src/main" \
  "$REPO_ROOT/android/erc681-sdk/src/main" \
  "$REPO_ROOT/ios/Sources/OPKTerminalCore" \
  "$REPO_ROOT/ios/Sources/OPKTerminalRPC" \
  "$REPO_ROOT/ios/App"; do
  if [[ -d "$candidate" ]]; then
    NATIVE_SCAN_ROOTS+=("$candidate")
  fi
done

if rg -n -F '?value=' "${NATIVE_SCAN_ROOTS[@]}"; then
  echo "Mobile boundary check failed: native-asset ERC-681 generation found." >&2
  exit 1
fi

if command -v jq >/dev/null 2>&1; then
  jq -e . "$REPO_ROOT/conformance/opk-erc681-v1.json" >/dev/null
else
  python3 -m json.tool "$REPO_ROOT/conformance/opk-erc681-v1.json" >/dev/null
fi

echo "Mobile boundary check passed: QR-only, keyless source boundary is intact."
