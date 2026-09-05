#!/usr/bin/env bash
# run.sh -- run the feed locally: one publication, a publisher, and the api
# serving both the endpoints and the web page on one port.
# Copyright 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
set -euo pipefail
cd "$(dirname "$0")"

jar=target/raposza-network-feed.jar
[ -f "$jar" ] || ./build.sh

export FEED_DATASET_DIR="${FEED_DATASET_DIR:-$PWD/local/dataset}"
export FEED_STATIC_DIR="${FEED_STATIC_DIR:-$PWD/web}"
export FEED_HTTP_PORT="${FEED_HTTP_PORT:-30000}"
export FEED_PUBLISH_INTERVAL_SECONDS="${FEED_PUBLISH_INTERVAL_SECONDS:-60}"

java -jar "$jar" replay
java -jar "$jar" worker &
pidWorker=$!
trap 'kill "$pidWorker" 2>/dev/null || true' EXIT

echo "open http://127.0.0.1:$FEED_HTTP_PORT/"
java -jar "$jar" api
