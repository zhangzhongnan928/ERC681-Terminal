#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

SOURCE_ROOTS=()
for candidate in \
  "$REPO_ROOT/android/app/src/main" \
  "$REPO_ROOT/android/app/build.gradle.kts" \
  "$REPO_ROOT/android/gradle/libs.versions.toml" \
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

# Match NFC APIs and signing/write primitives rather than generic words in comments. Camera APIs
# are permitted in the native app layer solely for configuration-address import and are checked
# separately below so they cannot leak into either reusable SDK.
FORBIDDEN_PATTERN='android\.nfc|NfcAdapter|HostApduService|android\.permission\.NFC|hardware\.nfc|CoreNFC|NFCReaderUsageDescription|NFCTagReaderSession|ISO7816Tag|IsoDep|CaptureActivity|KeyManager|org\.web3j\.crypto\.(Credentials|ECKeyPair|Keys|RawTransaction|TransactionEncoder)|\bRawTransaction\b|\bTransactionEncoder\b|KEY_ENCRYPTED_PRIVATE_KEY|Keys\.createEcKeyPair|Credentials\.create|privateKey[[:space:]]*[:=]|eth_send|ethSend|eth_sign|personal_sign|wallet_sendCalls|sendRawTransaction|sendTransaction|signTypedData|signMessage|TransactionSigner|secp256k1'

if rg -n -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$FORBIDDEN_PATTERN" "${SOURCE_ROOTS[@]}"; then
  echo "Mobile boundary check failed: forbidden NFC, key-custody, signing, or write-RPC code found." >&2
  exit 1
fi

SDK_ROOTS=()
for candidate in \
  "$REPO_ROOT/android/erc681-sdk/src/main" \
  "$REPO_ROOT/android/erc681-sdk/build.gradle.kts" \
  "$REPO_ROOT/ios/Sources" \
  "$REPO_ROOT/ios/Package.swift"; do
  if [[ -e "$candidate" ]]; then
    SDK_ROOTS+=("$candidate")
  fi
done

CAMERA_PATTERN='androidx\.camera|com\.google\.mlkit|BarcodeScanning|BarcodeScannerOptions|Manifest\.permission\.CAMERA|android\.permission\.CAMERA|android\.hardware\.camera|AVFoundation|AVCapture|AVMetadata|VisionKit|DataScannerViewController|VNDetectBarcodesRequest|NSCameraUsageDescription'
if rg -n -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$CAMERA_PATTERN" "${SDK_ROOTS[@]}"; then
  echo "Mobile boundary check failed: camera capability found in a reusable SDK." >&2
  exit 1
fi

# Direct camera and barcode APIs are allowed only in the dedicated configuration scanner adapters,
# with dependency and permission declarations confined to their build/manifest sources.
APP_CAMERA_MATCHES=$(rg -l -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$CAMERA_PATTERN" "${SOURCE_ROOTS[@]}" || true)
if [[ -n "$APP_CAMERA_MATCHES" ]]; then
  while IFS= read -r source; do
    case "$source" in
      "$REPO_ROOT/android/app/build.gradle.kts" | \
      "$REPO_ROOT/android/gradle/libs.versions.toml" | \
      "$REPO_ROOT/android/app/src/main/AndroidManifest.xml" | \
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/ui/components/AddressScannerDialog.kt" | \
      "$REPO_ROOT/ios/project.yml" | \
      "$REPO_ROOT/ios/App/Resources/Info.plist" | \
      "$REPO_ROOT/ios/App/Sources/ConfigurationAddressScanner.swift")
        ;;
      *)
        echo "Mobile boundary check failed: camera or barcode API outside the configuration scanner allowlist: $source" >&2
        exit 1
        ;;
    esac
  done <<< "$APP_CAMERA_MATCHES"
fi

# The scanner entry points may be invoked only by Settings and their own adapter definitions.
SCANNER_USAGE_PATTERN='\bAddressScannerDialog\b|\bConfigurationAddressScanner\b'
SCANNER_USAGE_MATCHES=$(rg -l --glob '!**/build/**' --glob '!**/.build/**' \
  "$SCANNER_USAGE_PATTERN" "${SOURCE_ROOTS[@]}" || true)
if [[ -n "$SCANNER_USAGE_MATCHES" ]]; then
  while IFS= read -r source; do
    case "$source" in
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/ui/components/AddressScannerDialog.kt" | \
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/ui/screens/SettingsScreen.kt" | \
      "$REPO_ROOT/ios/App/Sources/ConfigurationAddressScanner.swift" | \
      "$REPO_ROOT/ios/App/Sources/SettingsView.swift")
        ;;
      *)
        echo "Mobile boundary check failed: configuration scanner referenced outside Settings: $source" >&2
        exit 1
        ;;
    esac
  done <<< "$SCANNER_USAGE_MATCHES"
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

echo "Mobile boundary check passed: payment-QR, configuration-camera, and keyless boundaries are intact."
