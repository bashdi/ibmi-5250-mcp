#!/bin/sh
# Builds bin/ibmi-5250-mcp.jar (MCP server + ACS agent in one jar). Needs a JDK (javac, jar) on PATH.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes bin
javac --release 11 -encoding UTF-8 -d build/classes src/iaccess/*.java
printf 'Main-Class: iaccess.McpServer\nAgent-Class: iaccess.Boot\nCan-Redefine-Classes: false\nCan-Retransform-Classes: false\n' > build/manifest.txt
jar cfm bin/ibmi-5250-mcp.jar build/manifest.txt -C build/classes .
echo "built $(pwd)/bin/ibmi-5250-mcp.jar"
