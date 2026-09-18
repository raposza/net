<!-- Author Claude/bentzn -->
# Raposza Network Operations Feed

A machine-readable feed of Canton Network operational state: what is deployed
on DevNet, TestNet and MainNet, what is scheduled, whether it is confirmed, what
changed since you last looked, and where every fact came from.

What is published: every value in the dataset, the api and the page is derived
from banked evidence. The service polls its public sources on their own
cadences, banks every retrieved body content-addressed beside a journal line per
poll, reads the SV operations schedule and the two Splice sources into claims,
and derives one event per
upstream record with revisions, change records and provenance on each. Those
events are what is published, and the per-network summary is derived from them
and from nothing else.

EVERY VERSION PUBLISHED IS SCHEDULED, NOT RUNNING. No source this feed reads
reports what a network is currently running, so what you get is what the
operators have said they intend to do, and a date that has arrived is not
evidence that it happened. `metadata.content` states what a publication is made
of: `OBSERVED` when the index was read and carried events, `EMPTY` when it
carried none, `UNAVAILABLE` when it could not be read.

## Build and run

Java 21 and Maven are the only requirements.

```
./build.sh
./run.sh          then open http://127.0.0.1:30000/
./test.sh
```

## Endpoints

```
GET /api/v1/status
GET /api/v1/networks
GET /api/v1/networks/{network}
GET /api/v1/events
GET /api/v1/events/{id}
GET /api/v1/sources
```

Every response carries the publication it belongs to, in the body and in the
`X-Raposza-Publication` header, so two consumers can establish they read the
same state.

## Reporting a security problem

See `SECURITY.md`. Changes are recorded in `CHANGELOG.md`.

## Licence

Copyright 2026 bentzn. Licensed under the Apache License, Version 2.0; see
`LICENSE`.
