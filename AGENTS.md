# AGENTS.md

Operational notes for an AI assistant working in this repository.

## What this is

Raposza Network Operations Feed: a service that observes public Canton
Network operational sources, normalizes them into versioned events with
provenance, and publishes them as a REST API, a static dataset and change
notifications. See `docs/architecture.md` once it exists; it is the only
document a comment may cite.

## Rules

- A comment, a javadoc or a document in this repository must not point at
  anything outside it. Keep the reason, drop the pointer.
- Source header: `Copyright 2026 bentzn`, `SPDX-License-Identifier: Apache-2.0`,
  author line. No generation timestamps. Every source file carries it; the
  build refuses a file that does not.
- Java 21. No `var` unless essential. Lists `lst`, sets `set`, collections
  `coll`. No `mvn -q`.
- No LLM output in the ingestion path. Parsers are deterministic.
- The one thing that must never silently break: the fixture corpus under
  `fixtures/` is real banked source snapshots, and `./test.sh` diffs every
  normalizer's output over every fixture against the committed expectation.
  An unexplained difference is a failure, not a warning.

## Build and test

```
./build.sh    mvn -B clean package; produces target/raposza-network-feed.jar
./test.sh     builds, publishes once, starts the api, checks every endpoint
./run.sh      runs publisher and api locally on port 30000, web page included
```

`test.sh` runs unprivileged out of a temporary directory it removes on exit. It
installs nothing and writes nothing outside the project.

## Roles and configuration

One jar, three roles: `java -jar target/raposza-network-feed.jar <api|worker|replay>`.
`api` serves the read endpoints, `worker` publishes on an interval, `replay`
publishes once and exits.

Configuration is environment only; nothing is compiled in and nothing is read
from a configuration file.

```
FEED_HTTP_PORT                  api port, default 30000
FEED_HTTP_HOST                  bind address, default 0.0.0.0; set to
                                127.0.0.1 behind a reverse proxy
FEED_BUILD_ID                   reported by /api/v1/status, default unknown
FEED_DATASET_DIR                published dataset, default ./dataset
FEED_STATIC_DIR                 when set, the api also serves the web assets
FEED_PUBLISH_INTERVAL_SECONDS   worker interval, default 60
```

## What is real here and what is not

The dataset content is placeholder content written by hand. No collector exists,
no source has been read, and `metadata.content` is `PLACEHOLDER` so a consumer
can tell. The shape of the contract is real; the values are not.
