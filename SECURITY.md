<!-- Author Claude/bentzn -->
# Security

Reviewed 2026-10-17 for v0.9.0.

## Reporting a vulnerability

Write to info@raposza.com. A report sent by any other route may not reach
anyone.

Include what you did, what happened and what you expected, in enough detail to
reproduce it. A proof of concept is welcome. A scanner report without one is not
a report.

No response time is promised. This is a small project, and a promise nobody can
keep is worse than no promise.

## What this service is, for the purpose of a report

It reads public sources over the network, stores what it retrieved, derives
records from it, and serves those read-only. It has no accounts, no sessions and
no authentication, it stores nothing a visitor submits, and it writes nothing
back to any source it reads.

It also reads the CC/USD price, on a fixed interval: the rate each network
states in its DSO document, and the public spot tickers of a few trading venues
and one aggregator, all unauthenticated, with no account and no key at any of
them. Every reading is kept in a record of its own beside the poll journal,
with the part of the body it was read from, and the latest is served at
`/api/v1/prices` and shown on `/prices.html`. The page links each price to the
endpoint it was read from; it loads nothing from there.

One unauthenticated HTTP port is everything that listens. It answers the
documented read endpoints and serves the web assets; there is no write route, no
upload and no administrative surface, and a deployment is expected to put it
behind a reverse proxy.

Not every web asset is ours. The api browser at `/api.html` is Swagger UI,
served from a copy in `web/vendor/swagger-ui/` that records its version, the
registry's integrity value and a `SHA256SUMS` of every file. The page's design
tokens and its two typefaces, Inter and Hack, are served the same way from
`web/vendor/raposza-design/`, which records its version, a `SHA256SUMS` of every
file and the licence of each typeface. Nothing is loaded from another origin
at run time. A defect in that code is its author's: report it here if it is
exploitable through this service, and upstream in any case.

The three pages also load `/_gc/count.js`, with `data-goatcounter="/_gc/count"`,
for visitor counting. This repository carries neither path and the service
neither serves nor sees them: both belong to the deployment, which serves them
from the page's own origin, and where nothing serves them the request fails and
nothing is sent anywhere. The deployments Raposza runs serve GoatCounter there,
self-hosted on the same machine; it sets no cookie and stores no IP address. A
defect in that counting is reported here like any other.

It does write outward, to one place that is not a source: the generated data
repository of its own environment, whose whole contents it produces. Its commit
log keeps every state it has published, which is the same derived facts over
time and carries nothing a source did not state. Text a source supplied is
escaped where it is written into that repository's README, so it cannot inject
markup, images or HTML there, and written into `versions.yml` as a quoted
scalar with every line break and control character escaped, so it can neither
add a key nor make the file one a yaml loader refuses.

That shapes which defects matter most here:

- **The integrity of what is published.** Anything that lets a published record
  state something no source stated is the most serious class of defect in this
  project, ahead of anything about availability. Every published value is now
  derived from banked evidence, or for a price from the price record, so this
  class is no longer theoretical. A price is published as the decimal its source
  gave, in plain notation, beside the currency the source quotes it in, and is
  never converted into another.
- **Provenance.** Every published record carries where it came from. A defect
  that breaks that link belongs in the same class.
- **The parsers.** Every source body is bytes a third party controls. The XML
  parser refuses doctype declarations and resolves no external entity or schema;
  the JSON parsers refuse trailing content; the comma-separated roster is served
  as `text/plain`, so its shape is the only check there is and both its header
  and its field count are required; every normalizer refuses a body it does not
  recognise whole rather than reading half of it. A body that can make
  this service fetch something, read a local file, or allocate without bound
  belongs in the first class above and not in a footnote. One parse runs
  before a body is banked: a source that names a stable key is compared on
  that one JSON member, by a parser that also refuses trailing content, and a
  body it cannot read is banked rather than skipped. A price body is parsed
  when it is read, by a JSON parser that also refuses trailing content; each
  reader takes the members it names and nothing else, follows no url a body
  carries, and records a body without them as unparsed rather than guessing.
- **The credentials.** Two kinds, with different scopes. Each environment pushes
  its own generated data repository with its own key and can write no other. The
  release call carries a separate token rather than that key, because a push key
  cannot create a Release; it is scoped to one data repository and it can write
  its contents. Both are read from the environment and neither is in this
  repository.

## The build

Every build refuses, before it compiles anything, a repository other than
Maven Central, a plugin without a pinned version, and a version range or a
snapshot.

## Supported versions

The most recent release only. There are no maintenance branches and no
backports.

## Out of scope

- Availability of any single instance. The dataset is static and cacheable by
  design, and losing one instance is an operational matter, not a
  vulnerability.
- The upstream sources. What they publish is theirs; report it to them.
- The values served by the api while `metadata.content` reads `EMPTY` or
  `UNAVAILABLE`. Both say the publication carries no observed event, and nothing
  served beside them is a statement about any network. The prices are not
  covered by this: a price with a `readAt` is in scope whatever
  `metadata.content` reads. While it reads `OBSERVED`
  they are in scope, and the first item above is what they are in scope for. The
  data repository is not written at all in either of the other two states, so
  everything in it is in scope.
- Version numbers and field names before 1.0.0, which are not promised to be
  stable.
