#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
javac --release 21 -d build/classes src/main/java/portfolio/gateway/*.java
exec java -cp build/classes portfolio.gateway.Main
