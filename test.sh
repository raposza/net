#!/usr/bin/env bash
# test.sh -- build, publish once, start the api and prove every endpoint answers.
# Runs unprivileged out of a temporary directory that is removed on exit.
# Copyright 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
set -euo pipefail
cd "$(dirname "$0")"

jar=target/raposza-network-feed.jar
mvn -B clean package

work=$(mktemp -d)
pidApi=""
trap 'if [ -n "$pidApi" ]; then kill "$pidApi" 2>/dev/null || true; fi; rm -rf "$work"' EXIT

port=$(( 31000 + (RANDOM % 900) ))
fail=0

FEED_DATASET_DIR="$work/dataset" FEED_DB_DIR="$work/db" java -jar "$jar" replay

FEED_HTTP_PORT="$port" \
FEED_DATASET_DIR="$work/dataset" \
FEED_BUILD_ID="test" \
FEED_STATIC_DIR="$PWD/web" \
  java -jar "$jar" api > "$work/api.log" 2>&1 &
pidApi=$!

up=0
for cntTry in $(seq 1 60); do
  curl -sf "http://127.0.0.1:$port/api/v1/status" -o "$work/status.json" && { up=1; break; }
  sleep 1
done
if [ "$up" -ne 1 ]; then
  echo "FAIL api did not answer on port $port"
  cat "$work/api.log"
  exit 1
fi

chk() {
  if curl -sf -D "$work/head" "http://127.0.0.1:$port$2" -o "$work/body" && grep -q "$3" "$work/body"; then
    echo "ok   $1"
  else
    echo "FAIL $1  ($2 did not contain $3)"
    fail=1
  fi
}

chk status   /api/v1/status                       '"publicationId"'
chk networks /api/v1/networks                     '"MAINNET"'
chk mainnet  /api/v1/networks/mainnet             '"currentVersion"'
chk events   /api/v1/events                       'NETWORK_UPGRADE_PLANNED'
chk event    /api/v1/events/evt_mainnet_2026w39   '"WEEKLY_UPGRADE"'
chk sources  /api/v1/sources                      'canton-foundation-cips'
chk webpage  /index.html                          'Raposza'

# The published source list is the registry this build carries. A source id that
# is not in sources.json must not appear, however plausible it looks.
if curl -sf "http://127.0.0.1:$port/api/v1/sources" -o "$work/sources.json" \
    && ! grep -q 'splice-github-releases' "$work/sources.json"; then
  echo "ok   sources carry no id the registry does not define"
else
  echo "FAIL /api/v1/sources names a source that does not exist"
  fail=1
fi

curl -sf -D "$work/apihead" "http://127.0.0.1:$port/api/v1/status" -o /dev/null || true
if grep -qi '^x-raposza-publication:' "$work/apihead"; then
  echo "ok   publication header"
else
  echo "FAIL publication header absent"
  fail=1
fi

code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/v1/networks/nosuch")
if [ "$code" = "404" ]; then
  echo "ok   unknown network is 404"
else
  echo "FAIL unknown network returned $code, expected 404"
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "test.sh: GREEN"
else
  echo "test.sh: RED"
  cat "$work/api.log"
  exit 1
fi
