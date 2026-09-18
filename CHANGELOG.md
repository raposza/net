<!-- Author Claude/bentzn -->
# Changelog

Releases before 0.2.0 carried no version and no tag; their history is the commit
log.

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
