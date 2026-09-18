<!-- Author Claude/bentzn -->
# Security

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

That shapes which defects matter most here:

- **The integrity of what is published.** Anything that lets a published record
  state something no source stated is the most serious class of defect in this
  project, ahead of anything about availability.
- **Provenance.** Every published record carries where it came from. A defect
  that breaks that link belongs in the same class.
- **The keys.** Each environment pushes its own generated data repository with
  its own key and can write no other.

## Supported versions

The most recent release only. There are no maintenance branches and no
backports.

## Out of scope

- Availability of any single instance. The dataset is static and cacheable by
  design, and losing one instance is an operational matter, not a
  vulnerability.
- The upstream sources. What they publish is theirs; report it to them.
- The published values while `metadata.content` reads `PLACEHOLDER`. They carry
  the shape of the contract and are not observed facts, and they are not a
  statement about any network.
- Version numbers and field names before 1.0.0, which are not promised to be
  stable.
