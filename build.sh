#!/usr/bin/env bash
# build.sh -- build the one jar that carries all three roles.
# Copyright 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
set -euo pipefail
cd "$(dirname "$0")"

mvn -B clean package
echo "built $(pwd)/target/raposza-network-feed.jar"
