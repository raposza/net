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

# The index is brought into existence first, so a publication that finds it
# empty is distinguishable from one that could not read it at all.
FEED_DB_DIR="$work/db" FEED_EVIDENCE_DIR="$work/evidence" FEED_JOURNAL_DIR="$work/journal" java -jar "$jar" rebuild
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
chk content  /api/v1/status                       'EMPTY'
chk networks /api/v1/networks                     '"MAINNET"'
chk latest   /api/v1/networks                     '"spliceLatest"'
chk mainnet  /api/v1/networks/mainnet             'scheduledVersion'
chk events   /api/v1/events                       '"events"'
chk sources  /api/v1/sources                      'canton-foundation-cips'
chk planned  /api/v1/planned                      '"planned"'
chk changes  /api/v1/changes                      '"changes"'
chk prices   /api/v1/prices                       '"prices"'
chk pricesrc /api/v1/prices                       '"dso-mainnet"'
chk webpage  /index.html                          'Raposza'
chk svs      /index.html                          'Super Validators'
chk upcoming /index.html                          'Planned events'
chk past     /index.html                          'Past events'
chk openapi  /openapi.json                        '"openapi": "3.1.0"'
chk apidocs  /api.html                            'swagger-ui'
chk swagger  /vendor/swagger-ui/swagger-ui-bundle.js 'SwaggerUIBundle'
chk tokens   /vendor/raposza-design/tokens.css    'rz-accent:'
chk fonts    /vendor/raposza-design/fonts.css     'InterVariable.woff2'
chk design   /index.html                          'vendor/raposza-design/tokens.css'
chk pricepg  /prices.html                         'Canton Coin in US dollars'
chk pricejs  /prices.js                           '/prices'
chk pricedsg /prices.html                         'vendor/raposza-design/tokens.css'

# Nothing observes what a network runs, and nothing reports a synchronizer, so
# neither may reappear in a published network however plausible the field looks.
if curl -sf "http://127.0.0.1:$port/api/v1/networks" -o "$work/networks.json" \
    && ! grep -qE '"(synchronizer|currentVersion)"' "$work/networks.json"; then
  echo "ok   networks claim nothing that is not observed"
else
  echo "FAIL /api/v1/networks carries a running version or a synchronizer"
  fail=1
fi

# The published source list is the registry this build carries. A source id that
# is not in sources.json must not appear, however plausible it looks.
if curl -sf "http://127.0.0.1:$port/api/v1/sources" -o "$work/sources.json" \
    && ! grep -q 'splice-github-releases' "$work/sources.json"; then
  echo "ok   sources carry no id the registry does not define"
else
  echo "FAIL /api/v1/sources names a source that does not exist"
  fail=1
fi

# The page's script and style carry the release version in their urls, so a
# browser holding the previous release's copy fetches the new one instead of
# running an old script against a new page.
ver=$(sed -n 's:^  <version>\(.*\)</version>$:\1:p' pom.xml | head -1)
if [ -n "$ver" ] && grep -q "app.js?v=$ver\"" web/index.html && grep -q "style.css?v=$ver\"" web/index.html; then
  echo "ok   page assets carry version $ver"
else
  echo "FAIL web/index.html does not load app.js and style.css with ?v=$ver"
  fail=1
fi
if [ -n "$ver" ] && grep -q "prices.js?v=$ver\"" web/prices.html && grep -q "style.css?v=$ver\"" web/prices.html; then
  echo "ok   prices page assets carry version $ver"
else
  echo "FAIL web/prices.html does not load prices.js and style.css with ?v=$ver"
  fail=1
fi

# Every page carries the visitor counting tag. The deployment serves what it
# loads, from the page's own origin; the service serves neither path.
lstNoTag=""
for page in web/index.html web/prices.html web/api.html; do
  grep -qF '<script data-goatcounter="/_gc/count" async src="/_gc/count.js"></script>' "$page" || lstNoTag="$lstNoTag $page"
done
if [ -z "$lstNoTag" ]; then
  echo "ok   every page carries the counting tag"
else
  echo "FAIL no counting tag in:$lstNoTag"
  fail=1
fi

# The status names the release, read from the artifact: a jar built without
# filtering would say unknown or carry the placeholder itself.
if curl -sf "http://127.0.0.1:$port/api/v1/status" -o "$work/version.json" \
    && grep -Eq "\"version\" *: *\"$ver\"" "$work/version.json"; then
  echo "ok   status names version $ver"
else
  echo "FAIL /api/v1/status does not carry version $ver"
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
