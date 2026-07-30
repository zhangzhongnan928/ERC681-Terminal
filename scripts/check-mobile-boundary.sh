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

# NFC remains outside this product. Camera APIs are permitted in the native app layer solely for
# configuration-address import and are checked separately below.
NFC_PATTERN='android\.nfc|NfcAdapter|HostApduService|android\.permission\.NFC|hardware\.nfc|CoreNFC|NFCReaderUsageDescription|NFCTagReaderSession|ISO7816Tag|IsoDep'
if rg -n -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$NFC_PATTERN" "${SOURCE_ROOTS[@]}"; then
  echo "Mobile boundary check failed: forbidden NFC capability found." >&2
  exit 1
fi

READ_ONLY_SDK_ROOTS=()
for candidate in \
  "$REPO_ROOT/android/erc681-sdk/src/main" \
  "$REPO_ROOT/android/erc681-sdk/build.gradle.kts" \
  "$REPO_ROOT/ios/Sources/OPKTerminalCore" \
  "$REPO_ROOT/ios/Sources/OPKTerminalRPC"; do
  if [[ -e "$candidate" ]]; then
    READ_ONLY_SDK_ROOTS+=("$candidate")
  fi
done

# Key custody and writes are isolated from the reusable payment/monitoring SDKs. Native apps use
# dedicated wallet/operator modules whose public surface is constrained to ClearingVault sweeps.
SIGNING_PATTERN='KeyManager|org\.web3j\.crypto\.(Credentials|ECKeyPair|Keys|RawTransaction|TransactionEncoder)|\bRawTransaction\b|\bTransactionEncoder\b|KEY_ENCRYPTED_PRIVATE_KEY|Keys\.createEcKeyPair|Credentials\.create|privateKey[[:space:]]*[:=]|eth_send|ethSend|eth_sign|personal_sign|wallet_sendCalls|sendRawTransaction|sendTransaction|signTypedData|signMessage|TransactionSigner|\bP256K\b'
if rg -n -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$SIGNING_PATTERN" "${READ_ONLY_SDK_ROOTS[@]}"; then
  echo "Mobile boundary check failed: key custody, signing, or write RPC leaked into a read-only SDK." >&2
  exit 1
fi

SIGNING_MATCHES=$(rg -l -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$SIGNING_PATTERN" "${SOURCE_ROOTS[@]}" || true)
if [[ -n "$SIGNING_MATCHES" ]]; then
  while IFS= read -r source; do
    case "$source" in
      "$REPO_ROOT/android/app/build.gradle.kts" | \
      "$REPO_ROOT/android/gradle/libs.versions.toml" | \
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/data/repository/SettlementRepository.kt" | \
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/settlement/"* | \
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/wallet/"* | \
      "$REPO_ROOT/ios/Package.swift" | \
      "$REPO_ROOT/ios/App/Sources/AppModel.swift" | \
      "$REPO_ROOT/ios/App/Sources/StoredSettlement.swift" | \
      "$REPO_ROOT/ios/Sources/OPKTerminalOperator/"*)
        ;;
      *)
        echo "Mobile boundary check failed: signing or write capability outside the isolated operator allowlist: $source" >&2
        exit 1
        ;;
    esac
  done <<< "$SIGNING_MATCHES"
fi

EXPECTED_OPERATOR_ROOTS=(
  "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/wallet" \
  "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/settlement" \
  "$REPO_ROOT/ios/Sources/OPKTerminalOperator"
)
for candidate in "${EXPECTED_OPERATOR_ROOTS[@]}"; do
  if [[ ! -d "$candidate" ]]; then
    echo "Mobile boundary check failed: required isolated operator root is missing: $candidate" >&2
    exit 1
  fi
done

# A wallet primitive must not become a general signer by accident. Raw signing is package-internal,
# and its only production caller is the typed settlement coordinator/repository.
RAW_SIGNING_ENTRY_PATTERN='signSettlementTransaction|func sign\(digest:'
RAW_SIGNING_ENTRY_MATCHES=$(rg -l --glob '!**/build/**' --glob '!**/.build/**' \
  "$RAW_SIGNING_ENTRY_PATTERN" "${SOURCE_ROOTS[@]}" || true)
