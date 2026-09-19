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
  `fixtures/<source id>/` is real banked source snapshots, each named by its
  sha256 with the claims it must produce beside it as `<sha256>.claims.jsonl`,
  and `./test.sh` diffs every normalizer's output over every fixture against
  that committed expectation. An unexplained difference is a failure, not a
  warning. Expectations are rewritten only for a deliberate new normalizer
  version, with `FEED_FIXTURES_BLESS=1 mvn -B test`, and the difference is read
  before it is committed.

## Build and test

```
./build.sh    mvn -B clean package; produces target/raposza-network-feed.jar
./test.sh     builds, publishes once, starts the api, checks every endpoint
./run.sh      runs publisher and api locally on port 30000, web page included
```

`test.sh` runs unprivileged out of a temporary directory it removes on exit. It
installs nothing and writes nothing outside the project.

## Roles and configuration

One jar, five roles:
`java -jar target/raposza-network-feed.jar <api|worker|collect|replay|rebuild>`.
`api` serves the read endpoints, `worker` polls the sources whose interval has
elapsed, reads what it banked into claims and then publishes, `collect` polls
every enabled source once and exits, `replay` publishes once and exits,
`rebuild` throws the index away, replays it from the evidence store and the
journal, and reads the bodies into claims again.

Configuration is environment only; nothing is compiled in and nothing is read
from a configuration file.

```
FEED_HTTP_PORT                  api port, default 30000
FEED_HTTP_HOST                  bind address, default 0.0.0.0; set to
                                127.0.0.1 behind a reverse proxy
FEED_ENVIRONMENT                environment name reported by /api/v1/status,
                                default local; anything but prd makes the web
                                page show a banner
FEED_BUILD_ID                   reported by /api/v1/status, default unknown
FEED_DATASET_DIR                published dataset, default ./dataset
FEED_STATIC_DIR                 when set, the api also serves the web assets
FEED_PUBLISH_INTERVAL_SECONDS   worker interval, default 60
FEED_COLLECT                    whether the worker acquires, default false;
                                true only where the record is kept
FEED_EVIDENCE_DIR               evidence store root, default ./evidence
FEED_JOURNAL_DIR                poll journal root, default ./journal
FEED_GIT_DIR                    git mirror cache root, default ./git
FEED_DB_DIR                     embedded index root, default ./db
FEED_DB_URL                     jdbc url; defaults to an H2 file under
                                FEED_DB_DIR, so nothing needs setting
FEED_DB_USER                    index user, default feed
FEED_DB_PASSWORD                index password, empty for an embedded file
FEED_PUBLISH_GIT                whether the worker mirrors each publication
                                into its data repository, default false
FEED_GIT_REMOTE                 that repository, one per environment; no
                                default, and the channel is off without it
FEED_GIT_BRANCH_DATA            branch carrying the data, default main
FEED_GIT_BRANCH_HEARTBEAT       branch carrying the heartbeat, default heartbeat
FEED_GIT_PUBLISH_DIR            working clones, default ./gitpub
FEED_GIT_KEY                    private key this environment pushes with
FEED_GIT_KNOWN_HOSTS            pinned host key of the remote
FEED_GIT_PUSH_INTERVAL          shortest interval in seconds between pushes of
                                the data branch, default 300
FEED_HEARTBEAT_SECONDS          heartbeat interval in seconds, default -1 which
                                disables it; below the floor of 60 s it is also
                                disabled and says so
FEED_GITHUB_TOKEN               credential the release call authenticates with,
                                needing contents write on the data repository;
                                no default, and without it no Release is cut
```

The git publication channel mirrors each publication into a repository of three
files. `versions.yml` is the current state, reduced to the values a consumer acts
on: per network the version scheduled to be running, the minimum in force and the
next upgrade ahead, plus the latest Splice release that exists. `history.yml` is
every state that file has held, newest first, each entry a full snapshot of it -
the accumulated record is the one thing here a reader cannot rebuild from
anywhere else, so it is published as a file rather than left to a commit log. The
README carries the consumer contract, because there is nowhere else for it to
live, and a Fields section stating what each field means, since a field whose
meaning is guessed breaks a consumer's logic without breaking its parser.

Nothing is written unless the publication was derived from banked observations,
so an environment that has observed nothing leaves the repository as it was
rather than committing a file of nulls. A commit is then made only when the
values MOVED: the comparison is `versions.yml` with its `timestamp` line dropped,
because that line moves on every publication by design. The committed file
carries it. Both files are built in memory before either is written, so a history
file that cannot be read leaves the branch untouched instead of leaving a current
state with no record behind it - and a history file that does not open with the
line this code wrote is REFUSED rather than replaced.

