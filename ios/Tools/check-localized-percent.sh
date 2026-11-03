#!/bin/bash
# Check, on a Mac, what Foundation returns for the catalog's percent signs.
#
# `check-xcstrings.py` proves the catalog and the call sites agree as text; it cannot know which
# key Apple's Foundation derives from a literal containing `%`, or how the compiled catalog
# answers it. Only the real Foundation can, so this compiles the catalog the way the Xcode build
# does and asks `String(localized:)` for the strings the app shows. It touches no app code.
set -euo pipefail
cd "$(dirname "$0")/../.."

# swift-corelibs-foundation has no `String(localized:)`; an unsupported host must fail, not pass.
if [ "$(uname -s)" != "Darwin" ]; then
  echo "check-localized-percent.sh needs macOS: it uses Apple's Foundation and xcstringstool" >&2
  exit 1
fi

CATALOG=ios/UsageLimits/Sources/Resources/Localizable.xcstrings
COMPILED=$(mktemp -d)
trap 'rm -rf "$COMPILED"' EXIT

# The invocation the app build makes for each target's catalog.
xcrun xcstringstool compile --output-directory "$COMPILED" "$CATALOG"
echo "Compiled catalog:"
(cd "$COMPILED" && find . -type f | sort)

swift ios/Tools/check-localized-percent.swift "$COMPILED"