if [[ -n "$RAW_SIGNING_ENTRY_MATCHES" ]]; then
  while IFS= read -r source; do
    case "$source" in
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/wallet/OperatorWalletStore.kt" | \
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/data/repository/SettlementRepository.kt" | \
      "$REPO_ROOT/ios/Sources/OPKTerminalOperator/OperatorWallet.swift")
        ;;
      *)
        echo "Mobile boundary check failed: raw signing entry point referenced outside the constrained implementation: $source" >&2
        exit 1
        ;;
    esac
  done <<< "$RAW_SIGNING_ENTRY_MATCHES"
fi

if rg -n 'public[[:space:]]+(func|fun)[[:space:]]+sign[^[:alnum:]_].*(digest|RawTransaction|rawTransaction)' \
  "${EXPECTED_OPERATOR_ROOTS[@]}"; then
  echo "Mobile boundary check failed: a raw signing primitive is public." >&2
  exit 1
fi

RAW_BROADCAST_PATTERN='eth_sendRawTransaction|sendRawTransaction'
RAW_BROADCAST_MATCHES=$(rg -l --glob '!**/build/**' --glob '!**/.build/**' \
  "$RAW_BROADCAST_PATTERN" "${SOURCE_ROOTS[@]}" || true)
if [[ -n "$RAW_BROADCAST_MATCHES" ]]; then
  while IFS= read -r source; do
    case "$source" in
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/settlement/SettlementRpc.kt" | \
      "$REPO_ROOT/android/app/src/main/java/com/openpasskey/terminal/data/repository/SettlementRepository.kt" | \
      "$REPO_ROOT/ios/Sources/OPKTerminalOperator/OperatorRPC.swift" | \
      "$REPO_ROOT/ios/Sources/OPKTerminalOperator/SettlementCoordinator.swift")
        ;;
      *)
        echo "Mobile boundary check failed: raw-transaction broadcast referenced outside the constrained operator path: $source" >&2
        exit 1
        ;;
    esac
  done <<< "$RAW_BROADCAST_MATCHES"
fi

# Unlocked-node signing and arbitrary wallet RPCs must never enter the constrained signer. The only
# broadcast path is a locally signed raw transaction assembled for sweepSessions with value zero.
UNSAFE_OPERATOR_PATTERN='eth_sendTransaction|eth_sign|personal_sign|wallet_sendCalls|wallet_sendTransaction|signTypedData|sendUserOperation|approve\(|payout(To)?\(|refund\(|rescue(Token|ETH)?\(|deploy(Receiver)?\('
if rg -n -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$UNSAFE_OPERATOR_PATTERN" "${SOURCE_ROOTS[@]}"; then
  echo "Mobile boundary check failed: arbitrary or privileged transaction capability found in the operator module." >&2
  exit 1
fi

CAMERA_PATTERN='androidx\.camera|com\.google\.mlkit|BarcodeScanning|BarcodeScannerOptions|Manifest\.permission\.CAMERA|android\.permission\.CAMERA|android\.hardware\.camera|AVFoundation|AVCapture|AVMetadata|VisionKit|DataScannerViewController|VNDetectBarcodesRequest|NSCameraUsageDescription'
if rg -n -i --glob '!**/build/**' --glob '!**/.build/**' \
  "$CAMERA_PATTERN" "${READ_ONLY_SDK_ROOTS[@]}"; then
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

# Protocol 1.6 keeps native settlement on the same sweepSessions entry point. Reject any future
# native-specific transaction surface; plain-value ERC-681 generation itself is now required.
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

NATIVE_ENTRYPOINT_PATTERN='sweepSessionsNative|sweepNativeSessions|nativeSweepSessions|settleNative|sweepNative'
if rg -n "$NATIVE_ENTRYPOINT_PATTERN" "${NATIVE_SCAN_ROOTS[@]}"; then
  echo "Mobile boundary check failed: native-specific protocol entry point found." >&2
  exit 1
