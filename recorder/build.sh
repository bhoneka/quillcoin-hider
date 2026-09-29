#!/bin/sh
# Builds the recorder. Needs Apple's command line tools (xcode-select --install) and macOS 15 or newer.
set -e
cd "$(dirname "$0")"
swiftc -O -swift-version 5 -target arm64-apple-macosx15.0 quillcoin-recorder.swift -o quillcoin-recorder
echo "built: $(pwd)/quillcoin-recorder"