The data branch is ordinary git: every change is a commit on top of the last,
nothing is amended and nothing is force-pushed. Liveness is a branch of its own:
a repository that is correctly silent looks exactly like one whose writer has
stopped, so the heartbeat commits a timestamp on a fixed interval and never
touches the data branch. Failure to push is reported, retried on a later turn,
and never blocks a publication.

Releases are a separate act from a commit, and the only one a reader can
subscribe to: GitHub lets a watcher follow releases, and offers nothing at all
for commits or pushes. A push key cannot create one, which is why the release
call carries its own credential. Nothing cuts a Release yet - what makes a
publication worth announcing, and what the announcement says, both need a diff
over published events, and nothing derives one.

The api opens no database at all: it serves the published dataset directory and
nothing else, which is what lets it and the worker run as separate services over
one embedded file.

Only ONE environment acquires. The evidence and journal roots are per
environment, so several collectors would poll every source several times over for
copies of one answer and split the observed record between them. Everywhere else
the worker publishes and polls nothing; historical data is copied from the
environment that holds it when a test environment needs some. `FEED_COLLECT`
gates this and defaults to false, so an environment nobody configured is a
consumer. The `collect` role run by hand is not gated: that is a deliberate act.

The index admits ONE writer. `collect` and `rebuild` therefore fail with a lock
error while the worker is running, which is the correct outcome and not a bug:
stop the worker, run the role, start it again.

## What is primary and what is derived

Three things on disk, and only two of them matter.

```
evidence/   retrieved bodies, content-addressed.  PRIMARY, irreplaceable
journal/    one appended line per poll.           PRIMARY, irreplaceable
db/         an embedded index over both.          DERIVED, throw away freely
```

The evidence store holds bytes that cannot be fetched again once upstream
changes them. The journal holds when each poll ran and what it returned,
including the polls that returned nothing: a retrieval time is deliberately kept
OUT of the manifest, because putting it in would make identical bytes hash
differently every poll and destroy the deduplication, and an unchanged or failed
poll produces no bytes to store at all.

Together they reconstruct the index completely. The worker rebuilds on start
whenever it finds the index empty, so deleting the database file is a
recoverable state and not a loss, and there are no migrations: the index records
the digest of the schema it was built with, and a worker or `collect` run that
carries another schema drops the index and rebuilds it.

Back up `evidence/` and `journal/`. Backing up `db/` is optional and only saves
the replay.

## Sources and collection

`src/main/resources/sources.json` carries the source definitions. Authority is
policy: a source is promoted by a reviewed change to that file, never by
discovery. A source is pinned by its id, so a move of the upstream url is a
change recorded there and not a content change inferred from a redirect.

Two transports.

**git** collects commits. One commit on the first-parent line is one
observation, and the retrieved representation of that commit is a manifest: the
sorted list of tracked paths with the content address of each file. The manifest
is canonical, so an identical tree is identical bytes; each file body is stored
beside it under its own address, so a normalizer reads the file it wants without
a working copy. The collector shells out to `git`, which must be on the path, so
the transport behaviour is exactly the one an operator can reproduce by hand and
there is no second implementation of git to keep current.

**http** collects one body per poll. Unchanged is established three ways,
cheapest first: a conditional request answered 304, then a hash equal to the
last one banked, then nothing. No body is interpreted — a JSON document, an HTML
page and a calendar feed are bytes with a media type recorded beside them.

An http endpoint is only usable as evidence if its body is STABLE when nothing
has happened. Endpoints carrying counters — download counts, view counts — bank
a new observation on every poll that says nothing at all, and content addressing
cannot dedup a moving number. Choose the projection deliberately; that is why
Splice is tracked by tags rather than by its releases endpoint.

## What is real here and what is not

Collection is real: the git and http sources bank evidence, journal lines and
Three sources are normalized: every banked body of
the SV Operations Schedule is read into claims in the index, one set per typed
record, keyed by the record's own id; every banked body of the Splice tags
endpoint and of its release-notes feed into one set per tag, keyed by the tag
name, which is what joins the two. From those claims the index derives one
event per record, with a revision and a change record for every banked body
that moves one of its fields; a record that disappears is withdrawn, not
cancelled, and a body re-sorted upstream changes nothing. Those events are what
is published: the dataset, the api and the page carry them, and the per-network
summary is derived from them and from nothing else. Nothing is written by hand.
`metadata.content` states what a publication is made of - `OBSERVED` when the
index was read and carried events, `EMPTY` when it carried none, `UNAVAILABLE`
when it could not be read - so a consumer is never shown an empty table it
cannot account for.

Normalizers come after the corpus, not before it: each one is tested against
real banked snapshots, never against fabricated fragments, so the snapshots have
to exist first.
