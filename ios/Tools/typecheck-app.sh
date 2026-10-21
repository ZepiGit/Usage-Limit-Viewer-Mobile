#!/bin/bash
# Type-check the iOS app and widget targets without an Apple SDK.
#
# Apple framework shims let Linux type-check app and widget sources, including Darwin-only
# keychain and loopback declarations. Main-actor annotations catch concurrency mistakes.
# The permissive shims do not reproduce SwiftUI overload resolution or runtime behavior;
# real-SDK builds on macOS remain authoritative.
set -euo pipefail
cd "$(dirname "$0")/../.."

SHIMS=ios/Tools/AppleShims
KIT_SOURCES=$(find ios/UsageLimitsKit/Sources -name '*.swift')
APP_SOURCES=$(find ios/UsageLimits/Sources -name '*.swift')
KIT_MODULE=$(mktemp -d)
trap 'rm -rf "$KIT_MODULE"' EXIT

swift build --package-path "$SHIMS" >/dev/null
SHIM_BUILD=$(swift build --package-path "$SHIMS" --show-bin-path)

ALIASES=(
  -module-alias SwiftUI=SwiftUIShim
  -module-alias WidgetKit=WidgetKitShim
  -module-alias UIKit=UIKitShim
  -module-alias AuthenticationServices=AuthenticationServicesShim
  -module-alias BackgroundTasks=BackgroundTasksShim
  -module-alias UserNotifications=UserNotificationsShim
  -module-alias Combine=CombineShim
)

# The kit, built as it would be on Apple hardware. `swift build` produces a Linux flavour in
# which `#if canImport(Security)` and `#if canImport(Network)` are false, so
# KeychainCredentialStore and LoopbackListener simply are not there — and the app files that
# use them could not be checked at all. Aliasing those two frameworks makes the real Darwin
# sources compile, which is also the only way this checks them.
echo "Building the kit with its Darwin sources…"
swiftc -emit-module -module-name UsageLimitsKit \
  -module-alias Security=SecurityShim -module-alias Network=NetworkShim \
  -I "$SHIM_BUILD/Modules" -I "$SHIM_BUILD" \
  -emit-module-path "$KIT_MODULE/UsageLimitsKit.swiftmodule" \
  $KIT_SOURCES

count=$(echo "$APP_SOURCES" | wc -l)
echo "Type-checking $count app sources against the framework shims…"
swiftc -typecheck "${ALIASES[@]}" \
  "$@" \
  -I "$SHIM_BUILD/Modules" -I "$SHIM_BUILD" -I "$KIT_MODULE" \
  $APP_SOURCES

echo "OK — every name resolves, every call site matches, and nothing violates main-actor isolation."
