#!/bin/sh
# Builds a release-signed APK and publishes it with SHA256SUMS.txt on GitHub.
#   scripts/release.sh 0.1.0
# Needs ~/.twinfile/keystore.properties (or TWINFILE_SIGNING) and an authenticated gh.
set -eu
v="$1"
cd "$(dirname "$0")/.."
grep -q "versionName = \"$v\"" app/build.gradle.kts || { echo "versionName in app/build.gradle.kts is not $v"; exit 1; }
[ -f "docs/releases/v$v.md" ] || { echo "missing docs/releases/v$v.md"; exit 1; }
TWINFILE_REQUIRE_SIGNING=1 ./gradlew --console=plain clean assembleRelease testDebugUnitTest
rm -rf out && mkdir out
cp app/build/outputs/apk/release/app-release.apk "out/TwinFile-$v.apk"
# "<hash>  <name>" with no '*' before the name.
(cd out && sha256sum "TwinFile-$v.apk" | sed 's/ \*/  /' > SHA256SUMS.txt && cat SHA256SUMS.txt)
git tag "v$v"
git push origin main "v$v"
gh release create "v$v" --title "v$v" --notes-file "docs/releases/v$v.md" out/*
