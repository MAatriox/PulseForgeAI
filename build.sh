#!/usr/bin/env bash
# Builds PulseForge AI with plain javac (JDK 17+). No external dependencies.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes build/test-classes
find src/main/java -name "*.java" > build/sources.txt
javac -encoding UTF-8 -d build/classes @build/sources.txt
jar --create --file build/pulseforge.jar --main-class com.pulseforge.app.Main -C build/classes .
if [ "${1:-}" = "test" ]; then
  find src/test/java -name "*.java" > build/test-sources.txt
  javac -encoding UTF-8 -cp build/classes -d build/test-classes @build/test-sources.txt
  java -cp "build/classes$( [ "${OS:-}" = "Windows_NT" ] && echo ';' || echo ':' )build/test-classes" com.pulseforge.TestRunner
fi
echo "Built build/pulseforge.jar"