fi

if ! rg -q -F '?value=' \
  "$REPO_ROOT/android/erc681-sdk/src/main" \
  "$REPO_ROOT/ios/Sources/OPKTerminalCore"; then
  echo "Mobile boundary check failed: Protocol 1.6 native ERC-681 support is missing." >&2
  exit 1
fi

# Enforce platform storage invariants alongside the source allowlists. These checks intentionally
# fail if a future refactor removes non-migrating key storage or re-enables Android backup.
ANDROID_MANIFEST="$REPO_ROOT/android/app/src/main/AndroidManifest.xml"
if ! rg -q 'android:allowBackup="false"' "$ANDROID_MANIFEST" || \
   ! rg -q 'android:dataExtractionRules="@xml/data_extraction_rules"' "$ANDROID_MANIFEST" || \
   ! rg -q 'android:fullBackupContent="@xml/backup_rules"' "$ANDROID_MANIFEST"; then
  echo "Mobile boundary check failed: Android operator data is not explicitly excluded from backup and transfer." >&2
  exit 1
fi
for backup_file in \
  "$REPO_ROOT/android/app/src/main/res/xml/backup_rules.xml" \
  "$REPO_ROOT/android/app/src/main/res/xml/data_extraction_rules.xml"; do
  if [[ ! -f "$backup_file" ]] || ! rg -q '<exclude domain="sharedpref" path="\."' "$backup_file"; then
    echo "Mobile boundary check failed: Android private preferences are not excluded in $backup_file" >&2
    exit 1
  fi
done

IOS_WALLET="$REPO_ROOT/ios/Sources/OPKTerminalOperator/OperatorWallet.swift"
if ! rg -q 'kSecAttrAccessibleWhenUnlockedThisDeviceOnly' "$IOS_WALLET" || \
   ! rg -q 'kSecAttrSynchronizable as String: false' "$IOS_WALLET" || \
   ! rg -q '\.userPresence' "$IOS_WALLET"; then
  echo "Mobile boundary check failed: iOS operator key lost a ThisDeviceOnly, non-sync, user-presence guard." >&2
  exit 1
fi

# Core and read-only RPC must not acquire a reverse dependency on the operator module.
if rg -n 'import[[:space:]]+OPKTerminalOperator' \
  "$REPO_ROOT/ios/Sources/OPKTerminalCore" "$REPO_ROOT/ios/Sources/OPKTerminalRPC"; then
  echo "Mobile boundary check failed: the read-only Swift targets depend on the operator module." >&2
  exit 1
fi

# Both platforms must execute the shared settlement ABI and deterministic type-2 signing vectors.
ANDROID_SETTLEMENT_TEST="$REPO_ROOT/android/app/src/test/java/com/openpasskey/terminal/settlement/SettlementConformanceTest.kt"
IOS_SETTLEMENT_TEST="$REPO_ROOT/ios/Tests/OPKTerminalOperatorTests/OperatorWalletAndSettlementTests.swift"
for test_file in "$ANDROID_SETTLEMENT_TEST" "$IOS_SETTLEMENT_TEST"; do
  if [[ ! -f "$test_file" ]] || \
     ! rg -q 'opk-erc681-v1\.json' "$test_file" || \
     ! rg -q 'settlementAbi' "$test_file" || \
     ! rg -q 'settlementSigningVector' "$test_file"; then
    echo "Mobile boundary check failed: shared settlement ABI/signing fixtures are not consumed by $test_file" >&2
    exit 1
  fi
done

if command -v jq >/dev/null 2>&1; then
  jq -e . "$REPO_ROOT/conformance/opk-erc681-v1.json" >/dev/null
else
  python3 -m json.tool "$REPO_ROOT/conformance/opk-erc681-v1.json" >/dev/null
fi

echo "Mobile boundary check passed: payment-QR, configuration-camera, read-only SDK, and constrained-operator boundaries are intact."
