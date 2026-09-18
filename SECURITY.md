<!-- Author Claude/bentzn -->
# Security

Reviewed 2026-09-18 for v0.3.0.

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

One unauthenticated HTTP port is everything that listens. It answers the
documented read endpoints and serves the web assets; there is no write route, no
upload and no administrative surface, and a deployment is expected to put it
behind a reverse proxy.

It does write outward, to one place that is not a source: the generated data
repository of its own environment, whose whole contents it produces.

That shapes which defects matter most here:

- **The integrity of what is published.** Anything that lets a published record
  state something no source stated is the most serious class of defect in this
  project, ahead of anything about availability. Every published value is now
  derived from banked evidence, so this class is no longer theoretical.
- **Provenance.** Every published record carries where it came from. A defect
  that breaks that link belongs in the same class.
- **The parsers.** Every source body is bytes a third party controls. The XML
  parser refuses doctype declarations and resolves no external entity or schema;
  the JSON parsers refuse trailing content; every normalizer refuses a body it
  does not recognise whole rather than reading half of it. A body that can make
  this service fetch something, read a local file, or allocate without bound
  belongs in the first class above and not in a footnote.
- **The credentials.** Two kinds, with different scopes. Each environment pushes
  its own generated data repository with its own key and can write no other. The
  release call carries a separate token rather than that key, because a push key
  cannot create a Release; it is scoped to one data repository and it can write
  its contents. Both are read from the environment and neither is in this
  repository.

## Supported versions

The most recent release only. There are no maintenance branches and no
backports.

## Out of scope

- Availability of any single instance. The dataset is static and cacheable by
  design, and losing one instance is an operational matter, not a
  vulnerability.
- The upstream sources. What they publish is theirs; report it to them.
- The published values while `metadata.content` reads `EMPTY` or `UNAVAILABLE`.
  Both say the publication carries no observed event, and nothing served beside
  them is a statement about any network. While it reads `OBSERVED` they are in
  scope, and the first item above is what they are in scope for.
- Version numbers and field names before 1.0.0, which are not promised to be
  stable.
