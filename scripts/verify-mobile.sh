#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
OPK_TOOLS_ROOT=${OPK_LOCAL_TOOLS_ROOT:-"$REPO_ROOT/.tools"}

echo "[1/4] Checking payment-QR, configuration-camera, read-only SDK, and constrained-operator boundaries"
"$SCRIPT_DIR/check-mobile-boundary.sh"

if [[ -z ${JAVA_HOME:-} && -x "$OPK_TOOLS_ROOT/jdk17/Contents/Home/bin/java" ]]; then
  export JAVA_HOME="$OPK_TOOLS_ROOT/jdk17/Contents/Home"
fi
if [[ -z ${JAVA_HOME:-} && -x /usr/libexec/java_home ]]; then
  DETECTED_JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
  if [[ -n "$DETECTED_JAVA_HOME" && -x "$DETECTED_JAVA_HOME/bin/java" ]]; then
    export JAVA_HOME="$DETECTED_JAVA_HOME"
  fi
fi

if [[ -z ${ANDROID_SDK_ROOT:-} && -z ${ANDROID_HOME:-} && -d "$OPK_TOOLS_ROOT/android-sdk" ]]; then
  export ANDROID_SDK_ROOT="$OPK_TOOLS_ROOT/android-sdk"
  export ANDROID_HOME="$OPK_TOOLS_ROOT/android-sdk"
elif [[ -z ${ANDROID_SDK_ROOT:-} && -z ${ANDROID_HOME:-} && -d "$HOME/Library/Android/sdk" ]]; then
  export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
  export ANDROID_HOME="$HOME/Library/Android/sdk"
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

echo "[2/4] Testing SDK and app, publishing Maven artifacts, linting and assembling Android"
(
  cd "$REPO_ROOT/android"
  ./gradlew --no-daemon \
    :erc681-sdk:test \
    :erc681-sdk:publishAllPublicationsToProjectLocalRepository \
    :app:testDebugUnitTest \
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

echo "[3/4] Testing the Swift SDK and running shared conformance checks"
(
  cd "$REPO_ROOT/ios"
  "$OPK_SWIFT_COMMAND" build
  "$OPK_SWIFT_COMMAND" test
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
if [[ -z "$OPK_XCODEGEN_COMMAND" || ! -x "$OPK_XCODEGEN_COMMAND" ]]; then
  echo "XcodeGen is required to prove that the included app project matches project.yml." >&2
  echo "Install xcodegen or set OPK_XCODEGEN_BIN." >&2
  exit 1
fi

OPK_XCODEBUILD_COMMAND=${OPK_XCODEBUILD_BIN:-}
if [[ -z "$OPK_XCODEBUILD_COMMAND" ]]; then
  OPK_XCODEBUILD_COMMAND=$(command -v xcodebuild || true)
fi
if [[ -z "$OPK_XCODEBUILD_COMMAND" || ! -x "$OPK_XCODEBUILD_COMMAND" ]]; then
  echo "xcodebuild and a full Xcode installation are required to compile the iOS app." >&2
  exit 1
fi

echo "[4/4] Regenerating and compiling the iOS app"
XCODE_PROJECT="$REPO_ROOT/ios/OPKTerminal.xcodeproj"
PBXPROJ="$XCODE_PROJECT/project.pbxproj"
INFO_PLIST="$REPO_ROOT/ios/App/Resources/Info.plist"
if [[ ! -d "$XCODE_PROJECT" || ! -f "$PBXPROJ" || ! -f "$INFO_PLIST" ]]; then
  echo "Included Xcode project or Info.plist is missing." >&2
  exit 1
fi

hash_generated_project() {
  (
    cd "$REPO_ROOT/ios"
    find OPKTerminal.xcodeproj App/Resources/Info.plist -type f -print |
      LC_ALL=C sort |
      while IFS= read -r generated_file; do
        shasum -a 256 "$generated_file"
      done |
      shasum -a 256
  )
}

BEFORE_PROJECT_HASH=$(hash_generated_project)
(
  cd "$REPO_ROOT/ios"
  "$OPK_XCODEGEN_COMMAND" generate --spec project.yml
)
AFTER_PROJECT_HASH=$(hash_generated_project)
if [[ "$BEFORE_PROJECT_HASH" != "$AFTER_PROJECT_HASH" ]]; then
  echo "XcodeGen updated the included project. Review the generated changes, then rerun verification." >&2
  exit 1
fi

"$OPK_XCODEBUILD_COMMAND" \
  -project "$REPO_ROOT/ios/OPKTerminal.xcodeproj" \
  -scheme OPKTerminalApp \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$REPO_ROOT/ios/build/verification-derived-data" \
  -skipPackagePluginValidation \
  CODE_SIGNING_ALLOWED=NO \
  build

echo "Mobile verification passed."
