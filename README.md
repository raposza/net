<!-- Author Claude/bentzn -->
# Raposza Network Operations Feed

A machine-readable feed of Canton Network operational state: what is deployed
on DevNet, TestNet and MainNet, what is scheduled, whether it is confirmed, what
changed since you last looked, and where every fact came from.

Not yet released. The service builds and runs, but it publishes placeholder
content: no source is collected yet, and every value it serves was written by
hand. `metadata.content` is `PLACEHOLDER` in every publication until that
changes.

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

## Licence

Copyright 2026 bentzn. Licensed under the Apache License, Version 2.0; see
`LICENSE`.
