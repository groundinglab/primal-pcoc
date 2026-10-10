#!/bin/sh
# Host build of libpcoc_bridge.so for JVM unit tests.
set -eu
cd "$(dirname "$0")"
cargo test -p pcoc-bridge --offline || cargo test -p pcoc-bridge
cargo build -p pcoc-bridge
test -f target/debug/libpcoc_bridge.so
