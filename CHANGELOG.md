<!-- Author Claude/bentzn -->
# Changelog

Releases before 0.2.0 carried no version and no tag; their history is the commit
log.

## 0.6.0

- The published source list carries the `url` of each source. It was id,
  publisher, authority and cadence, which names a source without saying what was
  fetched. The field is added to `/sources`, to the dataset's `sources.json`, and
  to the registry list the api serves before a publication exists. Nothing is
  removed or renamed.
- The README of the data repository renders that url as a link under every source
  line, so a reader checking a published value against upstream can open the
  exact endpoint that was polled rather than a host name.

## 0.5.1

- The Super Validator rosters of TestNet and DevNet were published almost empty.
  Events from different sources describing one thing are joined at publication
  on kind and upstream reference, and a node's upstream reference is its name -
  which the same operator reuses on every network it runs on. The three rosters
  therefore collapsed into one and kept the first network's, so MainNet
  published 13 nodes, DevNet published the single node whose name appears
  nowhere else, and TestNet published none. The join now keys on the network as
  well. A release reference carries no network, so the two Splice sources join
  exactly as before.
- The test that asserted the DevNet roster concatenated each source's published
  events instead of joining them, which is not the path the service takes. It
  now goes through `Events.joined` as `Events.published` does, and asserts the
  size of all three rosters.

## 0.5.0

- `current` IS NOW AN OBSERVATION. It is the version the network reports it is
  running, from the synchronizer's own deployment endpoint, and no longer the
  latest scheduled upgrade whose date had passed. On the bodies this release was
  built against the two disagree: DevNet reports 0.8.3 where the schedule's
  latest arrived entry is 0.8.1. `minimum` and `scheduled` are unchanged and
  still come from the operations schedule, which is the only source for either.
- `versions.yml` gains, per network: `sv-version`, the Super Validator
  application version beside the synchronizer's; `serial-id`, which upstream
  increments once per logical synchronizer upgrade and which now carries what
  the migration id used to - release names, DNS entries, database names, chain
  ids and port numbers; `migration-id`, which upstream states is frozen and
  configured once; `chain-id-suffix`; `successor` and `legacy`, the versions
  being upgraded to and from, null while no upgrade is in flight since upstream
  supports the two coexisting rather than cutting over; and `super-validators`,
  every node with the version it reports and the url it answers on.
- AN ORGANIZATION IS CONNECTED TO A NODE, NOT TO A NETWORK. A roster carrying
  more than one version is normal during an upgrade, and the per-network figure
  cannot tell a consumer whether its own node has moved. That is why the roster
  is published per node rather than reduced to one number.
- Six new sources, two per network: the deployment endpoint and the Super
  Validator roster of MainNet, TestNet and DevNet. Authority OFFICIAL, publisher
  the Global Synchronizer Foundation, polled every 300 s. Each is pinned to one
  network and a body that names another is refused rather than banked under the
  wrong name.
- The roster is comma-separated text served as `text/plain`, so the media type
  says nothing and the shape is the only check: the header must be the one the
  parser was written against and every row must hold three fields. An HTML error
  page and a truncated response both arrive as `text/plain`.
- New event fields, which are new columns: `sv_version`, `migration_id`,
  `serial_id`, `chain_id_suffix`, `successor_version`, `legacy_version` and
  `scan_url`. An
  index built with the previous schema is dropped and rebuilt from the evidence
  store and the journal, as it is for any schema change.
- The network record served by the api gains `deployment` and `superValidators`
  beside the `splice` and `next` blocks it already carried.
- Every statement in the data `README.md` about what a deployment field means is
  taken from the Splice release notes, and nothing there is inferred from a
  field name. The first cut of this release left `serial_id` out and described
  `migration-id` as the number a deployment is pinned to and can be wrong about;
  upstream states the reverse of both, and the banked bodies agree with
  upstream.

## 0.4.0

- BREAKING, the data repository. `state.json` and `versions.txt` are gone. What
  the repository carries is `versions.yml`, the current state as the values a
  consumer acts on, and `history.yml`, every state that file has held. A consumer
  that wants the whole event catalogue reads the api; the repository is the small
  answer and the long record, and nothing in between.
- `versions.yml` states, per network, `current` - the version that is scheduled
  to be running - `minimum`, the minimum version in force, and `scheduled`, the
  next upgrade ahead as a date and a version or `null`. Beside them
  `splice-latest`, the highest version the Splice tags endpoint carries, and
  `timestamp`, which is when the Raposza service emitted the file and nothing
  else.
- EVERY VERSION IS A QUOTED STRING. Unquoted, `0.7` is a number to every yaml
  parser there is, and the minimum version is stated to a minor in some cases.
  `timestamp` and the scheduled `date` are left unquoted so they load as a
  timestamp and a date.
- `history.yml` is a list under one `history:` key, newest first, each entry a
  full snapshot of `versions.yml` including its timestamp. An entry is added only
  when a value changes, so consecutive entries are never equal and the first one
  always states what `versions.yml` states now. A file that does not open with
  the expected line is refused rather than rewritten: an unreadable history can
  be repaired by hand, an overwritten one cannot.
- The data branch is ordinary git again. Nothing is amended and nothing is
  force-pushed, so the commit log and the files agree about what the feed has
  said.
- Nothing is written to the repository at all unless the publication was derived
  from banked observations. An environment that has observed nothing leaves the
  files as they were, which is why no value there is ever a placeholder.
- The change unit is `versions.yml` without its `timestamp` line. The reduced
  file carries no source health and no per-record stamps, so a poll that banks
  nothing changes nothing, and the comparison no longer has to hunt repeated
  publication stamps through a whole document.
- The data branch holds exactly the three files above and no others. A file
  belonging to an earlier shape of the publication would otherwise survive its
  own removal for ever: the working clone carries it, nothing rewrites it, and
  every commit keeps it. Names beginning with a dot are left alone.
- The data `README.md` gains a Fields section: what every field in
  `versions.yml` means, in words, including that `timestamp` is the Raposza
  timestamp - when the service emitted the file, not when anything changed.

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
