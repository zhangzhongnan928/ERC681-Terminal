#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
OPK_TOOLS_ROOT=${OPK_LOCAL_TOOLS_ROOT:-"$REPO_ROOT/.tools"}

echo "[1/3] Checking QR-only, keyless source boundary"
"$SCRIPT_DIR/check-mobile-boundary.sh"

if [[ -z ${JAVA_HOME:-} && -x "$OPK_TOOLS_ROOT/jdk17/Contents/Home/bin/java" ]]; then
  export JAVA_HOME="$OPK_TOOLS_ROOT/jdk17/Contents/Home"
fi

if [[ -z ${ANDROID_SDK_ROOT:-} && -z ${ANDROID_HOME:-} && -d "$OPK_TOOLS_ROOT/android-sdk" ]]; then
  export ANDROID_SDK_ROOT="$OPK_TOOLS_ROOT/android-sdk"
  export ANDROID_HOME="$OPK_TOOLS_ROOT/android-sdk"
elif [[ -z ${ANDROID_SDK_ROOT:-} && -n ${ANDROID_HOME:-} ]]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
elif [[ -z ${ANDROID_HOME:-} && -n ${ANDROID_SDK_ROOT:-} ]]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

if [[ -z ${JAVA_HOME:-} || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 17 not found. Set JAVA_HOME or OPK_LOCAL_TOOLS_ROOT." >&2
  exit 1
fi
if [[ -z ${ANDROID_SDK_ROOT:-} || ! -d "$ANDROID_SDK_ROOT" ]]; then
  echo "Android SDK not found. Set ANDROID_SDK_ROOT, ANDROID_HOME, or OPK_LOCAL_TOOLS_ROOT." >&2
  exit 1
fi

export GRADLE_USER_HOME=${OPK_GRADLE_USER_HOME:-${GRADLE_USER_HOME:-"$REPO_ROOT/android/.gradle-user"}}

echo "[2/3] Testing SDK, publishing Maven artifacts, linting and assembling Android app"
(
  cd "$REPO_ROOT/android"
  ./gradlew --no-daemon \
    :erc681-sdk:test \
    :erc681-sdk:publishAllPublicationsToProjectLocalRepository \
    :app:lintDebug \
    :app:assembleDebug \
    :app:assembleRelease
)

OPK_SWIFT_COMMAND=${OPK_SWIFT_BIN:-}
if [[ -z "$OPK_SWIFT_COMMAND" ]]; then
  OPK_SWIFT_COMMAND=$(command -v swift || true)
fi
if [[ -z "$OPK_SWIFT_COMMAND" || ! -x "$OPK_SWIFT_COMMAND" ]]; then
  echo "Swift toolchain not found. Set OPK_SWIFT_BIN to the swift executable." >&2
  exit 1
fi

echo "[3/3] Building Swift SDK and running shared conformance checks"
(
  cd "$REPO_ROOT/ios"
  "$OPK_SWIFT_COMMAND" build
  "$OPK_SWIFT_COMMAND" run OPKTerminalConformance
)

OPK_XCODEGEN_COMMAND=${OPK_XCODEGEN_BIN:-}
if [[ -z "$OPK_XCODEGEN_COMMAND" ]]; then
  OPK_XCODEGEN_COMMAND=$(command -v xcodegen || true)
fi
if [[ -z "$OPK_XCODEGEN_COMMAND" ]]; then
  for candidate in "$OPK_TOOLS_ROOT"/xcodegen-*/xcodegen/bin/xcodegen; do
    if [[ -x "$candidate" ]]; then
      OPK_XCODEGEN_COMMAND=$candidate
      break
    fi
  done
fi
if [[ -n "$OPK_XCODEGEN_COMMAND" && -x "$OPK_XCODEGEN_COMMAND" ]]; then
  echo "Regenerating the included Xcode project from project.yml"
  (
    cd "$REPO_ROOT/ios"
    "$OPK_XCODEGEN_COMMAND" generate --spec project.yml
  )
elif [[ ! -f "$REPO_ROOT/ios/OPKTerminal.xcodeproj/project.pbxproj" ]]; then
  echo "Generated Xcode project is missing and XcodeGen was not found." >&2
  echo "Install xcodegen or set OPK_XCODEGEN_BIN." >&2
  exit 1
else
  echo "XcodeGen not found; retained the included generated Xcode project."
fi

echo "Mobile verification passed."
