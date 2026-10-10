#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
javac --release 21 -d build/test-classes src/main/java/portfolio/gateway/*.java src/test/java/portfolio/gateway/*.java
java -ea -cp build/test-classes portfolio.gateway.GatewayTest
python3 scripts/smoke.py
python3 scripts/upstream_smoke.py
