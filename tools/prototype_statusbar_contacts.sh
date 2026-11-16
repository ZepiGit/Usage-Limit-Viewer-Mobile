#!/usr/bin/env bash
# Throwaway contact-sheet generator. Run from this prototype worktree.
set -euo pipefail
cd "$(dirname "$0")/.."
flock /tmp/ulv-gradle.lock env JAVA_HOME="$HOME/.jdks/jdk-21.0.13+9" LC_ALL=C.UTF-8 LANG=C.UTF-8 \
  ./gradlew --offline :app:testDebugUnitTest --tests 'com.usagelimits.prototype.PrototypeStatusbarRenderingTest' --rerun-tasks
mkdir -p app/src/debug/assets/prototype
cp app/build/reports/statusbar-prototype/masks/{A,C}-*.png app/src/debug/assets/prototype/
printf '\nContact sheets: %s/app/build/reports/statusbar-prototype/\n' "$PWD"
