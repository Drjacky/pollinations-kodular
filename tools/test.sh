#!/bin/sh
# Unit tests for the plain-Java Pollinations client, against a local mock server.
# No network and no Pollen are used. Needs JDK 8+.
set -e
cd "$(dirname "$0")/.."
OUT=build/test-classes
rm -rf "$OUT" && mkdir -p "$OUT"
CP=test/lib/json-20240303.jar
javac -nowarn -source 8 -target 8 -d "$OUT" -cp "$CP" \
  src/io/github/drjacky/pollinations/client/PollinationsClient.java \
  test/java/PollinationsClientTest.java 2>&1 | grep -v "bootstrap class path\|^1 warning\|^warning: \[options\]" || true
java -cp "$OUT:$CP" PollinationsClientTest
