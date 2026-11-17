#!/usr/bin/env bash
# Throwaway contact-sheet generator. Run from this prototype worktree.
set -euo pipefail
cd "$(dirname "$0")/.."
test_filter='com.usagelimits.prototype.PrototypeStatusbarRenderingTest'
if [[ ${1:-} == G ]]; then
  test_filter+=.generateGlyphLeftContactSheets
fi
flock /tmp/ulv-gradle.lock env JAVA_HOME="$HOME/.jdks/jdk-21.0.13+9" LC_ALL=C.UTF-8 LANG=C.UTF-8 \
  ./gradlew --offline :app:testDebugUnitTest --tests "$test_filter" --rerun-tasks
mkdir -p app/src/debug/assets/prototype
if [[ ${1:-} == G ]]; then
  cp app/build/reports/statusbar-prototype/masks/G*.png app/src/debug/assets/prototype/
else
  cp app/build/reports/statusbar-prototype/masks/{A,B,C,E,F}-*.png app/build/reports/statusbar-prototype/masks/G*.png app/src/debug/assets/prototype/
fi
printf '\nContact sheets: %s/app/build/reports/statusbar-prototype/\n' "$PWD"
