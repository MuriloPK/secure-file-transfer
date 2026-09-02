---
name: Object storage availability boundary
description: Durable design rule for object-backed transfer repositories.
---

Object-backed transfers should use separate metadata and blob namespaces, with the
manifest as the availability boundary: chunks may exist before publication, but
listing must expose only successfully readable manifests.

**Why:** Chunk uploads can be interrupted or fail independently. Exposing a
partial transfer would make download and integrity errors look like available
user data.

**How to apply:** Keep credentials in the SDK/provider environment, stream blob
uploads/downloads, paginate metadata listing, and preserve existing service-level
hash/size validation rather than weakening it in the adapter.

For failed publications, cleanup must be attempted only before manifest
publication starts. If the manifest write has an indeterminate outcome, retain
the chunks and let a later explicit cleanup or retention policy handle them.

**Why:** Deleting after an uncertain manifest write can destroy chunks that an
available transfer already references.

**How to apply:** Keep the manifest-publication boundary in the application
service and make object cleanup re-check manifest presence before deleting
staged chunks.