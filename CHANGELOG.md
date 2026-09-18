<!-- Author Claude/bentzn -->
# Changelog

Releases before 0.2.0 carried no version and no tag; their history is the commit
log.

## 0.3.0

- The published dataset is no longer written by hand. The dataset, the api and
  the page carry the events the index derives from banked bodies of the SV
  Operations Schedule, with provenance on each, and the per-network summary is
  derived from those events and from nothing else.
- `metadata.content` no longer reads `PLACEHOLDER`. It reads `OBSERVED` when the
  index was read and carried events, `EMPTY` when it was read and carried none,
  and `UNAVAILABLE` when it could not be read. An environment that has banked
  nothing and one whose index is broken both serve empty tables, and a consumer
  has to be able to tell them apart.
- BREAKING, the network record. `splice.currentVersion` and the whole
  `synchronizer` block are gone. No source this feed reads reports what a
  network is running, or anything at all about its synchronizer, so both were
  invented values under names that read as facts. What replaces them is named
  for what it is: `splice.scheduledVersion` with `scheduledPrecision`,
  `scheduledFrom` and `scheduledEventId`, the same four for `splice.minimum`,
  and a `next` block carrying the earliest upgrade still ahead. EVERY VERSION
  PUBLISHED IS SCHEDULED, NOT RUNNING, and a date that has arrived is not
  evidence that anything happened.
- The three networks are always published, with null values where the schedule
  says nothing about one, so the dataset directory no longer gains and loses
  files as the calendar moves.
- BREAKING, the event record. `type` became `kind`; `slot` is gone, identity for
  this source being the upstream record id; `subject` became `version`, carrying
  `value`, `precision` and `change`; `authority` and `confidence` are gone,
  being properties of a claim rather than of an event; and `lastObservedAt` is
  now `lastBankedAt`, under that name because that is what it is. Added:
  `withdrawn`, `title`, `description`, `upstream` and `effective.to`.
- Two Splice sources are read into records, and one release is published from
  both. `splice-tags` carries identity and the whole history: every tag becomes a
  `SOFTWARE_RELEASE` event keyed by the tag name, with `commit_sha`, the version
  where the name is one and `REQUIRES_REVIEW` where it is not. The Atom feed
  `splice-release-notes` carries what tags cannot - a time, published as
  `commit_time`, and the notes as the description. It holds a rolling window of
  the newest entries, so it completes nothing and an entry leaving it withdraws
  nothing.
- `commit_time` IS THE TAGGED COMMIT'S TIME, NOT WHEN THE RELEASE BECAME
  AVAILABLE. Measured on 0.8.2: the feed stamps 16:29:55Z and the tag appeared on
  the tags endpoint 46 minutes later, when the release build finished. When a
  consumer could first have obtained a release is `firstObservedAt`.
- Events derived from different sources describing one thing are joined at
  publication, on kind and upstream reference: one published event, one
  provenance record per source. The primary is the source declared first, its
  values stand, and the other fills in only what it left null.
- New event fields `commit_sha` and `commit_time`, which are new columns. An
  index built with the previous schema is dropped and rebuilt from the evidence
  store and the journal, as it is for any schema change.
- `versions.txt`, a third file in the data repository: four lines a person can
  read, carrying nothing `feed.json` does not.
- The heartbeat carries `uptimeHours`, the publisher's own uptime, so a branch
  that is quiet can be told from a writer that restarted.
- Each published source carries `pollSeconds`, which is what lets the data
  `README.md` state how often a source is polled instead of asserting it.
- The data `README.md` is rewritten: a first paragraph on wiring BaseNet to the
  feed with a cron job, and a provenance section naming every source, its
  cadence, how a poll is banked and how records are made. The `What the versions
  mean` section is gone; the scheduled-not-running statement it carried is part
  of provenance now, and the stability paragraph no longer claims that nothing
  at all is promised before 1.0.0.
- The web page carries upcoming events only, and says what a publication is made
  of. Everything else, including what has passed and what was withdrawn
  upstream, is in `/api/v1/events`.

## 0.2.1

- The git publication channel committed on every publication instead of only on
  a change. The publication id and the creation stamp are repeated on every
  network and every event, and the comparison blanked them in the metadata block
  alone, so every document differed from the last. Measured on a live
  environment as one commit a minute. Both values are now blanked wherever they
  appear, matched by value rather than by field name, so a timestamp that later
  carries a real moment counts as a change with no list of field names to keep
  current.

## 0.2.0

- The git publication channel, the third delivery channel beside the api and the
  web page. Each publication is mirrored into a data repository holding two
  files: the whole dataset as one json document, and a README carrying the
  consumer contract. A commit is made only when the dataset MOVED, which is
  decided over a form of the document with the publication id and the creation
  stamp blanked, because those move on every publication by design.
- A heartbeat on a branch of its own, off by default. A repository that is
  correctly silent looks exactly like one whose writer has stopped, so the
  heartbeat commits a timestamp on a fixed interval and never touches the data
  branch. An interval below 60 s disables it rather than being clamped.
- `SECURITY.md`, and this file.
- New configuration: `FEED_PUBLISH_GIT`, `FEED_GIT_REMOTE`,
  `FEED_GIT_BRANCH_DATA`, `FEED_GIT_BRANCH_HEARTBEAT`, `FEED_GIT_PUBLISH_DIR`,
  `FEED_GIT_KEY`, `FEED_GIT_KNOWN_HOSTS`, `FEED_GIT_PUSH_INTERVAL`,
  `FEED_HEARTBEAT_SECONDS`. All of them default to off or to a local path, so an
  environment that sets none publishes to nothing.

The published dataset is unchanged and is still placeholder content:
`metadata.content` reads `PLACEHOLDER` in every publication.
