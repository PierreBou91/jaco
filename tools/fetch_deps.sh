#!/usr/bin/env bash
# One-time setup: fetch the sherpa-onnx Android library (Apache 2.0, ONNX Runtime linked statically)
# into a local Maven repository, because it is not published on Maven Central.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V=2.0.1
SHA=07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575
D="$ROOT/third_party/maven/com/k2fsa/sherpa/sherpa-onnx/$V"
mkdir -p "$D"
if ! echo "$SHA  $D/sherpa-onnx-$V.aar" | sha256sum -c --status 2>/dev/null; then
  curl -fL -o "$D/sherpa-onnx-$V.aar" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-static-link-onnxruntime-$V.aar"
  echo "$SHA  $D/sherpa-onnx-$V.aar" | sha256sum -c
fi
cat > "$D/sherpa-onnx-$V.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.k2fsa.sherpa</groupId>
  <artifactId>sherpa-onnx</artifactId>
  <version>$V</version>
  <packaging>aar</packaging>
</project>
POM
echo "sherpa-onnx $V ready in $D"
